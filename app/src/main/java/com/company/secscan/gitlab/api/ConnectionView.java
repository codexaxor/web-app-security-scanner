package com.company.secscan.gitlab.api;

import com.company.secscan.gitlab.GitlabConnection;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** A saved connection, without the token. Group connections carry the group fields; projects the project fields. */
public record ConnectionView(
        UUID id,
        String provider,
        String host,
        String credentialKind,
        String targetType,
        String baseUrl,
        Long gitlabGroupId,
        String groupFullPath,
        Long gitlabProjectId,
        String projectFullPath,
        String ownerLogin,
        String repoFullName,
        List<String> tokenScopes,
        LocalDate tokenExpiresAt) {

    public static ConnectionView of(GitlabConnection c) {
        List<String> scopes = c.getTokenScopes() == null || c.getTokenScopes().isBlank()
                ? List.of()
                : Arrays.asList(c.getTokenScopes().split(","));
        return new ConnectionView(c.getId(), c.getProvider(), c.getHost(), c.getCredentialKind(),
                c.getTargetType(), c.getBaseUrl(),
                c.getGitlabGroupId(), c.getGroupFullPath(),
                c.getGitlabProjectId(), c.getProjectFullPath(),
                c.getOwnerLogin(), c.getRepoFullName(),
                scopes, c.getTokenExpiresAt());
    }
}
