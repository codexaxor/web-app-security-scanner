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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves the re-score lost-update fix (B1): the re-score writes the score columns with a {@code status = 'open'}
 * guard, so a sub-issue that reconcile solved in the window since the re-score read it is never resurrected to an
 * {@code open}-era score, its status/solved_at are untouched, and no {@code triage_event} is written.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
class ReScoreConcurrencyIT {

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
    @Autowired
    PlatformTransactionManager txManager;

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
    void aSubIssueSolvedInTheWindowIsNotResurrectedByReScore() {
        UUID repoId = seedRepo();
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repoId, "sca",
                Fingerprints.groupKey("sca", "Maven/org.apache.commons:commons-text"),
                "org.apache.commons:commons-text", null));
        SubIssue sub = new SubIssue(WORKSPACE, group.getId(), repoId, "sca", "sca",
                Fingerprints.sca("org.apache.commons:commons-text", CVE, "pom.xml"), null);
        sub.setVulnId(CVE);
        sub.setToolSeverity("9.8");
        sub.setCveAliases(List.of(CVE));
        scorer.score(sub, "normal"); // open, score 98, not in KEV yet
        sub = subIssues.save(sub);
        UUID subId = sub.getId();
        assertThat(sub.getScore()).isEqualTo(98);

        // KEV now lists the CVE, so a re-score WOULD bump the score to 100 and flip in_kev — IF it touched the row.
        kevEntries.save(new KevEntry(CVE, "Apache Commons Text RCE", null));

        // --- the window: reconcile solves the sub-issue between a re-score reading it and writing it ---
        SubIssue solved = subIssues.findById(subId).orElseThrow();
        solved.solve();
        subIssues.save(solved);
        var solvedAt = subIssues.findById(subId).orElseThrow().getSolvedAt();

        // The column-scoped UPDATE must skip the now-solved row (status guard) — no write, 0 rows affected.
        // Wrapped in a transaction because the @Modifying query flushes (a direct call has no ambient tx).
        int rows = new TransactionTemplate(txManager).execute(s ->
                subIssues.updateScoreColumnsIfOpen(subId, 100, "[{\"code\":\"on_kev\",\"detail\":\"x\","
                        + "\"delta\":5}]", true, null));
        assertThat(rows).isZero();

        // And the full sweep likewise leaves it solved with its solved-era score/flags.
        reScore.rescoreAll();

        SubIssue after = subIssues.findById(subId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED); // stays solved; re-score never reopens
        assertThat(after.getScore()).isEqualTo(98);                 // NOT resurrected to 100
        assertThat(after.isInKev()).isFalse();                      // NOT flipped on
        assertThat(after.getSolvedAt()).isEqualTo(solvedAt);        // solved_at untouched
        assertThat(events.count()).isZero();                        // no triage_event ever
    }

    private UUID seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 42L, "ct", "read_api,read_repository", null));
        Repository repo = new Repository(WORKSPACE, conn.getId(), 7101L, "svc/rescore-concurrency", "main",
                List.of("Java"));
        return repositories.save(repo).getId();
    }
}
