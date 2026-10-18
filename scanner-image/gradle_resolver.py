#!/usr/bin/env python3
"""SecScan static Gradle dependency resolver (M4b, D-038 / D-039 / D-041 / D-042).

Reads Gradle build files as DATA ONLY -- never import/exec/eval from the scanned tree (D-004) -- and emits a
synthetic Maven ``pom.xml`` per build file so OSV-Scanner can resolve TRANSITIVE dependencies for Gradle
projects that ship no lockfile. Gradle findings therefore surface in OSV's **Maven** ecosystem (Gradle uses
Maven coordinates and Maven Central), which is also how a ``pom.xml`` scan reports them.

Invoked in the scanner container as::

    python3 -I gradle_resolver.py <checkout_dir> <synthetic_out_dir>

``-I`` (isolated) keeps the scanned tree and the CWD off ``sys.path`` so a planted ``json.py``/``tomllib.py``
can never be imported; only the standard library is used (``tomllib`` for version catalogs, ``urllib`` for the
BOM fetch). It prints a JSON OBJECT on stdout::

    {"poms":     [{"syntheticPath": "/work/synthetic/0/pom.xml", "buildFile": "app/build.gradle"}, ...],
     "warnings": [{"code": "GRADLE_UNPARSEABLE_CONSTRUCT", "message": "...", "filePath": "app/build.gradle"}, ...]}

run-scan.sh records ``poms`` in the manifest (``syntheticPoms``) and passes ``<synthetic_out_dir>`` to OSV; the
parser rewrites OSV's ``source.path`` back to the build file before fingerprinting (D-038). ``warnings`` are
appended to the scan's warnings so every skipped construct is a VISIBLE coverage gap (D-030), never silent.

BOM version expansion (D-042, supersedes D-041's version-less approach): OSV's pomxml enricher does NOT inherit
versions from a ``<dependencyManagement>`` BOM import -- it filters version-less dependencies -- so the resolver
itself FETCHES the governing BOM POMs from Maven Central and writes a concrete ``<version>`` on every
dependency it reports. It builds the managed-version map by reading each BOM's ``<dependencyManagement>`` and
resolving ``<properties>`` interpolation, the ``<parent>`` chain (fetched too) and nested
``<scope>import</scope><type>pom</type>`` entries (fetched and merged recursively). BOM coordinates come from
the Spring Boot Gradle plugin version (-> ``spring-boot-dependencies``), ``platform()``/``enforcedPlatform()``
and ``mavenBom "..."`` (``${prop}`` resolved from ext{}/set()/extra[]/gradle.properties). A dependency still
unresolved after full BOM expansion raises ``GRADLE_UNPARSEABLE_CONSTRUCT`` -- never guessed, never dropped.

Network (D-042): the resolver performs read-only HTTPS GETs of ``.pom`` files from ONE host -- Maven Central
(``repo.maven.apache.org``) or the mirror in ``SECSCAN_MAVEN_BASE_URL`` -- with TLS verification ALWAYS ON
(default context; never disabled) and cross-host redirects blocked. Fetches are bounded (per-POM timeout, total
cap, parent/import recursion depth, response-size cap, in-run cache); a fetch failure raises
``GRADLE_BOM_FETCH_FAILED`` for that BOM and the scan continues best-effort. No build is ever executed; fetched
POMs are parsed as data, never imported/executed (D-004).

Config selection (slice-1 review): resolve EVERY non-``buildSrc`` dependency configuration -- production AND
test -- and let OSV decide what to report (OSV is scope-agnostic, matching how M4 scans real poms); keep wins.
Principled dev/test exclusion (``include_dev_deps`` / ``DEV_DEPENDENCY``) is M7.

Best-effort: a parse error on one file is logged to stderr and skipped; a write failure is surfaced as a
warning (never a silent empty result); it never raises to the caller and never fails the scan.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from urllib.parse import urlsplit

try:
    import tomllib  # Python >= 3.11 (Alpine 3.21 ships python3 3.12.x)
except ModuleNotFoundError:  # pragma: no cover - the image pins a python3 that has tomllib
    tomllib = None

# scan_warning codes (free text, no migration). Coverage gaps we SHOW, never hide (D-030).
WARN_CODE = "GRADLE_UNPARSEABLE_CONSTRUCT"
WARN_FETCH = "GRADLE_BOM_FETCH_FAILED"

_BOOT_DEPENDENCIES = ("org.springframework.boot", "spring-boot-dependencies")

# Maven Central fetch (D-042). TLS verification is ALWAYS on (default context); never disabled.
DEFAULT_MAVEN_BASE = "https://repo.maven.apache.org/maven2"
_PER_POM_TIMEOUT = 15        # seconds per POM GET
_TOTAL_FETCH_CAP = 200       # max POMs fetched per scan run
_PARENT_DEPTH = 8            # max <parent> chain depth
_IMPORT_DEPTH = 6            # max nested <scope>import</scope> recursion
_MAX_POM_BYTES = 10 * 1024 * 1024
_PROP_RE = re.compile(r"\$\{([^}]+)\}")

_BASE_CONFIGS = frozenset({
    "implementation", "api", "compile", "runtime", "compileOnly", "compileOnlyApi", "runtimeOnly",
    "providedCompile", "providedRuntime", "annotationProcessor", "developmentOnly", "kapt", "ksp",
})
_CONFIG_VARIANT = re.compile(
    r"^[a-z][A-Za-z0-9]*(Implementation|CompileOnlyApi|CompileOnly|RuntimeOnly|AnnotationProcessor|Api|Compile|Runtime)$")

# A concrete, pinned version OSV can match: non-empty, version-safe characters only (rejects ranges, dynamic
# "+"/"*", an unresolved "$"/"${", whitespace). Also guarantees XML-safety for the synthetic pom.
_SAFE_TOKEN = re.compile(r"^[A-Za-z0-9._-]+$")
_MAX_FILE_BYTES = 5 * 1024 * 1024


def log(message):
    """Diagnostic to stderr only (captured to resolver.log in the container); never touches stdout (the map)."""
    print("gradle_resolver: " + message, file=sys.stderr)


def _is_config(token):
    return token in _BASE_CONFIGS or bool(_CONFIG_VARIANT.match(token))


def _concrete(version):
    return bool(version) and "${" not in version and bool(_SAFE_TOKEN.match(version))


# --- Comment stripping -------------------------------------------------------------------------------------

def strip_comments(text):
    """Remove ``/* ... */`` blocks and ``// ...`` line comments (approximate but safe: a mis-strip only skips a
    line, never executes anything)."""
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
    """Version variables visible to a build file: root + module ``gradle.properties`` plus every ``ext{}`` form
    in the build file. Only literal string values are captured; a captured extra never affects output unless a
    ``${name}`` references it."""
    variables = {}
    variables.update(parse_properties(os.path.join(checkout, "gradle.properties")))
    if os.path.abspath(build_dir) != os.path.abspath(checkout):
        variables.update(parse_properties(os.path.join(build_dir, "gradle.properties")))
    for match in re.finditer(r'(?:def|val)\s+(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'ext\.(\w+)\s*=\s*["\']([^"\']+)["\']', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''\bset\(\s*["'](\w+)["']\s*,\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''\bextra\[\s*["'](\w+)["']\s*\]\s*=\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''\bval\s+(\w+)\s+by\s+extra\s*\(\s*["']([^"']+)["']''', build_text):
        variables[match.group(1)] = match.group(2)
    for match in re.finditer(r'''(?m)^\s*(\w+)\s*=\s*["']([^"']+)["']''', build_text):
        variables.setdefault(match.group(1), match.group(2))
    return variables


def resolve_interpolation(value, variables):
    """Resolve ``$name`` / ``${name}`` against ``variables``; an unknown variable becomes a sentinel so the
    dependency is skipped rather than guessed."""
    if "$" not in value:
        return value

    def repl(match):
        name = match.group(1) or match.group(2)
        return variables.get(name, "\x00")

    return re.sub(r"\$\{([A-Za-z0-9_.]+)\}|\$([A-Za-z0-9_]+)", repl, value)


def _resolve_version(raw, variables):
    """A concrete version for a raw version token, or None when it is absent / not a concrete pin."""
    if raw is None:
        return None
    value = resolve_interpolation(raw, variables)
    if value.startswith("latest.") or value == "latest":
        return None
    return value if _SAFE_TOKEN.match(value) else None


# --- Version catalog (gradle/libs.versions.toml) -----------------------------------------------------------

def _norm(alias):
    return alias.replace("-", ".").replace("_", ".")


def _version_from_node(node, versions):
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


# --- Maven Central BOM fetch + expansion (D-042) -----------------------------------------------------------

def maven_base_url():
    return (os.environ.get("SECSCAN_MAVEN_BASE_URL") or DEFAULT_MAVEN_BASE).rstrip("/")


def pom_url(base, group, artifact, version):
    return "/".join([base, group.replace(".", "/"), artifact, version, artifact + "-" + version + ".pom"])


class _SameHostRedirect(urllib.request.HTTPRedirectHandler):
    """Block cross-host redirects so the resolver can reach only the configured Maven host."""

    def __init__(self, allowed_host):
        self.allowed_host = allowed_host

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urlsplit(newurl).hostname != self.allowed_host:
            return None
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class MavenFetcher:
    """Read-only HTTPS GET of ``.pom`` files from ONE host. TLS verification is ALWAYS on (urllib's default
    context verifies certificates; it is never disabled). Bounded by a per-POM timeout and a total-fetch cap,
    with an in-run cache. Returns the POM text, or None on any failure (cap reached, timeout, HTTP/TLS error)."""

    def __init__(self, base_url, timeout=_PER_POM_TIMEOUT, total_cap=_TOTAL_FETCH_CAP):
        self.base_url = base_url
        self.allowed_host = urlsplit(base_url).hostname
        self.timeout = timeout
        self.total_cap = total_cap
        self.fetched = 0
        self.cache = {}
        self.opener = urllib.request.build_opener(_SameHostRedirect(self.allowed_host))

    def fetch(self, group, artifact, version):
        key = (group, artifact, version)
        if key in self.cache:
            return self.cache[key]
        if self.fetched >= self.total_cap:
            self.cache[key] = None
            return None
        url = pom_url(self.base_url, group, artifact, version)
        self.fetched += 1
        text = None
        try:
            req = urllib.request.Request(url, method="GET",
                                         headers={"User-Agent": "secscan-gradle-resolver"})
            with self.opener.open(req, timeout=self.timeout) as resp:  # default TLS context verifies certs
                data = resp.read(_MAX_POM_BYTES + 1)
            if len(data) <= _MAX_POM_BYTES:
                text = data.decode("utf-8", errors="replace")
            else:
                log("POM too large, skipping: " + url)
        except (urllib.error.URLError, OSError, ValueError) as exc:
            log("BOM fetch failed " + url + ": " + str(exc))
        self.cache[key] = text
        return text


def _ln(tag):
    return tag.rsplit("}", 1)[-1]


def _child(el, name):
    for c in el:
        if _ln(c.tag) == name:
            return c
    return None


def _ctext(el, name):
    c = _child(el, name)
    return (c.text or "").strip() if c is not None and c.text else ""


def parse_pom(text):
    """Parse a Maven POM into ``(parent_or_None, properties, management)``; None on an unparseable/suspect POM.
    ``management`` is a list of ``(group, artifact, version_raw, scope, type)``. DTD/entity POMs are refused
    (defense against XML entity expansion -- Maven POMs never use them)."""
    head = text[:4096].lower()
    if "<!doctype" in head or "<!entity" in head:
        return None
    try:
        root = ET.fromstring(text)
    except ET.ParseError:
        return None
    parent = None
    pe = _child(root, "parent")
    if pe is not None:
        pg, pa, pv = _ctext(pe, "groupId"), _ctext(pe, "artifactId"), _ctext(pe, "version")
        if pg and pa and pv:
            parent = (pg, pa, pv)
    properties = {}
    props_el = _child(root, "properties")
    if props_el is not None:
        for c in props_el:
            properties[_ln(c.tag)] = (c.text or "").strip()
    management = []
    dm = _child(root, "dependencyManagement")
    if dm is not None:
        deps = _child(dm, "dependencies")
        if deps is not None:
            for d in deps:
                if _ln(d.tag) != "dependency":
                    continue
                mg, ma = _ctext(d, "groupId"), _ctext(d, "artifactId")
                if mg and ma:
                    management.append((mg, ma, _ctext(d, "version"), _ctext(d, "scope"), _ctext(d, "type")))
    return parent, properties, management


def _interp(value, props, limit=10):
    """Resolve ``${name}`` against ``props`` (bounded, multi-level). An unknown name is left intact so the
    result stays non-concrete and is reported as unresolved."""
    if not value or "${" not in value:
        return value
    current = value
    for _ in range(limit):
        if "${" not in current:
            break
        nxt = _PROP_RE.sub(lambda m: props.get(m.group(1), m.group(0)), current)
        if nxt == current:
            break
        current = nxt
    return current


def _effective_pom(group, artifact, version, fetcher, depth, seen):
    """``(properties, management)`` for an artifact POM merged with its parent chain (child overrides parent).
    ``management`` is ``{(group, artifact): (version_raw, scope, type)}``. Returns None only when THIS POM could
    not be fetched (a fetch failure the caller surfaces); a parent fetch failure is skipped best-effort."""
    text = fetcher.fetch(group, artifact, version)
    if text is None:
        return None
    parsed = parse_pom(text)
    if parsed is None:
        return {}, {}
    parent, properties, management = parsed
    props = {}
    managed = {}
    if parent is not None and depth > 0 and parent not in seen:
        seen.add(parent)
        parent_eff = _effective_pom(parent[0], parent[1], parent[2], fetcher, depth - 1, seen)
        if parent_eff is not None:
            props.update(parent_eff[0])
            managed.update(parent_eff[1])
    props.update(properties)
    props.setdefault("project.version", version)
    props.setdefault("project.groupId", group)
    for (mg, ma, mv, scope, mtype) in management:
        managed[(mg, ma)] = (mv, scope, mtype)
    return props, managed


def build_managed_map(bom_coords, fetcher):
    """``(merged_map, warnings)``: the concrete managed-version map for the given BOM coordinates, expanding
    each BOM's parent chain, ``<properties>`` and nested ``<scope>import</scope>`` BOMs. ``merged_map`` is
    ``{(group, artifact): version}`` (first declaration wins, Maven-style). ``warnings`` are ``(code, detail)``
    for BOMs that could not be fetched (GRADLE_BOM_FETCH_FAILED)."""
    merged = {}
    warnings = []

    def add_bom(coord, import_depth, seen_imports):
        if coord in seen_imports or import_depth <= 0:
            return
        seen_imports.add(coord)
        eff = _effective_pom(coord[0], coord[1], coord[2], fetcher, _PARENT_DEPTH, set())
        if eff is None:
            warnings.append((WARN_FETCH, "could not fetch BOM " + ":".join(coord)
                             + " from Maven Central; its managed versions are unavailable"))
            return
        props, managed = eff
        for (mg, ma), (mv, scope, mtype) in managed.items():
            version = _interp(mv, props)
            if scope == "import" and mtype == "pom":
                if _concrete(version):
                    add_bom((mg, ma, version), import_depth - 1, seen_imports)
            elif _concrete(version) and (mg, ma) not in merged:
                merged[(mg, ma)] = version

    for coord in bom_coords:
        add_bom(coord, _IMPORT_DEPTH, set())
    return merged, warnings


# --- Dependency / BOM extraction (syntactic; version resolution happens in resolve_build_file) -------------

_COORD_RE = re.compile(r"""["']([^"']+)["']""")
_MAP_GROUP = re.compile(r"""\bgroup\s*:\s*["']([^"']+)["']""")
_MAP_NAME = re.compile(r"""\bname\s*:\s*["']([^"']+)["']""")
_MAP_VERSION = re.compile(r"""\bversion\s*:\s*["']([^"']+)["']""")
_LIBS_RE = re.compile(r"\blibs((?:\.[A-Za-z0-9_]+)+)")
_PLATFORM_RE = re.compile(r"\b(?:enforcedPlatform|platform)\s*\(")
_CONFIG_SCAN = re.compile(r"\b([a-zA-Z][A-Za-z0-9]*)\b")
_INTERNAL_RE = re.compile(r"\b(project|files|fileTree|gradleApi|localGroovy|kotlin|gradleTestKit)\s*\(")
_BOOT_PLUGIN_VERSION = re.compile(
    r"""id\s*\(?\s*["']org\.springframework\.boot["']\s*\)?\s+version\s+["']([^"']+)["']""")
_BOOT_PLUGIN_CLASSPATH = re.compile(r"""spring-boot-gradle-plugin:([^\s"')]+)""")
_BOOT_PLUGIN_PRESENT = re.compile(
    r"""(?:id\s*\(?\s*|apply\s+plugin\s*:\s*)["']org\.springframework\.boot["']""")
_MAVEN_BOM = re.compile(r"""\bmavenBom\s*\(?\s*["']([^"']+)["']""")


def extract_from_line(line, libraries, bundles):
    """``(deps, boms, warnings)`` RAW candidates for one line. Elements are ``(group, artifact,
    raw_version_or_None)``; a ``platform(...)``/``enforcedPlatform(...)`` argument routes to ``boms``."""
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

    if _MAP_NAME.search(rest):
        g = _MAP_GROUP.search(rest)
        n = _MAP_NAME.search(rest)
        v = _MAP_VERSION.search(rest)
        if g and n:
            d, b = route([(g.group(1), n.group(1), v.group(1) if v else None)])
            return d, b, []
        return [], [], []

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


def _bom_coords(text, bom_candidates, variables, warnings):
    """Concrete BOM coordinates to import, from the Boot plugin, mavenBom "..." and platform(...)."""
    coords = []
    boot_version, boot_present = detect_boot_bom(text, variables)
    if boot_version:
        coords.append((_BOOT_DEPENDENCIES[0], _BOOT_DEPENDENCIES[1], boot_version))
    elif boot_present:
        warnings.append((WARN_CODE, "Spring Boot plugin is applied but its version could not be resolved; "
                         "spring-boot-dependencies BOM was not imported"))
    for raw in _MAVEN_BOM.findall(text):
        parts = raw.split(":")
        version = _resolve_version(parts[2], variables) if len(parts) >= 3 else None
        if len(parts) >= 3 and _SAFE_TOKEN.match(parts[0]) and _SAFE_TOKEN.match(parts[1]) and version:
            coords.append((parts[0], parts[1], version))
        else:
            warnings.append((WARN_CODE, "mavenBom '" + raw + "' could not be resolved to a concrete version; "
                             "not imported"))
    for group, artifact, raw in bom_candidates:
        version = _resolve_version(raw, variables)
        if version:
            coords.append((group, artifact, version))
        else:
            warnings.append((WARN_CODE, "platform BOM '" + group + ":" + artifact
                             + "' could not be resolved to a concrete version; not imported"))
    return list(dict.fromkeys(coords))


# --- Synthetic pom -----------------------------------------------------------------------------------------

def _xml_escape(value):
    return (value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace('"', "&quot;").replace("'", "&apos;"))


def build_synthetic_pom(index, deps, boms):
    """The synthetic pom XML: every dependency carries a concrete ``<version>`` (D-042). ``boms`` are also kept
    as ``<dependencyManagement>`` imports (harmless, documents intent)."""
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
    for group, artifact, version in deps:
        lines.append("    <dependency>")
        lines.append("      <groupId>" + _xml_escape(group) + "</groupId>")
        lines.append("      <artifactId>" + _xml_escape(artifact) + "</artifactId>")
        lines.append("      <version>" + _xml_escape(version) + "</version>")
        lines.append("    </dependency>")
    lines.append("  </dependencies>")
    lines.append("</project>")
    return "\n".join(lines) + "\n"


# --- Per-file resolution -----------------------------------------------------------------------------------

def resolve_build_file(checkout, build_path, libraries, bundles, fetcher):
    """``(deps, boms, warnings)`` for one build file. ``deps`` are ``(group, artifact, version)`` -- EVERY one
    concrete (D-042), de-duplicated, keep wins. ``boms`` are the imported BOM coordinates. ``warnings`` are
    ``(code, detail)``."""
    try:
        size = os.path.getsize(build_path)
    except OSError:
        return [], [], []
    if size > _MAX_FILE_BYTES:
        return [], [], [(WARN_CODE, "build file skipped: larger than " + str(_MAX_FILE_BYTES) + " bytes")]
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
        warnings.extend((WARN_CODE, detail) for detail in w)

    boms = _bom_coords(text, bom_candidates, variables, warnings)
    managed, fetch_warnings = build_managed_map(boms, fetcher)
    warnings.extend(fetch_warnings)

    by_ga = {}
    for group, artifact, raw in dep_candidates:
        version = _resolve_version(raw, variables)
        ga = (group, artifact)
        if version is not None:
            by_ga.setdefault(ga, set()).add(version)
        elif raw is None:
            managed_version = managed.get(ga)
            if managed_version is not None:
                by_ga.setdefault(ga, set()).add(managed_version)
            else:
                warnings.append((WARN_CODE, "dependency '" + group + ":" + artifact
                                 + "' has no version and no imported BOM manages it"))
        else:
            warnings.append((WARN_CODE, "dependency '" + group + ":" + artifact + "' has a non-concrete version '"
                             + raw + "' (skipped, not guessed)"))

    deps = []
    for (group, artifact), versions in by_ga.items():
        for version in sorted(versions):
            deps.append((group, artifact, version))

    if re.search(r"\bapply\s+from\s*:", text):
        warnings.append((WARN_CODE, "`apply from:` applies an external script that was not followed"))
    if "resolutionStrategy" in text:
        warnings.append((WARN_CODE, "`resolutionStrategy` version pinning/substitution was not applied"))

    return deps, boms, list(dict.fromkeys(warnings))


# --- Walk --------------------------------------------------------------------------------------------------

def _has_lockfile(directory):
    return (os.path.isfile(os.path.join(directory, "gradle.lockfile"))
            or os.path.isfile(os.path.join(directory, "buildscript-gradle.lockfile")))


def find_build_files(checkout):
    """``(eligible, buildsrc)``: build files with no sibling lockfile, and skipped ``buildSrc`` build files.
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
    eligible.sort()
    buildsrc.sort()
    return eligible, buildsrc


def _rel(checkout, path):
    return os.path.relpath(path, checkout).replace(os.sep, "/")


def resolve(checkout, out_dir, fetcher=None):
    """Resolve the whole checkout: ``{"poms": [...], "warnings": [...]}``. Never raises."""
    if fetcher is None:
        fetcher = MavenFetcher(maven_base_url())
    poms = []
    warnings = []

    def warn(code, detail, build_file):
        warnings.append({"code": code, "message": build_file + ": " + detail, "filePath": build_file})

    try:
        libraries, bundles = load_version_catalog(checkout)
        eligible, buildsrc = find_build_files(checkout)
        for build_path in buildsrc:
            warn(WARN_CODE, "buildSrc build logic is not resolved (dependencies declared here are not scanned)",
                 _rel(checkout, build_path))
        index = 0
        for build_path in eligible:
            rel = _rel(checkout, build_path)
            deps, boms, details = resolve_build_file(checkout, build_path, libraries, bundles, fetcher)
            for code, detail in details:
                warn(code, detail, rel)
            if not deps:
                continue
            pom_dir = os.path.join(out_dir, str(index))
            pom_path = os.path.join(pom_dir, "pom.xml")
            try:
                os.makedirs(pom_dir, exist_ok=True)
                with open(pom_path, "w", encoding="utf-8") as handle:
                    handle.write(build_synthetic_pom(index, deps, boms))
            except OSError as exc:
                warn(WARN_CODE, "could not write the synthetic pom (" + str(exc)
                     + "); its dependencies were not scanned", rel)
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
