package com.company.secscan.gitlab;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(GitLabHostPolicy.class);

    /**
     * Built-in SaaS hosts allowed by default (M8.7 approval 17): github.com + its API host and gitlab.com. They
     * can be switched off with {@code SECSCAN_SOURCE_ALLOW_BUILTIN_SAAS=false} for an egress-locked deployment
     * (D-033). Self-managed hosts stay deny-by-default via {@code SECSCAN_GITLAB_ALLOWED_HOSTS} (D-085 retired).
     */
    static final Set<String> BUILTIN_SAAS_HOSTS = Set.of("github.com", "api.github.com", "gitlab.com");

    private final Set<String> allowedHosts;

    @Autowired
    public GitLabHostPolicy(@Value("${SECSCAN_GITLAB_ALLOWED_HOSTS:}") String csv,
                            @Value("${SECSCAN_SOURCE_ALLOW_BUILTIN_SAAS:true}") boolean allowBuiltinSaas) {
        this(merge(parse(csv), allowBuiltinSaas));
    }

    public GitLabHostPolicy(Set<String> allowedHosts) {
        this.allowedHosts = Set.copyOf(allowedHosts);
    }

    /** Pure CSV constructor (tests): the configured hosts only, no built-in SaaS defaults. */
    public GitLabHostPolicy(String csv) {
        this(parse(csv));
    }

    private static Set<String> merge(Set<String> configured, boolean allowBuiltinSaas) {
        if (!allowBuiltinSaas) {
            return configured;
        }
        Set<String> all = new LinkedHashSet<>(configured);
        all.addAll(BUILTIN_SAAS_HOSTS);
        return all;
    }

    /**
     * Logs the effective allowlist at startup so an operator can confirm what SecScan may reach — and is warned
     * loudly when the list is empty, since that denies every outbound GitLab call (no connection test, listing,
     * polling or clone can succeed). Never logs anything but hostnames.
     */
    @PostConstruct
    void logStartupConfiguration() {
        if (allowedHosts.isEmpty()) {
            log.warn("{}", startupSummary());
        } else {
            log.info("{}", startupSummary());
        }
    }

    /** Human-readable one-line summary of the allowlist for the startup log (hosts only, never secrets). */
    String startupSummary() {
        if (allowedHosts.isEmpty()) {
            return "GitLab outbound allowlist (SECSCAN_GITLAB_ALLOWED_HOSTS) is EMPTY — all outbound GitLab calls "
                    + "will be denied (D-018). Set it to the GitLab host(s) SecScan may reach.";
        }
        return "GitLab outbound allowlist (SECSCAN_GITLAB_ALLOWED_HOSTS): " + allowedHosts.size()
                + " host(s): " + allowedHosts;
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
        // N-3: URI.getHost() returns an IPv6 literal wrapped in brackets, so the loopback address appears as
        // "[::1]", never the bare "::1" the old clause compared against (which therefore matched nothing).
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }
}
