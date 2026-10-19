#!/usr/bin/env python3
"""SecScan static end-of-life (EOL) detector (M6, D-055).

Reads a cloned repository as DATA ONLY (no build execution, D-004) and emits the runtimes/frameworks it can
statically identify, each as {product, version, cycle, filePath}. `product` is an endoflife.date v1 slug; `cycle`
is the endoflife release identifier (e.g. Spring Boot "2.7", Node "18", .NET "6.0", Java/Temurin "17",
Alpine "3.14"). The worker joins these against the `eol_cycle` intel cache to decide whether each is actually an
issue (past-EOL / ending-soon) — this script makes NO EOL judgement itself and reaches no network.

Output (stdout): {"detections": [ {product, version, cycle, filePath} ], "warnings": [ {code,message,filePath} ],
"status": "ok"}.

Run as `python3 -I eol_detect.py <checkout>` so the scanned tree and CWD are off sys.path (a planted module can
never be imported). Stdlib only.
"""
import json
import os
import re
import sys

# The default endoflife.date slug for a bare Java language level (no vendor named). Configurable (D-055); a
# Dockerfile `FROM <vendor-jdk>:N` overrides it with the named distro slug.
JAVA_DEFAULT_SLUG = "eclipse-temurin"

# Known container base-image repo -> endoflife.date slug. Only images we can map to a product are emitted.
IMAGE_SLUGS = {
    "alpine": "alpine-linux",
    "node": "nodejs",
    "eclipse-temurin": "eclipse-temurin",
    "amazoncorretto": "amazon-corretto",
    "keycloak": "keycloak",
    "quay.io/keycloak/keycloak": "keycloak",
}


def _cycle_major(version):
    # First integer run anywhere, so range/prefix syntax (^18, >=20.0.0, 18.x, v18) yields the major.
    m = re.search(r"(\d+)", version or "")
    return m.group(1) if m else None


def _cycle_major_minor(version):
    m = re.search(r"(\d+)\.(\d+)", version or "")
    return f"{m.group(1)}.{m.group(2)}" if m else _cycle_major(version)


def _read(path):
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            return fh.read()
    except OSError:
        return ""


def detect_spring_boot(text, rel, out):
    # Maven: <parent> spring-boot-starter-parent <version>X.Y.Z</version>, or a <spring-boot.version> property.
    m = re.search(r"spring-boot-starter-parent.*?<version>\s*([0-9]+\.[0-9]+(?:\.[0-9]+)?)", text, re.DOTALL)
    if not m:
        m = re.search(r"<spring-boot\.version>\s*([0-9]+\.[0-9]+(?:\.[0-9]+)?)", text)
    # Gradle plugin: id 'org.springframework.boot' version 'X.Y.Z'  /  id("...") version "X.Y.Z"
    if not m:
        m = re.search(r"org\.springframework\.boot['\"]?\s*\)?\s*version\s*[('\"]+\s*([0-9]+\.[0-9]+(?:\.[0-9]+)?)",
                      text)
    if m:
        out.append({"product": "spring-boot", "version": m.group(1), "cycle": _cycle_major_minor(m.group(1)),
                    "filePath": rel})


def detect_java(text, rel, out):
    # Maven: <java.version>, <maven.compiler.release|source|target>. Gradle: sourceCompatibility,
    # JavaLanguageVersion.of(N). Take the first that parses to a major version.
    for pat in (r"<java\.version>\s*([0-9]+(?:\.[0-9]+)?)",
                r"<maven\.compiler\.(?:release|source|target)>\s*([0-9]+(?:\.[0-9]+)?)",
                r"JavaLanguageVersion\.of\(\s*([0-9]+)\s*\)",
                r"sourceCompatibility\s*=?\s*['\"]?(?:JavaVersion\.VERSION_)?([0-9]+)"):
        m = re.search(pat, text)
        if m:
            raw = m.group(1)
            # Legacy "1.8" style -> major 8.
            major = raw.split(".")[1] if raw.startswith("1.") and "." in raw else raw.split(".")[0]
            out.append({"product": JAVA_DEFAULT_SLUG, "version": raw, "cycle": major, "filePath": rel})
            return


def detect_dotnet(text, rel, out):
    # .csproj: <TargetFramework(s)>netX.Y</TargetFramework(s)> (also netcoreappX.Y). Emit each distinct cycle.
    seen = set()
    for m in re.finditer(r"<TargetFrameworks?>\s*([^<]+)", text):
        for tfm in re.split(r"[;,]", m.group(1)):
            mm = re.match(r"\s*(?:net|netcoreapp)([0-9]+\.[0-9]+)", tfm)
            if mm and mm.group(1) not in seen:
                seen.add(mm.group(1))
                out.append({"product": "dotnet", "version": tfm.strip(), "cycle": mm.group(1), "filePath": rel})


def detect_node_file(path, rel, out):
    base = os.path.basename(path)
    if base == ".nvmrc":
        v = _read(path).strip().lstrip("v")
        c = _cycle_major(v)
        if c:
            out.append({"product": "nodejs", "version": v, "cycle": c, "filePath": rel})
    elif base == "package.json":
        text = _read(path)
        m = re.search(r'"engines"\s*:\s*\{[^}]*?"node"\s*:\s*"([^"]+)"', text, re.DOTALL)
        if m:
            c = _cycle_major(m.group(1))
            if c:
                out.append({"product": "nodejs", "version": m.group(1).strip(), "cycle": c, "filePath": rel})


def detect_dockerfile(text, rel, out):
    for m in re.finditer(r"(?im)^\s*FROM\s+([^\s]+)", text):
        image = m.group(1).split("@")[0]  # drop any @sha256 digest
        if ":" not in image:
            continue
        repo, tag = image.rsplit(":", 1)
        repo = repo.lower()
        slug = IMAGE_SLUGS.get(repo)
        if slug is None:
            # match a trailing path component too (e.g. docker.io/library/node)
            slug = IMAGE_SLUGS.get(repo.rsplit("/", 1)[-1])
        if slug is None or tag in ("latest", ""):
            continue
        cycle = _cycle_major_minor(tag) if slug == "alpine-linux" else _cycle_major(tag)
        if cycle:
            out.append({"product": slug, "version": tag, "cycle": cycle, "filePath": rel})


def scan(root):
    detections = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != ".git"]
        for name in filenames:
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root)
            if name == "pom.xml":
                text = _read(path)
                detect_spring_boot(text, rel, detections)
                detect_java(text, rel, detections)
            elif name.endswith((".gradle", ".gradle.kts")):
                text = _read(path)
                detect_spring_boot(text, rel, detections)
                detect_java(text, rel, detections)
            elif name.endswith(".csproj"):
                detect_dotnet(_read(path), rel, detections)
            elif name in (".nvmrc", "package.json"):
                detect_node_file(path, rel, detections)
            elif name == "Dockerfile" or name.startswith("Dockerfile."):
                detect_dockerfile(_read(path), rel, detections)
    # De-duplicate identical (product, cycle, filePath) tuples.
    seen = set()
    unique = []
    for d in detections:
        key = (d["product"], d["cycle"], d["filePath"])
        if key not in seen:
            seen.add(key)
            unique.append(d)
    return unique


def main(argv):
    if len(argv) < 2:
        print(json.dumps({"detections": [], "warnings": [], "status": "error"}))
        return 1
    root = argv[1]
    try:
        detections = scan(root)
    except Exception as exc:  # never crash the scan; emit a partial result + a warning
        print(json.dumps({"detections": [], "status": "ok",
                          "warnings": [{"code": "EOL_DETECTOR_ERROR",
                                        "message": f"EOL detector error: {type(exc).__name__}", "filePath": ""}]}))
        return 0
    print(json.dumps({"detections": detections, "warnings": [], "status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
