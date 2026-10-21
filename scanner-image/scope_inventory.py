#!/usr/bin/env python3
"""SecScan declared dev/test scope inventory (M7, D-059).

OSV-Scanner exposes NO dependency-group ("dev"/"test") marker (D-035 Q2), so to auto-triage dev/test
dependencies we read the DECLARED scope ourselves — from the repo's own manifests, as DATA ONLY (no build
execution, D-004). We mark ONLY what is confidently dev/test:
  - Maven  pom.xml:        <dependency><scope>test</scope>  -> {coordinate "groupId:artifactId", scope "test"}
  - npm    package.json:   devDependencies keys NOT also in dependencies -> {coordinate "<name>", scope "dev"}
  - Gradle build.gradle(.kts): testImplementation / testRuntimeOnly / ... literals NOT also in a prod config
                              -> {coordinate "group:artifact", scope "test"}
A coordinate that ALSO appears in a production scope/config is NOT marked (it is a real dependency). Anything we
cannot classify (an OSV transitive, a version-catalog alias we can't resolve) is simply omitted, so it STAYS
VISIBLE — we never hide a finding on a guess (D-059). The worker applies DEV_DEPENDENCY/TEST_SCOPE only when the
repository's effective include-dev-deps is false.

Run as `python3 -I scope_inventory.py <checkout>` (scanned tree off sys.path; stdlib only; no network). Output
(stdout): {"scopes": [{coordinate, scope, ecosystem, filePath}], "warnings": [...], "status": "ok"}.
"""
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SCOPE_TEST = "test"
SCOPE_DEV = "dev"

# Gradle config name -> is it a TEST config? Prod configs resolve to visible deps.
_GRADLE_TEST_RE = re.compile(r"^(test|androidTest|integrationTest|functionalTest|jmh)[A-Za-z0-9]*$")
_GRADLE_PROD_RE = re.compile(
    r"^(implementation|api|compile|runtime|compileOnly|compileOnlyApi|runtimeOnly|providedCompile|"
    r"providedRuntime|annotationProcessor|developmentOnly|kapt|ksp)$")
# config( 'group:artifact[:version]' )  /  config "group:artifact"
_GRADLE_DECL_RE = re.compile(
    r"""([A-Za-z][A-Za-z0-9]*)\s*[(\s]\s*['"]([A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+)(?::[^'"]*)?['"]""")


def _read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def _strip_ns(tag):
    return tag.rsplit("}", 1)[-1]


def scan(checkout):
    scopes = []
    warnings = []
    root_real = os.path.realpath(checkout)
    for dirpath, dirnames, filenames in os.walk(checkout, followlinks=False):
        dirnames[:] = [d for d in dirnames if d != ".git" and not os.path.islink(os.path.join(dirpath, d))]
        for name in filenames:
            path = os.path.join(dirpath, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue  # never follow a symlink out of the clone
            if os.path.realpath(path) != path and not os.path.realpath(path).startswith(root_real + os.sep):
                continue
            rel = os.path.relpath(path, checkout)
            if name == "pom.xml":
                _maven(path, rel, scopes)
            elif name == "package.json":
                _npm(path, rel, scopes)
            elif name in ("build.gradle", "build.gradle.kts"):
                _gradle(path, rel, scopes)
    return {"scopes": scopes, "warnings": warnings, "status": "ok"}


def _maven(path, rel, scopes):
    try:
        tree = ET.parse(path)
    except (ET.ParseError, OSError):
        return
    test = set()
    prod = set()
    for dep in tree.iter():
        if _strip_ns(dep.tag) != "dependency":
            continue
        gid = aid = scope = None
        for child in dep:
            tag = _strip_ns(child.tag)
            if tag == "groupId":
                gid = (child.text or "").strip()
            elif tag == "artifactId":
                aid = (child.text or "").strip()
            elif tag == "scope":
                scope = (child.text or "").strip().lower()
        if not gid or not aid:
            continue
        coord = gid + ":" + aid
        (test if scope == "test" else prod).add(coord)
    for coord in sorted(test - prod):
        scopes.append({"coordinate": coord, "scope": SCOPE_TEST, "ecosystem": "Maven", "filePath": rel})


def _npm(path, rel, scopes):
    try:
        doc = json.loads(_read(path) or "{}")
    except json.JSONDecodeError:
        return
    if not isinstance(doc, dict):
        return
    prod = set(doc.get("dependencies", {}) or {}) if isinstance(doc.get("dependencies"), dict) else set()
    dev = doc.get("devDependencies", {})
    if not isinstance(dev, dict):
        return
    for name in sorted(set(dev) - prod):
        scopes.append({"coordinate": name, "scope": SCOPE_DEV, "ecosystem": "npm", "filePath": rel})


def _gradle(path, rel, scopes):
    text = _read(path)
    if not text:
        return
    test = set()
    prod = set()
    for m in _GRADLE_DECL_RE.finditer(text):
        config, coord = m.group(1), m.group(2)
        if _GRADLE_TEST_RE.match(config):
            test.add(coord)
        elif _GRADLE_PROD_RE.match(config):
            prod.add(coord)
    for coord in sorted(test - prod):
        scopes.append({"coordinate": coord, "scope": SCOPE_TEST, "ecosystem": "Maven", "filePath": rel})


def main(argv):
    if len(argv) != 2:
        print(json.dumps({"scopes": [], "warnings": [], "status": "error"}))
        return 2
    try:
        result = scan(argv[1])
    except Exception as exc:  # never crash the scan on an inventory read
        result = {"scopes": [], "warnings": [{"code": "SCOPE_INVENTORY_ERROR",
                                              "message": type(exc).__name__, "filePath": None}], "status": "error"}
    print(json.dumps(result))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
