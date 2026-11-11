#!/usr/bin/env python3
"""In-image custom-rule recall test (M8.15 S7, D-104). Runs INSIDE the built scanner image against the SHIPPED
baked custom pack (opengrep-rules/custom) over fixtures/sast-custom, under the production SAST flags
(--taint-intrafile, --timeout 60). Proves each of the M8.15 S7 MIT rules fires on its POSITIVE fixture and is
SILENT on its NEGATIVE counterpart — the "positive + negative cases asserted in the (default) build" the review
asked for. The six rules were chosen from the SAST scorecard's weakest categories (see docs/reports/
SAST_SCORECARD.md).

Run (as the gradle task does), mounting fixtures/sast-custom at /fixture:ro, inside the image:
    python3 -I /host/custom_rules_in_image_test.py
Exits non-zero (and prints FAIL lines) on any violation. Stdlib only.
"""
import json
import os
import subprocess
import sys
import tempfile

FIXTURE = os.environ.get("FIXTURE", "/fixture")
RULES_DIR = "/etc/secscan/opengrep-rules/custom"

# rule id (under opengrep-rules.custom.) -> positive fixture file it MUST fire on. It must be SILENT on the
# matching negative/ file (asserted generically below).
POSITIVE = {
    "servlet-trust-boundary-session": "positive/ServletVulns.java",
    "servlet-reflected-xss-inline": "positive/ServletVulns.java",
    "mybatis-dollar-sql-injection": "positive/OrderMapper.java",
    "nodejs-nosql-where-injection": "positive/nosql.js",
    "nodejs-ssrf-http-client": "positive/ssrf.js",
    "nodejs-unsafe-yaml-load": "positive/unsafeyaml.js",
}

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def scan():
    out = tempfile.mkstemp(suffix=".json")[1]
    cmd = ["opengrep", "scan", "--config", RULES_DIR, "--taint-intrafile", "--timeout", "60",
           "--json-output=" + out, "--disable-version-check", "--disable-nosem",
           "--x-ignore-semgrepignore-files", FIXTURE]
    env = dict(os.environ, HOME="/opt/sast-exec", TMPDIR="/opt/sast-exec", XDG_CACHE_HOME="/opt/sast-exec",
               LANG="C.UTF-8", LC_ALL="C.UTF-8")
    os.makedirs("/opt/sast-exec", exist_ok=True)
    os.chmod("/opt/sast-exec", 0o777)
    subprocess.run(cmd, cwd="/etc/secscan", env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    with open(out, encoding="utf-8") as fh:
        return json.load(fh).get("results", [])


def main():
    results = scan()
    # rule short id -> set of repo-relative files it fired on
    fired = {}
    for r in results:
        cid = r.get("check_id", "")
        short = cid.split(".")[-1]
        path = r.get("path") or (r.get("location") or {}).get("path") or ""
        rel = path[len(FIXTURE) + 1:] if path.startswith(FIXTURE + "/") else os.path.basename(path)
        fired.setdefault(short, set()).add(rel)

    for rule, positive in POSITIVE.items():
        files = fired.get(rule, set())
        if positive not in files:
            fail(f"{rule} did not fire on its positive fixture {positive} (fired on: {sorted(files) or 'nothing'})")
        neg = sorted(f for f in files if f.startswith("negative/"))
        if neg:
            fail(f"{rule} fired on negative fixture(s): {', '.join(neg)}")
        if positive in files and not neg:
            print(f"OK {rule}: fires on {positive}, silent on its negative")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print(f"\nOK: all {len(POSITIVE)} S7 custom rules fire on their positive and are silent on their negative.")


if __name__ == "__main__":
    main()
