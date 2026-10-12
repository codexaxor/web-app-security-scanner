package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A project reachable through two connections is still stored once; the first connection keeps ownership. */
class DedupAcrossConnectionsIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long PROJECT_ID = 102L;

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
        gitlab = new GitLabWireMock();
        gitlab.stubVersion().stubTokenSelf("token-self.json")
                .stubGroup("services")
                .stubProject("services%2Fib-keycloak")
                .stubProjectsFromSample(42L)
                .stubLanguages(PROJECT_ID, "{\"Java\":100.0}");
        connectionService.create(gitlab.baseUrl(), TOKEN, "group", "services");
        connectionService.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    private UUID groupConnId() {
        return connections.findByWorkspaceIdAndGitlabGroupId(Workspace.DEFAULT_ID, 42L).orElseThrow().getId();
    }

    private UUID projectConnId() {
        return connections.findByWorkspaceIdAndGitlabProjectId(Workspace.DEFAULT_ID, PROJECT_ID).orElseThrow()
                .getId();
    }

    @Test
    void sameProjectSelectedViaTwoConnectionsIsStoredOnceAndKeepsFirstOwner() {
        UUID groupConn = groupConnId();
        UUID projectConn = projectConnId();

        repositoryService.select(groupConn, List.of(PROJECT_ID));
        repositoryService.select(projectConn, List.of(PROJECT_ID));

        List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(stored).hasSize(1);
        // First connection to select it (the group) keeps ownership (D-020).
        assertThat(stored.get(0).getConnectionId()).isEqualTo(groupConn);
        assertThat(stored.get(0).getGitlabProjectId()).isEqualTo(PROJECT_ID);
    }

    @Test
    void concurrentSelectOfTheSameProjectStoresItOnceWithoutError() throws Exception {
        // Two connections select the same not-yet-stored project at once. The loser of the
        // uq_repository_workspace_project race must recover (reattach) rather than 500, and exactly one row must
        // exist, still owned by the winning connection (first-wins).
        UUID groupConn = groupConnId();
        UUID projectConn = projectConnId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<List<RepositoryView>> viaGroup = () -> {
            start.await();
            return repositoryService.select(groupConn, List.of(PROJECT_ID));
        };
        Callable<List<RepositoryView>> viaProject = () -> {
            start.await();
            return repositoryService.select(projectConn, List.of(PROJECT_ID));
        };
        try {
            Future<List<RepositoryView>> f1 = pool.submit(viaGroup);
            Future<List<RepositoryView>> f2 = pool.submit(viaProject);
            start.countDown();
            // Neither call throws (a 500 would surface here as an ExecutionException).
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);

            List<Repository> stored = repositories.findByWorkspaceId(Workspace.DEFAULT_ID);
            assertThat(stored).hasSize(1);
            assertThat(stored.get(0).getConnectionId()).isIn(groupConn, projectConn);
        } finally {
            pool.shutdownNow();
        }
    }
}
