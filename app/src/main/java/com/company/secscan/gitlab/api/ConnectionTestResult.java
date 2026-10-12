package com.company.secscan.gitlab.api;

import java.time.LocalDate;
import java.util.List;

/**
 * Outcome of a connection test, returned to the Connect screen. On success {@code ok} is true and the GitLab
 * version, token scopes and expiry are populated, along with the resolved target: for a group connection
 * {@code groupId}/{@code groupFullPath}, for a project connection {@code projectId}/{@code projectFullPath}.
 * On failure {@code errorCode}/{@code message} explain why. {@code expiringSoon} warns when the token expires
 * within 14 days (the token value is never included).
 */
public record ConnectionTestResult(
        boolean ok,
        String targetType,
        String gitlabVersion,
        Long groupId,
        String groupFullPath,
        Long projectId,
        String projectFullPath,
        List<String> tokenScopes,
        LocalDate tokenExpiresAt,
        boolean expiringSoon,
        String errorCode,
        String message) {

    public static ConnectionTestResult successGroup(String version, long groupId, String groupFullPath,
                                                    List<String> scopes, LocalDate expiresAt, boolean soon) {
        return new ConnectionTestResult(true, "group", version, groupId, groupFullPath, null, null,
                scopes, expiresAt, soon, null, null);
    }

    public static ConnectionTestResult successProject(String version, long projectId, String projectFullPath,
                                                      List<String> scopes, LocalDate expiresAt, boolean soon) {
        return new ConnectionTestResult(true, "project", version, null, null, projectId, projectFullPath,
                scopes, expiresAt, soon, null, null);
    }

    public static ConnectionTestResult failure(String errorCode, String message) {
        return new ConnectionTestResult(false, null, null, null, null, null, null, null, null,
                false, errorCode, message);
    }
}
