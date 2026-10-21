#!/bin/sh
# SecScan scanner entrypoint (M2 + M4b + M6): clone one repo at one commit, run Betterleaks (secrets),
# OSV-Scanner (SCA), Opengrep (SAST) and Checkov (IaC), and write SANITIZED results. All scanners run fully
# offline on the clone (no new egress host in M6; D-053/D-054).
#
# Security contract (CLAUDE.md + D-014 + D-024 + D-036 + D-037):
#   - The token (if any) arrives only as a read-only file, never an env var or the clone URL, and is sent as a
#     basic-auth header via a git config file on tmpfs — never on the command line, never logged.
#   - Betterleaks runs UNREDACTED into tmpfs /work, but the raw secret NEVER leaves this container: we compute a
#     SHA-256 and a masked preview here and write only those to /out. /work is tmpfs and dies with the container.
#   - In-repo suppressors are IGNORED (D-037): a pinned --config overrides any .betterleaks.toml/.gitleaks.toml,
#     --ignore-gitleaks-allow ignores inline allow comments, and --gitleaks-ignore-path points at a baked empty
#     file so the repo's .betterleaksignore/.gitleaksignore is never read.
#   - Validation is OFF: we never pass --validation (or any --validation-* flag), so no live provider calls are
#     made. Betterleaks' rule validate() blocks only run under --validation.
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
RAW="$WORK/betterleaks-raw.json"

# Pinned scanner configs baked into the image (D-037). Passed with --config so in-repo suppressors are ignored.
BL_CONFIG=/etc/secscan/betterleaks.toml
BL_IGNORE=/etc/secscan/betterleaks-empty.ignore
OSV_CONFIG=/etc/secscan/osv-scanner.toml

: "${SCAN_CLONE_URL:?SCAN_CLONE_URL is required}"
BRANCH="${SCAN_BRANCH:-}"
DEPTH="${SCAN_CLONE_DEPTH:-1}"

GIT_VERSION="$(git --version | awk '{print $3}')"
BETTERLEAKS_VERSION="$(betterleaks version 2>/dev/null || echo unknown)"

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

# --- Secret scan (Betterleaks, unredacted into tmpfs; D-036) ---
# `betterleaks dir` does NOT follow symlinks unless asked to (we never do): a symlink in the repo must never
# make the scanner read a file outside the checkout.
# --exit-code 2 is returned when leaks are found, so we can tell "leaks found" (2) apart from a real betterleaks
# error (betterleaks returns 1 on error, e.g. a bad config/unreadable target); 0 = clean. The JSON report is
# written to a file only (never stdout). In-repo suppressors are ignored (D-037): --config (precedence #1)
# overrides any in-repo config, --ignore-gitleaks-allow ignores inline allow comments, and --gitleaks-ignore-path
# points at a baked empty file so the repo's ignore file is not read. We never pass --validation (no live calls).
START="$(date +%s)"
set +e
betterleaks dir "$CHECKOUT" --no-banner --report-format json --report-path "$RAW" --exit-code 2 \
    --config "$BL_CONFIG" --ignore-gitleaks-allow --gitleaks-ignore-path "$BL_IGNORE" \
    >"$WORK/betterleaks.log" 2>&1
GL_EXIT=$?
set -e
END="$(date +%s)"
DUR_MS=$(( (END - START) * 1000 ))

# A run is successful only when betterleaks exited 0 (clean) or 2 (leaks found) AND wrote a valid JSON report.
# Betterleaks writes an ARRAY when there are findings but the JSON literal `null` when there are none, so both
# "array" and "null" are valid; any other exit code, or a missing/invalid report, is a scanner failure
# (status "failed", exit 20).
if { [ "$GL_EXIT" -eq 0 ] || [ "$GL_EXIT" -eq 2 ]; } \
        && [ -f "$RAW" ] && jq -e 'type == "array" or type == "null"' "$RAW" >/dev/null 2>&1; then
    SECRET_STATUS="ok"
else
    SECRET_STATUS="failed"
fi

# --- Sanitize: compute SHA-256 + masked preview here; the raw secret never reaches /out ---
: > "$WORK/secrets.ndjson"
if [ "$SECRET_STATUS" = "ok" ] && [ -s "$RAW" ]; then
    # Emit tab-separated rows with the secret base64-encoded, so arbitrary bytes survive the read loop. File
    # paths are made repo-relative (the checkout prefix stripped) for stable fingerprints in M3. `// []` turns a
    # `null` (no findings) report into an empty list. Betterleaks' JSON fields (RuleID/File/StartLine/Secret)
    # are Gitleaks-compatible.
    jq -r --arg prefix "$CHECKOUT/" \
        '(. // []) | .[] | [.RuleID, (.File|ltrimstr($prefix)), (.StartLine|tostring), (.Secret|@base64)] | @tsv' \
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
        # Full NuGet transitive resolution is deferred (D-043): OSV v2.7.0 resolves transitive NuGet deps only
        # from a lockfile, and we do NOT build a deps.dev resolver / synthetic lockfile. Make the gap HONEST
        # (D-030): this is ADDITIONAL to MISSING_NUGET_LOCKFILE above; both coexist (and the D-035 dedup keys on
        # MISSING_NUGET_LOCKFILE only, so it is unaffected).
        add_warning NUGET_NO_LOCKFILE_TRANSITIVE_GAP \
            "Without a NuGet lockfile, transitive NuGet dependencies are not resolved; only direct PackageReferences are scanned (D-043)." \
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

# --- Static Gradle resolution (D-038/D-039/D-041/D-042) ---
# Emit a synthetic Maven pom OUTSIDE the clone for each Gradle build file that ships NO lockfile, so OSV can
# resolve its TRANSITIVE dependencies (a locked project is resolved by OSV natively; a build-file-only project
# otherwise finds nothing). Gradle findings therefore surface in OSV's MAVEN ecosystem. The resolver reads the
# repo as DATA only -- `python3 -I` keeps the scanned tree and the CWD off sys.path so a planted json.py can
# never be imported (D-004) -- and writes the poms plus a {syntheticPath -> repo-relative build file} map to
# tmpfs /work. It is best-effort: on ANY failure we fall back to an empty map and the scan still runs (OSV over
# the checkout, plus the MISSING_GRADLE_LOCKFILE inventory warning). The parser rewrites OSV's synthetic-pom
# source path back to the build file before fingerprinting (D-038); an unmapped /work path is dropped (D-035).
#
# NETWORK (D-042): because OSV's pomxml enricher does NOT inherit versions from a <dependencyManagement> BOM
# import, the resolver itself FETCHES the governing BOM POMs (Spring Boot/Cloud, platform(), mavenBom) so it can
# write a concrete <version> on every dependency. It performs read-only HTTPS GETs of .pom files from ONE host
# only -- Maven Central (repo.maven.apache.org, already allowed by D-033) or the mirror in
# SECSCAN_MAVEN_BASE_URL -- with TLS verification always ON and cross-host redirects blocked (enforced in the
# resolver). Fetches are bounded (per-POM timeout, total cap, recursion depth, in-run cache); a fetch failure
# raises GRADLE_BOM_FETCH_FAILED and the scan continues. No build is executed; fetched POMs are parsed as data.
# The rest of the scan keeps its existing network posture; only this step needs Maven-Central egress.
SYNTH="$WORK/synthetic"
RESOLVER_OUT="$WORK/resolver-out.json"
SYNTH_MAP="$WORK/synthetic-map.json"
mkdir -p "$SYNTH"
set +e
python3 -I /usr/local/bin/gradle_resolver.py "$CHECKOUT" "$SYNTH" > "$RESOLVER_OUT" 2>"$WORK/resolver.log"
RESOLVER_EXIT=$?
set -e
# The resolver prints {poms:[...], warnings:[{code,message,filePath}]} on stdout AND writes, beside each
# synthetic pom it emits, a `buildfile` sidecar naming the originating build file. On any stdout failure, fall
# back to an empty object so the warnings read below is safe.
if [ "$RESOLVER_EXIT" -ne 0 ] || ! jq -e 'type == "object"' "$RESOLVER_OUT" >/dev/null 2>&1; then
    printf '{"poms":[],"warnings":[]}' > "$RESOLVER_OUT"
fi
# Surface every skipped construct/non-concrete version as a scan warning (GRADLE_UNPARSEABLE_CONSTRUCT, D-030):
# append the resolver's warnings to the inventory warnings so they reach the manifest and the issue feed.
jq -c '.warnings[]?' "$RESOLVER_OUT" >> "$WORK/warnings.ndjson"

# Build the synthetic-pom -> build-file map (D-038) from the files ON DISK -- the `buildfile` sidecar beside
# each synthetic pom -- NOT from the resolver's stdout. Each entry is keyed on the synthetic pom's real path,
# which is EXACTLY what OSV scans under $SYNTH and reports as source.path, so the parser's rewrite always
# matches (single- and multi-module alike). Deriving it from disk also means it survives a lost/truncated
# resolver stdout (e.g. a killed resolver): any synthetic pom that exists for OSV to scan carries its mapping,
# so a multi-module finding can never be orphaned into an UNRESOLVED_PATH drop.
: > "$WORK/synth-map.ndjson"
find "$SYNTH" -type f -name pom.xml 2>/dev/null | while read -r pom; do
    bf="$(dirname "$pom")/buildfile"
    [ -f "$bf" ] || continue
    jq -nc --arg s "$pom" --arg b "$(cat "$bf")" '{syntheticPath:$s, buildFile:$b}' >> "$WORK/synth-map.ndjson"
done
jq -s '.' "$WORK/synth-map.ndjson" > "$SYNTH_MAP"

# --- SCA scan (OSV-Scanner over the whole tree; deps.dev online, D-006/D-034) ---
# Structure-agnostic: nested modules, monorepos and mixed ecosystems all work; "no manifests" is a warning
# (NO_MANIFESTS), never a failure. TMPDIR is tmpfs /work because the root filesystem is read-only. We scan both
# the checkout and the synthetic-pom dir ($SYNTH); the synthetic dir is empty (harmless) when no Gradle build
# file needed resolving.
export TMPDIR="$WORK"
OSV_JSON="$OUT/osv.json"
OSV_VERSION="$(osv-scanner --version 2>/dev/null | awk '/osv-scanner version/{print $3; exit}')"
START="$(date +%s)"
set +e
# --config overrides any in-repo osv-scanner.toml so [[IgnoredVulns]]/[[PackageOverrides]] cannot suppress or
# downgrade a finding (D-037, verified against v2.7.0).
osv-scanner scan source -r --config "$OSV_CONFIG" --format json --output-file "$OSV_JSON" "$CHECKOUT" "$SYNTH" >"$WORK/osv.log" 2>&1
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

# On an OSV failure, surface its own stdout/stderr (captured to $WORK/osv.log) on the container's stderr so the
# worker's log_excerpt is debuggable -- otherwise an SCA failure leaves an empty excerpt (it is redirected to a
# tmpfs file that dies with the container). OSV's output is dependency/resolver data, never a secret value, and
# the worker scrubs token-shaped strings from the excerpt regardless. A clean/normal run stays quiet so the
# excerpt is not drowned out.
if [ "$SCA_STATUS" = "failed" ] && [ -s "$WORK/osv.log" ]; then
    echo "osv-scanner failed (exit $OSV_EXIT); last output follows:" >&2
    tail -c 4000 "$WORK/osv.log" >&2 || true
fi

# --- SAST scan (Opengrep over the clone; fully offline, D-053) ---
# Rules come ONLY via --config (no in-repo rule auto-load). In-repo suppressors are ignored (D-037):
# --disable-nosem ignores inline nosemgrep/nosem/noopengrep, and --x-ignore-semgrepignore-files ignores every
# in-repo .semgrepignore. --disable-version-check keeps it offline (Opengrep sends no telemetry; the removed
# Semgrep --metrics flag does not exist). We run from /etc/secscan with a RELATIVE `--config opengrep-rules`, so
# every check_id is the stable `opengrep-rules.<pack>.<id>` (never an absolute path); the parser relativizes the
# absolute $CHECKOUT file paths. Findings are read from the JSON, not the exit code: success = a valid JSON
# report AND exit 0/1 (1 is possible per Opengrep build); any other code = scanner failure.
SAST_JSON="$OUT/opengrep.json"
SAST_RAW="$WORK/opengrep-raw.json"
# Opengrep unpacks its (trusted, pinned) core binary to HOME/TMPDIR and execs it. /work is tmpfs noexec (so no
# CLONED repo code can ever run), so point Opengrep's own runtime at the separate exec-able tmpfs
# /opt/sast-exec (ScannerContainerConfig.TOOL_EXEC_MOUNT). The scanned repo stays on /work and is never placed
# on an exec mount, so "never execute scanned code" (D-004) is preserved — only our pinned tool runs here.
# NOTE: even `opengrep --version` execs the core, so it also needs the exec-able HOME/TMPDIR.
# The RAW report is written to tmpfs /work (NOT /out) because Opengrep's `extra.lines`/`extra.metavars` carry
# matched SOURCE LINES — which include secrets our own rules match — and must never leave the container
# (D-023/D-027). We compute the normalized-snippet SHA-256 HERE and write a sanitized report to /out with the
# raw lines/metavars stripped (S1); only the hash leaves the container.
SAST_TMP=/opt/sast-exec
OPENGREP_VERSION="$(HOME="$SAST_TMP" TMPDIR="$SAST_TMP" XDG_CACHE_HOME="$SAST_TMP" opengrep --version 2>/dev/null | head -1)"
START="$(date +%s)"
set +e
( cd /etc/secscan && HOME="$SAST_TMP" TMPDIR="$SAST_TMP" XDG_CACHE_HOME="$SAST_TMP" \
    opengrep scan --config opengrep-rules --json-output="$SAST_RAW" \
    --disable-version-check --disable-nosem --x-ignore-semgrepignore-files "$CHECKOUT" ) \
    >"$WORK/opengrep.log" 2>&1
SAST_EXIT=$?
set -e
END="$(date +%s)"
SAST_DUR_MS=$(( (END - START) * 1000 ))
[ -f "$SAST_RAW" ] || printf '{"results":[],"errors":[]}' > "$SAST_RAW"
if { [ "$SAST_EXIT" -eq 0 ] || [ "$SAST_EXIT" -eq 1 ]; } \
        && jq -e 'has("results")' "$SAST_RAW" >/dev/null 2>&1; then
    SAST_STATUS=ok
else
    SAST_STATUS=failed
fi
# --- Sanitize SAST (mirror the secret sanitize, D-023/S1): per result, compute the NORMALIZED-snippet SHA-256
# (lines trimmed, internal whitespace collapsed, blank lines dropped — same algorithm as the old Java
# SnippetNormalizer, which this replaces) and emit a cleaned result with extra.lines/metavars/fingerprint
# REMOVED and extra.snippet_sha256 added. The raw matched lines never reach /out. ---
: > "$WORK/sast.ndjson"
if [ "$SAST_STATUS" = "ok" ]; then
    jq -c '.results[]?' "$SAST_RAW" 2>/dev/null | while IFS= read -r result; do
        snippet="$(printf '%s' "$result" | jq -r '.extra.lines // ""')"
        hash="$(printf '%s' "$snippet" | awk 'NF>0{$1=$1;print}' | sha256sum | cut -d' ' -f1)"
        unset snippet
        printf '%s' "$result" | jq -c --arg h "$hash" \
            'del(.extra.lines, .extra.metavars, .extra.fingerprint) | .extra.snippet_sha256 = $h' \
            >> "$WORK/sast.ndjson"
    done
fi
jq -s '{results: ., errors: []}' "$WORK/sast.ndjson" > "$SAST_JSON"
if [ "$SAST_STATUS" = "failed" ] && [ -s "$WORK/opengrep.log" ]; then
    echo "opengrep failed (exit $SAST_EXIT); last output follows:" >&2
    tail -c 4000 "$WORK/opengrep.log" >&2 || true
fi

# --- IaC scan (Checkov over the clone; fully offline, D-054) ---
# Only STATIC-parse frameworks are enabled (checkov.yaml: dockerfile, kubernetes, terraform, cloudformation).
# Helm/Kustomize are NOT run — rendering them shells out to helm/kustomize (D-004, BLOCKER 1); Checkov 3.3.29 has
# no docker_compose framework (compose files are flagged as a gap below). A Chart.yaml present raises an honest
# IAC_HELM_NOT_RENDERED warning below (D-030). In-repo config is ignored (D-037): we pass our baked
# --config-file AND delete any in-repo .checkov.yaml/.checkov.yml from the clone (below), because --config-file
# alone does NOT stop Checkov honoring a repo config. Inline
# `#checkov:skip=` CANNOT be disabled by a flag, but a skipped check is still reported in skipped_checks[] — the
# parser RECOVERS those and promotes them back to findings. Offline (SHOULD-FIX 3): --skip-download (in the
# config) plus CKV_SKIP_PACKAGE_UPDATE_CHECK suppress every network call. Multi-framework JSON is an ARRAY of
# per-framework report objects. Exit: 0 = all passed/skipped, 1 = a check failed; both are success (findings are
# read from the JSON). Any other code = scanner failure.
IAC_JSON="$OUT/checkov.json"
CHECKOV_VERSION="$(checkov --version 2>/dev/null | head -1)"
export CKV_SKIP_PACKAGE_UPDATE_CHECK=True
# D-037: Checkov reads an in-repo .checkov.yaml/.checkov.yml from the scanned tree and HONORS its `skip-check`
# EVEN when we pass our own --config-file (verified), so a repo could silently hide its own findings. Our
# --config-file cannot override that, so we REMOVE every in-repo Checkov config from the EPHEMERAL in-container
# clone (never the real repo — the clone dies with the container; D-030 is about the real repository) before
# scanning. The removal is CLONE-SCOPED (rooted at $CHECKOUT), covers BOTH .checkov.yaml and .checkov.yml at ANY
# depth, and is SYMLINK-SAFE: find runs in -P mode (never follows a symlink), and we delete symlink ENTRIES too
# (-type l) so Checkov can never follow a symlinked .checkov.yaml out of the clone; a symlink's target (outside
# the clone) is never touched. We delete every ENTRY of that name — regular file, symlink OR DIRECTORY (S3: a
# repo could commit a *directory* named .checkov.yaml, which `-type f`/`-type l` missed, making Checkov crash and
# IaC always fail) — with `-depth -exec rm -rf` (contents first; rm removes a symlink entry without following it).
# No `|| true`: if removal FAILS we must NOT scan with a repo config present — we mark IaC failed (closes
# nothing). Inline `#checkov:skip=` can't be flag-disabled but is reported in skipped_checks[] and recovered.
IAC_STATUS=ok
START="$(date +%s)"
if ! find "$CHECKOUT" -depth \( -name '.checkov.yaml' -o -name '.checkov.yml' \) -exec rm -rf {} + \
        2>"$WORK/ckcfg.err"; then
    echo "failed to remove in-repo Checkov config from the clone; failing IaC (not scanning with it present)" >&2
    sed 's/[A-Za-z0-9+/=]\{20,\}/<redacted>/g' "$WORK/ckcfg.err" >&2 || true
    IAC_STATUS=failed
fi
set +e
if [ "$IAC_STATUS" = "ok" ]; then
    # `checkov -o json` writes the JSON report to stdout (banner/chatter to stderr); capture each separately.
    checkov -d "$CHECKOUT" -o json --config-file /etc/secscan/checkov.yaml >"$IAC_JSON" 2>"$WORK/checkov.log"
    IAC_EXIT=$?
else
    IAC_EXIT=1
fi
set -e
END="$(date +%s)"
IAC_DUR_MS=$(( (END - START) * 1000 ))
if [ "$IAC_STATUS" = "ok" ] && { [ "$IAC_EXIT" -eq 0 ] || [ "$IAC_EXIT" -eq 1 ]; } \
        && [ -f "$IAC_JSON" ] && jq -e 'type == "array" or type == "object"' "$IAC_JSON" >/dev/null 2>&1; then
    IAC_STATUS=ok
else
    IAC_STATUS=failed
fi
[ -f "$IAC_JSON" ] || printf '[]' > "$IAC_JSON"
if [ "$IAC_STATUS" = "failed" ] && [ -s "$WORK/checkov.log" ]; then
    echo "checkov failed (exit $IAC_EXIT); last output follows:" >&2
    tail -c 4000 "$WORK/checkov.log" >&2 || true
fi
# D-030 honest gap: Helm charts are not rendered (no helm binary, D-004). Flag any Chart.yaml so the coverage
# gap is shown, not hidden.
if find "$CHECKOUT" -type f -name Chart.yaml 2>/dev/null | head -1 | grep -q .; then
    add_warning IAC_HELM_NOT_RENDERED \
        "Helm charts are not rendered or scanned (chart rendering requires executing helm, which is disallowed; D-004/D-054)." \
        ""
fi
# D-030 honest gap: Checkov 3.3.29 has no docker_compose framework, so compose files are not scanned. Flag any
# docker-compose/compose file so the coverage gap is shown, not hidden (D-054).
if find "$CHECKOUT" -type f \( -name 'docker-compose.yml' -o -name 'docker-compose.yaml' \
        -o -name 'compose.yml' -o -name 'compose.yaml' \) 2>/dev/null | head -1 | grep -q .; then
    add_warning IAC_DOCKER_COMPOSE_UNSUPPORTED \
        "Docker Compose files are not scanned (Checkov 3.3.29 has no docker_compose framework; D-054)." \
        ""
fi

# --- EOL detection (static; M6, D-055) ---
# Our own detector identifies runtimes/frameworks (Spring Boot, Java, .NET, Node, Dockerfile base images, …)
# by PARSING files only (no build execution, D-004) and emits {detections:[{product,version,cycle,filePath}]}.
# It reaches no network; the worker joins these against the eol_cycle intel cache to decide which are actually
# EOL/ending-soon issues. `python3 -I` keeps the scanned tree off sys.path.
EOL_JSON="$OUT/eol.json"
START="$(date +%s)"
set +e
python3 -I /usr/local/bin/eol_detect.py "$CHECKOUT" > "$EOL_JSON" 2>"$WORK/eol.log"
EOL_EXIT=$?
set -e
END="$(date +%s)"
EOL_DUR_MS=$(( (END - START) * 1000 ))
# Success = exit 0 AND a valid JSON document with a detections array; otherwise the EOL scanner failed (and so
# closes nothing — domain rule 3).
if [ "$EOL_EXIT" -eq 0 ] && [ -f "$EOL_JSON" ] && jq -e 'has("detections")' "$EOL_JSON" >/dev/null 2>&1; then
    EOL_STATUS=ok
else
    EOL_STATUS=failed
fi
[ -f "$EOL_JSON" ] || printf '{"detections":[],"warnings":[],"status":"error"}' > "$EOL_JSON"
# Surface the detector's own warnings into the scan warnings.
jq -c '.warnings[]?' "$EOL_JSON" >> "$WORK/warnings.ndjson" 2>/dev/null || true
if [ "$EOL_STATUS" = "failed" ] && [ -s "$WORK/eol.log" ]; then
    echo "eol detector failed (exit $EOL_EXIT); last output follows:" >&2
    tail -c 2000 "$WORK/eol.log" >&2 || true
fi

# --- .secscan.yml (M7, D-057): read the OPTIONAL in-repo config as DATA ONLY and emit a bounded, whitelisted
# JSON the worker applies at triage step 9. This is the ONLY carve-out of D-037, and only because the worker
# surfaces + audits every suppression it yields. `python3 -I` keeps the scanned tree off sys.path; the reader is
# symlink-safe, realpath-confined to the clone, and NEVER echoes raw file bytes. A failure here never fails the
# scan — the config is simply treated as absent. ---
SECSCAN_CONFIG_JSON="$WORK/secscan_config.json"
set +e
python3 -I /usr/local/bin/secscan_config.py "$CHECKOUT" > "$SECSCAN_CONFIG_JSON" 2>"$WORK/secscan_config.log"
set -e
if [ ! -s "$SECSCAN_CONFIG_JSON" ] || ! jq -e 'has("present")' "$SECSCAN_CONFIG_JSON" >/dev/null 2>&1; then
    printf '{"present":false,"excludePaths":[],"ignoreCves":{},"includeDevDependencies":null,"warnings":[],"status":"error"}' \
        > "$SECSCAN_CONFIG_JSON"
fi
# Surface the reader's own warnings (symlink refused, unknown/oversized keys dropped, …) into the scan warnings.
jq -c '.warnings[]?' "$SECSCAN_CONFIG_JSON" >> "$WORK/warnings.ndjson" 2>/dev/null || true

# --- Declared dev/test scope inventory (M7, D-059): read the repo's OWN manifests (pom test scope, npm
# devDependencies, Gradle test configs) as DATA ONLY so the worker can auto-triage DEV_DEPENDENCY/TEST_SCOPE.
# OSV exposes no scope marker, and this never guesses — unclassifiable deps are omitted and stay visible. A
# failure here never fails the scan (the inventory is simply empty). ---
SCOPES_JSON="$WORK/scopes.json"
set +e
python3 -I /usr/local/bin/scope_inventory.py "$CHECKOUT" > "$SCOPES_JSON" 2>"$WORK/scopes.log"
set -e
if [ ! -s "$SCOPES_JSON" ] || ! jq -e 'has("scopes")' "$SCOPES_JSON" >/dev/null 2>&1; then
    printf '{"scopes":[],"warnings":[],"status":"error"}' > "$SCOPES_JSON"
fi

# --- Manifest ---
jq -s '.' "$WORK/warnings.ndjson" > "$WORK/warnings.json"
jq -nc \
    --arg commit "$COMMIT" \
    --arg checkout "$CHECKOUT" \
    --arg git "$GIT_VERSION" \
    --arg betterleaks "$BETTERLEAKS_VERSION" \
    --arg osv "$OSV_VERSION" \
    --arg opengrep "$OPENGREP_VERSION" \
    --arg checkov "$CHECKOV_VERSION" \
    --arg sstatus "$SECRET_STATUS" \
    --argjson sexit "$GL_EXIT" \
    --argjson sdur "$DUR_MS" \
    --arg castatus "$SCA_STATUS" \
    --argjson caexit "$OSV_EXIT" \
    --argjson cadur "$OSV_DUR_MS" \
    --argjson nopkg "$OSV_NO_PACKAGES" \
    --arg saststatus "$SAST_STATUS" \
    --argjson sastexit "$SAST_EXIT" \
    --argjson sastdur "$SAST_DUR_MS" \
    --arg iacstatus "$IAC_STATUS" \
    --argjson iacexit "$IAC_EXIT" \
    --argjson iacdur "$IAC_DUR_MS" \
    --arg eolstatus "$EOL_STATUS" \
    --argjson eolexit "$EOL_EXIT" \
    --argjson eoldur "$EOL_DUR_MS" \
    --slurpfile warnings "$WORK/warnings.json" \
    --slurpfile synth "$SYNTH_MAP" \
    --slurpfile cfg "$SECSCAN_CONFIG_JSON" \
    --slurpfile scopes "$SCOPES_JSON" \
    '{schemaVersion:1, cloneFailed:false, commit:$commit, checkoutPath:$checkout,
      toolVersions:{git:$git, betterleaks:$betterleaks, "osv-scanner":$osv, opengrep:$opengrep, checkov:$checkov},
      warnings: ($warnings[0] // []),
      syntheticPoms: ($synth[0] // []),
      config: ($cfg[0] // null),
      declaredScopes: (($scopes[0] // {}).scopes // []),
      scopeInventoryStatus: (($scopes[0] // {}).status // "error"),
      scanners:[
        {name:"secret", status:$sstatus, exitCode:$sexit, durationMs:$sdur, findingsFile:"secrets.json"},
        {name:"sca", status:$castatus, exitCode:$caexit, durationMs:$cadur, findingsFile:"osv.json",
         noPackages:$nopkg},
        {name:"sast", status:$saststatus, exitCode:$sastexit, durationMs:$sastdur, findingsFile:"opengrep.json"},
        {name:"iac", status:$iacstatus, exitCode:$iacexit, durationMs:$iacdur, findingsFile:"checkov.json"},
        {name:"eol", status:$eolstatus, exitCode:$eolexit, durationMs:$eoldur, findingsFile:"eol.json"}
      ]}' \
    > "$OUT/result.json"

# A scanner failure is reported per-scanner in the manifest (the worker records a partial scan). Exit non-zero
# only to signal an unexpected scanner failure; clone failures exit 10 above.
if [ "$SECRET_STATUS" = "failed" ] || [ "$SCA_STATUS" = "failed" ] \
        || [ "$SAST_STATUS" = "failed" ] || [ "$IAC_STATUS" = "failed" ] || [ "$EOL_STATUS" = "failed" ]; then
    exit 20
fi
exit 0
