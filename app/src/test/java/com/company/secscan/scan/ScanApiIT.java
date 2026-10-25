package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/** Start Scan / listing behavior: one in-flight scan per repo, dedup on double-start, inactive repos rejected. */
class ScanApiIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ScanService service;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanJobRepository jobs;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    ScanEnqueuer enqueuer;

    private Repository seedRepository() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    @Test
    void startCreatesAQueuedScanAndJob() {
        Repository repo = seedRepository();

        ScanView scan = service.startManual(repo.getId());

        assertThat(scan.status()).isEqualTo(Scan.STATUS_QUEUED);
        assertThat(scan.trigger()).isEqualTo(Scan.TRIGGER_MANUAL);
        ScanJob job = jobs.findByScanId(scan.id()).orElseThrow();
        assertThat(job.getState()).isEqualTo(ScanJob.STATE_QUEUED);
        assertThat(job.getAttempts()).isZero();
    }

    @Test
    void startSnapshotsTheEffectiveBranchOntoTheScan() {
        Repository repo = seedRepository();   // default_branch "main", no scan_branch

        ScanView scan = service.startManual(repo.getId());

        assertThat(scan.branch()).isEqualTo("main");
        assertThat(scans.findById(scan.id()).orElseThrow().getBranch()).isEqualTo("main");
    }

    @Test
    void startSnapshotsTheConfiguredScanBranch() {
        Repository repo = seedRepository();
        repo.setScanBranch("dev");            // D-076: overrides the default
        repositories.save(repo);

        ScanView scan = service.startManual(repo.getId());

        assertThat(scan.branch()).isEqualTo("dev");
        assertThat(scans.findById(scan.id()).orElseThrow().getBranch()).isEqualTo("dev");
    }

    @Test
    void findByWorkspaceIdAndIdInIsWorkspaceScoped() {   // B-1
        Repository repo = seedRepository();
        ScanView scan = service.startManual(repo.getId());

        assertThat(scans.findByWorkspaceIdAndIdIn(WORKSPACE, List.of(scan.id())))
                .extracting(Scan::getId).containsExactly(scan.id());
        assertThat(scans.findByWorkspaceIdAndIdIn(UUID.randomUUID(), List.of(scan.id()))).isEmpty();
    }

    @Test
    void recordDefaultBranchUpdatesOnlyThatColumn() {   // SF-1
        Repository repo = seedRepository();
        repo.setSensitivity("high");
        repositories.save(repo);

        enqueuer.recordDefaultBranch(repo.getId(), "release");

        Repository after = repositories.findById(repo.getId()).orElseThrow();
        assertThat(after.getDefaultBranch()).isEqualTo("release");
        assertThat(after.getSensitivity()).isEqualTo("high");   // not clobbered by a whole-entity save
    }

    @Test
    void startingAgainReturnsTheSameInFlightScan() {
        Repository repo = seedRepository();

        ScanView first = service.startManual(repo.getId());
        ScanView second = service.startManual(repo.getId());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(scans.findByWorkspaceIdAndRepositoryIdAndStatusIn(
                WORKSPACE, repo.getId(), List.of(Scan.STATUS_QUEUED, Scan.STATUS_RUNNING))).hasSize(1);
    }

    @Test
    void inactiveRepositoryCannotBeScanned() {
        Repository repo = seedRepository();
        repo.deactivate("NO_ACCESSIBLE_CONNECTION");
        repositories.save(repo);

        assertThatThrownBy(() -> service.startManual(repo.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("inactive");
    }

    @Test
    void listAndDetailReflectTheScan() {
        Repository repo = seedRepository();
        ScanView scan = service.startManual(repo.getId());

        assertThat(service.listForRepository(repo.getId(), 0, 50).content())
                .extracting(ScanView::id).containsExactly(scan.id());

        var detail = service.get(scan.id());
        assertThat(detail.scan().id()).isEqualTo(scan.id());
        assertThat(detail.tasks()).isEmpty();
        assertThat(detail.findingCount()).isZero();
    }
}
