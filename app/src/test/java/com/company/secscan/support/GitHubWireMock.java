package com.company.secscan.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/** A standalone WireMock GitHub REST API for adapter tests: starts on a dynamic port and stubs what it needs. */
public final class GitHubWireMock {

    private final WireMockServer server;

    public GitHubWireMock() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        this.server.start();
    }

    /** github.com reaches api.github.com; the client maps localhost to the base URL as-is, so stubs are at root. */
    public String baseUrl() {
        return "http://localhost:" + server.port();
    }

    public WireMockServer server() {
        return server;
    }

    public void stop() {
        server.stop();
    }

    public GitHubWireMock stubUser(String login, String type) {
        server.stubFor(get(urlEqualTo("/user"))
                .willReturn(json("{\"login\":\"" + login + "\",\"type\":\"" + type + "\"}")));
        return this;
    }

    public GitHubWireMock stubUserStatus(int status, String... headers) {
        server.stubFor(get(urlEqualTo("/user")).willReturn(withHeaders(aResponse().withStatus(status), headers)));
        return this;
    }

    public GitHubWireMock stubOwner(String login, String type) {
        server.stubFor(get(urlEqualTo("/users/" + login))
                .willReturn(json("{\"login\":\"" + login + "\",\"type\":\"" + type + "\"}")));
        return this;
    }

    public GitHubWireMock stubRepoById(long id, String body) {
        server.stubFor(get(urlEqualTo("/repositories/" + id)).willReturn(json(body)));
        return this;
    }

    public GitHubWireMock stubRepoByIdStatus(long id, int status, String... headers) {
        server.stubFor(get(urlEqualTo("/repositories/" + id))
                .willReturn(withHeaders(aResponse().withStatus(status), headers)));
        return this;
    }

    /** One page of an owner's repos with a Link header pointing at a last page (pagination). */
    public GitHubWireMock stubOwnerReposPage(boolean org, String owner, int page, String body, int lastPage) {
        String link = "<https://api.github.com/x?page=" + lastPage + ">; rel=\"last\"";
        server.stubFor(get(urlPathEqualTo((org ? "/orgs/" : "/users/") + owner + "/repos"))
                .willReturn(json(body).withHeader("Link", link)));
        return this;
    }

    public GitHubWireMock stubBranches(String fullName, String body) {
        server.stubFor(get(urlPathEqualTo("/repos/" + fullName + "/branches")).willReturn(json(body)));
        return this;
    }

    /** One page of a repository's branches (keyed by the {@code page} query param) with a Link rel="last" header. */
    public GitHubWireMock stubBranchPage(String fullName, int page, String body, int lastPage) {
        String link = "<https://api.github.com/x?page=" + lastPage + ">; rel=\"last\"";
        server.stubFor(get(urlPathEqualTo("/repos/" + fullName + "/branches"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(json(body).withHeader("Link", link)));
        return this;
    }

    /** A branch-list page that returns a status (e.g. 429 for a rate limit) for a given page. */
    public GitHubWireMock stubBranchPageStatus(String fullName, int page, int status, String... headers) {
        server.stubFor(get(urlPathEqualTo("/repos/" + fullName + "/branches"))
                .withQueryParam("page", equalTo(String.valueOf(page)))
                .willReturn(withHeaders(aResponse().withStatus(status), headers)));
        return this;
    }

    /** How many branch-LIST requests (not the exact-branch GET) have hit this server since the last reset. */
    public int branchListRequestCount(String fullName) {
        return server.countRequestsMatching(
                getRequestedFor(urlPathEqualTo("/repos/" + fullName + "/branches")).build()).getCount();
    }

    /** Count every request seen (any path) since the last reset. */
    public int totalRequestCount() {
        return server.getAllServeEvents().size();
    }

    public void resetRequests() {
        server.resetRequests();
    }

    public GitHubWireMock stubBranch(String fullName, String branch, int status) {
        server.stubFor(get(urlEqualTo("/repos/" + fullName + "/branches/" + branch))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(status == 200 ? "{\"name\":\"" + branch + "\",\"protected\":false}" : "{}")));
        return this;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder withHeaders(
            com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder b, String... headers) {
        for (int i = 0; i + 1 < headers.length; i += 2) {
            b = b.withHeader(headers[i], headers[i + 1]);
        }
        return b;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(body);
    }
}
