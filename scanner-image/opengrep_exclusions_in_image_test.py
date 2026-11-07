#!/usr/bin/env python3
"""In-image SAST exclusion test (D-104, M8.15 S2). Runs INSIDE the built scanner image against the SHIPPED
baked rules + exclusion lists. Proves:

  1. DIFF GATE: regenerating the category-exclusion list from metadata.category reproduces the committed
     scanner-image/opengrep-excluded-by-category.txt byte-for-byte (else category coverage changed unreviewed).
  2. NO DEAD IDS: every id in the deny-list and the category list is a real loaded check_id (a typo would make
     --exclude-rule a silent no-op).
  3. NEW-PACKS-ONLY: no excluded id belongs to java/csharp/javascript/typescript (removing an existing rule
     would make reconcile "solve" its live findings).
  4. EXCLUSION WORKS end-to-end: a trigger file that hits excluded rules yields those ids with NO excludes,
     and yields NONE of them when scanned the production way (--exclude-rule for every listed id).

Run (as the gradle task does), mounting scanner-image at /host:ro, inside the image:
    python3 -I /host/opengrep_exclusions_in_image_test.py
Exits non-zero (and prints FAIL lines) on any violation. Stdlib + PyYAML (shipped).
"""
import json
import os
import subprocess
import sys
import tempfile

RULES = "/etc/secscan/opengrep-rules"
HOST = os.environ.get("HOST", "/host")
DENY_FILE = HOST + "/opengrep-denylist.txt"
CAT_FILE = HOST + "/opengrep-excluded-by-category.txt"
GEN = HOST + "/gen_category_exclusions.py"
EXISTING_PACKS = ("java", "csharp", "javascript", "typescript")
failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def read_ids(path):
    ids = set()
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.split("#", 1)[0].strip()
            if line:
                ids.add(line)
    return ids


def loaded_check_ids():
    """All distinct check_ids opengrep loads from the baked rules (parse ids like the loader; no filename)."""
    import re
    idre = re.compile(r'^\s*-\s+id:\s*(\S+)\s*$', re.M)
    out = set()
    for dp, _, fns in os.walk(RULES):
        for fn in fns:
            low = fn.lower()
            if not (low.endswith(".yaml") or low.endswith(".yml")):
                continue
            if low.endswith(".test.yaml") or low.endswith(".test.yml"):
                continue
            reldir = os.path.relpath(dp, RULES).replace(os.sep, ".")
            try:
                txt = open(os.path.join(dp, fn), encoding="utf-8", errors="replace").read()
            except OSError:
                continue
            for m in idre.finditer(txt):
                out.add("opengrep-rules." + reldir + "." + m.group(1))
    return out


def scan(target, exclude_ids):
    out = tempfile.mkstemp(suffix=".json")[1]
    cmd = ["opengrep", "scan", "--config", "opengrep-rules", "--json-output=" + out,
           "--disable-version-check", "--disable-nosem", "--x-ignore-semgrepignore-files"]
    for i in sorted(exclude_ids):
        cmd += ["--exclude-rule", i]
    cmd.append(target)
    env = dict(os.environ, HOME="/tmp", TMPDIR="/tmp", XDG_CACHE_HOME="/tmp",
               LANG="C.UTF-8", LC_ALL="C.UTF-8")
    subprocess.run(cmd, cwd="/etc/secscan", env=env,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
    with open(out, encoding="utf-8") as fh:
        data = json.load(fh)
    return {r.get("check_id") for r in data.get("results", [])}


def main():
    deny = read_ids(DENY_FILE)
    cat = read_ids(CAT_FILE)
    exclude = deny | cat
    print(f"deny-list={len(deny)} category-excluded={len(cat)} union={len(exclude)}")

    # 1. diff gate
    gen = subprocess.run([sys.executable, "-I", GEN, RULES], capture_output=True, text=True)
    if gen.returncode != 0:
        fail(f"generator exited {gen.returncode}: {gen.stderr.strip()}")
    else:
        committed = open(CAT_FILE, encoding="utf-8").read()
        if gen.stdout != committed:
            fail("regenerated category-exclusion list differs from committed "
                 "opengrep-excluded-by-category.txt (re-run gen_category_exclusions.py and commit the diff)")

    # 2 + 3. dead ids + new-packs-only
    loaded = loaded_check_ids()
    for cid in sorted(exclude):
        if cid not in loaded:
            fail(f"excluded id is not a loaded check_id (dead/typo): {cid}")
        pack = cid.split(".")[1] if cid.startswith("opengrep-rules.") else "?"
        if pack in EXISTING_PACKS:
            fail(f"excluded id belongs to an EXISTING pack {pack} (forbidden): {cid}")

    # 4. end-to-end exclusion on a synthetic trigger dir
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "trig.py"), "w", encoding="utf-8") as fh:
            fh.write("import os, subprocess\n"
                     "def run(cmd):\n"
                     "    os.system(cmd)\n"           # dangerous-system-call(+audit)
                     "    subprocess.run(cmd)\n"       # dangerous-subprocess-use(+audit)
                     "    open('f.txt').read()\n")     # unspecified-open-encoding (best-practice, category-excluded)
        with open(os.path.join(d, "app.py"), "w", encoding="utf-8") as fh:
            fh.write("from flask import Flask\n"
                     "app = Flask(__name__)\n"
                     "app.run(host='0.0.0.0')\n")      # avoid_app_run_with_bad_host (denied)
        base_ids = scan(d, set())
        filt_ids = scan(d, exclude)

    hit_excluded = base_ids & exclude
    if not hit_excluded:
        fail("trigger files hit NO excluded rule — test proves nothing; adjust triggers")
    else:
        print(f"trigger hit {len(hit_excluded)} excluded rule(s) with no excludes, e.g. "
              + ", ".join(sorted(hit_excluded)[:4]))
    leaked = filt_ids & exclude
    if leaked:
        fail("excluded rules STILL present after --exclude-rule: " + ", ".join(sorted(leaked)))
    # spot-check specific ids we expect the triggers to hit
    for expect in ("opengrep-rules.python.lang.security.audit.dangerous-system-call-audit",
                   "opengrep-rules.python.lang.best-practice.unspecified-open-encoding"):
        if expect not in base_ids:
            fail(f"expected trigger id not seen in baseline (rule set changed?): {expect}")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: diff gate clean, no dead/existing-pack ids, --exclude-rule removes every listed id.")


if __name__ == "__main__":
    main()
