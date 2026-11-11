#!/usr/bin/env python3
"""In-image SAST locale test (D-104, M8.15 S3). Proves that Opengrep loads a rule containing NON-ASCII text
when run under the production locale C.UTF-8, and that run-scan.sh actually sets that locale.

Background: the opengrep CLI (Python) reads rule YAML with the locale's preferred encoding. Under a non-UTF-8
locale (LC_ALL=C with Python's C-locale coercion disabled) a non-ASCII rule crashes with
'UnicodeDecodeError: ascii codec can't decode ...'. Our fix runs opengrep under LANG=C.UTF-8 LC_ALL=C.UTF-8.

Assertions:
  A. C.UTF-8  -> opengrep exits 0 AND the non-ASCII rule fires (>=1 result).      [the fix]
  B. run-scan.sh passes LC_ALL=C.UTF-8 to the opengrep scan.                       [production path]
  C. non-UTF-8 locale -> opengrep fails to load the same rule (exit != 0 / 0 results). [the hazard is real;
     uses PYTHONUTF8=0 PYTHONCOERCECLOCALE=0 to force the ascii codec — if a future toolchain stops crashing
     here this is the one to relax, A+B are the load-bearing guarantees]

Run inside the image, mounting scanner-image at /host:ro:  python3 -I /host/opengrep_locale_in_image_test.py
Stdlib only.
"""
import json
import os
import subprocess
import sys
import tempfile

HOST = os.environ.get("HOST", "/host")
RULE = """rules:
  - id: nonascii-eval-probe
    languages: [python]
    severity: WARNING
    message: "Évité — do not use eval() “naïvely” → danger ☠"
    patterns:
      - pattern: eval(...)
"""
failures = []


def fail(m):
    failures.append(m)
    print("FAIL: " + m)


def run(target, rule_path, extra_env):
    out = tempfile.mkstemp(suffix=".json")[1]
    env = dict(os.environ, HOME="/tmp", TMPDIR="/tmp", XDG_CACHE_HOME="/tmp")
    env.update(extra_env)
    p = subprocess.run(
        ["opengrep", "scan", "--config", rule_path, "--json-output=" + out,
         "--disable-version-check", target],
        env=env, capture_output=True, text=True)
    n = None
    try:
        with open(out, encoding="utf-8") as fh:
            n = len(json.load(fh).get("results", []))
    except Exception:
        n = None
    return p.returncode, n, (p.stderr or "")[-400:]


def main():
    with tempfile.TemporaryDirectory() as d:
        rule = os.path.join(d, "nonascii.yaml")
        with open(rule, "w", encoding="utf-8") as fh:
            fh.write(RULE)
        tgt = os.path.join(d, "t")
        os.mkdir(tgt)
        with open(os.path.join(tgt, "a.py"), "w", encoding="utf-8") as fh:
            fh.write("x = eval(input())\n")

        # A. C.UTF-8 loads + fires
        rc, n, err = run(tgt, rule, {"LANG": "C.UTF-8", "LC_ALL": "C.UTF-8"})
        print(f"A C.UTF-8: exit={rc} results={n}")
        if rc != 0 or not n:
            fail(f"non-ASCII rule did NOT load+fire under C.UTF-8 (exit={rc} results={n}); err: {err}")

        # C. non-UTF-8 locale fails to load
        rc2, n2, err2 = run(tgt, rule, {"LANG": "C", "LC_ALL": "C",
                                        "PYTHONUTF8": "0", "PYTHONCOERCECLOCALE": "0"})
        crashed = (rc2 != 0) or (not n2)
        print(f"C non-UTF-8: exit={rc2} results={n2} crashed={crashed}")
        if not crashed:
            fail("non-ASCII rule loaded under a forced non-UTF-8 locale — the locale fix is not load-bearing "
                 "(test may need a stronger non-ASCII rule)")

    # B. run-scan.sh sets C.UTF-8 on the opengrep scan
    rs = open(HOST + "/run-scan.sh", encoding="utf-8").read()
    if "LC_ALL=C.UTF-8" not in rs or "opengrep scan" not in rs:
        fail("run-scan.sh does not set LC_ALL=C.UTF-8 for the opengrep scan")
    else:
        print("B run-scan.sh sets LC_ALL=C.UTF-8 for opengrep: OK")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: C.UTF-8 loads non-ASCII rules; non-UTF-8 locale crashes; run-scan.sh uses C.UTF-8.")


if __name__ == "__main__":
    main()
