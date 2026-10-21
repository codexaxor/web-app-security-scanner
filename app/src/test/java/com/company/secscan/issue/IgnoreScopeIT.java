package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.triage.ReasonCode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * S5 gate (D-058): every manual ignore scope moves the matching finding(s) to {@code ignored} with reason code
 * IGNORE_RULE and a user {@code triage_event}, and records the holding rule. One test per scope: subissue, group,
 * path, cve, rule.
 */
class IgnoreScopeIT extends TriageSeedSupport {

    @Autowired TriageService triage;
    @Autowired TriageEventRepository events;
    @Autowired IgnoreRuleRepository ignoreRules;
    @Autowired ReconcileService reconcile;
    @Autowired IssueGroupRepository groups;

    @Test
    void ignoresASubIssue() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        reconcile.reconcile(scan, WORKSPACE);
        SubIssue sub = byVuln(repo, "CVE-2022-42889");

        triage.ignoreSubIssue(sub.getId(), "false positive");

        assertIgnoredByRule(byVuln(repo, "CVE-2022-42889"));
    }

    @Test
    void ignoresAWholeGroup() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        // Two CVEs on one package => one SCA group.
        seedSca(scan, repo, "CVE-2100-1", "org.apache.commons:commons-text", "pom.xml");
        seedSca(scan, repo, "CVE-2100-2", "org.apache.commons:commons-text", "pom.xml");
        reconcile.reconcile(scan, WORKSPACE);
        UUID groupId = byVuln(repo, "CVE-2100-1").getGroupId();

        triage.ignoreGroup(groupId, "whole package accepted");

        assertIgnoredByRule(byVuln(repo, "CVE-2100-1"));
        assertIgnoredByRule(byVuln(repo, "CVE-2100-2"));
    }

    @Test
    void ignoresByPathPrefixPerRepo() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSast(scan, repo, "opengrep-rules.custom.x", "src/test/java/FooTest.java", "h1");
        seedSast(scan, repo, "opengrep-rules.custom.y", "src/main/java/Foo.java", "h2");
        reconcile.reconcile(scan, WORKSPACE);

        triage.createRule(IgnoreRule.SCOPE_PATH, repo, "src/test", "test code");

        assertIgnoredByRule(byFile(repo, "src/test/java/FooTest.java"));
        assertThat(byFile(repo, "src/main/java/Foo.java").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void ignoresByCveGlobally() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSca(scan, repo, "CVE-2022-42889", "org.apache.commons:commons-text", "pom.xml");
        seedSca(scan, repo, "CVE-2100-9", "org.other:lib", "pom.xml");
        reconcile.reconcile(scan, WORKSPACE);

        triage.createRule(IgnoreRule.SCOPE_CVE, null, "CVE-2022-42889", "accepted everywhere");

        assertIgnoredByRule(byVuln(repo, "CVE-2022-42889"));
        assertThat(byVuln(repo, "CVE-2100-9").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void ignoresByRuleId() {
        UUID repo = seedRepo();
        UUID scan = seedScan(repo);
        seedSast(scan, repo, "opengrep-rules.custom.csrf", "A.java", "h1");
        seedSast(scan, repo, "opengrep-rules.custom.csrf", "B.java", "h2");
        seedSast(scan, repo, "opengrep-rules.custom.other", "C.java", "h3");
        reconcile.reconcile(scan, WORKSPACE);

        triage.createRule(IgnoreRule.SCOPE_RULE, repo, "opengrep-rules.custom.csrf", "rule accepted");

        assertIgnoredByRule(byFile(repo, "A.java"));
        assertIgnoredByRule(byFile(repo, "B.java"));
        assertThat(byFile(repo, "C.java").getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    private void assertIgnoredByRule(SubIssue sub) {
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(sub.getIgnoreReason()).isEqualTo(ReasonCode.IGNORE_RULE.code());
        assertThat(sub.getIgnoredByRuleId()).isNotNull();
        List<TriageEvent> trail = events.findByWorkspaceIdAndSubIssueIdOrderByAtAsc(WORKSPACE, sub.getId());
        assertThat(trail).anySatisfy(e -> {
            assertThat(e.getToStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
            assertThat(e.getReasonCode()).isEqualTo(ReasonCode.IGNORE_RULE.code());
            assertThat(e.getActor()).isEqualTo(TriageEvent.ACTOR_USER);
        });
    }
}
