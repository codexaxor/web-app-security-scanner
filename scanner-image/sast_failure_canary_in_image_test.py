#!/usr/bin/env python3
"""In-image SAST FAILURE-path canary test (M8.15 review B3). Runs INSIDE the built scanner image.

Opengrep's --verbose run log quotes scanned SOURCE on a parse error ("`<source line>` was unexpected"). It must
never leave the container: not via /out, not via run-scan.sh's stdout/stderr (the worker stores those as the
scan_task log excerpt). This drives the REAL run-scan.sh over a file:// clone of fixtures/sast-fail-canary (a file
whose parse-error line carries a fake canary) twice:

  1. SUCCESS path (real opengrep): canary absent from /out + stdout/stderr.
  2. FAILURE path: a PATH shim runs the REAL opengrep (so the real verbose log is written) and then exits 2, so
     run-scan.sh takes its SAST-failed branch. Asserts: SAST status "failed", run-scan exit 20, stderr carries
     ONLY the sanitized summary "opengrep failed (exit 2); error types: ..." (normalized type names, no container
     path / line range), and the canary is absent from /out, stdout and stderr.

Non-vacuity: each run asserts the canary IS present in the in-container verbose log (/work/opengrep-verbose.log).
Set RUN_SCAN to point at another run-scan.sh (e.g. the pre-fix one) to see this test fail against it.
Exits non-zero (FAIL lines) on any violation. Stdlib only.
"""
import json
import os
import shutil
import stat
import subprocess
import sys

FIXTURE = os.environ.get("FIXTURE", "/fixture")
RUN_SCAN = os.environ.get("RUN_SCAN", "/usr/local/bin/run-scan.sh")
OUT = "/out"
VERBOSE_LOG = "/work/opengrep-verbose.log"
CANARY = "L3AKfailCanary8888"
SHIM_DIR = "/tmp/opengrep-fail-shim"

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def prepare_repo():
    repo = "/tmp/repo"
    shutil.rmtree(repo, ignore_errors=True)
    shutil.copytree(FIXTURE, repo)
    for args in (["init", "-q", "-b", "main"], ["config", "user.email", "t@example.com"],
                 ["config", "user.name", "t"], ["add", "-A"], ["commit", "-qm", "fixture"]):
        subprocess.run(["git", *args], cwd=repo, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    with open(os.path.join(FIXTURE, "app", "broken.py"), encoding="utf-8") as fh:
        if CANARY not in fh.read():
            fail("canary not planted in the fixture — test is vacuous")


def make_shim():
    os.makedirs(SHIM_DIR, exist_ok=True)
    shim = os.path.join(SHIM_DIR, "opengrep")
    with open(shim, "w", encoding="utf-8") as fh:
        # Real opengrep first (writes the real verbose log + raw JSON), then a forced failure for `scan` only.
        fh.write('#!/bin/sh\n/usr/local/bin/opengrep "$@"\nrc=$?\n'
                 'if [ "$1" = "scan" ]; then exit 2; fi\nexit $rc\n')
    os.chmod(shim, stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)


def reset_dirs():
    """Empty /work (run-scan.sh clones into /work/checkout) and /out between runs. /work is EMPTIED, not removed:
    it is the image's WORKDIR, so removing it would leave this process with a deleted cwd."""
    for d in (OUT, "/work", "/opt/sast-exec"):
        os.makedirs(d, exist_ok=True)
        os.chmod(d, 0o777)
    for name in os.listdir(OUT) + [os.path.join("/work", n) for n in os.listdir("/work")]:
        p = name if name.startswith("/work/") else os.path.join(OUT, name)
        if os.path.isdir(p):
            shutil.rmtree(p)
        else:
            os.remove(p)


def run(label, shim):
    reset_dirs()
    env = dict(os.environ, SCAN_CLONE_URL="file:///tmp/repo")
    if shim:
        env["PATH"] = SHIM_DIR + ":" + env.get("PATH", "/usr/local/bin:/usr/bin:/bin")
    proc = subprocess.run([RUN_SCAN], env=env, capture_output=True, text=True, cwd="/")
    with open(os.path.join(OUT, "result.json"), encoding="utf-8") as fh:
        manifest = json.load(fh)
    sast = next((s for s in manifest.get("scanners", []) if s["name"] == "sast"), None)
    if sast is None:
        fail(f"[{label}] no sast scanner in result.json (cloneFailed={manifest.get('cloneFailed')}, "
             f"run-scan exit {proc.returncode})")
        sast = {}

    # non-vacuity: the real verbose log DID capture the canary
    try:
        with open(VERBOSE_LOG, encoding="utf-8", errors="replace") as fh:
            if CANARY not in fh.read():
                fail(f"[{label}] canary not in the in-container verbose log — test is vacuous")
    except OSError:
        fail(f"[{label}] verbose log {VERBOSE_LOG} missing — test is vacuous")

    hits = []
    for root, _dirs, files in os.walk(OUT):
        for name in files:
            with open(os.path.join(root, name), "rb") as fh:
                if CANARY.encode() in fh.read():
                    hits.append(os.path.join(root, name))
    if CANARY in proc.stdout:
        hits.append("<run-scan stdout>")
    if CANARY in proc.stderr:
        hits.append("<run-scan stderr = stored log excerpt>")
    if hits:
        fail(f"[{label}] CANARY LEAKED into: " + ", ".join(hits))
    else:
        print(f"[{label}] canary OK: absent from /out, stdout and stderr (present only in the in-container "
              f"verbose log)")
    return proc, sast


def main():
    prepare_repo()

    proc, sast = run("success", shim=False)
    if sast.get("status") != "ok":
        fail(f"[success] SAST status {sast.get('status')!r}, expected 'ok'")

    make_shim()
    proc, sast = run("failure", shim=True)
    if sast.get("status") != "failed" or sast.get("exitCode") != 2:
        fail(f"[failure] SAST status/exit {sast.get('status')!r}/{sast.get('exitCode')!r}, expected 'failed'/2")
    if proc.returncode != 20:
        fail(f"[failure] run-scan.sh exit {proc.returncode}, expected 20 (a scanner failed)")
    summary = [ln for ln in proc.stderr.splitlines() if ln.startswith("opengrep failed")]
    if not summary:
        fail("[failure] no 'opengrep failed (exit N); error types: ...' summary on stderr")
    else:
        line = summary[0]
        print(f"[failure] sanitized summary: {line}")
        if "(exit 2)" not in line or "PartialParsing" not in line:
            fail(f"[failure] summary lacks exit code / normalized type: {line}")
        if "/work" in line or "/checkout" in line or '"line"' in line or "[" in line:
            fail(f"[failure] summary carries a container path / line range / raw type payload: {line}")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: the verbose run log (which quotes scanned source) never leaves the container, on success or "
          "failure; the failure excerpt is a sanitized summary only.")


if __name__ == "__main__":
    main()
