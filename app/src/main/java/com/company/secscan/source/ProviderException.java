package com.company.secscan.source;

/**
 * A source-provider failure with a provider-neutral {@link ProviderError} category, a safe user-facing message
 * (never containing a token or secret), and, for {@link ProviderError#RATE_LIMITED}, the retry delay in seconds
 * when the provider supplied one. Thrown by every {@link SourceProvider} adapter so callers map one taxonomy.
 */
public class ProviderException extends RuntimeException {

    private final ProviderError error;
    private final Long retryAfterSeconds;

    public ProviderException(ProviderError error, String message) {
        this(error, message, null, null);
    }

    public ProviderException(ProviderError error, String message, Throwable cause) {
        this(error, message, null, cause);
    }

    public ProviderException(ProviderError error, String message, Long retryAfterSeconds, Throwable cause) {
        super(message, cause);
        this.error = error;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ProviderError error() {
        return error;
    }

    public boolean isTransient() {
        return error.isTransient();
    }

    /** Retry delay in seconds for a rate limit, when the provider supplied Retry-After / a reset; else null. */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
