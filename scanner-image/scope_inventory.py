#!/usr/bin/env python3
"""SecScan declared dev/test scope inventory (M7, D-059; review S4).

OSV-Scanner exposes NO dependency-group ("dev"/"test") marker (D-035 Q2), so to auto-triage dev/test
dependencies we read the DECLARED scope ourselves — from the repo's own manifests, as DATA ONLY (no build
execution, D-004). We mark ONLY what is confidently dev/test:
  - Maven  pom.xml:        <dependency><scope>test</scope>  -> {coordinate "groupId:artifactId", scope "test"}
  - npm    package.json:   devDependencies keys NOT also in dependencies -> {coordinate "<name>", scope "dev"}
           package-lock.json: a package node with "dev": true (covers TRANSITIVES) -> {"<name>", "dev"}
  - Gradle build.gradle(.kts): testImplementation / testRuntimeOnly / ... literals -> {"group:artifact", "test"}

KEYED BY (manifest path, coordinate), review S4: each emitted entry carries the manifest it was declared in, so
the worker auto-ignores a finding only when ITS OWN manifest declares the coordinate dev/test. On top of that, a
coordinate declared PRODUCTION in ANY manifest of the repo is dropped entirely (a REPO-WIDE prod set): a test
dependency in module A that is a compile dependency in module B, or an npm devDependency that is also a prod
(transitive) package, STAYS VISIBLE. Anything we cannot classify is simply omitted, so it STAYS VISIBLE — we
never hide a finding on a guess (D-059). The worker applies DEV_DEPENDENCY/TEST_SCOPE only when the repository's
effective include-dev-deps is false.

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
ECO_MAVEN = "Maven"
ECO_NPM = "npm"

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
    # candidates are collected per manifest; prod[ecosystem] is a REPO-WIDE set that suppresses any coordinate
    # declared production anywhere (review S4).
    candidates = []  # list of {coordinate, scope, ecosystem, filePath}
    prod = {ECO_MAVEN: set(), ECO_NPM: set()}
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
                _maven(path, rel, candidates, prod)
            elif name == "package.json":
                _npm(path, rel, dirpath, checkout, candidates, prod)
            elif name in ("build.gradle", "build.gradle.kts"):
                _gradle(path, rel, candidates, prod)

    # Emit each candidate UNLESS its coordinate is production anywhere in the repo (dedup per manifest+coordinate).
    scopes = []
    seen = set()
    for c in candidates:
        if c["coordinate"] in prod.get(c["ecosystem"], set()):
            continue
        key = (c["filePath"], c["coordinate"])
        if key in seen:
            continue
        seen.add(key)
        scopes.append(c)
    scopes.sort(key=lambda s: (s["filePath"], s["coordinate"]))
    return {"scopes": scopes, "warnings": warnings, "status": "ok"}


def _maven(path, rel, candidates, prod):
    try:
        tree = ET.parse(path)
    except (ET.ParseError, OSError):
        return
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
        if scope == "test":
            candidates.append({"coordinate": coord, "scope": SCOPE_TEST, "ecosystem": ECO_MAVEN, "filePath": rel})
        else:
            prod[ECO_MAVEN].add(coord)


def _npm(path, rel, dirpath, checkout, candidates, prod):
    try:
        doc = json.loads(_read(path) or "{}")
    except json.JSONDecodeError:
        doc = {}
    if isinstance(doc, dict):
        deps = doc.get("dependencies", {})
        if isinstance(deps, dict):
            for name in deps:
                prod[ECO_NPM].add(name)
        dev = doc.get("devDependencies", {})
        if isinstance(dev, dict):
            for name in dev:
                candidates.append({"coordinate": name, "scope": SCOPE_DEV, "ecosystem": ECO_NPM, "filePath": rel})
    # A sibling package-lock.json carries per-package "dev" flags (covers transitives). Authoritative.
    lock_path = os.path.join(dirpath, "package-lock.json")
    if os.path.isfile(lock_path) and not os.path.islink(lock_path):
        lock_rel = os.path.relpath(lock_path, checkout)
        _npm_lock(lock_path, lock_rel, candidates, prod)


def _npm_lock(path, rel, candidates, prod):
    try:
        doc = json.loads(_read(path) or "{}")
    except json.JSONDecodeError:
        return
    if not isinstance(doc, dict):
        return
    # npm v2/v3 lockfile: a "packages" object keyed by "node_modules/<name>" (nested for transitives).
    packages = doc.get("packages")
    if isinstance(packages, dict):
        for key, node in packages.items():
            if not key or not isinstance(node, dict):
                continue  # "" is the project root
            name = key.split("node_modules/")[-1]
            if not name:
                continue
            if node.get("dev") is True:
                candidates.append({"coordinate": name, "scope": SCOPE_DEV, "ecosystem": ECO_NPM, "filePath": rel})
            else:
                prod[ECO_NPM].add(name)  # dev:false anywhere -> stays visible (review S4)
    # npm v1 lockfile: a nested "dependencies" tree with per-entry "dev" flags.
    _npm_lock_v1(doc.get("dependencies"), rel, candidates, prod)


def _npm_lock_v1(deps, rel, candidates, prod):
    if not isinstance(deps, dict):
        return
    for name, node in deps.items():
        if not isinstance(node, dict):
            continue
        if node.get("dev") is True:
            candidates.append({"coordinate": name, "scope": SCOPE_DEV, "ecosystem": ECO_NPM, "filePath": rel})
        else:
            prod[ECO_NPM].add(name)
        _npm_lock_v1(node.get("dependencies"), rel, candidates, prod)


def _gradle(path, rel, candidates, prod):
    text = _read(path)
    if not text:
        return
    for m in _GRADLE_DECL_RE.finditer(text):
        config, coord = m.group(1), m.group(2)
        if _GRADLE_TEST_RE.match(config):
            candidates.append({"coordinate": coord, "scope": SCOPE_TEST, "ecosystem": ECO_MAVEN, "filePath": rel})
        elif _GRADLE_PROD_RE.match(config):
            prod[ECO_MAVEN].add(coord)


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
