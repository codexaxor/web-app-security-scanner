#!/usr/bin/env python3
"""In-image SAST exclusion test (D-104, M8.15 S2). Runs INSIDE the built scanner image against the SHIPPED
baked rules + exclusion lists. Proves:

  1. DIFF GATE: regenerating the category-exclusion list from metadata.category reproduces the committed
     scanner-image/opengrep-excluded-by-category.txt byte-for-byte (else category coverage changed unreviewed).
  2. NO DEAD IDS: every id in the deny-list and the category list is a real loaded check_id (a typo would make
     --exclude-rule a silent no-op).
  3. NEW-PACKS-ONLY (ALLOW-LIST, M8.15 review S5): every excluded id must belong to one of the six NEW packs
     (python/go/php/ruby/kotlin/scala). ANY other pack prefix — java/csharp/javascript/typescript, custom,
     aikido, or a future pack — fails the build (removing a rule that already has live findings would make
     reconcile "solve" them, i.e. "rule retired ≠ fixed").
  4. EXCLUSION WORKS end-to-end: a trigger file that hits excluded rules yields those ids with NO excludes,
     and yields NONE of them when scanned the production way (--exclude-rule for every listed id).
  5. EXISTING PACKS FROZEN (M8.15 review S4): the java/csharp/javascript/typescript check_ids Opengrep itself
     LOADS from the built image (its --verbose "Rules:" list) equal the committed golden
     scanner-image/opengrep-existing-packs-check-ids.txt (388 distinct) — so the YAML-only bake, a rules-SHA bump
     or a Dockerfile copy change can never silently change a fingerprint input of an existing finding.

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
# ALLOW-LIST (S5): the only packs an excluded id may come from — the six packs NEW in M8.15 (D-104).
NEW_PACKS = ("python", "go", "php", "ruby", "kotlin", "scala")
# The four ORIGINAL opengrep-rules packs whose loaded check_id set is frozen by the golden file (S4).
EXISTING_PACKS = ("java", "csharp", "javascript", "typescript")
GOLDEN_EXISTING = HOST + "/opengrep-existing-packs-check-ids.txt"
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


def opengrep_loaded_existing_ids():
    """The java/csharp/javascript/typescript check_ids Opengrep ITSELF loads from the baked rules (authoritative —
    its --verbose 'Rules:' list), not a re-implementation of its loader."""
    import re
    idre = re.compile(r"opengrep-rules\.(?:" + "|".join(EXISTING_PACKS) + r")\.[A-Za-z0-9_.-]*[A-Za-z0-9_-]")
    with tempfile.TemporaryDirectory() as d:
        with open(os.path.join(d, "a.py"), "w", encoding="utf-8") as fh:
            fh.write("x = 1\n")
        out = os.path.join(d, "o.json")
        env = dict(os.environ, HOME="/tmp", TMPDIR="/tmp", XDG_CACHE_HOME="/tmp", LANG="C.UTF-8", LC_ALL="C.UTF-8")
        proc = subprocess.run(["opengrep", "scan", "--config", "opengrep-rules", "--verbose",
                               "--max-log-list-entries", "100000", "--json-output=" + out,
                               "--disable-version-check", d],
                              cwd="/etc/secscan", env=env, capture_output=True, text=True, check=False)
    return set(idre.findall(proc.stdout + proc.stderr))


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
        pack = cid.split(".")[1] if cid.startswith("opengrep-rules.") and cid.count(".") >= 2 else "?"
        if pack not in NEW_PACKS:
            fail(f"excluded id is not from one of the six NEW packs {NEW_PACKS} (pack {pack!r}; forbidden): "
                 f"{cid}")

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

    # 5. existing four packs' loaded check_ids == golden (S4)
    golden = read_ids(GOLDEN_EXISTING)
    loaded_existing = opengrep_loaded_existing_ids()
    if len(golden) != 388:
        fail(f"golden {GOLDEN_EXISTING} has {len(golden)} ids, expected 388")
    if not loaded_existing:
        fail("could not read Opengrep's loaded rule list — golden check is vacuous")
    elif loaded_existing != golden:
        for cid in sorted(loaded_existing - golden):
            fail(f"existing-pack check_id loaded but NOT in golden (fingerprint input added/renamed): {cid}")
        for cid in sorted(golden - loaded_existing):
            fail(f"golden existing-pack check_id NOT loaded any more (fingerprint input removed/renamed): {cid}")
    else:
        print(f"golden OK: the {len(golden)} java/csharp/javascript/typescript check_ids Opengrep loads equal "
              f"the committed golden")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: diff gate clean, no dead ids, every excluded id from a NEW pack (allow-list), --exclude-rule "
          "removes every listed id, existing-pack check_ids == golden.")


if __name__ == "__main__":
    main()
