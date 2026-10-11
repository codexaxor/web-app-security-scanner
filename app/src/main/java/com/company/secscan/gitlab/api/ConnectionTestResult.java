package com.company.secscan.gitlab.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a connection test, returned to the Connect screen. On success {@code ok} is true and the GitLab
 * version, token scopes and expiry are populated; on failure {@code errorCode}/{@code message} explain why.
 * {@code expiringSoon} warns when the token expires within 14 days (the token value is never included).
 */
public record ConnectionTestResult(
        boolean ok,
        String gitlabVersion,
        Long groupId,
        String groupFullPath,
        List<String> tokenScopes,
        LocalDate tokenExpiresAt,
        boolean expiringSoon,
        String errorCode,
        String message) {

    public static ConnectionTestResult success(String version, long groupId, String groupFullPath,
                                               List<String> scopes, LocalDate expiresAt, boolean expiringSoon) {
        return new ConnectionTestResult(true, version, groupId, groupFullPath, scopes, expiresAt,
                expiringSoon, null, null);
    }

    public static ConnectionTestResult failure(String errorCode, String message) {
        return new ConnectionTestResult(false, null, null, null, null, null, false, errorCode, message);
    }
}
