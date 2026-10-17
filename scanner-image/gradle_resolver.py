#!/usr/bin/env python3
"""SecScan static Gradle dependency resolver (M4b, D-038 / D-039 / D-041).

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

BOM-managed versions (D-041): Spring Boot/Cloud Gradle builds declare most dependencies with NO inline version
(managed by a BOM). We import the relevant BOM(s) into the synthetic pom's ``<dependencyManagement>`` as
``<scope>import</scope><type>pom</type>`` and emit such dependencies version-LESS, so OSV's transitive resolver
fills the versions from the BOM. BOMs come from: the Spring Boot Gradle plugin version
(``id 'org.springframework.boot' version 'X'`` / legacy ``spring-boot-gradle-plugin:X``) ->
``org.springframework.boot:spring-boot-dependencies:X``; a ``platform(...)``/``enforcedPlatform(...)`` coordinate;
and a ``mavenBom "..."`` import. BOM versions resolve ``${prop}`` from ``ext{}`` (incl. ``set('p','v')``),
``extra[...]`` and ``gradle.properties``. OSV's resolver only engages when the pom has at least one
concrete-versioned dependency, so when every dependency is BOM-managed we add the first BOM as a versioned
``<type>pom</type>`` dependency purely to trigger resolution (a BOM pom contributes no packages of its own).

Config selection (slice-1 review): we resolve EVERY non-``buildSrc`` dependency configuration -- production AND
test -- and let OSV decide what to report (OSV is scope-agnostic, matching how M4 scans real poms); keep wins.
Principled dev/test exclusion (``include_dev_deps`` / ``DEV_DEPENDENCY``) is M7.

Never guess (D-031 / D-035): a dependency with an EXPLICIT but non-concrete version (``1.2.+``, ``[1.0,2.0)``,
an unresolved ``$ver``) is skipped and warned; a version-LESS dependency with no BOM to manage it is skipped and
warned; buildSrc, ``apply from:``, ``resolutionStrategy`` and unresolved catalog aliases are warned.

Best-effort: a parse error on one file is logged to stderr and that file is skipped; a write failure is
surfaced as a warning (never a silent empty result); it never raises to the caller and never fails the scan.
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

_BOOT_DEPENDENCIES = ("org.springframework.boot", "spring-boot-dependencies")

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
    """Version variables visible to a build file: root + module ``gradle.properties`` plus string assignments in
    the build file itself (D-041 broadens this to every ``ext{}`` form). Only literal string values are captured;
    anything computed stays unresolved and its dependency is skipped (and warned)."""
    variables = {}
    variables.update(parse_properties(os.path.join(checkout, "gradle.properties")))
    if os.path.abspath(build_dir) != os.path.abspath(checkout):
        variables.update(parse_properties(os.path.join(build_dir, "gradle.properties")))
    # Groovy ext.NAME = 'v' / def NAME = 'v'; Kotlin val NAME = "v".
    for match in re.finditer(r'(?:def|val)\s+(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'ext\.(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    # Groovy ext { set('NAME', 'v') } and Kotlin extra["NAME"] = "v" / val NAME by extra("v").
    for match in re.finditer(r'''\bset\(\s*["'](\w+)["']\s*,\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''\bextra\[\s*["'](\w+)["']\s*\]\s*=\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''\bval\s+(\w+)\s+by\s+extra\s*\(\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    # Bare assignments inside an ext{} / project scope: NAME = 'v' at the start of a line. Broad but harmless --
    # captured variables are only consulted for ${name} interpolation, so an unused extra never affects output.
    for match in re.finditer(r'''(?m)^\s*(\w+)\s*=\s*["']([^"']+)["']''', build_text):
        variables.setdefault(match.group(1), match.group(2))
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


def _resolve_version(raw, variables):
    """The concrete version string for a raw version token, or None when it is absent / not a concrete pin."""
    if raw is None:
        return None
    value = resolve_interpolation(raw, variables)
    if value.startswith("latest.") or value == "latest":
        return None  # dynamic selector: version-safe by characters, but not a concrete pin
    return value if _SAFE_TOKEN.match(value) else None


# --- Version catalog (gradle/libs.versions.toml) -----------------------------------------------------------

def _norm(alias):
    """Canonical catalog alias: Gradle maps ``-`` and ``_`` in aliases to ``.`` for the ``libs.`` accessor."""
    return alias.replace("-", ".").replace("_", ".")


def _version_from_node(node, versions):
    """A version string from a catalog version node: a plain string, a ``{ ref = }`` into [versions], or a
    ``{ require/strictly/prefer = }`` constraint. Returns None when it cannot be reduced to a string."""
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


# --- Dependency / BOM extraction (syntactic; version resolution happens in resolve_build_file) -------------

_COORD_RE = re.compile(r"""["']([^"']+)["']""")
_MAP_GROUP = re.compile(r"""\bgroup\s*:\s*["']([^"']+)["']""")
_MAP_NAME = re.compile(r"""\bname\s*:\s*["']([^"']+)["']""")
_MAP_VERSION = re.compile(r"""\bversion\s*:\s*["']([^"']+)["']""")
_LIBS_RE = re.compile(r"\blibs((?:\.[A-Za-z0-9_]+)+)")
_PLATFORM_RE = re.compile(r"\b(?:enforcedPlatform|platform)\s*\(")
_CONFIG_SCAN = re.compile(r"\b([a-zA-Z][A-Za-z0-9]*)\b")
# Recognized non-dependency argument forms on a config line (internal/local): never a Maven coordinate.
_INTERNAL_RE = re.compile(r"\b(project|files|fileTree|gradleApi|localGroovy|kotlin|gradleTestKit)\s*\(")
# Spring Boot Gradle plugin, both the plugins-DSL `id '...' version 'X'` and the legacy classpath coordinate.
_BOOT_PLUGIN_VERSION = re.compile(
    r"""id\s*\(?\s*["']org\.springframework\.boot["']\s*\)?\s+version\s+["']([^"']+)["']""")
_BOOT_PLUGIN_CLASSPATH = re.compile(r"""spring-boot-gradle-plugin:([^\s"')]+)""")
_BOOT_PLUGIN_PRESENT = re.compile(
    r"""(?:id\s*\(?\s*|apply\s+plugin\s*:\s*)["']org\.springframework\.boot["']""")
_MAVEN_BOM = re.compile(r"""\bmavenBom\s*\(?\s*["']([^"']+)["']""")


def extract_from_line(line, libraries, bundles):
    """``(deps, boms, warnings)`` RAW candidates for one line. Each element of ``deps``/``boms`` is a
    ``(group, artifact, raw_version_or_None)`` tuple (version resolved later, knowing the BOM set). A
    ``platform(...)``/``enforcedPlatform(...)`` argument is routed to ``boms``; everything else to ``deps``."""
    deps, boms, warnings = [], [], []
    for match in _CONFIG_SCAN.finditer(line):
        if not _is_config(match.group(1)):
            continue
        d, b, w = _extract_argument(line[match.end():], libraries, bundles)
        deps.extend(d)
        boms.extend(b)
        warnings.extend(w)
    return deps, boms, warnings


def _extract_argument(rest, libraries, bundles):
    is_bom = bool(_PLATFORM_RE.search(rest))

    def route(candidates):
        return ([], candidates) if is_bom else (candidates, [])

    # 1) Version-catalog accessor: libs.foo.bar / libs.bundles.x.
    libs_match = _LIBS_RE.search(rest)
    if libs_match:
        segments = [s for s in libs_match.group(1).split(".") if s]
        if not segments or segments[0] in ("versions", "plugins"):
            return [], [], []
        if segments[0] == "bundles":
            alias = _norm(".".join(segments[1:]))
            if alias not in bundles:
                return [], [], ["unresolved version-catalog bundle 'libs.bundles." + ".".join(segments[1:]) + "'"]
            cands, warns = [], []
            for member in bundles[alias]:
                entry = libraries.get(member)
                if entry is None:
                    warns.append("unresolved version-catalog reference 'libs." + member + "'")
                else:
                    cands.append(entry)
            d, b = route(cands)
            return d, b, warns
        entry = libraries.get(_norm(".".join(segments)))
        if entry is None:
            return [], [], ["unresolved version-catalog reference 'libs." + ".".join(segments) + "'"]
        d, b = route([entry])
        return d, b, []

    # 2) Map notation: group: 'g', name: 'a', version: 'v' (version optional).
    if _MAP_NAME.search(rest):
        g = _MAP_GROUP.search(rest)
        n = _MAP_NAME.search(rest)
        v = _MAP_VERSION.search(rest)
        if g and n:
            d, b = route([(g.group(1), n.group(1), v.group(1) if v else None)])
            return d, b, []
        return [], [], []

    # 3) String notation: the first quoted token is the g:a[:v] coordinate (platform('g:a:v') unwraps for free).
    if _INTERNAL_RE.search(rest) and not is_bom:
        return [], [], []
    coord_match = _COORD_RE.search(rest)
    if coord_match:
        parts = coord_match.group(1).split(":")
        if len(parts) >= 2 and _SAFE_TOKEN.match(parts[0]) and _SAFE_TOKEN.match(parts[1]):
            d, b = route([(parts[0], parts[1], parts[2] if len(parts) >= 3 else None)])
            return d, b, []
    return [], [], []


def detect_boot_bom(text, variables):
    """``(version_or_None, present_bool)`` for the Spring Boot Gradle plugin -> spring-boot-dependencies BOM."""
    m = _BOOT_PLUGIN_VERSION.search(text)
    if m:
        return _resolve_version(m.group(1), variables), True
    m = _BOOT_PLUGIN_CLASSPATH.search(text)
    if m:
        return _resolve_version(m.group(1), variables), True
    if _BOOT_PLUGIN_PRESENT.search(text):
        return None, True
    return None, False


# --- Synthetic pom -----------------------------------------------------------------------------------------

def _xml_escape(value):
    return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def build_synthetic_pom(index, deps, boms):
    """The synthetic pom XML. ``deps`` are ``(group, artifact, version_or_None, is_pom)`` (a ``None`` version is
    emitted version-less, managed by an imported BOM); ``boms`` are ``(group, artifact, version)`` imported into
    ``<dependencyManagement>`` as ``<scope>import</scope><type>pom</type>``."""
    lines = [
        '<project xmlns="http://maven.apache.org/POM/4.0.0">',
        "  <modelVersion>4.0.0</modelVersion>",
        "  <groupId>com.secscan.synthetic</groupId>",
        "  <artifactId>gradle-" + str(index) + "</artifactId>",
        "  <version>0.0.0</version>",
    ]
    if boms:
        lines.append("  <dependencyManagement>")
        lines.append("    <dependencies>")
        for group, artifact, version in boms:
            lines.append("      <dependency>")
            lines.append("        <groupId>" + _xml_escape(group) + "</groupId>")
            lines.append("        <artifactId>" + _xml_escape(artifact) + "</artifactId>")
            lines.append("        <version>" + _xml_escape(version) + "</version>")
            lines.append("        <type>pom</type>")
            lines.append("        <scope>import</scope>")
            lines.append("      </dependency>")
        lines.append("    </dependencies>")
        lines.append("  </dependencyManagement>")
    lines.append("  <dependencies>")
    for group, artifact, version, is_pom in deps:
        lines.append("    <dependency>")
        lines.append("      <groupId>" + _xml_escape(group) + "</groupId>")
        lines.append("      <artifactId>" + _xml_escape(artifact) + "</artifactId>")
        if version is not None:
            lines.append("      <version>" + _xml_escape(version) + "</version>")
        if is_pom:
            lines.append("      <type>pom</type>")
        lines.append("    </dependency>")
    lines.append("  </dependencies>")
    lines.append("</project>")
    return "\n".join(lines) + "\n"


# --- Per-file resolution -----------------------------------------------------------------------------------

def resolve_build_file(checkout, build_path, libraries, bundles):
    """``(deps, boms, warning_details)`` for one build file. ``deps`` are ``(group, artifact, version_or_None,
    is_pom)`` (keep wins + de-dup; ``None`` version = BOM-managed). ``boms`` are ``(group, artifact, version)``.
    ``warning_details`` is one string per skipped dependency/construct (GRADLE_UNPARSEABLE_CONSTRUCT)."""
    try:
        size = os.path.getsize(build_path)
    except OSError:
        return [], [], []
    if size > _MAX_FILE_BYTES:
        return [], [], ["build file skipped: larger than " + str(_MAX_FILE_BYTES) + " bytes"]
    try:
        with open(build_path, "r", encoding="utf-8", errors="replace") as handle:
            text = handle.read()
    except OSError as exc:
        log("could not read " + build_path + ": " + str(exc))
        return [], [], []

    text = strip_comments(text)
    variables = collect_variables(checkout, os.path.dirname(build_path), text)

    dep_candidates, bom_candidates, warnings = [], [], []
    for line in text.splitlines():
        d, b, w = extract_from_line(line, libraries, bundles)
        dep_candidates.extend(d)
        bom_candidates.extend(b)
        warnings.extend(w)

    # --- BOM imports (D-041) ---
    boms = []
    boot_version, boot_present = detect_boot_bom(text, variables)
    if boot_version:
        boms.append((_BOOT_DEPENDENCIES[0], _BOOT_DEPENDENCIES[1], boot_version))
    elif boot_present:
        warnings.append("Spring Boot plugin is applied but its version could not be resolved; "
                        "spring-boot-dependencies BOM was not imported")
    for raw in _MAVEN_BOM.findall(text):
        parts = raw.split(":")
        version = _resolve_version(parts[2], variables) if len(parts) >= 3 else None
        if len(parts) >= 3 and _SAFE_TOKEN.match(parts[0]) and _SAFE_TOKEN.match(parts[1]) and version:
            boms.append((parts[0], parts[1], version))
        else:
            warnings.append("mavenBom '" + raw + "' could not be resolved to a concrete version; not imported")
    for group, artifact, raw in bom_candidates:
        version = _resolve_version(raw, variables)
        if version:
            boms.append((group, artifact, version))
        else:
            warnings.append("platform BOM '" + group + ":" + artifact
                            + "' could not be resolved to a concrete version; not imported")
    boms = list(dict.fromkeys(boms))
    has_bom = bool(boms)

    # --- Dependencies: concrete version wins; no-version is BOM-managed (if a BOM exists) else warned; an
    #     explicit non-concrete version is always skipped+warned (the BOM manages only MISSING versions). ---
    by_ga = {}  # (group, artifact) -> set of versions (None = version-less)
    for group, artifact, raw in dep_candidates:
        version = _resolve_version(raw, variables)
        ga = (group, artifact)
        if version is not None:
            by_ga.setdefault(ga, set()).add(version)
        elif raw is None:
            if has_bom:
                by_ga.setdefault(ga, set()).add(None)  # managed by an imported BOM
            else:
                warnings.append("dependency '" + group + ":" + artifact
                                + "' declared with no version and no BOM was imported to manage it")
        else:
            warnings.append("dependency '" + group + ":" + artifact + "' has a non-concrete version '"
                            + raw + "' (skipped, not guessed)")

    deps = []
    for (group, artifact), versions in by_ga.items():
        concrete = sorted(v for v in versions if v is not None)
        if concrete:
            for v in concrete:
                deps.append((group, artifact, v, False))
        else:
            deps.append((group, artifact, None, False))  # version-less, BOM-managed

    # OSV's transitive resolver only engages when the pom has a concrete-versioned dependency. When every
    # dependency is BOM-managed (version-less), add the first BOM as a versioned <type>pom</type> dependency to
    # trigger resolution -- a BOM pom contributes no packages of its own, so this adds no findings.
    if has_bom and not any(v is not None for _, _, v, _ in deps) and any(v is None for _, _, v, _ in deps):
        bg, ba, bv = boms[0]
        deps.append((bg, ba, bv, True))

    # Constructs a static reader cannot follow -- surfaced so the gap is visible (D-030), not silent.
    if re.search(r"\bapply\s+from\s*:", text):
        warnings.append("`apply from:` applies an external script that was not followed")
    if "resolutionStrategy" in text:
        warnings.append("`resolutionStrategy` version pinning/substitution was not applied")

    return deps, boms, list(dict.fromkeys(warnings))


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
        rel = (root + os.sep).replace(checkout, "", 1)
        in_buildsrc = ("buildSrc" + os.sep) in rel or os.path.basename(root) == "buildSrc"
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
            deps, boms, details = resolve_build_file(checkout, build_path, libraries, bundles)
            for detail in details:
                warn(detail, rel)
            if not deps:
                continue  # nothing to resolve -> no synthetic pom (warnings above record the gaps)
            pom_dir = os.path.join(out_dir, str(index))
            pom_path = os.path.join(pom_dir, "pom.xml")
            try:
                os.makedirs(pom_dir, exist_ok=True)
                with open(pom_path, "w", encoding="utf-8") as handle:
                    handle.write(build_synthetic_pom(index, deps, boms))
            except OSError as exc:
                # A write/permission failure is a VISIBLE coverage gap (D-030), never a silent empty result.
                warn("could not write the synthetic pom (" + str(exc) + "); its dependencies were not scanned",
                     rel)
                continue
            poms.append({"syntheticPath": os.path.abspath(pom_path), "buildFile": rel})
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
