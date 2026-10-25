package com.company.secscan.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * A small WireMock helper that serves the GitLab sample fixtures. Each test starts its own instance on a
 * dynamic port and stubs the endpoints it needs.
 */
public final class GitLabWireMock {

    private final WireMockServer server;

    public GitLabWireMock() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        this.server.start();
    }

    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    /** The underlying server, for request verification in tests. */
    public WireMockServer server() {
        return server;
    }

    public void stop() {
        if (server.isRunning()) {
            server.stop();
        }
    }

    public GitLabWireMock stubVersion() {
        server.stubFor(get(urlEqualTo("/api/v4/version")).willReturn(json(sample("version.json"))));
        return this;
    }

    public GitLabWireMock stubTokenSelf(String sampleFile) {
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self"))
                .willReturn(json(sample(sampleFile))));
        return this;
    }

    public GitLabWireMock stubTokenSelfBody(String body) {
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self")).willReturn(json(body)));
        return this;
    }

    public GitLabWireMock stubTokenSelfStatus(int status) {
        server.stubFor(get(urlEqualTo("/api/v4/personal_access_tokens/self"))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public GitLabWireMock stubGroup(String urlEncodedPath) {
        server.stubFor(get(urlEqualTo("/api/v4/groups/" + urlEncodedPath))
                .willReturn(json(sample("group.json"))));
        return this;
    }

    /** Stub a group resolve with a custom JSON body (e.g. a different id, to exercise group-change rejection). */
    public GitLabWireMock stubGroupBody(String urlEncodedPath, String body) {
        server.stubFor(get(urlEqualTo("/api/v4/groups/" + urlEncodedPath)).willReturn(json(body)));
        return this;
    }

    /** Stub {@code GET /projects/:url-encoded-path} with the single-project sample (id 102, services/ib-keycloak). */
    public GitLabWireMock stubProject(String urlEncodedPath) {
        return stubProjectBody(urlEncodedPath, sample("project.json"));
    }

    public GitLabWireMock stubProjectBody(String urlEncodedPath, String body) {
        server.stubFor(get(urlEqualTo("/api/v4/projects/" + urlEncodedPath)).willReturn(json(body)));
        return this;
    }

    public GitLabWireMock stubProjectStatus(String urlEncodedPath, int status) {
        server.stubFor(get(urlEqualTo("/api/v4/projects/" + urlEncodedPath))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public GitLabWireMock stubProjects(long groupId, String bodyJson) {
        server.stubFor(get(urlPathEqualTo("/api/v4/groups/" + groupId + "/projects"))
                .willReturn(json(bodyJson).withHeader("x-next-page", "")));
        return this;
    }

    public GitLabWireMock stubProjectsFromSample(long groupId) {
        return stubProjects(groupId, sample("projects-special.json"));
    }

    public GitLabWireMock stubProjectsStatus(long groupId, int status) {
        server.stubFor(get(urlPathEqualTo("/api/v4/groups/" + groupId + "/projects"))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public GitLabWireMock stubLanguages(long projectId, String bodyJson) {
        server.stubFor(get(urlEqualTo("/api/v4/projects/" + projectId + "/languages"))
                .willReturn(json(bodyJson)));
        return this;
    }

    /** Stub {@code GET /projects/:id} (numeric id) with a project body (A6 default-branch refresh). */
    public GitLabWireMock stubProjectById(long projectId, String body) {
        server.stubFor(get(urlEqualTo("/api/v4/projects/" + projectId)).willReturn(json(body)));
        return this;
    }

    /** Stub one page of {@code GET /projects/:id/repository/branches} with x-total / x-total-pages headers. */
    public GitLabWireMock stubBranches(long projectId, String bodyJson, int total, int totalPages) {
        server.stubFor(get(urlPathEqualTo("/api/v4/projects/" + projectId + "/repository/branches"))
                .willReturn(json(bodyJson)
                        .withHeader("x-total", String.valueOf(total))
                        .withHeader("x-total-pages", String.valueOf(totalPages))));
        return this;
    }

    /** Stub {@code GET /projects/:id/repository/branches/:branch} (URL-encoded branch) with a branch body. */
    public GitLabWireMock stubBranch(long projectId, String urlEncodedBranch, String body) {
        server.stubFor(get(urlEqualTo(
                "/api/v4/projects/" + projectId + "/repository/branches/" + urlEncodedBranch))
                .willReturn(json(body)));
        return this;
    }

    /** Stub {@code GET /projects/:id/repository/branches/:branch} to return a status (e.g. 404 = branch gone). */
    public GitLabWireMock stubBranchStatus(long projectId, String urlEncodedBranch, int status) {
        server.stubFor(get(urlEqualTo(
                "/api/v4/projects/" + projectId + "/repository/branches/" + urlEncodedBranch))
                .willReturn(aResponse().withStatus(status)));
        return this;
    }

    public static String sample(String name) {
        try (var in = GitLabWireMock.class.getResourceAsStream("/samples/gitlab/" + name)) {
            if (in == null) {
                throw new IOException("missing sample: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
