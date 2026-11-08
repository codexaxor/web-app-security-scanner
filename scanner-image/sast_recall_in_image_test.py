#!/usr/bin/env python3
"""In-image SAST recall + canary-leak test (D-104, M8.15 S6). Runs INSIDE the built scanner image against the
SHIPPED baked rules + exclusion lists, scanning fixtures/sast-recall THE PRODUCTION WAY — the real
run-scan.sh over a file:// clone of the fixture, so the deny-list, category exclusions, C.UTF-8 locale,
--taint-intrafile and the D-053/S1 sanitize all apply exactly as in a real scan. Proves:

  1. RECALL: every expected (check_id, file) is present in the sanitized /out/opengrep.json — the six NEW packs
     (python/go/php/ruby/kotlin/scala) AND the existing java/javascript/typescript packs actually fire on real
     vulnerable code (incl. Spring JDBC/JPA SQLi, formatted SQL, command injection, XXE, actuator; express SQLi,
     XSS sink, eval) in the real image.
  2. NEGATIVES: no finding lands on a fixtures/sast-recall/negative/ file.
  3. EXCLUSIONS HOLD: no denied or category-excluded check_id ever appears in the output (the S6 end-to-end
     guard promised in the S2 plan).
  4. CANARY (BLOCKER): unique fake secrets planted in several hardcoded-secret cases (ruby/secret.rb,
     java/application.properties, python/flask_secret.py) appear NOWHERE in /out or in the scanner's
     stdout/stderr — proving the sanitize keeps matched source values (extra.lines/metavars) out of the results,
     even though Opengrep can interpolate metavars into extra.message (D-053/S1). A leak fails the build and must
     be fixed by sanitizing messages in-container.

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
DENY_FILES = ("/etc/secscan/opengrep-denylist.txt", "/etc/secscan/opengrep-excluded-by-category.txt")

# Expected (check_id, repo-relative file) — captured from a real run-scan.sh over this fixture in the built
# image. Each is a NON-denied, NON-category-excluded security rule. Covers the six NEW packs AND the existing
# java/javascript/typescript packs (so the YAML-only bake keeps the existing packs firing too).
EXPECTED = {
    # --- NEW packs ---
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
    ("opengrep-rules.python.flask.security.audit.avoid_hardcoded_config_SECRET_KEY", "python/flask_secret.py"),
    # --- EXISTING java pack (incl. Spring) ---
    ("opengrep-rules.java.lang.security.audit.sqli.jdbc-sqli", "java/SqlAndCmd.java"),
    ("opengrep-rules.java.lang.security.audit.sqli.jpa-sqli", "java/SqlAndCmd.java"),
    ("opengrep-rules.java.lang.security.audit.formatted-sql-string", "java/SqlAndCmd.java"),
    ("opengrep-rules.java.lang.security.audit.command-injection-process-builder", "java/SqlAndCmd.java"),
    ("opengrep-rules.java.lang.security.audit.xxe.documentbuilderfactory-disallow-doctype-decl-missing",
     "java/Xxe.java"),
    ("opengrep-rules.java.spring.security.audit.spring-actuator-fully-enabled", "java/application.properties"),
    ("opengrep-rules.custom.spring-actuator-exposed", "java/application.properties"),
    # --- EXISTING javascript/typescript packs ---
    ("opengrep-rules.javascript.express.security.injection.tainted-sql-string", "js/app.js"),
    ("opengrep-rules.javascript.express.security.audit.xss.direct-response-write", "js/app.js"),
    ("opengrep-rules.javascript.browser.security.eval-detected", "js/app.js"),
    ("opengrep-rules.javascript.browser.security.eval-detected", "ts/vuln.ts"),
    ("opengrep-rules.javascript.browser.security.insecure-document-method", "ts/vuln.ts"),
}

# Canary cases: an obviously-fake secret planted in several hardcoded-secret rules' files. Each must appear
# NOWHERE in /out or the scanner logs. {sentinel: fixture-relative file it is planted in}.
CANARIES = {
    "L3AKcanaryZZZ9000": "ruby/secret.rb",            # ruby brakeman check-secrets
    "L3AKjdbcCanary7777": "java/application.properties",  # custom.hardcoded-connection-string
    "L3AKflaskCanary5555": "python/flask_secret.py",  # python flask avoid_hardcoded_config_SECRET_KEY
}

# A tracked >1MB file planted at scan time; Opengrep skips it for size, so run-scan.sh must report it in
# skippedFiles with reason exceeded_size_limit and it must yield no finding (M8.15 S6 follow-up size-skip freeze).
OVERSIZED_FILE = "java/Oversized.java"

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
    # Plant a tracked >1MB Java file carrying a would-be finding. Opengrep skips it for size
    # (reason exceeded_size_limit), so run-scan.sh must list it in skippedFiles and it must NOT produce a finding
    # — proving the size-skip freeze path end-to-end (D-104 S6 follow-up). Generated at runtime, never committed.
    big_body = "class Oversized { String q(String id){ return \"x\"; } }\n// " + ("A" * 1_100_000) + "\n"
    with open(os.path.join(repo, "java", OVERSIZED_FILE.split("/", 1)[1]), "w", encoding="utf-8") as fh:
        fh.write("public " + big_body)
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
              f"across the six new packs + existing java/javascript/typescript")

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

    # 3b. size-skip: the planted >1MB file is reported in skippedFiles (reason exceeded_size_limit) and yields no
    # finding — proving run-scan.sh carries size/parse/read skips (not just errors[]) to the worker (D-104 S6).
    with open(os.path.join(OUT, "opengrep.json"), encoding="utf-8") as fh:
        skipped = json.load(fh).get("skippedFiles", [])
    skipped_by_path = {sf.get("path"): (sf.get("errorTypes") or []) for sf in skipped}
    if OVERSIZED_FILE not in skipped_by_path:
        fail(f"oversized file {OVERSIZED_FILE} not reported in skippedFiles (size skip not carried through)")
    elif "exceeded_size_limit" not in skipped_by_path[OVERSIZED_FILE]:
        fail(f"oversized file skip reason is {skipped_by_path[OVERSIZED_FILE]}, expected exceeded_size_limit")
    elif any(p == OVERSIZED_FILE for (_c, p) in present):
        fail(f"oversized file {OVERSIZED_FILE} unexpectedly produced a finding")
    else:
        print(f"size-skip OK: {OVERSIZED_FILE} reported skipped (exceeded_size_limit), no finding")

    # 4. canaries — each planted secret must not reach /out or the scanner logs
    out_blobs = {}
    for root, _dirs, files in os.walk(OUT):
        for name in files:
            p = os.path.join(root, name)
            try:
                with open(p, "rb") as fh:
                    out_blobs[p] = fh.read()
            except OSError:
                pass
    for canary, rel in CANARIES.items():
        # sanity: the canary must actually be in its fixture file (else the test proves nothing)
        try:
            with open(os.path.join(FIXTURE, *rel.split("/")), encoding="utf-8") as fh:
                if canary not in fh.read():
                    fail(f"canary {canary} not present in {rel} — that canary test is vacuous")
        except OSError:
            fail(f"canary fixture {rel} not readable — that canary test is vacuous")
        hits = [p for p, blob in out_blobs.items() if canary.encode() in blob]
        if canary in stdout:
            hits.append("<run-scan stdout>")
        if canary in stderr:
            hits.append("<run-scan stderr>")
        if hits:
            fail(f"CANARY {canary} (from {rel}) LEAKED into: " + ", ".join(hits)
                 + " — sanitize the matched value out in-container (D-053/S1) and record in D-104")
        else:
            print(f"canary OK ({rel}): the planted secret appears nowhere in /out or the scanner logs")

    if failures:
        print(f"\n{len(failures)} failure(s).")
        sys.exit(1)
    print("\nOK: recall across all six new packs, negatives clean, no excluded id, no canary leak.")


if __name__ == "__main__":
    main()
