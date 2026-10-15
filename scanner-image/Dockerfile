# SecScan scanner image (M2 + M4): git + Gitleaks (secrets) + OSV-Scanner (SCA). SAST (Opengrep) and IaC
# (Checkov) are added in later milestones. One disposable container per scan; destroyed afterwards. Only /out
# and the container logs leave it (CLAUDE.md security rules).
#
# Pinned: the base image by digest, Gitleaks and OSV-Scanner by version + SHA-256 checksum (point 8 / D-023 /
# D-034). apk package versions follow the pinned Alpine branch (noted as a Known gap until we snapshot a mirror).

FROM alpine:3.21@sha256:ce64758a109eb420d874a118f87920e625e12d3634e03b4a5573fd9f6e5d3507

# sh (busybox), base64, sha256sum, cut, awk, date are all provided by busybox; add git, jq, certs, and
# xmllint (libxml2-utils) for reading direct <PackageReference> versions out of .csproj for SCA warnings (M4).
RUN apk add --no-cache git jq ca-certificates libxml2-utils

# --- Gitleaks (pinned version + checksum, verified before use) ---
ARG TARGETARCH=amd64
ARG GITLEAKS_VERSION=8.21.2
ARG GITLEAKS_SHA256_AMD64=5bc41815076e6ed6ef8fbecc9d9b75bcae31f39029ceb55da08086315316e3ba
ARG GITLEAKS_SHA256_ARM64=654c935542c89f565aabe7bf7c6c500830f116c114f0aeb509d2460c1ac2e6da
RUN set -eu; \
    case "$TARGETARCH" in \
        amd64) asset="linux_x64";  sha="$GITLEAKS_SHA256_AMD64" ;; \
        arm64) asset="linux_arm64"; sha="$GITLEAKS_SHA256_ARM64" ;; \
        *) echo "unsupported TARGETARCH: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    url="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_${asset}.tar.gz"; \
    wget -q -O /tmp/gitleaks.tar.gz "$url"; \
    echo "${sha}  /tmp/gitleaks.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/gitleaks.tar.gz -C /usr/local/bin gitleaks; \
    rm /tmp/gitleaks.tar.gz; \
    gitleaks version

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
