package com.company.secscan.gitlab;

import com.company.secscan.gitlab.dto.GitLabGroup;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.gitlab.dto.GitLabTokenInfo;
import com.company.secscan.gitlab.dto.GitLabVersion;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read-only GitLab REST v4 client. Stateless: base URL and token are passed per call (one connection per
 * workspace today, but kept open to more). TLS verification is never disabled. The token is sent only in the
 * {@code PRIVATE-TOKEN} header and never logged.
 */
@Component
public class GitLabClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final int PER_PAGE = 100;

    private final GitLabHostPolicy hostPolicy;

    public GitLabClient(GitLabHostPolicy hostPolicy) {
        this.hostPolicy = hostPolicy;
    }

    public GitLabVersion version(String baseUrl, String token) {
        return call(() -> client(baseUrl, token).get().uri("/version").retrieve().body(GitLabVersion.class),
                GitLabError.NOT_FOUND);
    }

    public GitLabTokenInfo tokenInfo(String baseUrl, String token) {
        return call(() -> client(baseUrl, token).get().uri("/personal_access_tokens/self")
                        .retrieve().body(GitLabTokenInfo.class),
                GitLabError.NOT_FOUND);
    }

    /** Resolves a group by its full path (e.g. {@code services/backend}); 404 → {@link GitLabError#GROUP_NOT_FOUND}. */
    public GitLabGroup resolveGroup(String baseUrl, String token, String groupPath) {
        // Pre-encode the path (encodes '/'), then pass an absolute URI so RestClient does not encode it again.
        URI uri = URI.create(apiBase(baseUrl) + "/groups/"
                + URLEncoder.encode(groupPath, StandardCharsets.UTF_8));
        return call(() -> client(baseUrl, token).get().uri(uri).retrieve().body(GitLabGroup.class),
                GitLabError.GROUP_NOT_FOUND);
    }

    /** All non-archived projects in the group (and its subgroups), following GitLab's page headers. */
    public List<GitLabProject> listGroupProjects(String baseUrl, String token, long groupId) {
        return call(() -> {
            RestClient rc = client(baseUrl, token);
            List<GitLabProject> all = new ArrayList<>();
            int page = 1;
            while (true) {
                final int p = page;
                ResponseEntity<List<GitLabProject>> resp = rc.get()
                        .uri(b -> b.path("/groups/{id}/projects")
                                .queryParam("include_subgroups", true)
                                .queryParam("archived", false)
                                .queryParam("per_page", PER_PAGE)
                                .queryParam("page", p)
                                .queryParam("order_by", "path")
                                .build(groupId))
                        .retrieve()
                        .toEntity(new ParameterizedTypeReference<List<GitLabProject>>() {});
                if (resp.getBody() != null) {
                    all.addAll(resp.getBody());
                }
                String next = header(resp.getHeaders(), "x-next-page");
                if (next == null || next.isBlank()) {
                    break;
                }
                page = Integer.parseInt(next.trim());
            }
            return all;
        }, GitLabError.NOT_FOUND);
    }

    /** Project languages, most-used first (keys of {@code GET /projects/:id/languages}). */
    public List<String> projectLanguages(String baseUrl, String token, long projectId) {
        return call(() -> {
            Map<String, Double> langs = client(baseUrl, token).get()
                    .uri("/projects/{id}/languages", projectId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Double>>() {});
            if (langs == null || langs.isEmpty()) {
                return List.<String>of();
            }
            return langs.entrySet().stream()
                    .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                    .map(Map.Entry::getKey)
                    .toList();
        }, GitLabError.NOT_FOUND);
    }

    // --- internals ---

    private RestClient client(String baseUrl, String token) {
        // SSRF guard: require https and an allowed host before any outbound call (D-018).
        hostPolicy.requireAllowed(baseUrl);
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(CONNECT_TIMEOUT);
        rf.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(apiBase(baseUrl))
                .defaultHeader("PRIVATE-TOKEN", token)
                .requestFactory(rf)
                .build();
    }

    private static String apiBase(String baseUrl) {
        String b = baseUrl.strip();
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        return b + "/api/v4";
    }

    private static String header(HttpHeaders headers, String name) {
        return headers == null ? null : headers.getFirst(name);
    }

    private <T> T call(Supplier<T> op, GitLabError notFoundCategory) {
        try {
            return op.get();
        } catch (HttpClientErrorException e) {
            switch (e.getStatusCode().value()) {
                case 401:
                    throw new GitLabException(GitLabError.INVALID_TOKEN,
                            "GitLab rejected the token (401). Check that it is correct and not revoked.", e);
                case 403:
                    throw new GitLabException(GitLabError.MISSING_SCOPES,
                            "The token is not authorized (403); it is likely missing a required scope.", e);
                case 404:
                    throw new GitLabException(notFoundCategory,
                            notFoundCategory == GitLabError.GROUP_NOT_FOUND
                                    ? "Group not found, or the token cannot see it."
                                    : "GitLab resource not found (404).", e);
                case 429:
                    String retryAfter = e.getResponseHeaders() == null ? null
                            : e.getResponseHeaders().getFirst("Retry-After");
                    throw new GitLabException(GitLabError.RATE_LIMITED,
                            "GitLab rate limit reached (429)"
                                    + (retryAfter != null ? "; retry after " + retryAfter + "s." : "."), e);
                default:
                    throw new GitLabException(GitLabError.UNEXPECTED,
                            "GitLab returned " + e.getStatusCode().value() + ".", e);
            }
        } catch (HttpServerErrorException e) {
            throw new GitLabException(GitLabError.UNEXPECTED,
                    "GitLab server error (" + e.getStatusCode().value() + ").", e);
        } catch (ResourceAccessException e) {
            throw new GitLabException(GitLabError.UNREACHABLE,
                    "Could not reach GitLab. Is the host correct and the VPN connected?", e);
        } catch (RestClientException e) {
            throw new GitLabException(GitLabError.UNEXPECTED, "Unexpected error talking to GitLab.", e);
        }
    }
}
