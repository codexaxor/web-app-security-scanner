package com.company.secscan.api;

import com.company.secscan.config.EncryptionNotConfiguredException;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

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

    /**
     * Normalizes a {@link ResponseStatusException} (thrown across the triage/scan/repository services for 400/
     * 404/409) into the same small {@code {error, message}} body the UI reads (OD-14/change 9). The body carries
     * only the status name and the human reason — never a stack trace, exception class name or rejected value.
     */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> onResponseStatus(ResponseStatusException e) {
        HttpStatusCode code = e.getStatusCode();
        String error = (code instanceof HttpStatus hs) ? hs.name() : "ERROR";
        String message = e.getReason() != null ? e.getReason() : "Request failed.";
        return ResponseEntity.status(code).body(new ApiError(error, message));
    }

    /**
     * Bean-validation failures (@Valid) become a 400 with the same {@code {error, message}} shape. The message
     * lists the offending field names and their constraint messages — never the rejected values (change 9).
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("Request validation failed.");
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", message));
    }

    private static HttpStatus statusFor(GitLabError error) {
        return switch (error) {
            case INVALID_TOKEN, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case MISSING_SCOPES -> HttpStatus.FORBIDDEN;
            case GROUP_NOT_FOUND, PROJECT_NOT_FOUND, NOT_FOUND, CONNECTION_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case HOST_NOT_ALLOWED, INVALID_GROUP_PATH -> HttpStatus.BAD_REQUEST;
            case CONNECTION_EXISTS, CONNECTION_TARGET_MISMATCH -> HttpStatus.CONFLICT;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case UNREACHABLE, UNEXPECTED, UNEXPECTED_REDIRECT -> HttpStatus.BAD_GATEWAY;
        };
    }
}
