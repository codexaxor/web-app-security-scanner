# SecScan scanner image (M2): git + Gitleaks only. SCA (OSV-Scanner), SAST (Opengrep) and IaC (Checkov)
# are added in later milestones. One disposable container per scan; destroyed afterwards. Only /out and the
# container logs leave it (CLAUDE.md security rules).
#
# Pinned: the base image by digest and Gitleaks by version + SHA-256 checksum (point 8 / D-023). apk package
# versions follow the pinned Alpine branch (noted as a Known gap until we snapshot a mirror).

FROM alpine:3.21@sha256:ce64758a109eb420d874a118f87920e625e12d3634e03b4a5573fd9f6e5d3507

# sh (busybox), base64, sha256sum, cut, awk, date are all provided by busybox; add git, jq, certs.
RUN apk add --no-cache git jq ca-certificates

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
