#!/usr/bin/env python3
"""In-image SAST recall + canary-leak test (D-104, M8.15 S6). Runs INSIDE the built scanner image against the
SHIPPED baked rules + exclusion lists, scanning fixtures/sast-recall THE PRODUCTION WAY — the real
run-scan.sh over a file:// clone of the fixture, so the deny-list, category exclusions, C.UTF-8 locale,
--taint-intrafile and the D-053/S1 sanitize all apply exactly as in a real scan. Proves:

  1. RECALL: every expected (check_id, file) for the six NEW packs (python/go/php/ruby/kotlin/scala) is present
     in the sanitized /out/opengrep.json — the packs actually fire on real vulnerable code in the real image.
  2. NEGATIVES: no finding lands on a fixtures/sast-recall/negative/ file.
  3. EXCLUSIONS HOLD: no denied or category-excluded check_id ever appears in the output (the S6 end-to-end
     guard promised in the S2 plan).
  4. CANARY (BLOCKER): a unique fake secret planted in ruby/secret.rb (matched by a real hardcoded-secret rule)
     appears NOWHERE in /out or in the scanner's stdout/stderr — proving the sanitize keeps matched source
     values (extra.lines/metavars) out of the results, even though Opengrep can interpolate metavars into
     extra.message (D-053/S1). A leak here fails the build and must be fixed by sanitizing messages in-container.

Run (as the gradle task does), mounting fixtures/sast-recall at /fixture:ro, inside the image:
    SCAN_CLONE_URL=file:///tmp/repo python3 -I /host/sast_recall_in_image_test.py
Exits non-zero (and prints FAIL lines) on any violation. Stdlib only (jq not needed — we read JSON in Python).
"""
import json
import os
import shutil
import subprocess
import sys

FIXTURE = os.environ.get("FIXTURE", "/fixture")
OUT = "/out"
CHECKOUT_PREFIX = "/work/checkout/"
CANARY = "L3AKcanaryZZZ9000"
DENY_FILES = ("/etc/secscan/opengrep-denylist.txt", "/etc/secscan/opengrep-excluded-by-category.txt")

# Expected (check_id, repo-relative file) for the six NEW packs — captured from a real run-scan.sh over this
# fixture in the built image. Each is a NON-denied, NON-category-excluded security rule.
EXPECTED = {
    ("opengrep-rules.python.lang.security.insecure-hash-algorithm-md5", "python/vuln.py"),
    ("opengrep-rules.python.lang.security.audit.subprocess-shell-true", "python/vuln.py"),
    ("opengrep-rules.python.lang.security.audit.eval-detected", "python/vuln.py"),
    ("opengrep-rules.go.lang.security.audit.crypto.use-of-md5", "go/vuln.go"),
    ("opengrep-rules.go.lang.security.audit.dangerous-exec-command", "go/vuln.go"),
    ("opengrep-rules.php.lang.security.exec-use", "php/vuln.php"),
    ("opengrep-rules.php.lang.security.injection.echoed-request", "php/vuln.php"),
    ("opengrep-rules.php.lang.security.injection.tainted-exec", "php/vuln.php"),
    ("opengrep-rules.php.lang.security.tainted-exec", "php/vuln.php"),
    ("opengrep-rules.ruby.lang.security.dangerous-exec", "ruby/vuln.rb"),
    ("opengrep-rules.ruby.lang.security.dangerous-subshell", "ruby/vuln.rb"),
    ("opengrep-rules.ruby.rails.security.brakeman.check-secrets", "ruby/secret.rb"),
    ("opengrep-rules.kotlin.lang.security.use-of-md5", "kotlin/Vuln.kt"),
    ("opengrep-rules.kotlin.lang.security.command-injection-formatted-runtime-call", "kotlin/Vuln.kt"),
    ("opengrep-rules.scala.lang.security.audit.dangerous-seq-run", "scala/Vuln.scala"),
    ("opengrep-rules.scala.lang.security.audit.insecure-random", "scala/Vuln.scala"),
}

failures = []


def fail(msg):
    failures.append(msg)
    print("FAIL: " + msg)


def read_ids(path):
    ids = set()
    try:
        with open(path, encoding="utf-8") as fh:
            for line in fh:
                line = line.split("#", 1)[0].strip()
                if line:
                    ids.add(line)
    except OSError:
        pass
    return ids


def git(args, cwd):
    subprocess.run(["git", *args], cwd=cwd, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def run_production_scan():
    """Copy the fixture, make it a git repo, run the REAL run-scan.sh over a file:// clone of HEAD."""
    os.makedirs(OUT, exist_ok=True)
    os.makedirs("/opt/sast-exec", exist_ok=True)
    os.chmod(OUT, 0o777)
    os.chmod("/opt/sast-exec", 0o777)
    repo = "/tmp/repo"
    shutil.rmtree(repo, ignore_errors=True)
    shutil.copytree(FIXTURE, repo)
    git(["init", "-q", "-b", "main"], repo)
    git(["config", "user.email", "t@example.com"], repo)
    git(["config", "user.name", "t"], repo)
    git(["add", "-A"], repo)
    git(["commit", "-qm", "fixture"], repo)
    env = dict(os.environ, SCAN_CLONE_URL="file:///tmp/repo")
    proc = subprocess.run(["/usr/local/bin/run-scan.sh"], env=env,
                          capture_output=True, text=True)
    return proc.stdout, proc.stderr


def main():
    stdout, stderr = run_production_scan()

    with open(os.path.join(OUT, "result.json"), encoding="utf-8") as fh:
        manifest = json.load(fh)
    sast = next((s for s in manifest.get("scanners", []) if s.get("name") == "sast"), {})
    if sast.get("status") != "ok":
        fail(f"SAST scanner status is {sast.get('status')!r}, expected 'ok'")

    with open(os.path.join(OUT, "opengrep.json"), encoding="utf-8") as fh:
        results = json.load(fh).get("results", [])

    present = set()
    for r in results:
        cid = r.get("check_id", "")
        path = r.get("path", "") or r.get("location", {}).get("path", "")
        rel = path[len(CHECKOUT_PREFIX):] if path.startswith(CHECKOUT_PREFIX) else path
        present.add((cid, rel))

    # 1. recall
    missing = EXPECTED - {(c, p) for (c, p) in present}
    if missing:
        for cid, rel in sorted(missing):
            fail(f"expected finding missing: {cid} on {rel}")
    else:
        print(f"recall OK: all {len(EXPECTED)} expected (check_id, file) present "
              f"across python/go/php/ruby/kotlin/scala")

    # 2. negatives
    neg = sorted({p for (_c, p) in present if p.startswith("negative/")})
    if neg:
        fail("finding(s) on negative fixture file(s): " + ", ".join(neg))
    else:
        print("negatives OK: no finding on any negative/ file")

    # 3. no denied / category-excluded id present
    excluded = read_ids(DENY_FILES[0]) | read_ids(DENY_FILES[1])
    if not excluded:
        fail("could not read deny-list / category-exclusion files (nothing to check)")
    leaked_rules = sorted({c for (c, _p) in present if c in excluded})
    if leaked_rules:
        fail("denied/category-excluded id appeared in output: " + ", ".join(leaked_rules))
    else:
        print(f"exclusions OK: none of the {len(excluded)} excluded ids appear in the output")

    # 4. canary — the planted secret must not reach /out or the scanner logs
    hits = []
    for root, _dirs, files in os.walk(OUT):
        for name in files:
            p = os.path.join(root, name)
            try:
                with open(p, "rb") as fh:
                    if CANARY.encode() in fh.read():
                        hits.append(p)
            except OSError:
                pass
    if CANARY in stdout:
        hits.append("<run-scan stdout>")
    if CANARY in stderr:
        hits.append("<run-scan stderr>")
    # sanity: the canary must actually be in the fixture (else the test proves nothing)
    with open(os.path.join(FIXTURE, "ruby", "secret.rb"), encoding="utf-8") as fh:
        if CANARY not in fh.read():
            fail("canary sentinel not present in the fixture — the canary test is vacuous")
    if hits:
        fail("CANARY LEAKED into: " + ", ".join(hits)
             + " — sanitize the matched value out in-container (D-053/S1) and record in D-104")
    else:
        print("canary OK: the planted secret appears nowhere in /out or the scanner logs")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: recall across all six new packs, negatives clean, no excluded id, no canary leak.")


if __name__ == "__main__":
    main()
