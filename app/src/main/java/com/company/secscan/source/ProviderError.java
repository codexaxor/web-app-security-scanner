package com.company.secscan.source;

/**
 * Provider-neutral categories of a source-connection failure (GitLab, GitHub, public), mapped from each
 * provider's HTTP responses by {@link HttpErrors}. {@link #isTransient()} marks the ones that must NEVER be
 * treated as "the repository is gone" (no solve, no deactivation): a rate limit, a 5xx, or an unreachable host
 * are temporary and the scan/reconcile is retried or left untouched (D-080, M8.7 item 2).
 */
public enum ProviderError {
    /** 401: the credential is invalid, revoked or expired. */
    INVALID_CREDENTIAL(false),
    /** 403 without a rate-limit signal: the credential is missing a required scope / is not authorized. */
    FORBIDDEN(false),
    /** 404: the repository/branch/target was not found, or the credential cannot see it. */
    NOT_FOUND(false),
    /** 429, or a provider 403 that carries a rate-limit reset — transient; honour Retry-After / reset. */
    RATE_LIMITED(true),
    /** 5xx: the provider had a server error — transient. */
    UNAVAILABLE(true),
    /** DNS failure, connection refused or timeout — transient. */
    UNREACHABLE(true),
    /** A 3xx redirect (never followed, so a credential cannot leak to another host). */
    UNEXPECTED_REDIRECT(false),
    /** The URL/host is not https or not allowed (SSRF guard, D-018/D-033). */
    HOST_NOT_ALLOWED(false),
    /** Any other unexpected error. */
    UNEXPECTED(false);

    private final boolean transientError;

    ProviderError(boolean transientError) {
        this.transientError = transientError;
    }

    /** True when the condition is temporary and must not close findings or deactivate a repository. */
    public boolean isTransient() {
        return transientError;
    }
}
