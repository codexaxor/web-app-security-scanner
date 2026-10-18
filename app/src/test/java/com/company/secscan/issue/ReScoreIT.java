package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.KevEntry;
import com.company.secscan.intel.KevEntryRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the re-score job (D-049): after a KEV refresh an open sub-issue's score changes, but its status and
 * fingerprint do not, and ZERO triage_event rows are written — scoring is derived and never feeds the
 * fingerprint, so a re-score can never mass-solve or reopen (domain rule 2 / hard rule).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
class ReScoreIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CVE = "CVE-2022-42889";

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
    }

    @Autowired
    ReScoreService reScore;
    @Autowired
    SubIssueScorer scorer;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    KevEntryRepository kevEntries;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @BeforeEach
    void clean() {
        events.deleteAllInBatch();
        subIssues.deleteAllInBatch();
        groups.deleteAllInBatch();
        kevEntries.deleteAllInBatch();
        repositories.deleteAllInBatch();
        connections.deleteAllInBatch();
    }

    @Test
    void reScoreChangesScoreButNotStatusAndWritesNoTriageEvent() {
        UUID repoId = seedRepo();
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repoId, "sca",
                Fingerprints.groupKey("sca", "Maven/org.apache.commons:commons-text"),
                "org.apache.commons:commons-text", null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "sca", "sca",
                Fingerprints.sca("org.apache.commons:commons-text", CVE, "pom.xml"), null);
        sub.setVulnId(CVE);
        sub.setPackageName("org.apache.commons:commons-text");
        sub.setInstalledVersion("1.9");
        sub.setFixedVersion("1.10.0");
        sub.setToolSeverity("9.8");
        sub.setCvss("CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        sub.setCveAliases(List.of(CVE));
        scorer.score(sub, "normal"); // initial scan-time score, KEV not yet loaded
        sub = subIssues.save(sub);

        UUID subId = sub.getId();
        String fingerprintBefore = sub.getFingerprint();
        assertThat(sub.getScore()).isEqualTo(98);
        assertThat(sub.isInKev()).isFalse();
        assertThat(events.count()).isZero();

        // A KEV refresh lands: CVE is now known-exploited. Re-score (no rescan).
        kevEntries.save(new KevEntry(CVE, "Apache Commons Text RCE", null));
        int rescored = reScore.rescoreAll();

        SubIssue after = subIssues.findById(subId).orElseThrow();
        assertThat(rescored).isEqualTo(1);
        assertThat(after.isInKev()).isTrue();
        assertThat(after.getScore()).isEqualTo(100);          // 98 + 5 (KEV), clamped
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_OPEN); // status unchanged
        assertThat(after.getFingerprint()).isEqualTo(fingerprintBefore); // fingerprint unchanged
        assertThat(after.getScoreBreakdown()).anyMatch(l -> l.code().equals("on_kev"));
        assertThat(events.count()).isZero();                  // D-049: NO triage_event written

        // The group rollup followed the re-score.
        assertThat(groups.findById(group.getId()).orElseThrow().getSeverityScore()).isEqualTo(100);
    }

    private UUID seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 7001L, "svc/rescore", "main",
                List.of("Java"))).getId();
    }
}
