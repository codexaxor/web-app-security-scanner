package com.company.secscan.gitlab;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes the project-path field, which may be either a plain path ({@code celloscope/platform/core-keycloak})
 * or a pasted GitLab URL. For a URL it strips the host and any {@code /-/...} suffix (a GitLab project sub-page
 * such as {@code /-/tree/main}), and rejects a URL whose host is not allowed (so a link to a different GitLab is
 * not silently accepted). Unlike {@link GitLabGroupPath} it does not strip a {@code groups/} prefix: a project
 * path is a namespace path with no such prefix.
 */
public final class GitLabProjectPath {

    private GitLabProjectPath() {
    }

    public static String normalize(String input, GitLabHostPolicy policy) {
        if (input == null || input.isBlank()) {
            throw new GitLabException(GitLabError.INVALID_GROUP_PATH,
                    "Enter a project path or a GitLab project URL.");
        }
        String s = input.strip();
        if (s.contains("://")) {
            URI uri;
            try {
                uri = new URI(s);
            } catch (URISyntaxException e) {
                throw new GitLabException(GitLabError.INVALID_GROUP_PATH,
                        "That does not look like a valid GitLab URL.");
            }
            String host = uri.getHost();
            if (host == null || !policy.isAllowedHost(host)) {
                throw new GitLabException(GitLabError.HOST_NOT_ALLOWED,
                        "That URL points to a different GitLab host"
                                + (host == null ? "" : " (" + host + ")") + ".");
            }
            s = uri.getPath() == null ? "" : uri.getPath();
        }
        s = stripSlashes(s);
        int dash = s.indexOf("/-/");
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        s = stripSlashes(s);
        if (s.isEmpty() || !s.contains("/")) {
            // A project always lives under at least one namespace segment (group/project).
            throw new GitLabException(GitLabError.INVALID_GROUP_PATH,
                    "Enter the full project path, e.g. celloscope/platform/core-keycloak.");
        }
        return s;
    }

    private static String stripSlashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(start, end);
    }
}
