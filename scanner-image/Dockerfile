# SecScan scanner image (M2 + M4 + M4b): git + Betterleaks (secrets) + OSV-Scanner (SCA). SAST (Opengrep) and
# IaC (Checkov) are added in later milestones. One disposable container per scan; destroyed afterwards. Only
# /out and the container logs leave it (CLAUDE.md security rules).
#
# Pinned: the base image by digest, Betterleaks and OSV-Scanner by version + SHA-256 checksum (point 8 / D-023 /
# D-034 / D-036). apk package versions follow the pinned Alpine branch (Known gap until we snapshot a mirror).
#
# NOTE ON THE IMAGE TAG: the tag secscan-scanner:8.21.2 is a HISTORICAL IDENTIFIER (it began as the Gitleaks
# version), NOT the engine version. The secrets engine is now Betterleaks 1.7.3 (D-036) and the tag deliberately
# stays 8.21.2 — it only has to match ScannerProperties.image and the M10 rebuild tracker (D-034).

FROM alpine:3.21@sha256:ce64758a109eb420d874a118f87920e625e12d3634e03b4a5573fd9f6e5d3507

# sh (busybox), base64, sha256sum, cut, awk, date are all provided by busybox; add git, jq, certs, and
# xmllint (libxml2-utils) for reading direct <PackageReference> versions out of .csproj for SCA warnings (M4).
RUN apk add --no-cache git jq ca-certificates libxml2-utils

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

# Pinned scanner configs baked into the image and passed with --config so in-repo suppressors are ignored
# (D-037): Betterleaks (.betterleaks.toml/.gitleaks.toml, inline allow comments, .betterleaksignore) and
# OSV-Scanner (osv-scanner.toml [[IgnoredVulns]]/[[PackageOverrides]]). They may only keep detection strong,
# never relax it. See each file's header and run-scan.sh.
COPY betterleaks.toml /etc/secscan/betterleaks.toml
COPY osv-scanner.toml /etc/secscan/osv-scanner.toml
COPY betterleaks-empty.ignore /etc/secscan/betterleaks-empty.ignore
RUN chmod 0444 /etc/secscan/betterleaks.toml /etc/secscan/osv-scanner.toml /etc/secscan/betterleaks-empty.ignore

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
