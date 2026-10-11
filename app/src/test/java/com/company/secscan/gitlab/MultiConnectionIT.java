package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
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

/** A workspace may hold several connections at once: a whole group plus single projects. */
class MultiConnectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";

    @Autowired
    GitLabConnectionService service;
    @Autowired
    GitlabConnectionRepository connections;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
        gitlab.stubVersion().stubTokenSelf("token-self.json")
                .stubGroup("services")
                .stubProject("services%2Fib-keycloak");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void aGroupAndAProjectConnectionCoexist() {
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "group", "services").ok()).isTrue();
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak").ok()).isTrue();

        List<GitlabConnection> all = connections.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(GitlabConnection::isGroup);
        assertThat(all).anyMatch(GitlabConnection::isProject);
        assertThat(service.list()).hasSize(2);
    }

    @Test
    void creatingTheSameProjectTwiceIsRejected() {
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak").ok()).isTrue();

        var second = service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak");

        assertThat(second.ok()).isFalse();
        assertThat(second.errorCode()).isEqualTo(GitLabError.CONNECTION_EXISTS.name());
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
    }

    @Test
    void concurrentDuplicateCreateYieldsOneConnectionAndACleanError() throws Exception {
        // Two creates of the same target race. The unique index lets exactly one win; the other must come back
        // as CONNECTION_EXISTS (caught DataIntegrityViolationException), never a 500, and never a second row.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<ConnectionTestResult> create = () -> {
            start.await();
            return service.create(gitlab.baseUrl(), TOKEN, "group", "services");
        };
        try {
            Future<ConnectionTestResult> f1 = pool.submit(create);
            Future<ConnectionTestResult> f2 = pool.submit(create);
            start.countDown();
            List<ConnectionTestResult> results = List.of(f1.get(10, TimeUnit.SECONDS),
                    f2.get(10, TimeUnit.SECONDS));

            assertThat(results).filteredOn(ConnectionTestResult::ok).hasSize(1);
            assertThat(results).filteredOn(r -> !r.ok()).singleElement()
                    .satisfies(r -> assertThat(r.errorCode()).isEqualTo(GitLabError.CONNECTION_EXISTS.name()));
            assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
