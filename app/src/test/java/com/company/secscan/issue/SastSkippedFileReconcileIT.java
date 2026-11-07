package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanWarning;
import com.company.secscan.scan.ScanWarningRepository;
import com.company.secscan.scan.parse.ParsedScan;
import com.company.secscan.scan.parse.SastSkippedFile;
import com.company.secscan.scan.parse.ScannerTaskResult;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.scan.worker.ScanStateService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.15 S6 follow-up (D-104): a file Opengrep could not fully analyze (reported in its {@code errors[]}) must
 * CLOSE NOTHING (domain rule 3). When a prior SAST finding's file is skipped this scan, the finding stays OPEN
 * with no {@code triage_event}; a finding whose file was fully scanned and is now absent solves normally; and a
 * {@code SAST_FILES_SKIPPED} coverage warning is raised. Driven through the real worker write-path
 * ({@link ScanStateService#recordSuccess}) so the warning and the reconcile freeze are both exercised.
 */
class SastSkippedFileReconcileIT extends TriageSeedSupport {

    @Autowired ScanStateService scanState;
    @Autowired TriageEventRepository triageEvents;
    @Autowired ScanWarningRepository scanWarnings;

    private static final String SKIP_FILE = "src/main/java/Skip.java";
    private static final String KEEP_FILE = "src/main/java/Keep.java";
    private static final String RULE = "opengrep-rules.java.lang.security.audit.sqli.jdbc-sqli";

    private UUID record(UUID repo, List<RawFinding> findings, List<SastSkippedFile> skipped) {
        Scan scan = Scan.manual(WORKSPACE, repo);
        scan.markRunning();
        scan = scans.save(scan);
        ScannerTaskResult sast = new ScannerTaskResult("sast", "ok", 0, 5L, "opengrep.json", false);
        ParsedScan parsed = new ParsedScan(false, "commit-" + scan.getId(), Map.of(), List.of(sast), findings,
                List.of(), SecscanConfig.absent(), List.of(), ParsedScan.SCOPE_STATUS_OK, List.of(), List.of(),
                skipped);
        scanState.recordSuccess(WORKSPACE, scan.getId(), UUID.randomUUID(), repo, parsed, null,
                Scan.STATUS_COMPLETED);
        return scan.getId();
    }

    private static RawFinding sast(String file, String hash) {
        return RawFinding.sast(RULE, file, 10, hash, "HIGH", "ERROR", List.of("CWE-89"));
    }

    private long eventsFor(UUID subId, UUID scanId) {
        return triageEvents.findAll().stream()
                .filter(e -> subId.equals(e.getSubIssueId()) && scanId.equals(e.getScanId())).count();
    }

    @Test
    void aSkippedFileClosesNothingWhileAFullyScannedFileSolvesNormally() {
        UUID repo = seedRepo();

        // Scan 1: both findings present → both sub-issues open.
        record(repo, List.of(sast(SKIP_FILE, "hashSkip"), sast(KEEP_FILE, "hashKeep")), List.of());
        SubIssue skip = byFile(repo, SKIP_FILE);
        SubIssue keep = byFile(repo, KEEP_FILE);
        assertThat(skip.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(keep.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // Scan 2: neither finding is reported, but Skip.java is a skipped file (Timeout). Keep.java was fully
        // scanned.
        UUID scan2 = record(repo, List.of(),
                List.of(new SastSkippedFile(SKIP_FILE, List.of("Timeout"))));

        // The skipped file's finding is FROZEN: still open, and the rescan wrote NO triage_event for it.
        SubIssue skipAfter = subIssues.findByIdAndWorkspaceId(skip.getId(), WORKSPACE).orElseThrow();
        assertThat(skipAfter.getStatus()).as("finding in a skipped file is left untouched (not solved)")
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(eventsFor(skip.getId(), scan2)).as("no triage_event for a skipped-file finding").isZero();

        // The fully-scanned file's absent finding solves normally, with a SOLVED triage_event.
        SubIssue keepAfter = subIssues.findByIdAndWorkspaceId(keep.getId(), WORKSPACE).orElseThrow();
        assertThat(keepAfter.getStatus()).as("absent finding in a fully-scanned file solves normally")
                .isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(eventsFor(keep.getId(), scan2)).as("the solve wrote a triage_event").isEqualTo(1);

        // The coverage warning is raised, naming the count and the error type (no path/source).
        List<ScanWarning> skippedWarnings = scanWarnings.findByScanId(scan2).stream()
                .filter(w -> "SAST_FILES_SKIPPED".equals(w.getCode())).toList();
        assertThat(skippedWarnings).hasSize(1);
        assertThat(skippedWarnings.get(0).getMessage())
                .contains("1 file", "Timeout").doesNotContain(SKIP_FILE);
    }

    @Test
    void noSkippedFilesRaisesNoWarningAndSolvesAbsentFindings() {
        UUID repo = seedRepo();
        record(repo, List.of(sast(KEEP_FILE, "hashKeep")), List.of());
        UUID scan2 = record(repo, List.of(), List.of());

        assertThat(byFile(repo, KEEP_FILE).getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(scanWarnings.findByScanId(scan2))
                .noneMatch(w -> "SAST_FILES_SKIPPED".equals(w.getCode()));
    }
}
