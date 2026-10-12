package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScanLaunchStub;
import com.company.secscan.scan.worker.ScanStateService;
import com.company.secscan.scan.worker.ScannerLaunchSpec;
import com.company.secscan.scan.worker.ScannerLauncher;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * The queue mechanics and persistence, driven deterministically with a stubbed launcher (no Docker): claim +
 * lease, SKIP-LOCKED non-double-claim, success persistence, retry on a timed-out run, max-attempts failure, and
 * stale-lease reclaim. The real container is exercised by {@link ScanPipelineIT}.
 */
class ScanQueueIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String HASH = "91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d";

    @MockBean
    ScannerLauncher launcher;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanStateService state;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanJobRepository jobs;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    @BeforeEach
    void stubASuccessfulScannerByDefault() {
        Mockito.when(launcher.run(any())).thenAnswer(inv -> {
            ScannerLaunchSpec spec = inv.getArgument(0);
            ScanLaunchStub.writeSecretFinding(spec.outDir(), "deadbeefcommit", HASH, "AKIA");
            return new ScannerLauncher.LaunchResult(1, false, "stub log");
        });
    }

    private Repository seedRepository() {
        // A file:// connection means no token decryption and no reachability probe — the launcher is stubbed.
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file:///tmp/secscan-fixture", "svc/app", 101L, "ct", "read_api,read_repository",
                null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    @Test
    void successPersistsTheSecretFindingAndCompletesTheScan() {
        Repository repo = seedRepository();
        ScanView scan = scanService.startManual(repo.getId());

        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_RUNNING);

        processor.process(claimed.get(0));

        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
        assertThat(findings.findByScanId(scan.id())).singleElement().satisfies(f -> {
            assertThat(f.getType()).isEqualTo(ScanFinding.TYPE_SECRET);
            assertThat(f.getSecretHash()).isEqualTo(HASH);
            assertThat(f.getSecretPreview()).isEqualTo("AKIA");
        });
        assertThat(tasks.findByScanId(scan.id())).singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_OK));
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_DONE);
        assertThat(repositories.findById(repo.getId()).orElseThrow().getLastScannedSha())
                .isEqualTo("deadbeefcommit");
    }

    @Test
    void aLeasedJobIsNotClaimedAgain() {
        ScanView scan = scanService.startManual(seedRepository().getId());

        assertThat(claimer.claimBatch(10)).hasSize(1);
        assertThat(claimer.claimBatch(10)).isEmpty();
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getAttempts()).isEqualTo(1);
    }

    @Test
    void aTimedOutRunRequeuesTheJob() {
        // doReturn (not when/thenReturn) so the @BeforeEach answer is not re-invoked during re-stubbing.
        Mockito.doReturn(new ScannerLauncher.LaunchResult(null, true, "timed out"))
                .when(launcher).run(any());
        ScanView scan = scanService.startManual(seedRepository().getId());

        UUID jobId = claimer.claimBatch(10).get(0);
        processor.process(jobId);

        ScanJob job = jobs.findById(jobId).orElseThrow();
        assertThat(job.getState()).isEqualTo(ScanJob.STATE_QUEUED);
        assertThat(job.getAttempts()).isEqualTo(1);
        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_QUEUED);
    }

    @Test
    void aJobFailsAfterMaxAttempts() {
        ScanView scan = scanService.startManual(seedRepository().getId());
        ScanJob job = jobs.findByScanId(scan.id()).orElseThrow();
        for (int i = 0; i < ScanJob.MAX_ATTEMPTS; i++) {
            job.lease("worker");
        }
        jobs.save(job);

        state.failOrRequeue(job.getId(), scan.id(), "still failing");

        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_FAILED);
        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_FAILED);
    }

    @Test
    void aStaleLeaseIsReclaimed() {
        ScanView scan = scanService.startManual(seedRepository().getId());
        UUID jobId = claimer.claimBatch(10).get(0);

        // Negative age makes the just-taken lease count as expired for the sweep.
        claimer.sweepStaleLeases(Duration.ofSeconds(-1));

        assertThat(jobs.findById(jobId).orElseThrow().getState()).isEqualTo(ScanJob.STATE_QUEUED);
    }
}
