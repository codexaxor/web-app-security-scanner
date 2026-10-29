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
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.8 SF-5b: a token-credentialed connection whose stored ciphertext is blank/missing is a broken connection —
 * the worker fails the scan PERMANENTLY (CONNECTION_TOKEN_MISSING) before any clone, retries nothing, reconciles
 * nothing. An already-open finding is untouched and no triage_event is written (domain rule 3).
 */
class MissingTokenScanIT extends AbstractScanWorkerIT {

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

    @Test
    void aTokenConnectionWithABlankCiphertextFailsPermanentlyAndReconcilesNothing() {
        // credential_kind='token' (the forGroup default) with an EMPTY ciphertext — the DB check allows "" (not
        // null), and tokenFor() treats it as a missing credential. https://localhost passes the clone-host gate.
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "https://localhost", "svc", 9501L, "", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WS, conn.getId(), 9501L, "svc/app", "main",
                List.of("Java")));

        // An already-open finding that must NOT change status (no reconcile runs).
        IssueGroup group = groups.save(new IssueGroup(WS, repo.getId(), "sca",
                "sca|CVE-2022-0001|pom.xml", "Open finding", null));
        SubIssue open = subIssues.save(new SubIssue(WS, group.getId(), repo.getId(), "sca",
                ScanTask.SCANNER_SCA, "fp-open", null));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo("CONNECTION_TOKEN_MISSING");
        assertThat(finished.getReconciledAt()).isNull();                         // nothing reconciled
        // The open finding is untouched and wrote no triage_event.
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WS, open.getId())).isEmpty();
    }
}
