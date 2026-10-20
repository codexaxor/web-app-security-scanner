package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * Slice-0 hardening (point 2c): when the scanner container times out and is killed, the {@code timed_out}
 * scan_task status (defined in V6 but never written until now) is recorded on a terminal timeout — so the
 * outcome is auditable and, per domain rule 3, a timed-out scanner never closes its findings. A non-terminal
 * timeout requeues and writes no task (the retry produces its own). The launcher is mocked to report a timeout,
 * so no real container runs.
 */
class ScannerTimeoutIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

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
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;

    private Repository seedLocalRepo() {
        // A local file:// connection: no token decrypt and no host check, so the test exercises only the
        // timeout path. The launcher is mocked, so the path need not exist.
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file:///tmp/secscan-fixture", "svc/app", 101L, "ct", "read_api,read_repository",
                null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    @Test
    void aTerminalTimeoutRecordsATimedOutTaskClosesNothingAndFailsTheScan() {
        when(launcher.run(any())).thenReturn(new ScannerLauncher.LaunchResult(null, true, "stub log"));
        Repository repo = seedLocalRepo();

        // An already-open secret issue whose scanner (secret) is the one that times out: it must stay open.
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repo.getId(), "secret",
                "secret|aws-access-token", "Exposed aws-access-token", null));
        SubIssue open = new SubIssue(WORKSPACE, group.getId(), repo.getId(), "secret", ScanTask.SCANNER_SECRET,
                "fp-open", null);
        open.setRuleId("aws-access-token");
        open = subIssues.save(open);

        ScanView scan = scanService.startManual(repo.getId());
        ScanJob job = jobs.findByScanId(scan.id()).orElseThrow();
        for (int i = 0; i < ScanJob.MAX_ATTEMPTS; i++) {
            job.lease("worker");
        }
        jobs.save(job);

        processor.process(job.getId());

        Scan failed = scans.findById(scan.id()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(failed.getFailReason()).isEqualTo("SCANNER_TIMEOUT");
        assertThat(jobs.findById(job.getId()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_FAILED);
        // One disposable container runs all scanners, so a timeout records a timed_out task for each (M6: the
        // image now also runs Opengrep (SAST), Checkov (IaC) and the EOL detector).
        assertThat(tasks.findByScanId(scan.id()))
                .allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_TIMED_OUT))
                .extracting(ScanTask::getScanner)
                .containsExactlyInAnyOrder(ScanTask.SCANNER_SECRET, ScanTask.SCANNER_SCA,
                        ScanTask.SCANNER_SAST, ScanTask.SCANNER_IAC, ScanTask.SCANNER_EOL);
        assertThat(findings.findByScanId(scan.id())).isEmpty();

        // Domain rule 3: a timed-out scanner closes nothing — the open issue is untouched and wrote no event.
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, open.getId())).isEmpty();
    }

    @Test
    void aNonTerminalTimeoutRequeuesWithoutWritingATask() {
        when(launcher.run(any())).thenReturn(new ScannerLauncher.LaunchResult(null, true, "stub log"));
        Repository repo = seedLocalRepo();

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10); // one lease, attempts = 1 (< MAX_ATTEMPTS)
        assertThat(claimed).hasSize(1);

        processor.process(claimed.get(0));

        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_QUEUED);
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_QUEUED);
        // No stale task on a scan that will be retried.
        assertThat(tasks.findByScanId(scan.id())).isEmpty();
    }
}
