#!/usr/bin/env python3
"""In-image parity + refusal test for the scanner's branch-name validator (D-076 A1/A2, SF-5, N-1).

Runs INSIDE the built secscan-scanner image (busybox sh + the shipped secscan-lib.sh / run-scan.sh), so the shell
validator is tested against the REAL runtime, not host dash. Two checks:
  1. valid_branch_name agrees with scanner-image/branch-vectors.json (the SAME file BranchNameValidationTest reads),
     so the Java and shell validators are proven to agree on identical inputs (incl. unicode code-point lengths).
  2. run-scan.sh refuses an invalid SCAN_BRANCH with exit 11 BEFORE any git invocation (a git stub on PATH that
     records calls must never be touched).

Invoked as `python3 -I /host/branch_vectors_test.py` with LC_ALL=C.UTF-8 so busybox counts code points."""

import json
import os
import subprocess
import sys
import tempfile

LIB = os.environ.get("SECSCAN_LIB", "/usr/local/bin/secscan-lib.sh")
RUN_SCAN = os.environ.get("RUN_SCAN", "/usr/local/bin/run-scan.sh")
VECTORS = os.environ.get("BRANCH_VECTORS", "/host/branch-vectors.json")


def shell_valid(name):
    r = subprocess.run(
        ["sh", "-c", '. "$1"; valid_branch_name "$2"', "sh", LIB, name],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )
    return r.returncode == 0


def test_vectors():
    with open(VECTORS, encoding="utf-8") as fh:
        vectors = json.load(fh)
    fails = []
    for v in vectors:
        got = shell_valid(v["name"])
        if got != v["valid"]:
            fails.append((v["name"], v["valid"], got))
    for name, exp, got in fails:
        print(f"VECTOR MISMATCH: {name!r} expected={exp} got={got}", file=sys.stderr)
    if not fails:
        print(f"vectors: {len(vectors)} agree with the shell validator")
    return not fails


def test_run_scan_refuses_invalid_before_git():
    d = tempfile.mkdtemp()
    marker = os.path.join(d, "git-was-called")
    gitstub = os.path.join(d, "git")
    with open(gitstub, "w") as fh:
        fh.write(f'#!/bin/sh\necho called >> "{marker}"\nexit 0\n')
    os.chmod(gitstub, 0o755)
    os.makedirs("/out", exist_ok=True)
    os.makedirs("/work", exist_ok=True)
    env = dict(os.environ)
    env["PATH"] = d + ":" + env.get("PATH", "")
    env["SCAN_BRANCH"] = "a b"                      # invalid (space) → refused by valid_branch_name
    env["SCAN_CLONE_URL"] = "https://example/x.git"
    r = subprocess.run(["sh", RUN_SCAN], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    ok = r.returncode == 11 and not os.path.exists(marker)
    if ok:
        print("run-scan.sh: exit 11, git never called")
    else:
        print(f"run-scan refusal FAILED: exit={r.returncode} gitCalled={os.path.exists(marker)}", file=sys.stderr)
    return ok


if __name__ == "__main__":
    ok = test_vectors()
    ok = test_run_scan_refuses_invalid_before_git() and ok
    sys.exit(0 if ok else 1)
