#!/bin/sh
# SecScan scanner entrypoint (M2): clone one repo at one commit, run Gitleaks, and write SANITIZED results.
#
# Security contract (CLAUDE.md + D-014 + D-024):
#   - The token (if any) arrives only as a read-only file, never an env var or the clone URL, and is sent as a
#     basic-auth header via a git config file on tmpfs — never on the command line, never logged.
#   - Gitleaks runs UNREDACTED into tmpfs /work, but the raw secret NEVER leaves this container: we compute a
#     SHA-256 and a masked preview here and write only those to /out. /work is tmpfs and dies with the container.
#   - Tracing is never enabled (no `set -x`), and no secret value is ever echoed.
#
# Inputs (env):   SCAN_CLONE_URL (required), SCAN_BRANCH (optional), SCAN_CLONE_DEPTH (default 1)
# Inputs (files): /run/secrets/gitlab-token (optional, read-only)
# Outputs (/out): result.json (manifest: commit, tool versions, per-scanner status) and secrets.json (findings)
set -eu

. /usr/local/bin/secscan-lib.sh

WORK=/work
OUT=/out
CHECKOUT="$WORK/checkout"
TOKEN_FILE=/run/secrets/gitlab-token
RAW="$WORK/gitleaks-raw.json"

: "${SCAN_CLONE_URL:?SCAN_CLONE_URL is required}"
BRANCH="${SCAN_BRANCH:-}"
DEPTH="${SCAN_CLONE_DEPTH:-1}"

GIT_VERSION="$(git --version | awk '{print $3}')"
GITLEAKS_VERSION="$(gitleaks version 2>/dev/null || echo unknown)"

# --- Clone (token, if present, via a tmpfs git config header; never in the URL/argv/env) ---
GIT_CONFIG_GLOBAL="$WORK/gitconfig"
export GIT_CONFIG_GLOBAL
: > "$GIT_CONFIG_GLOBAL"
if [ -s "$TOKEN_FILE" ]; then
    # Basic auth: user "oauth2", password = token. Build the header value without putting the token on argv.
    basic="$(printf 'oauth2:%s' "$(cat "$TOKEN_FILE")" | base64 | tr -d '\n')"
    {
        echo '[http]'
        printf '\textraHeader = Authorization: Basic %s\n' "$basic"
    } > "$GIT_CONFIG_GLOBAL"
    unset basic
fi

set +e
# shellcheck disable=SC2086
git clone --depth "$DEPTH" --single-branch ${BRANCH:+--branch "$BRANCH"} "$SCAN_CLONE_URL" "$CHECKOUT" 2>"$WORK/clone.err"
CLONE_EXIT=$?
set -e
# The token header lives only here; drop it immediately after the clone.
: > "$GIT_CONFIG_GLOBAL"

if [ "$CLONE_EXIT" -ne 0 ]; then
    # Surface a clone failure distinctly so the worker can tell "no access" from "scanner crashed".
    # The error text may echo the URL but never the token (it is not in the URL).
    jq -nc --arg err "clone failed (exit $CLONE_EXIT)" \
        '{schemaVersion:1, cloneFailed:true, error:$err, scanners:[]}' > "$OUT/result.json"
    sed 's/[A-Za-z0-9+/=]\{20,\}/<redacted>/g' "$WORK/clone.err" >&2 || true
    exit 10
fi

COMMIT="$(git -C "$CHECKOUT" rev-parse HEAD)"

# --- Secret scan (Gitleaks, unredacted into tmpfs) ---
# `gitleaks dir` does NOT follow symlinks unless --follow-symlinks is passed (which we deliberately do not):
# a symlink in the repo must never make the scanner read a file outside the checkout.
# --exit-code 2 is returned when leaks are found, so we can tell "leaks found" (2) apart from a real gitleaks
# error (gitleaks returns 1 on error); 0 = clean. The JSON report is written to a file only (never stdout).
START="$(date +%s)"
set +e
gitleaks dir "$CHECKOUT" --no-banner --report-format json --report-path "$RAW" --exit-code 2 >"$WORK/gitleaks.log" 2>&1
GL_EXIT=$?
set -e
END="$(date +%s)"
DUR_MS=$(( (END - START) * 1000 ))

# A run is successful only when gitleaks exited 0 (clean) or 2 (leaks found) AND wrote a valid JSON array
# report. Any other exit code, or a missing/invalid report, is a scanner failure (status "failed", exit 20).
if { [ "$GL_EXIT" -eq 0 ] || [ "$GL_EXIT" -eq 2 ]; } \
        && [ -f "$RAW" ] && jq -e 'type == "array"' "$RAW" >/dev/null 2>&1; then
    SECRET_STATUS="ok"
else
    SECRET_STATUS="failed"
fi

# --- Sanitize: compute SHA-256 + masked preview here; the raw secret never reaches /out ---
: > "$WORK/secrets.ndjson"
if [ "$SECRET_STATUS" = "ok" ] && [ -s "$RAW" ]; then
    # Emit tab-separated rows with the secret base64-encoded, so arbitrary bytes survive the read loop. File
    # paths are made repo-relative (the checkout prefix stripped) for stable fingerprints in M3.
    jq -r --arg prefix "$CHECKOUT/" \
        '.[] | [.RuleID, (.File|ltrimstr($prefix)), (.StartLine|tostring), (.Secret|@base64)] | @tsv' \
        "$RAW" > "$WORK/findings.tsv"
    while IFS="$(printf '\t')" read -r rule file line secret_b64; do
        [ -n "$rule" ] || continue
        secret="$(printf '%s' "$secret_b64" | base64 -d)"
        hash="$(printf '%s' "$secret" | sha256sum | cut -d' ' -f1)"
        preview="$(secret_preview "$secret")"
        unset secret
        jq -nc --arg r "$rule" --arg f "$file" --argjson l "${line:-0}" \
               --arg h "$hash" --arg p "$preview" \
               '{ruleId:$r, file:$f, startLine:$l, secretHash:$h, preview:$p}' >> "$WORK/secrets.ndjson"
    done < "$WORK/findings.tsv"
fi
jq -s '.' "$WORK/secrets.ndjson" > "$OUT/secrets.json"

# --- Manifest ---
jq -nc \
    --arg commit "$COMMIT" \
    --arg git "$GIT_VERSION" \
    --arg gitleaks "$GITLEAKS_VERSION" \
    --arg status "$SECRET_STATUS" \
    --argjson exit "$GL_EXIT" \
    --argjson dur "$DUR_MS" \
    '{schemaVersion:1, cloneFailed:false, commit:$commit,
      toolVersions:{git:$git, gitleaks:$gitleaks},
      scanners:[{name:"secret", status:$status, exitCode:$exit, durationMs:$dur, findingsFile:"secrets.json"}]}' \
    > "$OUT/result.json"

# A scanner failure is reported in the manifest; exit non-zero only for an unexpected scanner crash so the
# worker records a partial scan rather than a hard failure.
if [ "$SECRET_STATUS" = "failed" ]; then
    exit 20
fi
exit 0
