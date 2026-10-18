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
 * from the rule id; probing both engines offline shows the rule id is IDENTICAL for the two secrets that matter
 * here — the fixture token ({@code github-pat} == {@code github-pat}) and, crucially, the RSA private key that
 * is the real open secret in {@code secscan-test-secrets} ({@code private-key} == {@code private-key}, matched
 * as the whole PEM block so the keyed hash is identical too) — so no id-map is needed. Each case seeds a
 * PRE-SWAP open secret (the row a Gitleaks-era scan stored) and reconciles a scan whose Betterleaks finding has
 * the same (rule id, path, keyed hash); the fingerprint matches, the sub-issue stays open, and reconcile writes
 * ZERO triage events — the whole point of the "freeze".
 */
class SecretEngineSwapReconcileIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    // Keyed HMACs under the test's all-zero 32-byte key (D-027); identical whichever engine produced the
    // finding, because the raw value and matched span are the same across the swap.
    private static final String TOKEN_HASH =
            "3de3e8dc1462110d0002325c561e0b4369fd8c28c842069bdb204a5e2d4242b5";
    private static final String PEM_HASH =
            "0000000000000000000000000000000000000000000000000000000000000abc";

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
    void tokenEngineSwapReFingerprintsNothingAndWritesNoTriageEvents() {
        // The fixture token: Gitleaks github-pat -> Betterleaks github-pat (equal id).
        assertSwapIsFingerprintSafe("github-pat", "config/application.properties", TOKEN_HASH, "ghp_");
    }

    @Test
    void privateKeyEngineSwapReFingerprintsNothingAndWritesNoTriageEvents() {
        // The real open secret in secscan-test-secrets (ARCHITECT_NOTES §9): an RSA private key. Betterleaks
        // v1.7.3 detects it offline under the SAME rule id as Gitleaks (private-key), matched as the whole PEM
        // block (so the keyed hash is identical too) — proven by the M4b probe and SecretPreviewLeakIT. The swap
        // must therefore re-fingerprint nothing.
        assertSwapIsFingerprintSafe("private-key", "config/deploy-key.pem", PEM_HASH, "****");
    }

    /**
     * Seeds a pre-swap open secret (the row a Gitleaks-era scan stored) and reconciles a completed scan whose
     * Betterleaks finding has the SAME (rule id, path, keyed hash); asserts the sub-issue stays open, keeps its
     * fingerprint, and that reconcile wrote ZERO triage events — no mass solve/reopen (D-029).
     */
    private void assertSwapIsFingerprintSafe(String rule, String path, String hash, String preview) {
        String keyId = SecretFingerprinter.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])).keyId();
        Repository repo = seedRepository();

        // PRE-SWAP: keyId = current key, so reconcile treats it as live (not frozen) and WOULD solve/reopen it
        // if the fingerprint diverged.
        SubIssue preSwap = seedOpenSecret(repo.getId(), keyId, rule, path, hash, preview);

        // POST-SWAP: a completed scan whose Betterleaks secret finding has the same (rule id, path, keyed hash).
        UUID scanId = seedScanWithSecretFinding(repo.getId(), rule, path, hash, preview);

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

    private SubIssue seedOpenSecret(UUID repoId, String keyId, String rule, String path, String hash,
                                    String preview) {
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repoId, "secret",
                Fingerprints.groupKey("secret", rule), "Exposed " + rule, null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "secret", ScanTask.SCANNER_SECRET,
                Fingerprints.secret(rule, path, hash), null);
        sub.setRuleId(rule);
        sub.setFilePath(path);
        sub.setSecretHash(hash);
        sub.setSecretPreview(preview);
        sub.setKeyId(keyId);
        return subIssues.save(sub);
    }

    private UUID seedScanWithSecretFinding(UUID repoId, String rule, String path, String hash, String preview) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, ScanTask.STATUS_OK, 2, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        // The keyed hash is stored directly here (the worker's ScanStateService would have applied the HMAC).
        scanFindings.save(ScanFinding.secret(WORKSPACE, scan.getId(), repoId, rule, path, 1, hash,
                preview, null));
        return scan.getId();
    }
}
