package com.company.secscan.source;

import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

/**
 * Maps a provider's HTTP failure to the one provider-neutral {@link ProviderException} taxonomy shared by every
 * {@link SourceProvider} adapter (M8.7 item 2). The mapping is: 401 → INVALID_CREDENTIAL; 403 → RATE_LIMITED
 * when it carries a rate-limit signal (GitHub's {@code x-ratelimit-remaining: 0} / Retry-After), else FORBIDDEN;
 * 404 → NOT_FOUND; 429 → RATE_LIMITED; 5xx → UNAVAILABLE (transient); connect/read timeout or DNS/refused →
 * UNREACHABLE (transient). Retry-After (seconds) and {@code x-ratelimit-reset} (epoch seconds) are surfaced as
 * the retry delay. Messages never include a token or response body. Redirects (3xx) are handled by the adapter's
 * client (never followed) and surfaced as {@link ProviderError#UNEXPECTED_REDIRECT}.
 */
public final class HttpErrors {

    private HttpErrors() {
    }

    /** Maps any {@link RestClientException} to a {@link ProviderException}. {@code notFoundMessage} is used for 404. */
    public static ProviderException map(RestClientException e, String notFoundMessage) {
        if (e instanceof HttpClientErrorException ce) {
            int status = ce.getStatusCode().value();
            HttpHeaders headers = ce.getResponseHeaders();
            switch (status) {
                case 401:
                    return new ProviderException(ProviderError.INVALID_CREDENTIAL,
                            "The credential was rejected (401). Check that it is correct and not revoked.", e);
                case 403:
                    Long reset403 = retryAfterSeconds(headers);
                    if (isRateLimited(headers)) {
                        return rateLimited(reset403, e);
                    }
                    return new ProviderException(ProviderError.FORBIDDEN,
                            "The credential is not authorized (403); it is likely missing a required scope.", e);
                case 404:
                    return new ProviderException(ProviderError.NOT_FOUND,
                            notFoundMessage == null ? "Not found (404)." : notFoundMessage, e);
                case 429:
                    return rateLimited(retryAfterSeconds(headers), e);
                default:
                    return new ProviderException(ProviderError.UNEXPECTED,
                            "The provider returned " + status + ".", e);
            }
        }
        if (e instanceof HttpServerErrorException se) {
            return new ProviderException(ProviderError.UNAVAILABLE,
                    "The provider had a server error (" + se.getStatusCode().value() + "). Try again later.", e);
        }
        if (e instanceof ResourceAccessException) {
            return new ProviderException(ProviderError.UNREACHABLE,
                    "Could not reach the provider (timeout or connection error). Try again later.", e);
        }
        return new ProviderException(ProviderError.UNEXPECTED, "Unexpected error talking to the provider.", e);
    }

    private static ProviderException rateLimited(Long retryAfter, RestClientException cause) {
        return new ProviderException(ProviderError.RATE_LIMITED,
                "The provider's rate limit was reached"
                        + (retryAfter != null ? "; retry after " + retryAfter + "s." : "; try again later."),
                retryAfter, cause);
    }

    /** True when a 403 is actually a rate limit (GitHub signals it with x-ratelimit-remaining: 0 or Retry-After). */
    private static boolean isRateLimited(HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        String remaining = headers.getFirst("x-ratelimit-remaining");
        if (remaining != null && remaining.trim().equals("0")) {
            return true;
        }
        return headers.getFirst("retry-after") != null || headers.getFirst("x-ratelimit-reset") != null;
    }

    /** Retry delay in seconds from Retry-After (seconds), else from x-ratelimit-reset (epoch seconds); else null. */
    static Long retryAfterSeconds(HttpHeaders headers) {
        if (headers == null) {
            return null;
        }
        Long retryAfter = parseLong(headers.getFirst("retry-after"));
        if (retryAfter != null) {
            return Math.max(retryAfter, 0);
        }
        Long reset = parseLong(headers.getFirst("x-ratelimit-reset"));
        if (reset != null) {
            long secs = reset - Instant.now().getEpochSecond();
            return Math.max(secs, 0);
        }
        return null;
    }

    private static Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
