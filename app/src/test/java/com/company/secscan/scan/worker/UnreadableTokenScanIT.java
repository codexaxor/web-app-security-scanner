package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanService;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.source.UnreadableCredentialException;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * M8.10 S12: a token-credentialed connection whose stored value cannot be decrypted (rotated key / damaged value).
 * A manual scan start still queues the scan (its default-branch refresh is best-effort, as for a missing token),
 * and the scan also covers a key that changes AFTER the scan was queued. The worker
 * fails that scan PERMANENTLY with {@code CONNECTION_TOKEN_UNREADABLE} before any clone, does not retry, and
 * reconciles nothing: an already-open finding stays open and no triage_event is written (domain rule 3 —
 * a failed scanner closes nothing).
 */
class UnreadableTokenScanIT extends AbstractScanWorkerIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void aManualStartWithAnUnreadableTokenIsQueuedAndTheWorkerFailsItPermanently() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "https://localhost", "svc2", 9602L, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WS, conn.getId(), 9602L, "svc2/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(WS, repo.getId());     // tolerated: queued, not a 500
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo(UnreadableCredentialException.CODE);
        assertThat(finished.getReconciledAt()).isNull();
    }

    @Test
    void aTokenThatBecameUnreadableAfterEnqueueFailsTheScanPermanentlyAndClosesNothing() {
        // Enqueue while the credential is merely missing (enqueue tolerates that), then make the stored value
        // unreadable — as a rotated SECSCAN_ENCRYPTION_KEY would — before the worker picks the job up.
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "https://localhost", "svc", 9601L, "", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WS, conn.getId(), 9601L, "svc/app", "main",
                List.of("Java")));
        IssueGroup group = groups.save(new IssueGroup(WS, repo.getId(), "sca",
                "sca|CVE-2022-0002|pom.xml", "Open finding", null));
        SubIssue open = subIssues.save(new SubIssue(WS, group.getId(), repo.getId(), "sca",
                ScanTask.SCANNER_SCA, "fp-open-unreadable", null));

        ScanView scan = scanService.startManual(WS, repo.getId());
        jdbc.update("update gitlab_connection set token_ciphertext = ? where id = ?",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", conn.getId());   // well-formed base64, wrong key

        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo(UnreadableCredentialException.CODE);
        assertThat(finished.getReconciledAt()).isNull();
        assertThat(jdbc.queryForObject("select state from scan_job where scan_id = ?", String.class, scan.id()))
                .isEqualTo("failed");                                            // permanent: not re-queued
        assertThat(claimer.claimBatch(10)).isEmpty();
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WS, open.getId())).isEmpty();
    }
}
