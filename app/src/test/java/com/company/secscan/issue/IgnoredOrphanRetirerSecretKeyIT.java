package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * S-2 (retirer path): the OD-19 one-off retire pass skips a secret fingerprinted under a replaced key id (D-029)
 * — a superseded identity whose absence cannot be trusted — while still retiring a current-key secret. Adds the
 * fingerprint key to the api context so {@code SecretFingerprinterHolder.isConfigured()} is true.
 */
class IgnoredOrphanRetirerSecretKeyIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String RULE = "private-key";

    @DynamicPropertySource
    static void fingerprintKey(DynamicPropertyRegistry registry) {
        registry.add("SECSCAN_FINGERPRINT_KEY", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired IgnoredOrphanRetirer retirer;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired IssueGroupRepository groups;
    @Autowired SubIssueRepository subIssues;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;
    @Autowired SecretFingerprinterHolder fingerprinter;

    @Test
    void aStaleKeySecretIsSkippedWhileCurrentKeySecretIsRetired() {
        String currentKey = fingerprinter.require().keyId();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repo.getId(), "secret",
                Fingerprints.groupKey("secret", RULE), "Exposed " + RULE, null));
        UUID ruleId = ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo.getId(), RULE,
                "accepted", "user")).getId();

        SubIssue stale = seedIgnoredSecret(group, repo.getId(), "a/stale.pem", "hs", "OLD-KEY-ID", ruleId);
        SubIssue live = seedIgnoredSecret(group, repo.getId(), "b/live.pem", "hl", currentKey, ruleId);

        // A reconciled, authoritative secret scan on the effective branch with NEITHER secret present (absent).
        Scan scan = Scan.manual(WORKSPACE, repo.getId(), "main");
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan.markReconciled();
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, ScanTask.STATUS_OK, 0, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);

        IgnoredOrphanRetirer.RetireResult dry = retirer.retire(true);
        assertThat(dry.candidates()).isEqualTo(1);
        assertThat(dry.candidateList()).singleElement()
                .satisfies(c -> assertThat(c.subIssueId()).isEqualTo(live.getId()));

        assertThat(retirer.retire(false).retired()).isEqualTo(1);
        assertThat(subIssues.findById(stale.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_IGNORED);   // frozen key → preserved
        assertThat(subIssues.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_SOLVED);
    }

    private SubIssue seedIgnoredSecret(IssueGroup group, UUID repoId, String path, String hash, String keyId,
                                       UUID ruleId) {
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "secret", ScanTask.SCANNER_SECRET,
                Fingerprints.secret(RULE, path, hash), null);
        sub.setRuleId(RULE);
        sub.setFilePath(path);
        sub.setSecretHash(hash);
        sub.setSecretPreview("****");
        sub.setKeyId(keyId);
        sub.ignore(com.company.secscan.triage.ReasonCode.IGNORE_RULE.code(), ruleId);
        return subIssues.save(sub);
    }
}
