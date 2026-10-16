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

# --- Inventory: scan-health warnings, independent of any scanner's result (D-030/D-031). ---
# A build file with no lockfile, or a non-concrete .csproj version, is a coverage gap we SHOW, never hide.
: > "$WORK/warnings.ndjson"
add_warning() {  # code  message  repo-relative-path
    jq -nc --arg c "$1" --arg m "$2" --arg f "$3" '{code:$c, message:$m, filePath:$f}' >> "$WORK/warnings.ndjson"
}
relpath() { printf '%s' "${1#"$CHECKOUT"/}"; }

# Gradle: build.gradle(.kts) with no lockfile in the same directory (OSV resolves nothing without one; M4b).
find "$CHECKOUT" -type f \( -name build.gradle -o -name build.gradle.kts \) 2>/dev/null | while read -r bg; do
    d="$(dirname "$bg")"
    if [ ! -f "$d/gradle.lockfile" ] && [ ! -f "$d/buildscript-gradle.lockfile" ]; then
        add_warning MISSING_GRADLE_LOCKFILE \
            "Gradle build file has no gradle.lockfile; dependency versions are not resolved (M4b)." \
            "$(relpath "$bg")"
    fi
done

# .NET: a .csproj with no packages.lock.json next to it is scanned for DIRECT PackageReference versions only
# (OSV reads .csproj natively, D-035) — transitive deps are not seen. Non-concrete versions are skipped+warned.
find "$CHECKOUT" -type f -name '*.csproj' 2>/dev/null | while read -r csproj; do
    d="$(dirname "$csproj")"
    rel="$(relpath "$csproj")"
    if [ ! -f "$d/packages.lock.json" ]; then
        add_warning MISSING_NUGET_LOCKFILE \
            "No packages.lock.json next to the project; only direct PackageReference versions are scanned, not transitive (M4b)." \
            "$rel"
    fi
    csproj_package_versions "$csproj" | while IFS="$(printf '\t')" read -r pkg ver; do
        # Empty version = central package management (version in Directory.Packages.props), which OSV resolves;
        # only warn for a present-but-non-concrete version (property/range/wildcard/VersionOverride).
        if [ -n "$ver" ] && nuget_version_unresolved "$ver"; then
            add_warning UNRESOLVED_VERSION \
                "PackageReference '$pkg' has a non-concrete version ('$ver'); skipped, not guessed (D-031)." "$rel"
        fi
    done
done

# --- SCA scan (OSV-Scanner over the whole tree; deps.dev online, D-006/D-034) ---
# Structure-agnostic: nested modules, monorepos and mixed ecosystems all work; "no manifests" is a warning
# (NO_MANIFESTS), never a failure. TMPDIR is tmpfs /work because the root filesystem is read-only.
export TMPDIR="$WORK"
OSV_JSON="$OUT/osv.json"
OSV_VERSION="$(osv-scanner --version 2>/dev/null | awk '/osv-scanner version/{print $3; exit}')"
START="$(date +%s)"
set +e
osv-scanner scan source -r --format json --output-file "$OSV_JSON" "$CHECKOUT" >"$WORK/osv.log" 2>&1
OSV_EXIT=$?
set -e
END="$(date +%s)"
OSV_DUR_MS=$(( (END - START) * 1000 ))

# Exit-code mapping (D-034, verified against osv-scanner v2.7.0): 0/1 = success (0 clean, 1 vulns found);
# 128 = no packages found (status ok, but NOT authoritative to close findings this scan — NO_MANIFESTS);
# any other code (127 general error, 129-255 non-result errors, …) = scanner failure.
OSV_NO_PACKAGES=false
case "$OSV_EXIT" in
    0 | 1) SCA_STATUS=ok ;;
    128)
        SCA_STATUS=ok
        OSV_NO_PACKAGES=true
        add_warning NO_MANIFESTS "No supported dependency manifests or lockfiles were found to scan." ""
        ;;
    *) SCA_STATUS=failed ;;
esac
# Guarantee a parseable findings file even when OSV wrote none (error/no-packages), so the parser never fails.
[ -f "$OSV_JSON" ] || printf '{"results":[]}' > "$OSV_JSON"

# --- Manifest ---
jq -s '.' "$WORK/warnings.ndjson" > "$WORK/warnings.json"
jq -nc \
    --arg commit "$COMMIT" \
    --arg checkout "$CHECKOUT" \
    --arg git "$GIT_VERSION" \
    --arg gitleaks "$GITLEAKS_VERSION" \
    --arg osv "$OSV_VERSION" \
    --arg sstatus "$SECRET_STATUS" \
    --argjson sexit "$GL_EXIT" \
    --argjson sdur "$DUR_MS" \
    --arg castatus "$SCA_STATUS" \
    --argjson caexit "$OSV_EXIT" \
    --argjson cadur "$OSV_DUR_MS" \
    --argjson nopkg "$OSV_NO_PACKAGES" \
    --slurpfile warnings "$WORK/warnings.json" \
    '{schemaVersion:1, cloneFailed:false, commit:$commit, checkoutPath:$checkout,
      toolVersions:{git:$git, gitleaks:$gitleaks, "osv-scanner":$osv},
      warnings: ($warnings[0] // []),
      scanners:[
        {name:"secret", status:$sstatus, exitCode:$sexit, durationMs:$sdur, findingsFile:"secrets.json"},
        {name:"sca", status:$castatus, exitCode:$caexit, durationMs:$cadur, findingsFile:"osv.json",
         noPackages:$nopkg}
      ]}' \
    > "$OUT/result.json"

# A scanner failure is reported per-scanner in the manifest (the worker records a partial scan). Exit non-zero
# only to signal an unexpected scanner failure; clone failures exit 10 above.
if [ "$SECRET_STATUS" = "failed" ] || [ "$SCA_STATUS" = "failed" ]; then
    exit 20
fi
exit 0
