package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.crypto.SecretFingerprinter;
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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the secrets-engine swap (Gitleaks 8.21.2 -> Betterleaks 1.7.3, D-036) does NOT re-fingerprint stored
 * secrets, so it never mass-solves/reopens them (D-029). The secret group key and fingerprint are both derived
 * from the rule id; probing both engines against the fixture token shows the rule id is IDENTICAL
 * ({@code github-pat} == {@code github-pat}), so no id-map is needed. This test seeds a PRE-SWAP open secret
 * (the row a Gitleaks-era scan would have stored) and then reconciles a scan whose Betterleaks finding has the
 * same (rule id, path, keyed hash). The fingerprint matches, the sub-issue stays open, and reconcile writes
 * ZERO triage events — the whole point of the "freeze".
 */
class SecretEngineSwapReconcileIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String RULE = "github-pat";
    private static final String PATH = "config/application.properties";
    // The keyed HMAC the worker stores for the fixture token under the test's all-zero 32-byte key (D-027);
    // identical whether Gitleaks or Betterleaks produced the finding, because the raw value is the same.
    private static final String STORED_HASH =
            "3de3e8dc1462110d0002325c561e0b4369fd8c28c842069bdb204a5e2d4242b5";

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

    @Test
    void engineSwapReFingerprintsNothingAndWritesNoTriageEvents() {
        // The test key id (first 8 hex of HMAC(key,"secscan-key-id")) under the all-zero key.
        String keyId = SecretFingerprinter.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])).keyId();

        Repository repo = seedRepository();

        // PRE-SWAP: the open secret sub-issue a Gitleaks-era scan would have stored. keyId = current key, so
        // reconcile treats it as live (not frozen) and would solve/reopen it if the fingerprint diverged.
        SubIssue preSwap = seedOpenSecret(repo.getId(), keyId);

        // POST-SWAP: a completed scan whose Betterleaks secret finding has the SAME (rule id, path, keyed hash).
        UUID scanId = seedScanWithSecretFinding(repo.getId());

        reconcile.reconcile(scanId, WORKSPACE);

        SubIssue after = subIssues.findById(preSwap.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(after.getFingerprint()).isEqualTo(preSwap.getFingerprint());
        // The whole point of the freeze: the engine swap wrote NO triage event.
        assertThat(events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, preSwap.getId())).isEmpty();
        // Exactly one open secret sub-issue remains — no duplicate was opened under a different fingerprint.
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .filteredOn(s -> "secret".equals(s.getType()))
                .singleElement()
                .satisfies(s -> assertThat(s.getStatus()).isEqualTo(SubIssue.STATUS_OPEN));
    }

    private Repository seedRepository() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
    }

    private SubIssue seedOpenSecret(UUID repoId, String keyId) {
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repoId, "secret",
                Fingerprints.groupKey("secret", RULE), "Exposed " + RULE, null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "secret", ScanTask.SCANNER_SECRET,
                Fingerprints.secret(RULE, PATH, STORED_HASH), null);
        sub.setRuleId(RULE);
        sub.setFilePath(PATH);
        sub.setSecretHash(STORED_HASH);
        sub.setSecretPreview("ghp_");
        sub.setKeyId(keyId);
        return subIssues.save(sub);
    }

    private UUID seedScanWithSecretFinding(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, ScanTask.STATUS_OK, 2, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        // The keyed hash is stored directly here (the worker's ScanStateService would have applied the HMAC).
        scanFindings.save(ScanFinding.secret(WORKSPACE, scan.getId(), repoId, RULE, PATH, 4, STORED_HASH,
                "ghp_", null));
        return scan.getId();
    }
}
