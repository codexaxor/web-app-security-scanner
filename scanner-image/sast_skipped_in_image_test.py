#!/usr/bin/env python3
"""In-image SAST skipped-file classification test (M8.15 review S1/S2/S3). Runs INSIDE the built scanner image
against the SHIPPED secscan-lib.sh / run-scan.sh and REAL Opengrep 1.31.0 output. Proves:

  1. REAL SAMPLE: sast_skipped_files over samples/opengrep/skipped/opengrep-raw-skipped-1.31.0.json produces
     EXACTLY the committed sanitized sample opengrep-skipped.json (skippedFiles + partiallyParsedFiles) — Timeout,
     full "Syntax error"/"Other syntax error", exceeded_size_limit and insufficient_permissions are FROZEN; the
     PartialParsing file is only COUNTED — so the parser test's sample cannot drift from the shipped code.
  2. UNEXPLAINED analysis failure is frozen (fail safe): the same real report with a file's errors[] entry
     removed leaves only paths.skipped analysis_failed_parser_or_internal_error → frozen with that type.
  3. NORMALIZED TYPES (S1): every error type in the output is a bare name ([A-Za-z0-9 _.-], ≤48) — no container
     path, no line range; sast_error_summary is the deduped, sorted name list.
  4. FAIL CLOSED (S3): an unparsable report, errors[] not an array, or paths.skipped absent → non-zero.
  5. END-TO-END over the real run-scan.sh (file:// clone of fixtures/sast-fail-canary, whose broken.py only
     PARTIALLY parses): SAST ok, skippedFiles [], partiallyParsedFiles 1.
  6. END-TO-END FAIL CLOSED: an opengrep PATH shim runs the REAL opengrep then deletes paths.skipped from its
     report (as if --verbose were lost) → run-scan.sh marks SAST "failed" (closes nothing), /out has no results,
     exit 20 — never "ok with nothing skipped".
Stdlib only.
"""
import json
import os
import shutil
import stat
import subprocess
import sys

LIB = "/usr/local/bin/secscan-lib.sh"
RUN_SCAN = "/usr/local/bin/run-scan.sh"
SAMPLES = os.environ.get("SAMPLES", "/samples")
RAW = SAMPLES + "/opengrep-raw-skipped-1.31.0.json"
SANITIZED = SAMPLES + "/opengrep-skipped.json"
FIXTURE = os.environ.get("FIXTURE", "/fixture")
OUT = "/out"
SHIM_DIR = "/tmp/opengrep-drop-skipped-shim"
NAME_CHARS = set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789 _.-")

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def lib(func, *args):
    cmd = '. "$0"; ' + func + ' "$@"'
    return subprocess.run(["sh", "-c", cmd, LIB, *args], capture_output=True, text=True)


def classify(raw_path):
    out = "/tmp/classified.json"
    if os.path.exists(out):
        os.remove(out)
    proc = lib("sast_skipped_files", raw_path, "/work/checkout/", out)
    data = None
    if proc.returncode == 0:
        with open(out, encoding="utf-8") as fh:
            data = json.load(fh)
    return proc.returncode, data


def check_types(label, data):
    for sf in data.get("skippedFiles", []):
        for t in sf.get("errorTypes", []):
            if not t or len(t) > 48 or set(t) - NAME_CHARS:
                fail(f"[{label}] non-normalized error type {t!r} for {sf.get('path')}")


def main():
    with open(SANITIZED, encoding="utf-8") as fh:
        expected = json.load(fh)
    with open(RAW, encoding="utf-8") as fh:
        raw = json.load(fh)

    # 1. real sample → exactly the committed sanitized sample
    rc, got = classify(RAW)
    if rc != 0:
        fail(f"[real] sast_skipped_files exited {rc} on the real sample")
    else:
        want = {"skippedFiles": expected["skippedFiles"], "partiallyParsedFiles": expected["partiallyParsedFiles"]}
        if got != want:
            fail(f"[real] classification {json.dumps(got)} != committed sample {json.dumps(want)}")
        else:
            frozen = ", ".join(f"{s['path']}={s['errorTypes']}" for s in got["skippedFiles"])
            print(f"[real] OK: frozen {frozen}; partiallyParsedFiles={got['partiallyParsedFiles']}")
        check_types("real", got)
    summary = lib("sast_error_summary", RAW).stdout
    if summary != "Other syntax error, PartialParsing, Syntax error, Timeout":
        fail(f"[real] sast_error_summary = {summary!r}")
    else:
        print(f"[real] OK: sast_error_summary = {summary!r}")

    # 2. unexplained analysis_failed (no errors[] entry) → frozen, fail safe
    variant = dict(raw)
    variant["errors"] = [e for e in raw["errors"] if not str(e.get("path", "")).endswith("/Garbage.java")]
    with open("/tmp/variant.json", "w", encoding="utf-8") as fh:
        json.dump(variant, fh)
    rc, got = classify("/tmp/variant.json")
    gj = next((s for s in (got or {}).get("skippedFiles", []) if s["path"] == "src/Garbage.java"), None)
    if rc != 0 or gj is None or gj["errorTypes"] != ["analysis_failed_parser_or_internal_error"]:
        fail(f"[unexplained] expected src/Garbage.java frozen as analysis_failed…, got rc={rc} {gj}")
    else:
        print("[unexplained] OK: analysis_failed with no errors[] entry is frozen (fail safe)")

    # 4. fail closed on unclassifiable reports
    for name, content in (("unparsable", '{"errors": ['),
                          ("errors-not-array", '{"errors": "x", "paths": {"skipped": []}}'),
                          ("no-paths-skipped", '{"errors": [], "paths": {"scanned": []}}')):
        p = f"/tmp/bad-{name}.json"
        with open(p, "w", encoding="utf-8") as fh:
            fh.write(content)
        rc, _ = classify(p)
        if rc == 0:
            fail(f"[fail-closed] {name}: sast_skipped_files returned 0 (must fail closed)")
        else:
            print(f"[fail-closed] OK: {name} → exit {rc}")

    # 5 + 6. end-to-end over the real run-scan.sh
    repo = "/tmp/repo"
    shutil.rmtree(repo, ignore_errors=True)
    shutil.copytree(FIXTURE, repo)
    for args in (["init", "-q", "-b", "main"], ["config", "user.email", "t@example.com"],
                 ["config", "user.name", "t"], ["add", "-A"], ["commit", "-qm", "fixture"]):
        subprocess.run(["git", *args], cwd=repo, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    def run_scan(path_prefix=None):
        # /work is EMPTIED, not removed: it is the image's WORKDIR (removing it breaks the cwd of child processes).
        for d in (OUT, "/work", "/opt/sast-exec"):
            os.makedirs(d, exist_ok=True)
            os.chmod(d, 0o777)
        for p in [os.path.join(OUT, n) for n in os.listdir(OUT)] + [os.path.join("/work", n) for n in os.listdir("/work")]:
            if os.path.isdir(p):
                shutil.rmtree(p)
            else:
                os.remove(p)
        env = dict(os.environ, SCAN_CLONE_URL="file:///tmp/repo")
        if path_prefix:
            env["PATH"] = path_prefix + ":" + env.get("PATH", "/usr/local/bin:/usr/bin:/bin")
        proc = subprocess.run([RUN_SCAN], env=env, capture_output=True, text=True, cwd="/")
        with open(os.path.join(OUT, "result.json"), encoding="utf-8") as fh:
            sast = next(s for s in json.load(fh)["scanners"] if s["name"] == "sast")
        with open(os.path.join(OUT, "opengrep.json"), encoding="utf-8") as fh:
            og = json.load(fh)
        return proc, sast, og

    proc, sast, og = run_scan()
    if sast["status"] != "ok" or og.get("skippedFiles") != [] or og.get("partiallyParsedFiles") != 1:
        fail(f"[e2e] expected ok / skippedFiles [] / partiallyParsedFiles 1, got {sast['status']} / "
             f"{og.get('skippedFiles')} / {og.get('partiallyParsedFiles')}")
    else:
        print("[e2e] OK: real run-scan.sh — a partially parsed file is counted (1), not frozen ([])")

    os.makedirs(SHIM_DIR, exist_ok=True)
    shim = os.path.join(SHIM_DIR, "opengrep")
    with open(shim, "w", encoding="utf-8") as fh:
        fh.write('#!/bin/sh\n/usr/local/bin/opengrep "$@"\nrc=$?\n'
                 'for a in "$@"; do case "$a" in --json-output=*) f="${a#--json-output=}";; esac; done\n'
                 'if [ "$1" = "scan" ] && [ -n "${f:-}" ] && [ -f "$f" ]; then\n'
                 '  jq "del(.paths.skipped)" "$f" > "$f.tmp" && mv "$f.tmp" "$f"\nfi\nexit $rc\n')
    os.chmod(shim, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)
    proc, sast, og = run_scan(SHIM_DIR)
    if sast["status"] != "failed" or og.get("results") != [] or proc.returncode != 20:
        fail(f"[e2e fail-closed] expected status failed / no results / exit 20, got {sast['status']} / "
             f"{len(og.get('results', []))} results / exit {proc.returncode}")
    elif "could not classify" not in proc.stderr:
        fail("[e2e fail-closed] no 'could not classify' explanation on stderr")
    else:
        print("[e2e fail-closed] OK: unclassifiable report → SAST failed (closes nothing), 0 results, exit 20")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: real-sample classification, unexplained-failure freeze, normalized types, fail-closed "
          "(unit + end-to-end).")


if __name__ == "__main__":
    main()
