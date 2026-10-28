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
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S-2 (reconcile path): the OD-19 same-branch retire ({@link TriageApplier}) must NOT close a FROZEN identity —
 * a secret sub-issue fingerprinted under a replaced key id (D-029). Even when it is ignored by a re-derivable
 * rule and absent from an authoritative secret scan, it stays ignored; a live-key secret in the same scan is
 * solved, proving the freeze is what spares it.
 */
class FrozenSecretReconcileOrphanIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String RULE = "private-key";

    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired IssueGroupRepository groups;
    @Autowired SubIssueRepository subIssues;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;
    @Autowired SecretFingerprinterHolder fingerprinter;

    private Repository seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main", List.of("Java")));
    }

    private IssueGroup seedGroup(UUID repoId) {
        return groups.save(new IssueGroup(WORKSPACE, repoId, "secret", Fingerprints.groupKey("secret", RULE),
                "Exposed " + RULE, null));
    }

    /** An ignored (IGNORE_RULE) secret sub-issue at {@code path}/{@code hash}, under {@code keyId}, held by a rule. */
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

    @Test
    void frozenSecretStaysIgnoredWhileLiveSecretIsSolved() {
        String currentKey = fingerprinter.require().keyId();
        Repository repo = seedRepo();
        IssueGroup group = seedGroup(repo.getId());
        // A rule-scope ignore on the secret rule id — structural, re-derivable (never a sub-issue-scope ignore).
        UUID ruleId = ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo.getId(), RULE,
                "accepted", "user")).getId();
        SubIssue frozen = seedIgnoredSecret(group, repo.getId(), "a/stale.pem", "hashstale", "OLD-KEY-ID", ruleId);
        SubIssue live = seedIgnoredSecret(group, repo.getId(), "b/live.pem", "hashlive", currentKey, ruleId);

        // A secret scan that succeeds + closes findings, on the same branch, with NEITHER secret present (absent).
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId(), "main"));
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SECRET, ScanTask.STATUS_OK, 0, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);

        reconcile.reconcile(scan.getId(), WORKSPACE);

        // The stale-key secret is frozen → never closed; the current-key secret is solved (re-derivable, absent).
        assertThat(subIssues.findById(frozen.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(subIssues.findById(live.getId()).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_SOLVED);
    }
}
