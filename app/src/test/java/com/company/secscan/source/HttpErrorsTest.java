package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/** Unit tests for the shared provider HTTP error mapping and transient classification (M8.7 item 2). */
class HttpErrorsTest {

    private static HttpClientErrorException client(HttpStatus status, HttpHeaders headers) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), headers, new byte[0],
                StandardCharsets.UTF_8);
    }

    @Test
    void unauthorizedIsInvalidCredentialAndPermanent() {
        ProviderException e = HttpErrors.map(client(HttpStatus.UNAUTHORIZED, null), "nf");
        assertThat(e.error()).isEqualTo(ProviderError.INVALID_CREDENTIAL);
        assertThat(e.isTransient()).isFalse();
    }

    @Test
    void plainForbiddenIsForbiddenAndPermanent() {
        ProviderException e = HttpErrors.map(client(HttpStatus.FORBIDDEN, new HttpHeaders()), "nf");
        assertThat(e.error()).isEqualTo(ProviderError.FORBIDDEN);
        assertThat(e.isTransient()).isFalse();
    }

    @Test
    void forbiddenWithExhaustedRateLimitIsTransientRateLimited() {
        HttpHeaders h = new HttpHeaders();
        h.add("x-ratelimit-remaining", "0");
        h.add("x-ratelimit-reset", String.valueOf(Instant.now().getEpochSecond() + 42));
        ProviderException e = HttpErrors.map(client(HttpStatus.FORBIDDEN, h), "nf");
        assertThat(e.error()).isEqualTo(ProviderError.RATE_LIMITED);
        assertThat(e.isTransient()).isTrue();
        assertThat(e.retryAfterSeconds()).isBetween(0L, 42L);
    }

    @Test
    void notFoundUsesTheGivenMessageAndIsPermanent() {
        ProviderException e = HttpErrors.map(client(HttpStatus.NOT_FOUND, null), "Repository not found.");
        assertThat(e.error()).isEqualTo(ProviderError.NOT_FOUND);
        assertThat(e.getMessage()).isEqualTo("Repository not found.");
        assertThat(e.isTransient()).isFalse();
    }

    @Test
    void tooManyRequestsHonoursRetryAfterSecondsAndIsTransient() {
        HttpHeaders h = new HttpHeaders();
        h.add("retry-after", "120");
        ProviderException e = HttpErrors.map(client(HttpStatus.TOO_MANY_REQUESTS, h), "nf");
        assertThat(e.error()).isEqualTo(ProviderError.RATE_LIMITED);
        assertThat(e.retryAfterSeconds()).isEqualTo(120L);
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void serverErrorIsUnavailableAndTransient() {
        HttpServerErrorException se = HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        ProviderException e = HttpErrors.map(se, "nf");
        assertThat(e.error()).isEqualTo(ProviderError.UNAVAILABLE);
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void connectionErrorIsUnreachableAndTransient() {
        ProviderException e = HttpErrors.map(new ResourceAccessException("timeout"), "nf");
        assertThat(e.error()).isEqualTo(ProviderError.UNREACHABLE);
        assertThat(e.isTransient()).isTrue();
    }

    @Test
    void messagesNeverContainTheResponseBodyOrToken() {
        ProviderException e = HttpErrors.map(client(HttpStatus.UNAUTHORIZED, null), "nf");
        assertThat(e.getMessage()).doesNotContain("token").doesNotContain("Bearer");
    }
}
