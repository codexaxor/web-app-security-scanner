package com.company.secscan.gitlab.api;

import com.company.secscan.gitlab.GitlabConnection;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

/** The saved connection, without the token. */
public record ConnectionView(
        String baseUrl,
        String groupFullPath,
        long gitlabGroupId,
        List<String> tokenScopes,
        LocalDate tokenExpiresAt) {

    public static ConnectionView of(GitlabConnection c) {
        List<String> scopes = c.getTokenScopes() == null || c.getTokenScopes().isBlank()
                ? List.of()
                : Arrays.asList(c.getTokenScopes().split(","));
        return new ConnectionView(c.getBaseUrl(), c.getGroupFullPath(), c.getGitlabGroupId(),
                scopes, c.getTokenExpiresAt());
    }
}
