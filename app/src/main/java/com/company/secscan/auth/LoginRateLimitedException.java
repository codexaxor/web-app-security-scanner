package com.company.secscan.auth;

import org.springframework.security.core.AuthenticationException;

/** A login (JSON or Basic) refused before the password check: a failure limit is exceeded (D-098). */
public class LoginRateLimitedException extends AuthenticationException {

    private final long retryAfterSeconds;

    public LoginRateLimitedException(long retryAfterSeconds) {
        super("Too many failed sign-in attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
