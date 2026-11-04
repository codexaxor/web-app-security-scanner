package com.company.secscan.api;

import org.springframework.http.HttpStatus;

/**
 * An error with a stable machine-readable code (e.g. {@code ACCOUNT_PENDING}, {@code REPOSITORY_LIMIT_REACHED})
 * rendered by {@link ApiExceptionHandler} as {@code {error: code, message}}; an optional {@code Retry-After}.
 */
public class ApiErrorException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiErrorException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiErrorException(HttpStatus status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
