# SecScan scanner image (M2 + M4 + M4b + M6): git + Betterleaks (secrets) + OSV-Scanner (SCA) + Opengrep (SAST)
# + Checkov (IaC) + a static EOL detector. One disposable container per scan; destroyed afterwards. Only /out
# and the container logs leave it (CLAUDE.md security rules).
#
# Pinned: the base image by digest; Betterleaks, OSV-Scanner and Opengrep by version + SHA-256 checksum; Checkov
# by version + a reproducible dependency lock; the Opengrep rule packs by commit SHA (point 8 / D-023 / D-034 /
# D-036 / D-053 / D-054). apk package versions follow the pinned Alpine branch (Known gap until we snapshot a
# mirror).
#
# NOTE ON THE IMAGE TAG: the tag secscan-scanner:8.21.2 is a HISTORICAL IDENTIFIER (it began as the Gitleaks
# version), NOT the engine version. The secrets engine is now Betterleaks 1.7.3 (D-036) and the tag deliberately
# stays 8.21.2 — it only has to match ScannerProperties.image and the M10 rebuild tracker (D-034).

FROM alpine:3.21@sha256:ce64758a109eb420d874a118f87920e625e12d3634e03b4a5573fd9f6e5d3507

# sh (busybox), base64, sha256sum, cut, awk, date are all provided by busybox; add git, jq, certs, and
# xmllint (libxml2-utils) for reading direct <PackageReference> versions out of .csproj for SCA warnings (M4).
# python3 is the static Gradle/.NET resolver runtime (D-039) AND the EOL detector (M6, eol_detect.py): stdlib
# only (tomllib needs >= 3.11). It is PINNED to the exact Alpine 3.21 package version (3.12.15-r0, which ships
# tomllib) -- the one apk we pin by version, per D-039; the others still follow the pinned Alpine branch (Known
# gap until we snapshot a mirror). py3-pip is the Checkov installer (M6, D-054) and follows the pinned branch.
# py3-yaml (PyYAML 6.0.2) is the .secscan.yml reader's parser (M7, D-057), PINNED to the exact Alpine 3.21
# package version (6.0.2-r0); secscan_config.py uses yaml.safe_load ONLY.
RUN apk add --no-cache git jq ca-certificates libxml2-utils python3=3.12.15-r0 py3-pip py3-yaml=6.0.2-r0

# --- Betterleaks (pinned version + checksum, verified against the release checksums.txt before use; D-036).
# Betterleaks (betterleaks/betterleaks, MIT, by the original Gitleaks author) replaces Gitleaks 8.21.2 as the
# secrets engine (OD-7). The release assets mirror Gitleaks' naming: betterleaks_<ver>_<asset>.tar.gz containing
# the `betterleaks` binary at the tar root. validation stays OFF (no --validation flag → no live provider
# calls); run-scan.sh enforces that. ---
ARG TARGETARCH=amd64
ARG BETTERLEAKS_VERSION=1.7.3
ARG BETTERLEAKS_SHA256_AMD64=9d2ed6fc387bc1c3f95557d5539077b1ce422f0db5ec38db5f14a141ad2947bf
ARG BETTERLEAKS_SHA256_ARM64=0541cd2980964a6fb8ea768e8b9507c91a3c06229193462f33a3ecd9cb146b99
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) asset="linux_x64";  sha="$BETTERLEAKS_SHA256_AMD64" ;; \
        arm64) asset="linux_arm64"; sha="$BETTERLEAKS_SHA256_ARM64" ;; \
        *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/betterleaks/betterleaks/releases/download/v${BETTERLEAKS_VERSION}/betterleaks_${BETTERLEAKS_VERSION}_${asset}.tar.gz"; \
    wget -q -O /tmp/betterleaks.tar.gz "$url"; \
    echo "${sha}  /tmp/betterleaks.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/betterleaks.tar.gz -C /usr/local/bin betterleaks; \
    rm /tmp/betterleaks.tar.gz; \
    betterleaks version

# --- OSV-Scanner (pinned version + checksum, verified before use; D-034). The release assets are raw static
# binaries (no tarball), verified against the per-arch SHA-256 from osv-scanner_SHA256SUMS. v2.7.0 source-scans
# Maven/Gradle/npm/yarn/pnpm/bun/NuGet lockfiles AND .csproj + NuGet Central Package Management natively. ---
ARG OSV_VERSION=2.7.0
ARG OSV_SHA256_AMD64=160c6253e2922e9e0a6a9771c5c4c40a4b3c6090b34a3cdd5e80c96cc2e4218b
ARG OSV_SHA256_ARM64=4061ef4c021ed3c47f203b818f337e351336cc5cd67c0274fc36bd1dfc728dd7
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) asset="linux_amd64"; sha="$OSV_SHA256_AMD64" ;; \
        arm64) asset="linux_arm64"; sha="$OSV_SHA256_ARM64" ;; \
        *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/google/osv-scanner/releases/download/v${OSV_VERSION}/osv-scanner_${asset}"; \
    wget -q -O /usr/local/bin/osv-scanner "$url"; \
    echo "${sha}  /usr/local/bin/osv-scanner" | sha256sum -c -; \
    chmod 0555 /usr/local/bin/osv-scanner; \
    osv-scanner --version

# --- Opengrep (SAST, pinned version + checksum, verified before use; D-053). The release assets are raw
# per-arch static binaries (like OSV-Scanner). We use the MUSLLINUX builds because the base image is Alpine
# (musl libc) — the manylinux (glibc) builds do NOT run here (they fail with exit 127). v1.31.0 is the stable
# line (NOT the 2.0.0 alpha). Engine is LGPL-2.1. NOTE the amd64 asset is named `opengrep_musllinux_x86` (not
# `_amd64`). Opengrep sends no telemetry (Semgrep's --metrics was removed); only a version-check ping exists,
# disabled with --disable-version-check in run-scan.sh. Checksums computed from the pinned v1.31.0 assets. ---
ARG OPENGREP_VERSION=1.31.0
ARG OPENGREP_SHA256_AMD64=02c9fba481611aa0664bc79ccf7ce59e9e334b76af8921c80b2f738d17fabf9b
ARG OPENGREP_SHA256_ARM64=b7cbedab0b6c9953ea27ce3baa006ad8b6fa1c50ee7433638b63f7fc731519d9
# Cosign (pinned by version + per-arch SHA-256) verifies the Opengrep release's keyless Sigstore signature:
# the .sig/.cert must be signed by Opengrep's own GitHub Actions identity (SHOULD-FIX 3). This is build-time
# only — cosign is removed afterwards and is never in the runtime image.
ARG COSIGN_VERSION=3.1.3
ARG COSIGN_SHA256_AMD64=4629c757b7618056f8ddd7e2625ae9fdd94c0372a65049520bc7d9df9efc7f71
ARG COSIGN_SHA256_ARM64=c5d324e091826b0d7a78eb16fef316450b4eb9aaec045611c08ba06f5e73220a
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) asset="opengrep_musllinux_x86";     sha="$OPENGREP_SHA256_AMD64"; carch="amd64"; csha="$COSIGN_SHA256_AMD64" ;; \
        arm64) asset="opengrep_musllinux_aarch64"; sha="$OPENGREP_SHA256_ARM64"; carch="arm64"; csha="$COSIGN_SHA256_ARM64" ;; \
        *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    base="https://github.com/opengrep/opengrep/releases/download/v${OPENGREP_VERSION}"; \
    wget -q -O /usr/local/bin/opengrep "${base}/${asset}"; \
    wget -q -O /tmp/opengrep.sig  "${base}/${asset}.sig"; \
    wget -q -O /tmp/opengrep.cert "${base}/${asset}.cert"; \
    echo "${sha}  /usr/local/bin/opengrep" | sha256sum -c -; \
    wget -q -O /tmp/cosign "https://github.com/sigstore/cosign/releases/download/v${COSIGN_VERSION}/cosign-linux-${carch}"; \
    echo "${csha}  /tmp/cosign" | sha256sum -c -; \
    chmod 0555 /tmp/cosign; \
    /tmp/cosign verify-blob \
        --certificate /tmp/opengrep.cert --signature /tmp/opengrep.sig \
        --certificate-identity 'https://github.com/opengrep/opengrep/.github/workflows/rolling-release.yml@refs/heads/release-v1.31.0' \
        --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
        /usr/local/bin/opengrep; \
    rm -f /tmp/cosign /tmp/opengrep.sig /tmp/opengrep.cert; \
    chmod 0555 /usr/local/bin/opengrep; \
    opengrep --version

# --- Checkov (IaC, pinned version + HASH-PINNED dependency lock; D-054 / review S2). Installed with pip into an
# isolated venv from checkov-requirements.txt — the full transitive closure, each distribution pinned by SHA-256
# (pip-compile --generate-hashes) and installed with --require-hashes so a tampered or substituted PyPI artifact
# fails the build, not just a version drift. The lock lists every wheel + sdist hash per version, so it verifies
# whatever pip selects on Alpine musl. Apache-2.0. Runs fully offline on the clone (--skip-download,
# CKV_SKIP_PACKAGE_UPDATE_CHECK=True; run-scan.sh). Only STATIC-parse frameworks are used (no helm/kustomize
# binary — D-004, BLOCKER 1). The `checkov` entrypoint is symlinked onto PATH. ---
ARG CHECKOV_VERSION=3.3.29
COPY checkov-requirements.txt /etc/secscan/checkov-requirements.txt
RUN set -eu; \
    python3 -m venv /opt/checkov-venv; \
    /opt/checkov-venv/bin/pip install --no-cache-dir --disable-pip-version-check \
        --require-hashes -r /etc/secscan/checkov-requirements.txt; \
    test "$(/opt/checkov-venv/bin/pip show checkov | awk '/^Version:/{print $2}')" = "${CHECKOV_VERSION}"; \
    ln -s /opt/checkov-venv/bin/checkov /usr/local/bin/checkov; \
    chmod 0444 /etc/secscan/checkov-requirements.txt; \
    checkov --version

# --- Opengrep rule bundle (D-053), baked at pinned commit SHAs so the rule set (and therefore every SAST
# check_id, which is a fingerprint input) is frozen. Three sources:
#   (a) AikidoSec/opengrep-rules  — MIT — curated Aikido rules (the `rules/` tree).
#   (b) opengrep/opengrep-rules   — Commons Clause (restricts resale/SaaS — FLAGGED for the open "SAST rule
#       licensing before SaaS" SPEC item and D-005; INTERNAL USE OK, does NOT block M6). Only the language
#       packs we need are copied (java, csharp, javascript, typescript).
#   (c) our custom Spring/.NET rules (MIT) land in /etc/secscan/opengrep-rules/custom in M6 Slice 1.
# NEVER semgrep/semgrep-rules (restrictive Semgrep Rules License). run-scan.sh runs opengrep from /etc/secscan
# with a RELATIVE `--config opengrep-rules`, so check_ids are the stable `opengrep-rules.<pack>.<id>` and never
# leak an absolute path. The clone + copy are build-time only; the scanner runs fully offline. ---
ARG AIKIDO_RULES_SHA=7ac79affecf709eb7263a243b518a417cd7e0ab2
ARG OPENGREP_RULES_SHA=f1d2b562b414783763fd02a6ed2736eaed622efa
RUN set -eu; \
    mkdir -p /etc/secscan/opengrep-rules/custom; \
    git clone -q --filter=blob:none https://github.com/AikidoSec/opengrep-rules /tmp/aikido; \
    git -C /tmp/aikido checkout -q "$AIKIDO_RULES_SHA"; \
    cp -r /tmp/aikido/rules /etc/secscan/opengrep-rules/aikido; \
    git clone -q --filter=blob:none https://github.com/opengrep/opengrep-rules /tmp/ogr; \
    git -C /tmp/ogr checkout -q "$OPENGREP_RULES_SHA"; \
    for lang in java csharp javascript typescript; do \
        cp -r "/tmp/ogr/$lang" "/etc/secscan/opengrep-rules/$lang"; \
    done; \
    rm -rf /tmp/aikido /tmp/ogr

# Our custom Spring/.NET rules (MIT, D-053 (c)). They land at opengrep-rules/custom, so their check_ids are the
# stable `opengrep-rules.custom.<id>`. Each has a positive+negative fixture under fixtures/ (CustomSastRulesIT).
COPY opengrep-rules-custom/ /etc/secscan/opengrep-rules/custom/
RUN find /etc/secscan/opengrep-rules -type d -exec chmod 0555 {} \; ; \
    find /etc/secscan/opengrep-rules -type f -exec chmod 0444 {} \;

# Pinned scanner configs baked into the image and passed with --config so in-repo suppressors are ignored
# (D-037): Betterleaks (.betterleaks.toml/.gitleaks.toml, inline allow comments, .betterleaksignore),
# OSV-Scanner (osv-scanner.toml [[IgnoredVulns]]/[[PackageOverrides]]) and Checkov (.checkov.yaml, via our own
# --config-file). Opengrep's in-repo suppressors are neutralized by flags (--disable-nosem,
# --x-ignore-semgrepignore-files) in run-scan.sh, not a config file. They may only keep detection strong, never
# relax it. See each file's header and run-scan.sh.
COPY betterleaks.toml /etc/secscan/betterleaks.toml
COPY osv-scanner.toml /etc/secscan/osv-scanner.toml
COPY betterleaks-empty.ignore /etc/secscan/betterleaks-empty.ignore
COPY checkov.yaml /etc/secscan/checkov.yaml
RUN chmod 0444 /etc/secscan/betterleaks.toml /etc/secscan/osv-scanner.toml \
    /etc/secscan/betterleaks-empty.ignore /etc/secscan/checkov.yaml

# The static Gradle resolver (D-038/D-039): read-only data file, run as `python3 -I gradle_resolver.py` so the
# scanned tree is never on sys.path (D-004). Its unit tests (gradle_resolver_test.py) are NOT shipped -- they
# run on the host under ./gradlew build (gradleResolverTest).
COPY gradle_resolver.py /usr/local/bin/gradle_resolver.py
RUN chmod 0444 /usr/local/bin/gradle_resolver.py

# The static EOL detector (M6, D-055): same posture -- run `python3 -I eol_detect.py` so the scanned tree is
# never on sys.path (D-004); reads files as data only, reaches no network. Its unit tests (eol_detect_test.py)
# are NOT shipped -- they run on the host under ./gradlew build (eolDetectTest).
COPY eol_detect.py /usr/local/bin/eol_detect.py
RUN chmod 0444 /usr/local/bin/eol_detect.py

# The .secscan.yml reader (M7, D-057): same posture -- run `python3 -I secscan_config.py` so the scanned tree is
# never on sys.path; reads the config as data only (yaml.safe_load), symlink-safe + realpath-confined, and emits
# a bounded whitelisted JSON. Its unit tests (secscan_config_test.py) are NOT shipped (host-run, secscanConfigTest).
COPY secscan_config.py /usr/local/bin/secscan_config.py
RUN chmod 0444 /usr/local/bin/secscan_config.py

COPY secscan-lib.sh /usr/local/bin/secscan-lib.sh
COPY run-scan.sh /usr/local/bin/run-scan.sh
RUN chmod 0555 /usr/local/bin/secscan-lib.sh /usr/local/bin/run-scan.sh

# A non-root default user; the launcher overrides this to the host uid that owns the bind-mounted /out so the
# container can write there while still running non-root (never uid 0).
RUN adduser -S -u 10001 -H -s /sbin/nologin scanner
USER 10001

# Fixed contract (see run-scan.sh): /work = tmpfs scratch, /out = bind mount for sanitized results,
# /run/secrets/gitlab-token = optional read-only one-time token file. Clone URL/branch come via env.
ENV HOME=/work
WORKDIR /work
ENTRYPOINT ["/usr/local/bin/run-scan.sh"]
