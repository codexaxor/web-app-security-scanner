package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 gate (D-062): a SAST ignore survives a snippet re-fingerprint. Ignore a SAST finding, then edit the matched
 * snippet (new snippet hash → new fingerprint) and rescan — the finding stays ignored because a SAST subissue
 * ignore matches on (rule_id, file_path), snippet-independent. No open SAST finding remains for that rule+file.
 */
class SastIgnoreChurnIT extends TriageSeedSupport {

    @Autowired TriageService triage;
    @Autowired ReconcileService reconcile;

    @Test
    void ignoredSastStaysIgnoredAfterTheSnippetIsEdited() {
        UUID repo = seedRepo();

        UUID scan1 = seedScan(repo);
        seedSast(scan1, repo, "opengrep-rules.custom.csrf", "src/Security.java", "hash-before");
        reconcile.reconcile(scan1, WORKSPACE);
        SubIssue before = byFile(repo, "src/Security.java");
        triage.ignoreSubIssue(before.getId(), "reviewed, accepted");
        assertThat(subBySnippet(repo, "hash-before").getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        // Edit the matched snippet -> new normalized-snippet hash -> NEW fingerprint for the same rule+file.
        UUID scan2 = seedScan(repo);
        seedSast(scan2, repo, "opengrep-rules.custom.csrf", "src/Security.java", "hash-after");
        reconcile.reconcile(scan2, WORKSPACE);

        // The re-fingerprinted finding is ignored by the same (rule_id, file_path) rule; nothing for that
        // rule+file is open.
        SubIssue after = subBySnippet(repo, "hash-after");
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(after.getIgnoreReason()).isEqualTo(com.company.secscan.triage.ReasonCode.IGNORE_RULE.code());
        List<SubIssue> openForRuleFile = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> "src/Security.java".equals(s.getFilePath()))
                .filter(s -> SubIssue.STATUS_OPEN.equals(s.getStatus()))
                .toList();
        assertThat(openForRuleFile).isEmpty();
    }

    private SubIssue subBySnippet(UUID repo, String snippetHash) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> snippetHash.equals(s.getSnippetHash())).findFirst().orElseThrow();
    }
}
