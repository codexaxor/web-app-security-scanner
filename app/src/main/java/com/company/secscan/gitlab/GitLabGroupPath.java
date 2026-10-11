package com.company.secscan.gitlab;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Normalizes the group-path field, which may be either a plain path ({@code celloscope/platform}) or a pasted
 * GitLab URL. For a URL it strips the host, the {@code groups/} prefix and any {@code /-/...} suffix, and rejects
 * a URL whose host is not allowed (so a link to a different GitLab is not silently accepted).
 */
public final class GitLabGroupPath {

    private GitLabGroupPath() {
    }

    public static String normalize(String input, GitLabHostPolicy policy) {
        if (input == null || input.isBlank()) {
            throw new GitLabException(GitLabError.GROUP_NOT_FOUND, "Enter a group path or a GitLab group URL.");
        }
        String s = input.strip();
        if (s.contains("://")) {
            URI uri;
            try {
                uri = new URI(s);
            } catch (URISyntaxException e) {
                throw new GitLabException(GitLabError.GROUP_NOT_FOUND, "That does not look like a valid GitLab URL.");
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
        if (s.startsWith("groups/")) {
            s = s.substring("groups/".length());
        }
        int dash = s.indexOf("/-/");
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        s = stripSlashes(s);
        if (s.isEmpty()) {
            throw new GitLabException(GitLabError.GROUP_NOT_FOUND, "No group path found in that input.");
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
