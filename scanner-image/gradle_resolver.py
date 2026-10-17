#!/usr/bin/env python3
"""SecScan static Gradle dependency resolver (M4b, D-038 / D-039).

Reads Gradle build files as DATA ONLY -- never import/exec/eval from the scanned tree (D-004) -- and emits a
synthetic Maven ``pom.xml`` per build file so OSV-Scanner can resolve TRANSITIVE dependencies for Gradle
projects that ship no lockfile. Gradle findings therefore surface in OSV's **Maven** ecosystem (Gradle uses
Maven coordinates and Maven Central), which is also how a ``pom.xml`` scan reports them.

Invoked in the scanner container as::

    python3 -I gradle_resolver.py <checkout_dir> <synthetic_out_dir>

``-I`` (isolated) keeps the scanned tree and the CWD off ``sys.path`` so a planted ``json.py``/``tomllib.py``
can never be imported; only the standard library is used (``tomllib`` for version catalogs -- Python >= 3.11).

It prints a JSON array on stdout mapping each synthetic pom to its originating build file (repo-relative)::

    [{"syntheticPath": "/work/synthetic/0/pom.xml", "buildFile": "app/build.gradle"}, ...]

run-scan.sh passes ``<synthetic_out_dir>`` to OSV and records this array in the manifest; the parser rewrites
OSV's ``source.path`` back to the build file before fingerprinting (D-038).

Scope and safety (D-031 / D-035 -- never guess):
  * Only build files WITHOUT a sibling lockfile are resolved; a locked Gradle project is resolved by OSV
    natively, so resolving it here would double-count.
  * Only PRODUCTION configurations are read (``implementation``, ``api``, ``compileOnly``, ``runtimeOnly``,
    ...); anything whose configuration name contains ``test`` (and build-only configs) is excluded, matching
    the dev/test exclusion rule.
  * Only CONCRETE versions are emitted. A dynamic version (``1.2.+``, ``latest.release``), a range
    (``[1.0,2.0)``) or an unresolved property (``$ver``) is skipped, never guessed.
  * The resolver is best-effort: a parse error on one file is logged to stderr and that file is skipped; it
    never raises to the caller and never fails the scan.
"""

import json
import os
import re
import sys

try:
    import tomllib  # Python >= 3.11 (Alpine 3.21 ships python3 3.12.x)
except ModuleNotFoundError:  # pragma: no cover - the image pins a python3 that has tomllib
    tomllib = None

# Production configurations we read, as Gradle's exact (camelCase) configuration names. A positive allowlist
# avoids picking up random lines; custom configurations are not resolved (bounded for M4b). Test/dev
# configurations (testImplementation, androidTestImplementation, annotationProcessor, developmentOnly, ...) are
# deliberately absent so their dependencies never surface -- and whole-word matching (\b...\b, case-sensitive)
# means "implementation" never matches inside "testImplementation".
PRODUCTION_CONFIGS = (
    "implementation", "api", "compileOnlyApi", "compileOnly", "runtimeOnly",
    "providedCompile", "providedRuntime", "compile", "runtime",
)

# A concrete, pinned version OSV can match: non-empty and only version-safe characters. This single rule rejects
# every non-concrete form at once -- a range ("[", "]", "(", ")", ","), a dynamic "+"/"*", an unresolved "$ver"
# (the "$" is not allowed) and any whitespace -- so nothing is ever guessed (D-035). It also guarantees the
# token is XML-safe for the synthetic pom.
_SAFE_TOKEN = re.compile(r"^[A-Za-z0-9._-]+$")
_MAX_FILE_BYTES = 5 * 1024 * 1024  # never read an absurdly large build file into memory


def log(message):
    """Diagnostic to stderr only (captured to resolver.log in the container); never touches stdout (the map)."""
    print("gradle_resolver: " + message, file=sys.stderr)


# --- Comment stripping -------------------------------------------------------------------------------------

def strip_comments(text):
    """Remove ``/* ... */`` blocks and ``// ...`` line comments.

    Approximate but safe: a ``//`` or ``/*`` inside a string literal is rare in a dependency declaration and the
    worst case of a mis-strip is that we skip a line, never that we execute anything. Quoted ``g:a:v`` coordinates
    contain no comment markers, so declarations are unaffected.
    """
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.DOTALL)
    out_lines = []
    for line in text.splitlines():
        idx = line.find("//")
        if idx != -1:
            line = line[:idx]
        out_lines.append(line)
    return "\n".join(out_lines)


# --- gradle.properties and in-file variables ---------------------------------------------------------------

def parse_properties(path):
    """Parse a ``gradle.properties`` file into ``{key: value}``; empty dict on any error."""
    props = {}
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            for raw in handle:
                line = raw.strip()
                if not line or line[0] in "#!":
                    continue
                if "=" in line:
                    key, _, value = line.partition("=")
                elif ":" in line:
                    key, _, value = line.partition(":")
                else:
                    continue
                props[key.strip()] = value.strip()
    except OSError:
        pass
    return props


def collect_variables(checkout, build_dir, build_text):
    """Version variables visible to a build file: root + module ``gradle.properties`` plus simple string
    assignments in the build file itself (Groovy ``def``/``ext.``, Kotlin ``val``). Only literal string values
    are captured; anything computed stays unresolved and its dependency is skipped."""
    variables = {}
    variables.update(parse_properties(os.path.join(checkout, "gradle.properties")))
    if os.path.abspath(build_dir) != os.path.abspath(checkout):
        variables.update(parse_properties(os.path.join(build_dir, "gradle.properties")))
    # def NAME = "1.2.3" | val NAME = "1.2.3" | ext.NAME = '1.2.3' | NAME = "1.2.3" (inside ext { })
    for match in re.finditer(r'(?:def|val)\s+(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'ext\.(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    return variables


def resolve_interpolation(value, variables):
    """Resolve ``$name`` / ``${name}`` against ``variables``. An unknown variable becomes a sentinel that fails
    the concrete-version check, so the dependency is skipped rather than guessed."""
    if "$" not in value:
        return value

    def repl(match):
        name = match.group(1) or match.group(2)
        return variables.get(name, "\x00")  # sentinel: not version-safe -> skipped

    return re.sub(r"\$\{([A-Za-z0-9_.]+)\}|\$([A-Za-z0-9_]+)", repl, value)


# --- Version catalog (gradle/libs.versions.toml) -----------------------------------------------------------

def _norm(alias):
    """Canonical catalog alias: Gradle maps ``-`` and ``_`` in aliases to ``.`` for the ``libs.`` accessor."""
    return alias.replace("-", ".").replace("_", ".")


def _version_from_node(node, versions):
    """A concrete version string from a catalog version node: a plain string, a ``{ ref = }`` into [versions],
    or a ``{ require/strictly/prefer = }`` constraint. Returns None when it is not resolvable to a concrete
    pin."""
    if isinstance(node, str):
        return node
    if isinstance(node, dict):
        if "ref" in node and isinstance(node["ref"], str):
            return versions.get(_norm(node["ref"]))
        for key in ("require", "strictly", "prefer"):
            if isinstance(node.get(key), str):
                return node[key]
    return None


def load_version_catalog(checkout):
    """Load ``gradle/libs.versions.toml`` into (libraries, bundles). ``libraries`` maps a normalized alias to a
    ``(group, artifact, version_or_None)`` tuple; ``bundles`` maps a normalized alias to member aliases. Empty
    on any error or when the catalog is absent."""
    libraries = {}
    bundles = {}
    if tomllib is None:
        return libraries, bundles
    path = os.path.join(checkout, "gradle", "libs.versions.toml")
    if os.path.islink(path) or not os.path.isfile(path):
        return libraries, bundles
    try:
        with open(path, "rb") as handle:
            data = tomllib.load(handle)
    except (OSError, tomllib.TOMLDecodeError, ValueError) as exc:
        log("could not parse version catalog: " + str(exc))
        return libraries, bundles

    versions = {}
    for alias, node in (data.get("versions") or {}).items():
        resolved = _version_from_node(node, {})
        if isinstance(resolved, str):
            versions[_norm(alias)] = resolved

    for alias, node in (data.get("libraries") or {}).items():
        group = artifact = version = None
        if isinstance(node, str):
            parts = node.split(":")
            if len(parts) >= 2:
                group, artifact = parts[0], parts[1]
                version = parts[2] if len(parts) >= 3 else None
        elif isinstance(node, dict):
            module = node.get("module")
            if isinstance(module, str) and ":" in module:
                group, _, artifact = module.partition(":")
            else:
                group = node.get("group")
                artifact = node.get("name")
            if "version" in node:
                version = _version_from_node(node["version"], versions)
        if isinstance(group, str) and isinstance(artifact, str):
            libraries[_norm(alias)] = (group, artifact, version if isinstance(version, str) else None)

    for alias, members in (data.get("bundles") or {}).items():
        if isinstance(members, list):
            bundles[_norm(alias)] = [_norm(m) for m in members if isinstance(m, str)]
    return libraries, bundles


# --- Dependency extraction ---------------------------------------------------------------------------------

_COORD_RE = re.compile(r"""["']([^"']+)["']""")
_MAP_GROUP = re.compile(r"""\bgroup\s*:\s*["']([^"']+)["']""")
_MAP_NAME = re.compile(r"""\bname\s*:\s*["']([^"']+)["']""")
_MAP_VERSION = re.compile(r"""\bversion\s*:\s*["']([^"']+)["']""")
_LIBS_RE = re.compile(r"\blibs((?:\.[A-Za-z0-9_]+)+)")
# A production configuration used anywhere on a line (not only at its start), so `dependencies { implementation
# '...' }` on one line is caught. Whole-word and case-sensitive, so it never fires inside testImplementation.
_CONFIG_SCAN = re.compile(r"\b(" + "|".join(PRODUCTION_CONFIGS) + r")\b")


def _concrete_coord(group, artifact, version, variables):
    """A validated ``(group, artifact, version)`` tuple, or None when any part is missing/non-concrete."""
    if not group or not artifact or not version:
        return None
    version = resolve_interpolation(version, variables)
    # A dynamic "latest.*" selector is version-safe by characters but is NOT a concrete pin -- skip it (D-035).
    if version.startswith("latest.") or version == "latest":
        return None
    if not (_SAFE_TOKEN.match(group) and _SAFE_TOKEN.match(artifact) and _SAFE_TOKEN.match(version)):
        return None
    return (group, artifact, version)


def extract_from_line(line, variables, libraries, bundles):
    """All concrete coordinates declared on one line. Each production-configuration keyword on the line
    contributes the first coordinate in its argument region (to end of line); a ``libs.bundles`` accessor may
    yield several."""
    coords = []
    for match in _CONFIG_SCAN.finditer(line):
        coords.extend(_extract_argument(line[match.end():], variables, libraries, bundles))
    return coords


def _extract_argument(rest, variables, libraries, bundles):
    """The coordinate(s) in one configuration's argument region ``rest`` (the text after the keyword)."""
    # 1) Version-catalog accessor: libs.foo.bar / libs.bundles.x (no quoted coordinate in the argument).
    libs_match = _LIBS_RE.search(rest)
    if libs_match:
        segments = [s for s in libs_match.group(1).split(".") if s]
        if not segments:
            return []
        head = segments[0]
        if head in ("versions", "plugins"):
            return []  # a version/plugin accessor is not a dependency
        if head == "bundles":
            alias = _norm(".".join(segments[1:]))
            coords = []
            for member in bundles.get(alias, []):
                coords.extend(_from_library(member, libraries, variables))
            return coords
        return _from_library(_norm(".".join(segments)), libraries, variables)

    # 2) Map notation: group: 'g', name: 'a', version: 'v' (version optional).
    if _MAP_NAME.search(rest):
        g = _MAP_GROUP.search(rest)
        n = _MAP_NAME.search(rest)
        v = _MAP_VERSION.search(rest)
        coord = _concrete_coord(g.group(1) if g else None, n.group(1) if n else None,
                                v.group(1) if v else None, variables)
        return [coord] if coord else []

    # 3) String notation: the first quoted token is the g:a:v coordinate (platform('g:a:v') unwraps for free).
    coord_match = _COORD_RE.search(rest)
    if coord_match:
        parts = coord_match.group(1).split(":")
        if len(parts) >= 3:
            coord = _concrete_coord(parts[0], parts[1], parts[2], variables)
            return [coord] if coord else []
    return []


def _from_library(alias, libraries, variables):
    """Resolve a catalog library alias to a concrete coordinate (or nothing)."""
    entry = libraries.get(alias)
    if entry is None:
        return []
    group, artifact, version = entry
    coord = _concrete_coord(group, artifact, version, variables)
    return [coord] if coord else []


def resolve_build_file(checkout, build_path, libraries, bundles):
    """All concrete, de-duplicated production coordinates declared in one build file."""
    try:
        size = os.path.getsize(build_path)
    except OSError:
        return []
    if size > _MAX_FILE_BYTES:
        log("skipping oversized build file: " + build_path)
        return []
    try:
        with open(build_path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        log("could not read " + build_path + ": " + str(exc))
        return []

    text = strip_comments(text)
    variables = collect_variables(checkout, os.path.dirname(build_path), text)

    seen = set()
    coords = []
    for line in text.splitlines():
        for coord in extract_from_line(line, variables, libraries, bundles):
            if coord not in seen:
                seen.add(coord)
                coords.append(coord)
    return coords


# --- Synthetic pom -----------------------------------------------------------------------------------------

def _xml_escape(value):
    return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def write_synthetic_pom(out_dir, index, coords):
    """Write ``<out_dir>/<index>/pom.xml`` for the given coordinates; return its absolute path."""
    pom_dir = os.path.join(out_dir, str(index))
    os.makedirs(pom_dir, exist_ok=True)
    pom_path = os.path.join(pom_dir, "pom.xml")
    lines = [
        '<project xmlns="http://maven.apache.org/POM/4.0.0">',
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>com.secscan.synthetic</groupId>",
        "  <artifactId>gradle-" + str(index) + "</artifactId>",
        "  <version>0.0.0</version>",
        "  <dependencies>",
    ]
    for group, artifact, version in coords:
        lines.append("    <dependency>")
        lines.append("      <groupId>" + _xml_escape(group) + "</groupId>")
        lines.append("      <artifactId>" + _xml_escape(artifact) + "</artifactId>")
        lines.append("      <version>" + _xml_escape(version) + "</version>")
        lines.append("    </dependency>")
    lines.append("  </dependencies>")
    lines.append("</project>")
    with open(pom_path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")
    return os.path.abspath(pom_path)


# --- Walk --------------------------------------------------------------------------------------------------

def _has_lockfile(directory):
    return (os.path.isfile(os.path.join(directory, "gradle.lockfile"))
            or os.path.isfile(os.path.join(directory, "buildscript-gradle.lockfile")))


def find_build_files(checkout):
    """Repo build files eligible for static resolution: ``build.gradle``/``build.gradle.kts`` with NO sibling
    lockfile (a locked project is resolved by OSV natively). Symlinks are never followed or read; ``buildSrc``
    (build-logic, not runtime) is skipped."""
    results = []
    for root, dirs, files in os.walk(checkout, followlinks=False):
        dirs[:] = [d for d in dirs if d != "buildSrc" and not os.path.islink(os.path.join(root, d))]
        if ".git" in dirs:
            dirs.remove(".git")
        if _has_lockfile(root):
            continue
        for name in ("build.gradle", "build.gradle.kts"):
            candidate = os.path.join(root, name)
            if os.path.isfile(candidate) and not os.path.islink(candidate):
                results.append(candidate)
    results.sort()  # stable synthetic indices regardless of walk order
    return results


def main(argv):
    if len(argv) != 3:
        log("usage: gradle_resolver.py <checkout_dir> <synthetic_out_dir>")
        print("[]")
        return 2
    checkout, out_dir = argv[1], argv[2]
    mapping = []
    try:
        libraries, bundles = load_version_catalog(checkout)
        index = 0
        for build_path in find_build_files(checkout):
            coords = resolve_build_file(checkout, build_path, libraries, bundles)
            if not coords:
                continue  # nothing concrete to resolve -> no synthetic pom (MISSING_GRADLE_LOCKFILE still warns)
            synthetic_path = write_synthetic_pom(out_dir, index, coords)
            rel = os.path.relpath(build_path, checkout).replace(os.sep, "/")
            mapping.append({"syntheticPath": synthetic_path, "buildFile": rel})
            index += 1
    except Exception as exc:  # best-effort: never fail the scan on a resolver bug
        log("unexpected error, emitting partial map: " + repr(exc))
    print(json.dumps(mapping))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
