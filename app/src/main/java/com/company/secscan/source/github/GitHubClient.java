package com.company.secscan.source.github;

import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.SourcePage;
import com.company.secscan.source.github.dto.GitHubBranch;
import com.company.secscan.source.github.dto.GitHubRepo;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read-only GitHub REST v3 client (github.com; GitHub Enterprise Server later). Stateless: the connection base
 * URL and (fine-grained PAT) token are passed per call. TLS is always verified; redirects are never followed (a
 * token must not replay to another host); the token travels only in the {@code Authorization: Bearer} header and
 * is never logged or placed in a URL (D-014). Errors map to the shared {@link ProviderException} taxonomy.
 */
@Component
public class GitHubClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final int PER_PAGE = 100;
    private static final String API_VERSION = "2022-11-28";
    private static final Pattern LINK_LAST = Pattern.compile("[?&]page=(\\d+)[^>]*>;\\s*rel=\"last\"");

    private final GitLabHostPolicy hostPolicy;   // a host allowlist (SSRF guard); generalised in V6.

    public GitHubClient(GitLabHostPolicy hostPolicy) {
        this.hostPolicy = hostPolicy;
    }

    /** The authenticated user/owner the token belongs to (verify credential). */
    public GitHubOwner currentUser(String baseUrl, String token) {
        return call(() -> client(baseUrl, token).get().uri("/user").retrieve().body(GitHubOwner.class),
                "The GitHub token is not valid.");
    }

    /** Owner metadata (to tell a user target from an organization target). */
    public GitHubOwner owner(String baseUrl, String token, String login) {
        URI uri = URI.create(apiBase(baseUrl) + "/users/" + enc(login));
        return call(() -> client(baseUrl, token).get().uri(uri).retrieve().body(GitHubOwner.class),
                "GitHub owner not found.");
    }

    /** One page of an owner's repositories (org or user); totals derived from the Link header. */
    public SourcePage<GitHubRepo> listOwnerRepos(String baseUrl, String token, String owner, boolean org,
                                                 int page, int perPage) {
        String path = (org ? "/orgs/" : "/users/") + enc(owner) + "/repos";
        return call(() -> {
            ResponseEntity<List<GitHubRepo>> resp = client(baseUrl, token).get()
                    .uri(b -> b.path(path)
                            .queryParam("per_page", Math.min(perPage, PER_PAGE))
                            .queryParam("page", Math.max(page, 1))
                            .queryParam("sort", "full_name")
                            .build())
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<GitHubRepo>>() {});
            List<GitHubRepo> body = resp.getBody() == null ? List.of() : resp.getBody();
            int totalPages = lastPage(resp.getHeaders(), Math.max(page, 1));
            long totalElements = (long) totalPages * Math.min(perPage, PER_PAGE);
            return new SourcePage<>(body, Math.max(totalElements, body.size()), Math.max(totalPages, 1));
        }, "GitHub owner not found.");
    }

    /** A repository by its stable numeric id (identity); 404 → NOT_FOUND. */
    public GitHubRepo repoById(String baseUrl, String token, long id) {
        return call(() -> client(baseUrl, token).get().uri("/repositories/{id}", id)
                .retrieve().body(GitHubRepo.class), "GitHub repository not found.");
    }

    /** A repository by {@code owner/name}; 404 → NOT_FOUND. */
    public GitHubRepo repoByFullName(String baseUrl, String token, String fullName) {
        URI uri = URI.create(apiBase(baseUrl) + "/repos/" + fullNamePath(fullName));
        return call(() -> client(baseUrl, token).get().uri(uri).retrieve().body(GitHubRepo.class),
                "GitHub repository not found.");
    }

    /** One page of a repository's branches (no server-side search on GitHub — the caller filters). */
    public SourcePage<GitHubBranch> listBranches(String baseUrl, String token, String fullName, int page,
                                                 int perPage) {
        URI base = URI.create(apiBase(baseUrl) + "/repos/" + fullNamePath(fullName) + "/branches");
        return call(() -> {
            ResponseEntity<List<GitHubBranch>> resp = client(baseUrl, token).get()
                    .uri(b -> URI.create(base + "?per_page=" + Math.min(perPage, PER_PAGE)
                            + "&page=" + Math.max(page, 1)))
                    .retrieve()
                    .toEntity(new ParameterizedTypeReference<List<GitHubBranch>>() {});
            List<GitHubBranch> body = resp.getBody() == null ? List.of() : resp.getBody();
            int totalPages = lastPage(resp.getHeaders(), Math.max(page, 1));
            long totalElements = (long) totalPages * Math.min(perPage, PER_PAGE);
            return new SourcePage<>(body, Math.max(totalElements, body.size()), Math.max(totalPages, 1));
        }, "GitHub repository not found.");
    }

    /**
     * The branch by exact name (GET branch), or {@code null} on 404 — the exact-match fast path for the branch
     * picker search (M8.8 S5b). A rate limit or other error propagates as {@link ProviderException}.
     */
    public GitHubBranch getBranch(String baseUrl, String token, String fullName, String branch) {
        URI uri = URI.create(apiBase(baseUrl) + "/repos/" + fullNamePath(fullName) + "/branches/" + enc(branch));
        try {
            return client(baseUrl, token).get().uri(uri).retrieve().body(GitHubBranch.class);
        } catch (RestClientException e) {
            ProviderException mapped = com.company.secscan.source.HttpErrors.map(e, "branch not found");
            if (mapped.error() == ProviderError.NOT_FOUND) {
                return null;
            }
            throw mapped;
        }
    }

    /** True if the branch exists (GET branch → 200); 404 → false. Authoritative existence check. */
    public boolean branchExists(String baseUrl, String token, String fullName, String branch) {
        URI uri = URI.create(apiBase(baseUrl) + "/repos/" + fullNamePath(fullName) + "/branches/" + enc(branch));
        try {
            client(baseUrl, token).get().uri(uri).retrieve().body(GitHubBranch.class);
            return true;
        } catch (RestClientException e) {
            ProviderException mapped = com.company.secscan.source.HttpErrors.map(e, "branch not found");
            if (mapped.error() == ProviderError.NOT_FOUND) {
                return false;
            }
            throw mapped;
        }
    }

    /** GitHub owner (login + type: {@code User} or {@code Organization}). */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record GitHubOwner(String login, String type) {
        public boolean isOrganization() {
            return "Organization".equalsIgnoreCase(type);
        }
    }

    // --- internals ---

    private RestClient client(String baseUrl, String token) {
        String api = apiBase(baseUrl);
        hostPolicy.requireAllowed(api);   // https + allowlisted host (SSRF guard, D-018/D-033)
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory rf = new JdkClientHttpRequestFactory(httpClient);
        rf.setReadTimeout(READ_TIMEOUT);
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(api)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", API_VERSION)
                .requestFactory(rf)
                .defaultStatusHandler(HttpStatusCode::is3xxRedirection, (request, response) -> {
                    throw new ProviderException(ProviderError.UNEXPECTED_REDIRECT,
                            "GitHub returned a redirect (" + response.getStatusCode().value()
                                    + "); redirects are not followed.");
                });
        if (token != null && !token.isBlank()) {
            builder = builder.defaultHeader("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /** github.com reaches the API at api.github.com; a test/GHES host serves the API at its own base. */
    private static String apiBase(String baseUrl) {
        String b = baseUrl == null ? "" : baseUrl.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        String lower = b.toLowerCase(Locale.ROOT);
        if (lower.equals("https://github.com") || lower.equals("http://github.com")) {
            return "https://api.github.com";
        }
        return b;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** owner/name → owner%2F... is wrong for GitHub; it wants owner/name with each segment encoded, slash kept. */
    private static String fullNamePath(String fullName) {
        String[] parts = fullName.split("/", 2);
        if (parts.length != 2) {
            return enc(fullName);
        }
        return enc(parts[0]) + "/" + enc(parts[1]);
    }

    private static int lastPage(HttpHeaders headers, int current) {
        if (headers == null) {
            return current;
        }
        String link = headers.getFirst("Link");
        if (link == null) {
            return current;
        }
        Matcher m = LINK_LAST.matcher(link);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return current;
            }
        }
        return current;
    }

    private <T> T call(Supplier<T> op, String notFoundMessage) {
        try {
            return op.get();
        } catch (ProviderException e) {
            throw e;   // already mapped (e.g. the redirect handler)
        } catch (RestClientException e) {
            throw com.company.secscan.source.HttpErrors.map(e, notFoundMessage);
        }
    }
}
