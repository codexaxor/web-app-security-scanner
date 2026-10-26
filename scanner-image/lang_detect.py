#!/usr/bin/env python3
"""SecScan static language detector (M8.5, D-081).

Reads a cloned repository as DATA ONLY (no build execution, D-004) and reports the programming languages it
contains, by summing file SIZES per extension (never reading file CONTENTS). The worker stores the result on
`repository.languages`, refreshed on every successful scan.

Rules (approved plan, M8.5 item 6):
  * counts file sizes by extension only — contents are never opened/read;
  * skips `.git`, `node_modules`, `vendor`, `build`, `dist`, `target`, `.gradle`;
  * never follows symlinks (os.walk followlinks=False, symlinked dirs AND files skipped) and realpath-confines
    every file to the checkout, mirroring eol_detect.py / gradle_resolver.py;
  * display names match GitLab's (Java, Kotlin, HTML, CSS, JavaScript, TypeScript, Python, FreeMarker, Shell,
    Dockerfile, C#, …);
  * keeps only languages with a >= 1% share of counted bytes;
  * bounded (stops after MAX_FILES entries).

Output (stdout): {"languages": ["Java", "Kotlin", ...], "status": "ok"} with languages ordered by share
descending. On usage/IO error: {"languages": [], "status": "error", "error": "..."} and a non-zero exit — the
worker treats an empty list as "no update" and NEVER clears existing languages.

Run as `python3 -I lang_detect.py <checkout>` so the scanned tree and CWD are off sys.path. Stdlib only.
"""
import json
import os
import sys

SKIP_DIRS = {".git", "node_modules", "vendor", "build", "dist", "target", ".gradle"}
MAX_FILES = 200000
MIN_SHARE = 0.01  # keep languages with >= 1% of counted bytes

# File extension (lowercase, with dot) -> GitLab-style language name. Only recognised source languages count
# toward the shares; everything else (data, docs, binaries) is ignored.
EXT_LANG = {
    ".java": "Java",
    ".kt": "Kotlin", ".kts": "Kotlin",
    ".groovy": "Groovy", ".gradle": "Groovy",
    ".scala": "Scala",
    ".html": "HTML", ".htm": "HTML",
    ".css": "CSS", ".scss": "SCSS", ".sass": "Sass", ".less": "Less",
    ".js": "JavaScript", ".jsx": "JavaScript", ".mjs": "JavaScript", ".cjs": "JavaScript",
    ".ts": "TypeScript", ".tsx": "TypeScript",
    ".vue": "Vue", ".svelte": "Svelte",
    ".py": "Python",
    ".rb": "Ruby",
    ".go": "Go",
    ".rs": "Rust",
    ".cs": "C#",
    ".c": "C", ".h": "C",
    ".cpp": "C++", ".cc": "C++", ".cxx": "C++", ".hpp": "C++",
    ".php": "PHP",
    ".sh": "Shell", ".bash": "Shell",
    ".ps1": "PowerShell",
    ".ftl": "FreeMarker", ".ftlh": "FreeMarker",
    ".sql": "SQL",
    ".swift": "Swift",
    ".tf": "HCL", ".hcl": "HCL",
    ".yaml": "YAML", ".yml": "YAML",
    ".xml": "XML",
}


def _language_of(name):
    lower = name.lower()
    if lower == "dockerfile" or lower.startswith("dockerfile.") or lower.endswith(".dockerfile"):
        return "Dockerfile"
    _, ext = os.path.splitext(lower)
    return EXT_LANG.get(ext)


def detect(root):
    root_real = os.path.realpath(root)
    if not os.path.isdir(root_real):
        raise OSError("not a directory: " + root)
    sizes = {}
    seen = 0
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        # Prune skipped and symlinked directories in place (so os.walk never descends into them).
        dirnames[:] = [d for d in dirnames
                       if d not in SKIP_DIRS and not os.path.islink(os.path.join(dirpath, d))]
        for name in filenames:
            if seen >= MAX_FILES:
                break
            seen += 1
            lang = _language_of(name)
            if lang is None:
                continue
            path = os.path.join(dirpath, name)
            if os.path.islink(path) or not os.path.isfile(path):
                continue  # never follow a symlinked file
            real = os.path.realpath(path)
            if real != root_real and not real.startswith(root_real + os.sep):
                continue  # escaped the checkout
            try:
                size = os.path.getsize(path)  # SIZE only; contents are never read
            except OSError:
                continue
            sizes[lang] = sizes.get(lang, 0) + size
        if seen >= MAX_FILES:
            break

    total = sum(sizes.values())
    if total <= 0:
        return []
    kept = [(lang, n) for lang, n in sizes.items() if n / total >= MIN_SHARE]
    kept.sort(key=lambda item: (-item[1], item[0]))  # share desc, then name for stability
    return [lang for lang, _ in kept]


def main(argv):
    if len(argv) < 2:
        print(json.dumps({"languages": [], "status": "error", "error": "usage"}))
        return 1
    try:
        languages = detect(argv[1])
    except OSError as e:
        print(json.dumps({"languages": [], "status": "error", "error": str(e)}))
        return 1
    print(json.dumps({"languages": languages, "status": "ok"}))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
