package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScannerLauncher;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M8.10 item 8 (D-097): at most ONE running scan per workspace. Further scans of that workspace wait in the queue
 * (never rejected) while other workspaces' scans are claimed; when the running one finishes, the next is
 * claimable. Concurrent claimers cannot both start a job for the same workspace (claims are serialised).
 * Claims only — the launcher is a mock and nothing is processed.
 */
class FairUseScanClaimIT extends AbstractScanWorkerIT {

    @MockBean
    ScannerLauncher launcher;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobRepository jobs;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    JdbcTemplate jdbc;

    private UUID repo(UUID workspaceId, long projectId) {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(workspaceId,
                "file:///tmp/secscan-fixture", "svc/app-" + projectId, projectId, "ct", "read_api,read_repository",
                null));
        return repositories.save(new Repository(workspaceId, conn.getId(), projectId, "svc/app-" + projectId,
                "main", List.of("Java"))).getId();
    }

    private UUID enqueue(UUID workspaceId, UUID repoId) {
        UUID scanId = scanService.startManual(workspaceId, repoId).id();
        return jobs.findByScanId(scanId).orElseThrow().getId();
    }

    @Test
    void oneRunningScanPerWorkspaceOthersWaitOtherWorkspacesProceed() {
        UUID a = Workspace.DEFAULT_ID;
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        UUID a1 = enqueue(a, repo(a, 201));
        UUID a2 = enqueue(a, repo(a, 202));
        UUID b1 = enqueue(b, repo(b, 203));

        List<UUID> first = claimer.claimBatch(10);
        assertThat(first).containsExactlyInAnyOrder(a1, b1);          // one per workspace, oldest first
        assertThat(state(a2)).isEqualTo(ScanJob.STATE_QUEUED);         // waits, not rejected

        assertThat(claimer.claimBatch(10)).as("A still has a running job").isEmpty();

        jdbc.update("update scan_job set state = 'done', locked_by = null, locked_at = null where id = ?", a1);
        assertThat(claimer.claimBatch(10)).containsExactly(a2);
    }

    @Test
    void concurrentClaimersNeverStartTwoJobsOfOneWorkspace() throws Exception {
        UUID a = Workspace.DEFAULT_ID;
        enqueue(a, repo(a, 211));
        enqueue(a, repo(a, 212));
        enqueue(a, repo(a, 213));

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<UUID>>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Callable<List<UUID>> claim = () -> {
                    start.await();
                    return claimer.claimBatch(10);
                };
                futures.add(pool.submit(claim));
            }
            start.countDown();
            int claimed = 0;
            for (Future<List<UUID>> f : futures) {
                claimed += f.get(30, TimeUnit.SECONDS).size();
            }
            assertThat(claimed).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from scan_job where workspace_id = ? and state = "
                    + "'running'", Long.class, a)).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }

    private String state(UUID jobId) {
        return jobs.findById(jobId).orElseThrow().getState();
    }
}
