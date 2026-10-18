package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.intel.KevEntry;
import com.company.secscan.intel.KevEntryRepository;
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
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Scan-time scoring driven through reconcile by seeding rows directly (no scanner container). Proves the M5
 * done-when at the persistence layer — a KEV-listed CVE scores +5 with the reason in {@code score_breakdown}
 * (the API exposure of the same is asserted by {@code IssueApiIT} once the view is enriched) — plus the
 * sensitivity adjustments, EPSS stored-but-not-applied, and the group severity/fix-time/subtitle rollup.
 */
class ScoringReconcileIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String TEXT4SHELL_CVE = "CVE-2022-42889";
    private static final String TEXT_VECTOR = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H";

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
    ScanFindingRepository scanFindings;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    KevEntryRepository kevEntries;
    @Autowired
    EpssScoreRepository epssScores;

    @BeforeEach
    void clearIntel() {
        kevEntries.deleteAllInBatch();
        epssScores.deleteAllInBatch();
    }

    @Test
    void kevListedCveScoresPlusFiveWithTheReasonInBreakdown() {
        UUID repo = seedRepository("normal");
        UUID scan = seedScanWithOkSca(repo);
        seedScaFinding(scan, repo, TEXT4SHELL_CVE, "org.apache.commons:commons-text", "1.9", "1.10.0", "9.8",
                TEXT_VECTOR, "CRITICAL", List.of("CWE-94"), List.of(TEXT4SHELL_CVE),
                "Arbitrary code execution in Apache Commons Text");
        kevEntries.save(new KevEntry(TEXT4SHELL_CVE, "Apache Commons Text RCE", null));

        reconcile.reconcile(scan, WORKSPACE);

        SubIssue sub = onlySub();
        assertThat(sub.isInKev()).isTrue();
        assertThat(sub.getScore()).isEqualTo(100); // base 98 + KEV 5, clamped
        assertThat(sub.getCvss()).isEqualTo(TEXT_VECTOR);
        assertThat(sub.getCveAliases()).containsExactly(TEXT4SHELL_CVE);
        assertThat(sub.getScoreBreakdown()).anySatisfy(line -> {
            assertThat(line.code()).isEqualTo("on_kev");
            assertThat(line.delta()).isEqualTo(5);
        });

        // Group rollup: severity = the open sub's score; subtitle = the CWE-94 phrase; minor upgrade = 15 min.
        IssueGroup group = groups.findById(sub.getGroupId()).orElseThrow();
        assertThat(group.getSeverityScore()).isEqualTo(100);
        assertThat(group.getFixTimeMinutes()).isEqualTo(15);
        assertThat(group.getSubtitle()).isEqualTo("Code injection can lead to arbitrary code execution");
    }

    @Test
    void repoSensitivityHighAddsFiveLowSubtractsTen() {
        UUID highRepo = seedRepository("high");
        UUID highScan = seedScanWithOkSca(highRepo);
        seedScaFinding(highScan, highRepo, "CVE-2100-0001", "com.example:a", "1.0.0", "1.0.1", "5.0",
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:L/A:N", "MODERATE", List.of("CWE-20"), List.of(),
                "Example");
        reconcile.reconcile(highScan, WORKSPACE);
        SubIssue high = subFor(highRepo);
        assertThat(high.getScore()).isEqualTo(55); // 50 + 5
        assertThat(high.getScoreBreakdown()).anyMatch(l -> l.code().equals("sensitivity_high") && l.delta() == 5);

        UUID lowRepo = seedRepository("low");
        UUID lowScan = seedScanWithOkSca(lowRepo);
        seedScaFinding(lowScan, lowRepo, "CVE-2100-0002", "com.example:b", "1.0.0", "1.0.1", "5.0",
                "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:L/I:L/A:N", "MODERATE", List.of("CWE-20"), List.of(),
                "Example");
        reconcile.reconcile(lowScan, WORKSPACE);
        SubIssue low = subFor(lowRepo);
        assertThat(low.getScore()).isEqualTo(40); // 50 - 10
        assertThat(low.getScoreBreakdown()).anyMatch(l -> l.code().equals("sensitivity_low") && l.delta() == -10);
    }

    @Test
    void epssIsStoredAndDisplayedButDoesNotChangeTheScore() {
        UUID repo = seedRepository("normal");
        UUID scan = seedScanWithOkSca(repo);
        seedScaFinding(scan, repo, TEXT4SHELL_CVE, "org.apache.commons:commons-text", "1.9", "1.10.0", "9.8",
                TEXT_VECTOR, "CRITICAL", List.of("CWE-94"), List.of(TEXT4SHELL_CVE), "RCE");
        epssScores.save(new EpssScore(TEXT4SHELL_CVE, new BigDecimal("0.90000"), new BigDecimal("0.99000")));

        reconcile.reconcile(scan, WORKSPACE);

        SubIssue sub = onlySub();
        assertThat(sub.getEpss()).isEqualByComparingTo("0.90000");
        assertThat(sub.getScore()).isEqualTo(98); // NOT KEV, EPSS adjustments OFF in M5 -> base only
        assertThat(sub.getScoreBreakdown()).noneMatch(l -> l.code().contains("epss"));
    }

    @Test
    void groupFixTimeMinutesIsTheSumOfOpenSubIssues() {
        UUID repo = seedRepository("normal");
        UUID scan = seedScanWithOkSca(repo);
        // Two vulns on ONE package (one group): a minor upgrade (15) and a major upgrade (60) -> sum 75.
        seedScaFinding(scan, repo, "CVE-2100-1000", "org.apache.commons:commons-text", "1.9", "1.10.0", "7.0",
                null, "HIGH", List.of("CWE-94"), List.of(), "minor");
        seedScaFinding(scan, repo, "CVE-2100-2000", "org.apache.commons:commons-text", "1.9", "2.0.0", "9.0",
                null, "CRITICAL", List.of("CWE-502"), List.of(), "major");

        reconcile.reconcile(scan, WORKSPACE);

        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo);
        assertThat(subs).hasSize(2);
        IssueGroup group = groups.findById(subs.get(0).getGroupId()).orElseThrow();
        assertThat(group.getFixTimeMinutes()).isEqualTo(75);   // 15 (minor) + 60 (major)
        assertThat(group.getSeverityScore()).isEqualTo(90);    // max(70, 90)
    }

    // --- seeding helpers ---

    private UUID seedRepository(String sensitivity) {
        long seq = REPO_SEQ.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + seq, 40L + seq, "ct", "read_api,read_repository", null));
        Repository repo = new Repository(WORKSPACE, conn.getId(),
                1000L + seq, "svc/app-" + seq, "main", List.of("Java"));
        repo.setSensitivity(sensitivity);
        return repositories.save(repo).getId();
    }

    private static final java.util.concurrent.atomic.AtomicLong REPO_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private UUID seedScanWithOkSca(UUID repoId) {
        Scan scan = Scan.manual(WORKSPACE, repoId);
        scan.markRunning();
        scan.finish(Scan.STATUS_COMPLETED);
        scan = scans.save(scan);
        ScanTask task = new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 5L,
                null);
        task.setClosesFindings(true);
        tasks.save(task);
        return scan.getId();
    }

    private void seedScaFinding(UUID scanId, UUID repoId, String vulnId, String pkg, String version,
                                String fixed, String maxSeverity, String cvss, String label, List<String> cwe,
                                List<String> aliases, String summary) {
        ScanFinding f = ScanFinding.sca(WORKSPACE, scanId, repoId, vulnId, pkg, "Maven", version, fixed,
                "pom.xml", maxSeverity);
        f.setOsvEnrichment(cvss, label, cwe, aliases, summary);
        scanFindings.save(f);
    }

    private SubIssue onlySub() {
        List<SubIssue> subs = subIssues.findAll();
        assertThat(subs).hasSize(1);
        return subs.get(0);
    }

    private SubIssue subFor(UUID repoId) {
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repoId);
        assertThat(subs).hasSize(1);
        return subs.get(0);
    }
}
