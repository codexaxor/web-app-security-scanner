package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.10 inventory item 10: the worker's provisional-reassignment probe ({@code ScanJobProcessor} →
 * {@link RepositoryConnectionGuard}) used to scope to the Default workspace. It now acts only within the claimed
 * job's workspace: confirming/deactivating a workspace-B connection changes B's repositories, and the same call
 * scoped to Default touches nothing.
 */
class WorkerConnectionGuardWorkspaceIT extends AbstractGitLabIT {

    @Autowired
    RepositoryConnectionGuard guard;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void confirmActsOnlyWithinTheGivenWorkspace() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        UUID conn = connection(b);
        UUID repo = provisionalRepo(b, conn, 101);

        guard.confirm(Workspace.DEFAULT_ID, conn);          // the old (Default-scoped) behaviour: a no-op for B
        assertThat(repositories.findById(repo).orElseThrow().isProvisional()).isTrue();

        guard.confirm(b, conn);                             // the job's own workspace
        assertThat(repositories.findById(repo).orElseThrow().isProvisional()).isFalse();
    }

    @Test
    void deactivateActsOnlyWithinTheGivenWorkspace() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        UUID conn = connection(b);
        UUID repo = provisionalRepo(b, conn, 102);

        guard.deactivate(Workspace.DEFAULT_ID, conn);
        assertThat(repositories.findById(repo).orElseThrow().isActive()).isTrue();

        guard.deactivate(b, conn);
        Repository after = repositories.findById(repo).orElseThrow();
        assertThat(after.isActive()).isFalse();
        assertThat(after.getWorkspaceId()).isEqualTo(b);
    }

    private UUID connection(UUID workspaceId) {
        return connections.save(GitlabConnection.forProject(workspaceId, "http://localhost:1", "acme/app", 7L,
                "ciphertext", "read_api", null)).getId();
    }

    private UUID provisionalRepo(UUID workspaceId, UUID connectionId, long projectId) {
        Repository r = new Repository(workspaceId, connectionId, projectId, "acme/app-" + projectId, "main",
                List.of());
        r.reassignTo(connectionId);   // provisional until a use confirms access
        return repositories.save(r).getId();
    }
}
