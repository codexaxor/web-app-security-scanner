package com.company.secscan.gitlab;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Allowlist for outbound GitLab calls — an SSRF guard (D-018). Every outbound request must target {@code https}
 * and a host listed in {@code SECSCAN_GITLAB_ALLOWED_HOSTS} (comma-separated). The list is empty by default,
 * which denies everything until an operator opts specific hosts in. Loopback hosts may use {@code http} (so a
 * local or mocked GitLab works), but still must be explicitly allowlisted.
 */
@Component
public class GitLabHostPolicy {

    private final Set<String> allowedHosts;

    @Autowired
    public GitLabHostPolicy(@Value("${SECSCAN_GITLAB_ALLOWED_HOSTS:}") String csv) {
        this(parse(csv));
    }

    public GitLabHostPolicy(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    private static Set<String> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True if {@code host} (case-insensitive) is in the allowlist. */
    public boolean isAllowedHost(String host) {
        return host != null && allowedHosts.contains(host.toLowerCase(Locale.ROOT));
    }

    /**
     * Validates a base URL before any outbound call. Throws {@link GitLabException} with
     * {@link GitLabError#HOST_NOT_ALLOWED} and a safe message if the scheme or host is not permitted.
     */
    public void requireAllowed(String baseUrl) {
        URI uri;
        try {
            uri = new URI(baseUrl == null ? "" : baseUrl.strip());
        } catch (URISyntaxException e) {
            throw new GitLabException(GitLabError.HOST_NOT_ALLOWED, "The GitLab URL is not a valid URL.");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null) {
            throw new GitLabException(GitLabError.HOST_NOT_ALLOWED,
                    "The GitLab URL must be absolute with a host, e.g. https://gitlab.example.com.");
        }
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean loopbackHttp = "http".equalsIgnoreCase(scheme) && isLoopback(host);
        if (!https && !loopbackHttp) {
            throw new GitLabException(GitLabError.HOST_NOT_ALLOWED, "GitLab must be reached over https.");
        }
        if (!isAllowedHost(host)) {
            throw new GitLabException(GitLabError.HOST_NOT_ALLOWED,
                    "Host '" + host + "' is not allowed. Add it to SECSCAN_GITLAB_ALLOWED_HOSTS.");
        }
    }

    private static boolean isLoopback(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }
}
