package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reconcile edge cases driven by seeding rows directly (no scanner container): a failed scanner closes nothing
 * (M3 done-when 4), the stale guard (plan addition 2) and the secret fingerprint-key guard (plan addition 5).
 * The full open/solved/reopened lifecycle against the real container is {@link ReconcileLifecycleIT}.
 */
class ReconcileServiceIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ReconcileService reconcile;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    ScanFindingRepository scanFindings;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    SecretFingerprinterHolder fingerprinter;

    private Repository seedRepository() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    private SubIssue seedOpenSecret(UUID repoId, String fingerprint, String keyId) {
        return seedSecret(seedGroup(repoId), repoId, fingerprint, keyId, SubIssue.STATUS_OPEN);
    }

    private IssueGroup seedGroup(UUID repoId) {
        return groups.save(new IssueGroup(WORKSPACE, repoId, "secret", "secret|aws-access-token",
                "Exposed aws-access-token", null));
    }

    private SubIssue seedSecret(IssueGroup group, UUID repoId, String fingerprint, String keyId, String status) {
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "secret", "secret", fingerprint, null);
        sub.setRuleId("aws-access-token");
        sub.setKeyId(keyId);
        if (SubIssue.STATUS_SOLVED.equals(status)) {
            sub.solve();
        }
        return subIssues.save(sub);
    }

    /** Saves a finished scan with one secret task of the given status and no findings; returns its id. */
    private UUID seedScanWithSecretTask(UUID repoId, String taskStatus, String scanStatus) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(scanStatus);
        scan = scans.save(scan);
        tasks.save(new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, taskStatus, 0, 5L, null));
        return scan.getId();
    }

    /** A completed scan with an OK secret task and one secret finding (so reconcile has an incoming match). */
    private UUID seedScanWithSecretFinding(UUID repoId, String ruleId, String filePath, String secretHash) {
        UUID scanId = seedScanWithSecretTask(repoId, ScanTask.STATUS_OK, Scan.STATUS_COMPLETED);
        scanFindings.save(ScanFinding.secret(WORKSPACE, scanId, repoId, ruleId, filePath, 1, secretHash,
                "****", null));
        return scanId;
    }

    @Test
    void aFailedSecretScannerClosesNothing() {
        Repository repo = seedRepository();
        SubIssue open = seedOpenSecret(repo.getId(), "fp-keep", fingerprinter.require().keyId());
        // A scan where the secret scanner FAILED (partial): the finding is absent but must not be solved.
        UUID scanId = seedScanWithSecretTask(repo.getId(), ScanTask.STATUS_FAILED, Scan.STATUS_PARTIAL);

        reconcile.reconcile(scanId);

        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, open.getId())).isEmpty();
        assertThat(scans.findById(scanId).orElseThrow().getReconciledAt()).isNotNull();
    }

    @Test
    void anOkScannerSolvesAMissingOpenFinding() {
        // Control for the test above: with the scanner OK, the same missing finding IS solved.
        Repository repo = seedRepository();
        SubIssue open = seedOpenSecret(repo.getId(), "fp-gone", fingerprinter.require().keyId());
        UUID scanId = seedScanWithSecretTask(repo.getId(), ScanTask.STATUS_OK, Scan.STATUS_COMPLETED);

        reconcile.reconcile(scanId);

        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
    }

    @Test
    void aStaleScanDoesNotCloseFindingsWhenANewerScanIsAlreadyReconciled() throws InterruptedException {
        Repository repo = seedRepository();
        SubIssue open = seedOpenSecret(repo.getId(), "fp-stale", fingerprinter.require().keyId());

        // Older scan A (will be reconciled last, out of order); newer scan B already reconciled. A short gap
        // guarantees B.created_at (DB default now()) is strictly greater than A's.
        UUID olderA = seedScanWithSecretTask(repo.getId(), ScanTask.STATUS_OK, Scan.STATUS_COMPLETED);
        Thread.sleep(10);
        Scan newerB = scans.save(markReconciled(Scan.manual(WORKSPACE, repo.getId())));

        reconcile.reconcile(olderA);

        // A is stale (B is newer and reconciled), so it must not solve the open finding.
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(scans.findById(olderA).orElseThrow().getReconciledAt()).isNotNull();
        assertThat(newerB.getReconciledAt()).isNotNull();
    }

    @Test
    void aChangedFingerprintKeyDoesNotSolveSecretFindings() {
        Repository repo = seedRepository();
        // Existing secret sub-issue was fingerprinted under a DIFFERENT key id than the current one.
        SubIssue open = seedOpenSecret(repo.getId(), "fp-oldkey", "oldkeyid");
        assertThat(fingerprinter.require().keyId()).isNotEqualTo("oldkeyid");
        UUID scanId = seedScanWithSecretTask(repo.getId(), ScanTask.STATUS_OK, Scan.STATUS_COMPLETED);

        reconcile.reconcile(scanId);

        // The key changed, so the (re-fingerprinted) secret must NOT be mass-solved (D-029).
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void afterAKeyChangeCurrentKeySecretsSolveAndReopenWhileOldKeyRowsAreFrozen() {
        // D-029 refinement: a changed key does not freeze the whole secret scanner. Secrets under the current
        // key reconcile normally (solve + reopen); only rows under a superseded key id are left untouched.
        Repository repo = seedRepository();
        String currentKey = fingerprinter.require().keyId();
        IssueGroup group = seedGroup(repo.getId());

        // Old-key open row: superseded identity, must stay open with no event.
        SubIssue oldKey = seedSecret(group, repo.getId(), "fp-oldkey", "oldkeyid", SubIssue.STATUS_OPEN);
        // Current-key open row, absent from this scan: must be solved.
        SubIssue currentGone =
                seedSecret(group, repo.getId(), "fp-currentkey-gone", currentKey, SubIssue.STATUS_OPEN);
        // Current-key solved row whose finding is present this scan: must be reopened. Its fingerprint is the
        // one the incoming finding will produce (Fingerprints.secret(ruleId, filePath, secretHash)).
        String reopenFp = Fingerprints.secret("aws-access-token", "config/app.yml", "deadbeef");
        SubIssue currentReopen = seedSecret(group, repo.getId(), reopenFp, currentKey, SubIssue.STATUS_SOLVED);

        UUID scanId = seedScanWithSecretFinding(repo.getId(), "aws-access-token", "config/app.yml", "deadbeef");

        reconcile.reconcile(scanId);

        // Old-key row frozen: untouched, no triage event.
        assertThat(subIssues.findById(oldKey.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, oldKey.getId())).isEmpty();
        // Current-key rows reconcile normally.
        assertThat(subIssues.findById(currentGone.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_SOLVED);
        SubIssue reopened = subIssues.findById(currentReopen.getId()).orElseThrow();
        assertThat(reopened.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(reopened.getSolvedAt()).isNull();
    }

    private Scan markReconciled(Scan scan) {
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan.markReconciled();
        return scan;
    }
}
