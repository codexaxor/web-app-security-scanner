package com.company.secscan.gitlab;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.gitlab.dto.GitLabTokenInfo;
import com.company.secscan.gitlab.dto.GitLabVersion;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** WireMock-backed tests for {@link GitLabClient}: parsing, pagination and error categorization. */
class GitLabClientTest {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";

    private WireMockServer server;
    private String baseUrl;
    private final GitLabClient client = new GitLabClient(new GitLabHostPolicy(java.util.Set.of("localhost")));

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        baseUrl = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    void parsesVersion() throws IOException {
        server.stubFor(get(urlEqualTo("/api/v4/version"))
                .willReturn(json(sample("version.json"))));

        GitLabVersion v = client.version(baseUrl, TOKEN);

        assertThat(v.version()).isEqualTo("16.11.1");
    }

    @Test
    void parsesTokenScopesAndExpiry() throws IOException {
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self"))
                .willReturn(json(sample("token-self.json"))));

        GitLabTokenInfo info = client.tokenInfo(baseUrl, TOKEN);

        assertThat(info.active()).isTrue();
        assertThat(info.scopes()).containsExactlyInAnyOrder("read_api", "read_repository");
        assertThat(info.expiresAt()).isEqualTo(LocalDate.of(2027, 6, 30));
    }

    @Test
    void resolvesGroupByUrlEncodedPath() throws IOException {
        // The '/' in the path must be encoded as %2F in the request line.
        server.stubFor(get(urlEqualTo("/api/v4/groups/services%2Fbackend"))
                .willReturn(json(sample("group.json"))));

        assertThat(client.resolveGroup(baseUrl, TOKEN, "services/backend").id()).isEqualTo(42L);
    }

    @Test
    void missingGroupMapsToGroupNotFound() {
        server.stubFor(get(urlEqualTo("/api/v4/groups/nope"))
                .willReturn(aResponse().withStatus(404)));

        GitLabException ex = catchThrowableOfType(
                () -> client.resolveGroup(baseUrl, TOKEN, "nope"), GitLabException.class);
        assertThat(ex.getError()).isEqualTo(GitLabError.GROUP_NOT_FOUND);
    }

    @Test
    void resolvesProjectByUrlEncodedPath() throws IOException {
        server.stubFor(get(urlEqualTo("/api/v4/projects/services%2Fib-keycloak"))
                .willReturn(json(sample("project.json"))));

        GitLabProject p = client.resolveProject(baseUrl, TOKEN, "services/ib-keycloak");

        assertThat(p.id()).isEqualTo(102L);
        assertThat(p.pathWithNamespace()).isEqualTo("services/ib-keycloak");
        assertThat(p.defaultBranch()).isEqualTo("master");
    }

    @Test
    void missingProjectMapsToProjectNotFound() {
        server.stubFor(get(urlEqualTo("/api/v4/projects/nope%2Fgone"))
                .willReturn(aResponse().withStatus(404)));

        assertThat(catchThrowableOfType(
                () -> client.resolveProject(baseUrl, TOKEN, "nope/gone"), GitLabException.class).getError())
                .isEqualTo(GitLabError.PROJECT_NOT_FOUND);
    }

    @Test
    void forbiddenProjectResolveMapsToMissingScopes() {
        server.stubFor(get(urlEqualTo("/api/v4/projects/services%2Fib-keycloak"))
                .willReturn(aResponse().withStatus(403)));

        assertThat(catchThrowableOfType(
                () -> client.resolveProject(baseUrl, TOKEN, "services/ib-keycloak"), GitLabException.class)
                .getError()).isEqualTo(GitLabError.MISSING_SCOPES);
    }

    @Test
    void listGroupProjectsFollowsPagination() {
        server.stubFor(get(urlPathEqualTo("/api/v4/groups/42/projects"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(json(projectsArray(1, 100)).withHeader("x-next-page", "2")));
        server.stubFor(get(urlPathEqualTo("/api/v4/groups/42/projects"))
                .withQueryParam("page", equalTo("2"))
                .willReturn(json(projectsArray(101, 51)).withHeader("x-next-page", "")));

        List<GitLabProject> projects = client.listGroupProjects(baseUrl, TOKEN, 42);

        assertThat(projects).hasSize(151);
        assertThat(projects.get(0).pathWithNamespace()).isEqualTo("services/proj-1");
    }

    @Test
    void projectLanguagesSortedByUsage() throws IOException {
        server.stubFor(get(urlEqualTo("/api/v4/projects/7/languages"))
                .willReturn(json(sample("languages.json"))));

        assertThat(client.projectLanguages(baseUrl, TOKEN, 7))
                .containsExactly("Java", "TypeScript", "JavaScript");
    }

    @Test
    void notFoundOnNonGroupCallMapsToNotFound() {
        // A 404 outside group resolve is NOT_FOUND, never GROUP_NOT_FOUND (point 5).
        server.stubFor(get(urlEqualTo("/api/v4/projects/7/languages")).willReturn(aResponse().withStatus(404)));

        assertThat(catchThrowableOfType(() -> client.projectLanguages(baseUrl, TOKEN, 7), GitLabException.class)
                .getError()).isEqualTo(GitLabError.NOT_FOUND);
    }

    @Test
    void redirectIsNotFollowedToAnotherHostAndTokenNeverLeaks() {
        WireMockServer other = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        other.start();
        try {
            // The primary host 302-redirects to the other host; we must not follow it.
            server.stubFor(get(urlEqualTo("/api/v4/version")).willReturn(aResponse().withStatus(302)
                    .withHeader("Location", "http://localhost:" + other.port() + "/api/v4/version")));

            GitLabException ex = catchThrowableOfType(
                    () -> client.version(baseUrl, TOKEN), GitLabException.class);

            assertThat(ex.getError()).isEqualTo(GitLabError.UNEXPECTED_REDIRECT);
            // No request reached the redirect target, so the PRIVATE-TOKEN header never leaked there.
            assertThat(other.getAllServeEvents()).isEmpty();
        } finally {
            other.stop();
        }
    }

    @Test
    void rejectsDisallowedHostBeforeAnyCall() {
        GitLabClient strict = new GitLabClient(new GitLabHostPolicy(java.util.Set.of("gitlab.example.com")));

        assertThat(catchThrowableOfType(
                () -> strict.version("https://evil.example.org", TOKEN), GitLabException.class).getError())
                .isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void rejectsNonHttpsForNonLoopbackHost() {
        GitLabClient strict = new GitLabClient(new GitLabHostPolicy(java.util.Set.of("gitlab.example.com")));

        assertThat(catchThrowableOfType(
                () -> strict.version("http://gitlab.example.com", TOKEN), GitLabException.class).getError())
                .isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void invalidTokenMapsTo401() {
        server.stubFor(get(urlEqualTo("/api/v4/version")).willReturn(aResponse().withStatus(401)));

        assertThat(catchThrowableOfType(() -> client.version(baseUrl, TOKEN), GitLabException.class).getError())
                .isEqualTo(GitLabError.INVALID_TOKEN);
    }

    @Test
    void forbiddenMapsToMissingScopes() {
        server.stubFor(get(urlEqualTo("/api/v4/version")).willReturn(aResponse().withStatus(403)));

        assertThat(catchThrowableOfType(() -> client.version(baseUrl, TOKEN), GitLabException.class).getError())
                .isEqualTo(GitLabError.MISSING_SCOPES);
    }

    @Test
    void rateLimitedMapsTo429() {
        server.stubFor(get(urlEqualTo("/api/v4/version"))
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "30")));

        assertThat(catchThrowableOfType(() -> client.version(baseUrl, TOKEN), GitLabException.class).getError())
                .isEqualTo(GitLabError.RATE_LIMITED);
    }

    @Test
    void connectionFaultMapsToUnreachable() {
        server.stubFor(get(urlEqualTo("/api/v4/version"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client.version(baseUrl, TOKEN))
                .isInstanceOf(GitLabException.class)
                .extracting(e -> ((GitLabException) e).getError())
                .isEqualTo(GitLabError.UNREACHABLE);
    }

    // --- helpers ---

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }

    private static String sample(String name) throws IOException {
        try (var in = GitLabClientTest.class.getResourceAsStream("/samples/gitlab/" + name)) {
            if (in == null) {
                throw new IOException("missing sample: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String projectsArray(long startId, int count) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < count; i++) {
            long id = startId + i;
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"id\":").append(id)
                    .append(",\"path_with_namespace\":\"services/proj-").append(id).append("\"")
                    .append(",\"default_branch\":\"main\",\"archived\":false,\"empty_repo\":false}");
        }
        return sb.append("]").toString();
    }
}
