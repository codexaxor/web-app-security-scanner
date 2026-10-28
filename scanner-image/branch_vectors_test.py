#!/usr/bin/env python3
"""In-image parity + refusal + clone-safety test for the scanner (D-076 A1/A2, SF-5, N-1, SF-4).

Runs INSIDE the built secscan-scanner image (busybox sh + the shipped secscan-lib.sh / run-scan.sh), so the shell
validator and the clone invocation are tested against the REAL runtime, not host dash. Checks:
  1. valid_branch_name agrees with scanner-image/branch-vectors.json (the SAME file BranchNameValidationTest reads),
     so the Java and shell validators are proven to agree on identical inputs (incl. unicode code-point lengths).
  2. run-scan.sh refuses an invalid SCAN_BRANCH with exit 11 BEFORE any git invocation (a git stub on PATH that
     records calls must never be touched).
  3. (SF-4, file:// fixtures / no network — a git stub records the clone instead) run-scan.sh:
     - refuses a non-https / non-file:// clone URL with exit 12 BEFORE git runs;
     - on a token clone writes `Authorization: Basic base64(<user>:<token>)` via the tmpfs git config, with the
       user `oauth2` for GitLab (default) and `x-access-token` for GitHub (SCAN_CLONE_USER);
     - with no token file, sends NO auth header at all;
     - passes `-c http.followRedirects=false` and keeps every existing flag (--depth/--single-branch) and the
       single `--branch=<name>` argv element.

Invoked as `python3 -I /host/branch_vectors_test.py` with LC_ALL=C.UTF-8 so busybox counts code points."""

import base64
import json
import os
import subprocess
import sys
import tempfile

LIB = os.environ.get("SECSCAN_LIB", "/usr/local/bin/secscan-lib.sh")
RUN_SCAN = os.environ.get("RUN_SCAN", "/usr/local/bin/run-scan.sh")
VECTORS = os.environ.get("BRANCH_VECTORS", "/host/branch-vectors.json")
TOKEN_PATH = "/run/secrets/gitlab-token"


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


# ----------------------------------------------------------------------------------------------------- SF-4 ----

def _read(path):
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def _run_clone(url, branch=None, token=None, clone_user=None):
    """Run run-scan.sh with a git stub that records the clone invocation and the effective git config (which
    carries any auth header), then fails the clone so run-scan.sh stops right after it (exit 10). Returns
    (returncode, clone_args_or_None, git_config_or_None)."""
    d = tempfile.mkdtemp()
    args_out = os.path.join(d, "clone-args")
    config_out = os.path.join(d, "clone-config")
    gitstub = os.path.join(d, "git")
    with open(gitstub, "w") as fh:
        # Only the `clone` subcommand is recorded (run-scan.sh also calls `git --version` first); the clone is
        # made to "fail" (exit 1) so the script exits 10 before running any scanner on a non-existent checkout.
        fh.write(
            "#!/bin/sh\n"
            'case "$1" in\n'
            "  clone)\n"
            f'    printf "%s\\n" "$*" > "{args_out}"\n'
            f'    cat "$GIT_CONFIG_GLOBAL" > "{config_out}" 2>/dev/null || true\n'
            "    exit 1 ;;\n"
            '  --version) echo "git version 2.99.0"; exit 0 ;;\n'
            "  *) exit 0 ;;\n"
            "esac\n"
        )
    os.chmod(gitstub, 0o755)
    os.makedirs("/out", exist_ok=True)
    os.makedirs("/work", exist_ok=True)
    os.makedirs("/run/secrets", exist_ok=True)
    if token is None:
        if os.path.exists(TOKEN_PATH):
            os.remove(TOKEN_PATH)
    else:
        with open(TOKEN_PATH, "w", encoding="utf-8") as fh:
            fh.write(token)  # no trailing newline — the worker's one-time file carries the raw token (D-014)
    env = dict(os.environ)
    env["PATH"] = d + ":" + env.get("PATH", "")
    env["SCAN_CLONE_URL"] = url
    env.pop("SCAN_BRANCH", None)
    env.pop("SCAN_CLONE_USER", None)
    if branch is not None:
        env["SCAN_BRANCH"] = branch
    if clone_user is not None:
        env["SCAN_CLONE_USER"] = clone_user
    r = subprocess.run(["sh", RUN_SCAN], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return r.returncode, _read(args_out), _read(config_out)


def _check(name, cond):
    print(f"{'ok' if cond else 'FAIL'}: {name}", file=sys.stderr if not cond else sys.stdout)
    return cond


def test_non_https_url_refused_before_git():
    rc, args, _ = _run_clone("http://evil.example/x.git")
    return _check("non-https clone URL refused with exit 12 before git", rc == 12 and args is None)


def test_clone_flags_and_no_token_no_header():
    rc, args, config = _run_clone("https://gitlab.example/x.git", branch="release/1.0")
    ok = rc == 10 and args is not None
    ok = ok and "http.followRedirects=false" in args           # SF-3/SF-4 redirect lock
    ok = ok and "--depth" in args and "--single-branch" in args  # existing flags kept
    ok = ok and "--branch=release/1.0" in args                 # single --branch=<name> argv element
    ok = ok and (config is None or "Authorization" not in config)  # no token file → no auth header
    return _check("clone keeps flags + redirect lock + branch argv, no header without a token", ok)


def test_gitlab_token_header_default_user():
    rc, _, config = _run_clone("https://gitlab.example/x.git", token="s3cr3t-token")
    expected = base64.b64encode(b"oauth2:s3cr3t-token").decode()
    return _check("GitLab token clone sends Authorization: Basic base64(oauth2:token)",
                  rc == 10 and config is not None and f"Authorization: Basic {expected}" in config)


def test_github_token_header_x_access_token():
    rc, _, config = _run_clone("https://github.com/o/r.git", token="gh-pat-xyz", clone_user="x-access-token")
    expected = base64.b64encode(b"x-access-token:gh-pat-xyz").decode()
    return _check("GitHub token clone sends Authorization: Basic base64(x-access-token:token)",
                  rc == 10 and config is not None and f"Authorization: Basic {expected}" in config)


if __name__ == "__main__":
    ok = test_vectors()
    ok = test_run_scan_refuses_invalid_before_git() and ok
    ok = test_non_https_url_refused_before_git() and ok
    ok = test_clone_flags_and_no_token_no_header() and ok
    ok = test_gitlab_token_header_default_user() and ok
    ok = test_github_token_header_x_access_token() and ok
    # Leave no token file behind for any later in-image step.
    if os.path.exists(TOKEN_PATH):
        os.remove(TOKEN_PATH)
    sys.exit(0 if ok else 1)
