package com.company.secscan.api;

import com.company.secscan.config.EncryptionNotConfiguredException;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns our exceptions into small JSON error bodies with sensible HTTP statuses. Never includes the token. */
@RestControllerAdvice
public class ApiExceptionHandler {

    public record ApiError(String error, String message) {
    }

    @ExceptionHandler(EncryptionNotConfiguredException.class)
    public ResponseEntity<ApiError> onEncryption(EncryptionNotConfiguredException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("ENCRYPTION_NOT_CONFIGURED", e.getMessage()));
    }

    @ExceptionHandler(GitLabException.class)
    public ResponseEntity<ApiError> onGitLab(GitLabException e) {
        return ResponseEntity.status(statusFor(e.getError()))
                .body(new ApiError(e.getError().name(), e.getMessage()));
    }

    private static HttpStatus statusFor(GitLabError error) {
        return switch (error) {
            case INVALID_TOKEN, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case MISSING_SCOPES -> HttpStatus.FORBIDDEN;
            case GROUP_NOT_FOUND, NOT_FOUND -> HttpStatus.NOT_FOUND;
            case HOST_NOT_ALLOWED -> HttpStatus.BAD_REQUEST;
            case CONNECTION_GROUP_MISMATCH -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNREACHABLE, UNEXPECTED -> HttpStatus.BAD_GATEWAY;
        };
    }
}
