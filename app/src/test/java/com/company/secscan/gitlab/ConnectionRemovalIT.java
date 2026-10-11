package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.api.ConnectionRemovalResult;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.repository.RepositoryService;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removing a connection never deletes repository rows: each is reassigned to another connection that still
 * exposes it, or deactivated with NO_ACCESSIBLE_CONNECTION (D-020). A provisional reassignment is confirmed or
 * flipped to inactive the next time the target connection is used.
 */
class ConnectionRemovalIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long GROUP_ID = 42L;
    private static final long PROJECT_ID = 102L;
    private static final String PROJECT_PATH = "services/ib-keycloak";

    @Autowired
    GitLabConnectionService service;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    JdbcTemplate jdbc;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
        gitlab.stubVersion().stubTokenSelf("token-self.json")
                .stubGroup("services")
                .stubProject("services%2Fib-keycloak")
                .stubProjectsFromSample(GROUP_ID)
                .stubLanguages(PROJECT_ID, "{\"Java\":100.0}");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    private UUID groupConnId() {
        return connections.findByWorkspaceIdAndGitlabGroupId(Workspace.DEFAULT_ID, GROUP_ID).orElseThrow().getId();
    }

    private UUID projectConnId() {
        return connections.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, PROJECT_ID).orElseThrow()
                .getId();
    }

    private Repository onlyRepo() {
        List<Repository> all = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    @Test
    void removingAConnectionReassignsToAGroupThatStillExposesTheProject() {
        service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        service.create(gitlab.baseUrl(), TOKEN, "project", PROJECT_PATH);
        UUID groupConn = groupConnId();
        UUID projectConn = projectConnId();
        repositoryService.select(projectConn, List.of(PROJECT_ID)); // owned by the project connection

        ConnectionRemovalResult result = service.delete(projectConn);

        assertThat(result.reassigned()).isEqualTo(1);
        assertThat(result.deactivated()).isZero();
        assertThat(connections.findById(projectConn)).isEmpty(); // connection row (and its token) gone
        Repository repo = onlyRepo();
        assertThat(repo.isActive()).isTrue();
        assertThat(repo.getConnectionId()).isEqualTo(groupConn); // reassigned
        assertThat(repo.isProvisional()).isTrue();               // provisional until next use
    }

    @Test
    void removingTheOnlyConnectionDeactivatesTheRepository() {
        service.create(gitlab.baseUrl(), TOKEN, "project", PROJECT_PATH);
        UUID projectConn = projectConnId();
        repositoryService.select(projectConn, List.of(PROJECT_ID));

        ConnectionRemovalResult result = service.delete(projectConn);

        assertThat(result.reassigned()).isZero();
        assertThat(result.deactivated()).isEqualTo(1);
        Repository repo = onlyRepo(); // row kept, just inactive
        assertThat(repo.isActive()).isFalse();
        assertThat(repo.getInactiveReason()).isEqualTo("NO_ACCESSIBLE_CONNECTION");
    }

    @Test
    void provisionalReassignmentIsConfirmedWhenTheNewConnectionWorks() {
        service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        service.create(gitlab.baseUrl(), TOKEN, "project", PROJECT_PATH);
        UUID groupConn = groupConnId();
        UUID projectConn = projectConnId();
        repositoryService.select(projectConn, List.of(PROJECT_ID));
        service.delete(projectConn);
        assertThat(onlyRepo().isProvisional()).isTrue();

        // Using the group connection succeeds → the reassignment is confirmed.
        repositoryService.listProjects(groupConn, 0, 50);

        Repository repo = onlyRepo();
        assertThat(repo.isProvisional()).isFalse();
        assertThat(repo.isActive()).isTrue();
    }

    @Test
    void provisionalReassignmentIsDeactivatedWhenTheNewConnectionCannotReachIt() {
        service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        service.create(gitlab.baseUrl(), TOKEN, "project", PROJECT_PATH);
        UUID groupConn = groupConnId();
        UUID projectConn = projectConnId();
        repositoryService.select(projectConn, List.of(PROJECT_ID));
        service.delete(projectConn);

        // The group connection can no longer list its projects (token no longer authorized).
        gitlab.stubProjectsStatus(GROUP_ID, 401);
        assertThatThrownBy(() -> repositoryService.listProjects(groupConn, 0, 50))
                .isInstanceOf(GitLabException.class);

        Repository repo = onlyRepo();
        assertThat(repo.isActive()).isFalse();
        assertThat(repo.getInactiveReason()).isEqualTo("NO_ACCESSIBLE_CONNECTION");
    }

    @Test
    void reselectingADeactivatedRepositoryReattachesItToTheSelectingConnection() {
        // Select via a project connection, then remove it with no replacement -> repo detached + inactive.
        service.create(gitlab.baseUrl(), TOKEN, "project", PROJECT_PATH);
        UUID projectConn = projectConnId();
        repositoryService.select(projectConn, List.of(PROJECT_ID));
        service.delete(projectConn);
        Repository detached = onlyRepo();
        assertThat(detached.isActive()).isFalse();
        assertThat(detached.getConnectionId()).isNull();

        // A new group connection exposes the project; selecting it reattaches the row (not a new one).
        service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        UUID groupConn = groupConnId();
        repositoryService.select(groupConn, List.of(PROJECT_ID));

        Repository repo = onlyRepo();
        assertThat(repo.isActive()).isTrue();
        assertThat(repo.getConnectionId()).isEqualTo(groupConn);
        assertThat(repo.getInactiveReason()).isNull();
        assertThat(repo.isProvisional()).isFalse();
    }

    @Test
    void reassignmentTieBreakPrefersMostRecentlyVerifiedAndTreatsNullAsOldest() {
        // Three group connections with the same namespace path (distinct ids). The owner A is removed; B and C
        // are equally specific prefixes of the repo path, so the tie breaks on last_verified_at: C (set) beats
        // B (null = oldest).
        UUID a = insertGroupConnection(40L, "services", Instant.now().minusSeconds(3600));
        UUID b = insertGroupConnection(41L, "services", null);
        UUID c = insertGroupConnection(42L, "services", Instant.now());
        insertActiveRepository(a, PROJECT_ID, "services/ib-keycloak");

        var result = service.delete(a);

        assertThat(result.reassigned()).isEqualTo(1);
        Repository repo = onlyRepo();
        assertThat(repo.getConnectionId()).isEqualTo(c);
        assertThat(repo.getConnectionId()).isNotEqualTo(b);
    }

    @Test
    void deletingAMissingConnectionIsRejected() {
        assertThatThrownBy(() -> service.delete(UUID.randomUUID()))
                .isInstanceOf(GitLabException.class)
                .extracting(e -> ((GitLabException) e).getError())
                .isEqualTo(GitLabError.CONNECTION_NOT_FOUND);
    }

    private UUID insertGroupConnection(long groupId, String fullPath, Instant lastVerifiedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into gitlab_connection "
                        + "(id, workspace_id, base_url, target_type, group_full_path, gitlab_group_id, "
                        + "token_ciphertext, last_verified_at) "
                        + "values (?, ?, 'http://localhost', 'group', ?, ?, 'ct', ?)",
                id, Workspace.DEFAULT_ID, fullPath, groupId,
                lastVerifiedAt == null ? null : java.sql.Timestamp.from(lastVerifiedAt));
        return id;
    }

    private void insertActiveRepository(UUID connectionId, long projectId, String pathWithNamespace) {
        jdbc.update("insert into repository "
                        + "(id, workspace_id, connection_id, gitlab_project_id, path_with_namespace, "
                        + "default_branch) values (?, ?, ?, ?, ?, 'main')",
                UUID.randomUUID(), Workspace.DEFAULT_ID, connectionId, projectId, pathWithNamespace);
    }
}
