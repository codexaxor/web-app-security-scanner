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

It prints a JSON OBJECT on stdout::

    {"poms":     [{"syntheticPath": "/work/synthetic/0/pom.xml", "buildFile": "app/build.gradle"}, ...],
     "warnings": [{"code": "GRADLE_UNPARSEABLE_CONSTRUCT", "message": "...", "filePath": "app/build.gradle"}, ...]}

run-scan.sh records ``poms`` in the manifest (``syntheticPoms``) and passes ``<synthetic_out_dir>`` to OSV; the
parser rewrites OSV's ``source.path`` back to the build file before fingerprinting (D-038). ``warnings`` are
appended to the scan's warnings so every skipped construct is a VISIBLE coverage gap (D-030), never silent.

Config selection (reviewed for slice 1):
  * We resolve EVERY non-``buildSrc`` dependency-declaration configuration -- production AND test -- and let OSV
    decide what to report. OSV is scope-agnostic (it reports a Maven ``<scope>test</scope>`` dependency too), so
    this MATCHES how M4 already scans real ``pom.xml`` files. We over-include rather than silently drop (D-030);
    a package in a production config is always kept even if it also appears in a test config (keep wins). The
    principled dev/test exclusion (the ``include_dev_deps`` switch / ``DEV_DEPENDENCY`` reason) is M7, not here.

Never guess (D-031 / D-035):
  * Only build files WITHOUT a sibling lockfile are resolved; a locked Gradle project is resolved by OSV
    natively, so resolving it here would double-count.
  * Only CONCRETE versions are emitted. A dependency with no version (BOM-managed), a dynamic version
    (``1.2.+``, ``latest.release``), a range (``[1.0,2.0)``) or an unresolved property (``$ver``) is skipped and
    raises ``GRADLE_UNPARSEABLE_CONSTRUCT`` -- never guessed.
  * Constructs the static reader cannot follow (``buildSrc``, ``apply from:``, ``resolutionStrategy``, an
    unresolved version-catalog alias) raise ``GRADLE_UNPARSEABLE_CONSTRUCT`` too.

Best-effort: a parse error on one file is logged to stderr and that file is skipped; it never raises to the
caller and never fails the scan.
"""

import json
import os
import re
import sys

try:
    import tomllib  # Python >= 3.11 (Alpine 3.21 ships python3 3.12.x)
except ModuleNotFoundError:  # pragma: no cover - the image pins a python3 that has tomllib
    tomllib = None

# The scan_warning code for a Gradle construct/version the static resolver cannot resolve (free text, no
# migration). Each one is a coverage gap we SHOW, never hide (D-030).
WARN_CODE = "GRADLE_UNPARSEABLE_CONSTRUCT"

# A dependency-declaration configuration keyword. We over-include every non-buildSrc configuration (production
# and test alike -- see the module docstring), so this matches the standard base configurations plus any
# source-set/variant prefix of a known camelCase suffix (testImplementation, integrationTestImplementation,
# debugRuntimeOnly, testFixturesApi, ...). It does NOT match arbitrary identifiers (classpath, id, exclude).
_BASE_CONFIGS = frozenset({
    "implementation", "api", "compile", "runtime", "compileOnly", "compileOnlyApi", "runtimeOnly",
    "providedCompile", "providedRuntime", "annotationProcessor", "developmentOnly", "kapt", "ksp",
})
_CONFIG_VARIANT = re.compile(
    r"^[a-z][A-Za-z0-9]*(Implementation|CompileOnlyApi|CompileOnly|RuntimeOnly|AnnotationProcessor|Api|Compile|Runtime)$")

# A concrete, pinned version OSV can match: non-empty and only version-safe characters. This single rule rejects
# every non-concrete form at once -- a range ("[", "]", "(", ")", ","), a dynamic "+"/"*", an unresolved "$ver"
# (the "$" is not allowed) and any whitespace -- so nothing is ever guessed (D-035). It also guarantees the
# token is XML-safe for the synthetic pom.
_SAFE_TOKEN = re.compile(r"^[A-Za-z0-9._-]+$")
_MAX_FILE_BYTES = 5 * 1024 * 1024  # never read an absurdly large build file into memory


def log(message):
    """Diagnostic to stderr only (captured to resolver.log in the container); never touches stdout (the map)."""
    print("gradle_resolver: " + message, file=sys.stderr)


def _is_config(token):
    return token in _BASE_CONFIGS or bool(_CONFIG_VARIANT.match(token))


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
    are captured; anything computed stays unresolved and its dependency is skipped (and warned)."""
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
# A dependency configuration used anywhere on a line (not only at its start), so `dependencies { implementation
# '...' }` on one line is caught. The matched word is validated by _is_config.
_CONFIG_SCAN = re.compile(r"\b([a-zA-Z][A-Za-z0-9]*)\b")
# Recognized non-dependency argument forms on a config line (internal/local): never a Maven coordinate, so they
# are ignored WITHOUT a warning (they are not a coverage gap).
_INTERNAL_RE = re.compile(r"\b(project|files|fileTree|gradleApi|localGroovy|kotlin|gradleTestKit)\s*\(")


def _concrete_version(version, variables):
    """The concrete version string, or None when it is not a resolvable concrete pin."""
    version = resolve_interpolation(version, variables)
    if version.startswith("latest.") or version == "latest":
        return None  # dynamic selector: version-safe by characters, but not a concrete pin
    return version if _SAFE_TOKEN.match(version) else None


def extract_from_line(line, variables, libraries, bundles):
    """``(coords, warnings)`` for one line. Each dependency-configuration keyword contributes the first
    coordinate in its argument region (to end of line); a ``libs.bundles`` accessor may yield several. A
    recognized dependency that cannot be resolved to a concrete coordinate yields a warning detail string."""
    coords = []
    warnings = []
    for match in _CONFIG_SCAN.finditer(line):
        if not _is_config(match.group(1)):
            continue
        rest = line[match.end():]
        c, w = _extract_argument(rest, variables, libraries, bundles)
        coords.extend(c)
        warnings.extend(w)
    return coords, warnings


def _extract_argument(rest, variables, libraries, bundles):
    """``(coords, warnings)`` for one configuration's argument region ``rest`` (the text after the keyword)."""
    # 1) Version-catalog accessor: libs.foo.bar / libs.bundles.x (no quoted coordinate in the argument).
    libs_match = _LIBS_RE.search(rest)
    if libs_match:
        segments = [s for s in libs_match.group(1).split(".") if s]
        if not segments or segments[0] in ("versions", "plugins"):
            return [], []  # a version/plugin accessor is not a dependency
        if segments[0] == "bundles":
            alias = _norm(".".join(segments[1:]))
            if alias not in bundles:
                return [], ["unresolved version-catalog bundle 'libs.bundles." + ".".join(segments[1:]) + "'"]
            coords, warnings = [], []
            for member in bundles[alias]:
                c, w = _from_library(member, libraries, variables, "libs.bundles." + ".".join(segments[1:]))
                coords.extend(c)
                warnings.extend(w)
            return coords, warnings
        return _from_library(_norm(".".join(segments)), libraries, variables, "libs." + ".".join(segments))

    # 2) Map notation: group: 'g', name: 'a', version: 'v' (version optional).
    if _MAP_NAME.search(rest):
        g = _MAP_GROUP.search(rest)
        n = _MAP_NAME.search(rest)
        v = _MAP_VERSION.search(rest)
        group = g.group(1) if g else None
        name = n.group(1) if n else None
        if group and name:
            return _coord_or_warning(group, name, v.group(1) if v else None, variables)
        return [], []

    # 3) String notation: the first quoted token is the g:a:v coordinate (platform('g:a:v') unwraps for free).
    #    Ignore recognized internal/local forms (project/files/...), which are never Maven coordinates.
    if _INTERNAL_RE.search(rest):
        return [], []
    coord_match = _COORD_RE.search(rest)
    if coord_match:
        parts = coord_match.group(1).split(":")
        if len(parts) >= 2 and _SAFE_TOKEN.match(parts[0]) and _SAFE_TOKEN.match(parts[1]):
            version = parts[2] if len(parts) >= 3 else None
            return _coord_or_warning(parts[0], parts[1], version, variables)
    return [], []


def _coord_or_warning(group, artifact, version, variables):
    """A concrete ``[(g, a, v)]`` coordinate, or ``([], [warning])`` when the version is missing/non-concrete
    (a visible coverage gap -- never guessed; D-035)."""
    ga = group + ":" + artifact
    if version is None:
        return [], ["dependency '" + ga + "' declared with no version (not resolved; e.g. BOM-managed)"]
    concrete = _concrete_version(version, variables)
    if concrete is None:
        return [], ["dependency '" + ga + "' has a non-concrete version '" + version + "' (skipped, not guessed)"]
    return [(group, artifact, concrete)], []


def _from_library(alias, libraries, variables, accessor):
    """Resolve a catalog library alias to a concrete coordinate, or warn that it is unresolved."""
    entry = libraries.get(alias)
    if entry is None:
        return [], ["unresolved version-catalog reference '" + accessor + "'"]
    group, artifact, version = entry
    return _coord_or_warning(group, artifact, version, variables)


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


# --- Per-file resolution -----------------------------------------------------------------------------------

def resolve_build_file(checkout, build_path, libraries, bundles):
    """``(coords, warning_details)`` for one build file. ``coords`` are concrete, de-duplicated (keep wins:
    a package kept once regardless of how many configurations declare it). ``warning_details`` is one string per
    skipped dependency/construct, to be surfaced as GRADLE_UNPARSEABLE_CONSTRUCT."""
    try:
        size = os.path.getsize(build_path)
    except OSError:
        return [], []
    if size > _MAX_FILE_BYTES:
        return [], ["build file skipped: larger than " + str(_MAX_FILE_BYTES) + " bytes"]
    try:
        with open(build_path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        log("could not read " + build_path + ": " + str(exc))
        return [], []

    text = strip_comments(text)
    variables = collect_variables(checkout, os.path.dirname(build_path), text)

    seen = set()
    coords = []
    warnings = []
    for line in text.splitlines():
        line_coords, line_warnings = extract_from_line(line, variables, libraries, bundles)
        for coord in line_coords:
            if coord not in seen:
                seen.add(coord)
                coords.append(coord)
        warnings.extend(line_warnings)

    # Constructs a static reader cannot follow -- surfaced so the gap is visible (D-030), not silent.
    if re.search(r"\bapply\s+from\s*:", text):
        warnings.append("`apply from:` applies an external script that was not followed")
    if "resolutionStrategy" in text:
        warnings.append("`resolutionStrategy` version pinning/substitution was not applied")

    # De-duplicate identical warnings within a file (e.g. the same unresolved alias on several lines).
    return coords, list(dict.fromkeys(warnings))


# --- Walk --------------------------------------------------------------------------------------------------

def _has_lockfile(directory):
    return (os.path.isfile(os.path.join(directory, "gradle.lockfile"))
            or os.path.isfile(os.path.join(directory, "buildscript-gradle.lockfile")))


def find_build_files(checkout):
    """``(eligible, buildsrc)``: repo build files eligible for static resolution
    (``build.gradle``/``build.gradle.kts`` with NO sibling lockfile -- a locked project is resolved by OSV
    natively), and the ``buildSrc`` build files that were skipped (build logic, surfaced as a warning).
    Symlinks are never followed or read."""
    eligible = []
    buildsrc = []
    for root, dirs, files in os.walk(checkout, followlinks=False):
        dirs[:] = [d for d in dirs if not os.path.islink(os.path.join(root, d)) and d != ".git"]
        in_buildsrc = ("buildSrc" + os.sep) in (root + os.sep).replace(checkout, "", 1) \
            or os.path.basename(root) == "buildSrc"
        for name in ("build.gradle", "build.gradle.kts"):
            candidate = os.path.join(root, name)
            if not (os.path.isfile(candidate) and not os.path.islink(candidate)):
                continue
            if in_buildsrc:
                buildsrc.append(candidate)
            elif not _has_lockfile(root):
                eligible.append(candidate)
    eligible.sort()  # stable synthetic indices regardless of walk order
    buildsrc.sort()
    return eligible, buildsrc


def _rel(checkout, path):
    return os.path.relpath(path, checkout).replace(os.sep, "/")


def resolve(checkout, out_dir):
    """Resolve the whole checkout: ``{"poms": [...], "warnings": [...]}``. Never raises."""
    poms = []
    warnings = []

    def warn(detail, build_file):
        warnings.append({"code": WARN_CODE, "message": build_file + ": " + detail, "filePath": build_file})

    try:
        libraries, bundles = load_version_catalog(checkout)
        eligible, buildsrc = find_build_files(checkout)
        for build_path in buildsrc:
            warn("buildSrc build logic is not resolved (dependencies declared here are not scanned)",
                 _rel(checkout, build_path))
        index = 0
        for build_path in eligible:
            rel = _rel(checkout, build_path)
            coords, details = resolve_build_file(checkout, build_path, libraries, bundles)
            for detail in details:
                warn(detail, rel)
            if not coords:
                continue  # nothing concrete to resolve -> no synthetic pom (warnings above record the gaps)
            synthetic_path = write_synthetic_pom(out_dir, index, coords)
            poms.append({"syntheticPath": synthetic_path, "buildFile": rel})
            index += 1
    except Exception as exc:  # best-effort: never fail the scan on a resolver bug
        log("unexpected error, emitting partial result: " + repr(exc))
    return {"poms": poms, "warnings": warnings}


def main(argv):
    if len(argv) != 3:
        log("usage: gradle_resolver.py <checkout_dir> <synthetic_out_dir>")
        print(json.dumps({"poms": [], "warnings": []}))
        return 2
    print(json.dumps(resolve(argv[1], argv[2])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
