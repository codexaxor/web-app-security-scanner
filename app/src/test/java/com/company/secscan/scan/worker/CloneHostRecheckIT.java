package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Slice-0 hardening (plan change #5): the worker re-checks the ACTUAL clone URL's host against the outbound
 * allowlist ({@code SECSCAN_GITLAB_ALLOWED_HOSTS}, "localhost" in these tests) at clone time — the api's
 * save-time check is not trusted. A repository whose connection points at a non-allowlisted host must fail the
 * scan permanently and never launch a scanner container (SSRF guard, D-018).
 */
class CloneHostRecheckIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    // Force the test-only file:// flag OFF here (it is ON in the base) so the file:// rejection path is
    // exercised. @DynamicPropertySource outranks the base @TestPropertySource.
    @DynamicPropertySource
    static void disableLocalFileClone(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.allow-local-file-clone", () -> "false");
    }

    @MockBean
    ScannerLauncher launcher;

    @Autowired
    com.company.secscan.scan.ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanJobRepository jobs;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void aNonAllowlistedCloneHostFailsPermanentlyAndNeverLaunches() {
        // evil.example.com is NOT in the allowlist; the ciphertext is never decrypted because the host check
        // runs first, so a dummy value is fine here.
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "https://evil.example.com", "svc/app", 101L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        // Permanent failure: scan failed with a persisted reason code, job failed (not requeued), nothing scanned.
        Scan failed = scans.findById(scan.id()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(failed.getFailReason()).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_FAILED);
        assertThat(findings.findByScanId(scan.id())).isEmpty();
        assertThat(tasks.findByScanId(scan.id())).isEmpty();
        // The container was never launched: the clone URL was rejected before any launch.
        verify(launcher, never()).run(any());
    }

    @Test
    void aFileCloneUrlWithoutTheTestFlagFailsPermanentlyAndNeverLaunches() {
        // file:// is permitted only behind secscan.scanner.allow-local-file-clone (OFF in this class). Without
        // it, a stored file:// URL is rejected exactly like a non-allowlisted host — never a local-path read.
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file:///tmp/secscan-fixture", "svc/app", 101L, "ct", "read_api,read_repository",
                null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan failed = scans.findById(scan.id()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(failed.getFailReason()).isEqualTo("HOST_NOT_ALLOWED");
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_FAILED);
        verify(launcher, never()).run(any());
    }
}
