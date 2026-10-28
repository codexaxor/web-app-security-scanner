package com.company.secscan.source;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Strict parser for an "Add public repository" URL (OD-31/D-085, M8.7 item 5). Accepts https only; the host
 * decides the provider (github.com → github; gitlab.com or a self-managed host → gitlab); path segments are
 * charset-validated and may not contain traversal. The caller still checks the host against the allowlist
 * (GitLabHostPolicy) — this type only validates shape and derives identity, never reaches the network.
 *
 * <p>github.com repositories are exactly {@code owner/name}; GitLab repositories may be nested group paths
 * ({@code group/subgroup/project}), so {@code fullName} keeps the whole path and {@code owner}/{@code name} are
 * its first/last segments.
 */
public record PublicRepoUrl(String provider, String host, String baseUrl, String owner, String name,
                            String fullName) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._-]+");

    public static PublicRepoUrl parse(String rawUrl) {
        URI uri;
        try {
            uri = new URI(rawUrl == null ? "" : rawUrl.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("The repository URL is not a valid URL.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("The repository URL must include a host.");
        }
        host = host.toLowerCase(Locale.ROOT);
        boolean loopback = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("::1");
        // https only for real hosts; loopback may use http (a local/mocked provider), mirroring the SSRF guard.
        if (!("https".equals(scheme) || ("http".equals(scheme) && loopback))) {
            throw new IllegalArgumentException("Public repositories must be added over https.");
        }
        String authority = uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        String baseUrl = scheme + "://" + authority;

        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.toLowerCase(Locale.ROOT).endsWith(".git")) {
            path = path.substring(0, path.length() - 4);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.isBlank()) {
            throw new IllegalArgumentException("The repository URL must include an owner and repository name.");
        }
        List<String> segments = Arrays.stream(path.split("/")).toList();
        for (String s : segments) {
            if (s.isBlank() || "..".equals(s) || ".".equals(s) || !SEGMENT.matcher(s).matches()) {
                throw new IllegalArgumentException("The repository path contains an invalid segment.");
            }
        }

        boolean github = "github.com".equals(host);
        String provider = github ? "github" : "gitlab";
        if (github && segments.size() != 2) {
            throw new IllegalArgumentException("A github.com repository URL must be https://github.com/owner/name.");
        }
        String fullName = String.join("/", segments);
        String owner = segments.get(0);
        String name = segments.get(segments.size() - 1);
        return new PublicRepoUrl(provider, authority, baseUrl, owner, name, fullName);
    }
}
