package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.gitlab.api.ProjectView;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Listing excludes archived/deleted, flags empty repos, and selection persists repositories per workspace. */
class RepositorySelectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long GROUP_ID = 42L;

    @Autowired
    GitLabConnectionService connectionService;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        repositories.deleteAll();
        connections.deleteAll();
        gitlab = new GitLabWireMock();
        // Save a connection pointing at the mock.
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        connectionService.save(gitlab.baseUrl(), TOKEN, "services");
        // Group projects + languages for the selectable ones.
        gitlab.stubProjectsFromSample(GROUP_ID)
                .stubLanguages(101, "{\"Java\":100.0}")
                .stubLanguages(102, "{\"Java\":100.0}")
                .stubLanguages(103, "{\"TypeScript\":80.0,\"JavaScript\":20.0}")
                .stubLanguages(104, "{\"C#\":100.0}")
                .stubLanguages(105, "{\"JavaScript\":100.0}");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void listingExcludesArchivedAndDeletionAndFlagsEmpty() {
        PagedResponse<ProjectView> page = repositoryService.listProjects(0, 50);

        // 8 projects minus archived (106) and marked-for-deletion (107) = 6; empty (108) is listed but not selectable.
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.content()).noneMatch(p -> p.pathWithNamespace().contains("legacy-archived"));
        assertThat(page.content()).noneMatch(p -> p.pathWithNamespace().contains("to-be-deleted"));

        ProjectView empty = page.content().stream()
                .filter(p -> p.gitlabProjectId() == 108).findFirst().orElseThrow();
        assertThat(empty.selectable()).isFalse();
        assertThat(empty.notSelectableReason()).isEqualTo("empty repository");

        ProjectView webPortal = page.content().stream()
                .filter(p -> p.gitlabProjectId() == 103).findFirst().orElseThrow();
        assertThat(webPortal.defaultBranch()).isEqualTo("develop");
        assertThat(webPortal.languages()).contains("TypeScript");
    }

    @Test
    void listingPaginatesServerSide() {
        PagedResponse<ProjectView> page = repositoryService.listProjects(0, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void selectPersistsRepositoriesPerWorkspace() {
        List<RepositoryView> selected = repositoryService.select(List.of(101L, 102L, 103L));
        assertThat(selected).hasSize(3);

        List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(stored).hasSize(3);
        assertThat(stored).allMatch(r -> r.getWorkspaceId().equals(Workspace.DEFAULT_ID));
        assertThat(stored).allMatch(Repository::isActive);

        Repository keycloak = stored.stream()
                .filter(r -> r.getGitlabProjectId() == 102).findFirst().orElseThrow();
        assertThat(keycloak.getDefaultBranch()).isEqualTo("master");
        assertThat(keycloak.getPathWithNamespace()).isEqualTo("services/ib-keycloak");
    }

    @Test
    void cannotSelectEmptyRepository() {
        assertThatThrownBy(() -> repositoryService.select(List.of(108L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("empty repository");
    }

    @Test
    void cannotSelectArchivedProject() {
        assertThatThrownBy(() -> repositoryService.select(List.of(106L)))
                .isInstanceOf(ResponseStatusException.class);
    }
}
