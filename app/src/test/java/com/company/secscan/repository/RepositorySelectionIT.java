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
import java.util.UUID;
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
    private UUID connId;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
        // Save a group connection pointing at the mock.
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        connectionService.create(gitlab.baseUrl(), TOKEN, "group", "services");
        connId = connections.findByWorkspaceIdAndGitlabGroupId(Workspace.DEFAULT_ID, GROUP_ID).orElseThrow()
                .getId();
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
        PagedResponse<ProjectView> page = repositoryService.listProjects(connId, 0, 50);

        // 8 projects minus archived (106) and marked-for-deletion (107) = 6; empty (108) listed but not selectable.
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.content()).noneMatch(p -> p.pathWithNamespace().contains("legacy-archived"));
        assertThat(page.content()).noneMatch(p -> p.pathWithNamespace().contains("to-be-deleted"));

        ProjectView empty = page.content().stream()
                .filter(p -> p.gitlabProjectId() == 108).findFirst().orElseThrow();
        assertThat(empty.selectable()).isFalse();
        assertThat(empty.notSelectableReason()).isEqualTo("Empty repository (no commits yet).");  // B2

        ProjectView webPortal = page.content().stream()
                .filter(p -> p.gitlabProjectId() == 103).findFirst().orElseThrow();
        assertThat(webPortal.defaultBranch()).isEqualTo("develop");
        assertThat(webPortal.languages()).contains("TypeScript");
    }

    @Test
    void listingPaginatesServerSide() {
        PagedResponse<ProjectView> page = repositoryService.listProjects(connId, 0, 2);

        assertThat(page.content()).hasSize(2);
        assertThat(page.totalElements()).isEqualTo(6);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void selectPersistsRepositoriesPerWorkspace() {
        List<RepositoryView> selected = repositoryService.select(connId, List.of(101L, 102L, 103L)).repositories();
        assertThat(selected).hasSize(3);
        assertThat(selected).allMatch(v -> v.connectionId().equals(connId));
        assertThat(selected).allMatch(v -> "group".equals(v.connectionTargetType()));

        List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(stored).hasSize(3);
        assertThat(stored).allMatch(r -> r.getWorkspaceId().equals(Workspace.DEFAULT_ID));
        assertThat(stored).allMatch(Repository::isActive);

        Repository keycloak = stored.stream()
                .filter(r -> r.getGitlabProjectId() == 102).findFirst().orElseThrow();
        assertThat(keycloak.getDefaultBranch()).isEqualTo("master");
        assertThat(keycloak.getPathWithNamespace()).isEqualTo("services/ib-keycloak");
        assertThat(keycloak.getConnectionId()).isEqualTo(connId);
    }

    @Test
    void cannotSelectEmptyRepository() {
        assertThatThrownBy(() -> repositoryService.select(connId, List.of(108L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("empty repository");
    }

    @Test
    void cannotSelectArchivedProject() {
        assertThatThrownBy(() -> repositoryService.select(connId, List.of(106L)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void reportsAddedVsAlreadySelectedCounts() {   // B1
        repositoryService.select(connId, List.of(101L, 102L));
        var result = repositoryService.select(connId, List.of(101L, 103L));
        assertThat(result.added()).isEqualTo(1);            // 103 is new
        assertThat(result.alreadySelected()).isEqualTo(1);  // 101 was already selected
        assertThat(result.repositories()).hasSize(2);
    }

    @Test
    void reselectingADetachedRepositoryReactivatesItAndCountsItReactivated() {   // SF-3 / D-020
        repositoryService.select(connId, List.of(101L));                    // added, active
        Repository repo = repositories.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, 101L)
                .orElseThrow();
        repo.detachAndDeactivate("NO_ACCESSIBLE_CONNECTION");                // its connection was removed
        repositories.save(repo);

        var result = repositoryService.select(connId, List.of(101L));       // re-selected through a connection

        assertThat(result.reactivated()).isEqualTo(1);
        assertThat(result.added()).isZero();
        assertThat(result.alreadySelected()).isZero();
        assertThat(repositories.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, 101L)
                .orElseThrow().isActive()).isTrue();
    }

    @Test
    void labelsUnreadableCodeHonestlyNotEmpty() {   // B2
        // Override the group-projects stub with a project that has code but an unreadable default branch.
        gitlab.stubProjects(GROUP_ID, "[{\"id\":201,\"path_with_namespace\":\"services/locked\","
                + "\"default_branch\":null,\"empty_repo\":false,\"archived\":false}]");

        PagedResponse<ProjectView> page = repositoryService.listProjects(connId, 0, 50);
        ProjectView locked = page.content().stream()
                .filter(p -> p.gitlabProjectId() == 201).findFirst().orElseThrow();
        assertThat(locked.selectable()).isFalse();
        assertThat(locked.notSelectableReason()).contains("No access to repository code");
    }
}
