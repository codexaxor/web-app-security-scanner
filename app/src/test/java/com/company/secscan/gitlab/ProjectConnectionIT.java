package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.gitlab.api.ProjectView;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.repository.RepositoryService;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Project-mode connections: resolve a single project (path or URL), verify scopes/errors, and select it. */
class ProjectConnectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final String PATH = "services/ib-keycloak";
    private static final String ENCODED = "services%2Fib-keycloak";
    private static final long PROJECT_ID = 102L;

    @Autowired
    GitLabConnectionService service;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RepositoryRepository repositories;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    private UUID projectConnectionId() {
        return connections.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, PROJECT_ID).orElseThrow()
                .getId();
    }

    @Test
    void createResolvesAndStoresTheProject() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubProject(ENCODED);

        ConnectionTestResult r = service.create(gitlab.baseUrl(), TOKEN, "project", PATH);

        assertThat(r.ok()).isTrue();
        assertThat(r.targetType()).isEqualTo("project");
        assertThat(r.projectId()).isEqualTo(PROJECT_ID);
        assertThat(r.projectFullPath()).isEqualTo(PATH);

        GitlabConnection saved = connections.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, PROJECT_ID)
                .orElseThrow();
        assertThat(saved.isProject()).isTrue();
        assertThat(saved.getGitlabGroupId()).isNull();
        assertThat(saved.getTokenCiphertext()).doesNotContain(TOKEN);
    }

    @Test
    void acceptsAPastedProjectUrl() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubProject(ENCODED);

        ConnectionTestResult r = service.test(gitlab.baseUrl(), TOKEN, "project",
                gitlab.baseUrl() + "/services/ib-keycloak/-/tree/master");

        assertThat(r.ok()).isTrue();
        assertThat(r.projectId()).isEqualTo(PROJECT_ID);
    }

    @Test
    void projectNotFoundIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubProjectStatus(ENCODED, 404);

        ConnectionTestResult r = service.create(gitlab.baseUrl(), TOKEN, "project", PATH);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.PROJECT_NOT_FOUND.name());
    }

    @Test
    void missingScopeIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-missing-scope.json").stubProject(ENCODED);

        ConnectionTestResult r = service.create(gitlab.baseUrl(), TOKEN, "project", PATH);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.MISSING_SCOPES.name());
    }

    @Test
    void expiredTokenIsReported() {
        gitlab.stubVersion().stubTokenSelf("token-self-expired.json").stubProject(ENCODED);

        ConnectionTestResult r = service.create(gitlab.baseUrl(), TOKEN, "project", PATH);

        assertThat(r.ok()).isFalse();
        assertThat(r.errorCode()).isEqualTo(GitLabError.TOKEN_EXPIRED.name());
    }

    @Test
    void listsAndSelectsTheSingleProject() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubProject(ENCODED)
                .stubLanguages(PROJECT_ID, "{\"Java\":100.0}");
        service.create(gitlab.baseUrl(), TOKEN, "project", PATH);
        UUID connId = projectConnectionId();

        PagedResponse<ProjectView> page = repositoryService.listProjects(connId, 0, 50);
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content().get(0).gitlabProjectId()).isEqualTo(PROJECT_ID);
        assertThat(page.content().get(0).selectable()).isTrue();

        List<RepositoryView> selected = repositoryService.select(connId, List.of(PROJECT_ID)).repositories();
        assertThat(selected).singleElement()
                .satisfies(v -> {
                    assertThat(v.gitlabProjectId()).isEqualTo(PROJECT_ID);
                    assertThat(v.connectionId()).isEqualTo(connId);
                    assertThat(v.connectionTargetType()).isEqualTo("project");
                });
        assertThat(repositories.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
    }

    @Test
    void cannotSelectAProjectNotExposedByTheConnection() {
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubProject(ENCODED);
        service.create(gitlab.baseUrl(), TOKEN, "project", PATH);
        UUID connId = projectConnectionId();

        assertThatThrownBy(() -> repositoryService.select(connId, List.of(999L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not reachable through this connection");
        assertThat(repositories.findByWorkspaceId(Workspace.DEFAULT_ID)).isEmpty();
    }
}
