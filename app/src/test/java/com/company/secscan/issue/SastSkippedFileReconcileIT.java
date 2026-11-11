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
        return record(repo, findings, skipped, 0);
    }

    private UUID record(UUID repo, List<RawFinding> findings, List<SastSkippedFile> skipped, int partiallyParsed) {
        Scan scan = Scan.manual(WORKSPACE, repo);
        scan.markRunning();
        scan = scans.save(scan);
        ScannerTaskResult sast = new ScannerTaskResult("sast", "ok", 0, 5L, "opengrep.json", false);
        ParsedScan parsed = new ParsedScan(false, "commit-" + scan.getId(), Map.of(), List.of(sast), findings,
                List.of(), SecscanConfig.absent(), List.of(), ParsedScan.SCOPE_STATUS_OK, List.of(), List.of(),
                skipped, partiallyParsed);
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

        // The coverage warning is raised, naming the count, the error type and the repo-relative path.
        List<ScanWarning> skippedWarnings = scanWarnings.findByScanId(scan2).stream()
                .filter(w -> "SAST_FILES_SKIPPED".equals(w.getCode())).toList();
        assertThat(skippedWarnings).hasSize(1);
        // Message names the count, the error type, and the repo-relative path(s) — but never source text.
        assertThat(skippedWarnings.get(0).getMessage())
                .contains("1 file", "Timeout", SKIP_FILE)
                .doesNotContain("partially parsed");
    }

    @Test
    void aSizeSkippedFileAlsoFreezesAndWarnsWithTheSizeReason() {
        // run-scan.sh classifies a file over Opengrep's size limit as skipped (reason exceeded_size_limit); the
        // worker must freeze its findings and name the size reason in the warning, exactly like a Timeout.
        UUID repo = seedRepo();
        record(repo, List.of(sast(SKIP_FILE, "hashSkip")), List.of());
        SubIssue skip = byFile(repo, SKIP_FILE);

        UUID scan2 = record(repo, List.of(),
                List.of(new SastSkippedFile(SKIP_FILE, List.of("exceeded_size_limit"))));

        assertThat(subIssues.findByIdAndWorkspaceId(skip.getId(), WORKSPACE).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(eventsFor(skip.getId(), scan2)).isZero();
        List<ScanWarning> warn = scanWarnings.findByScanId(scan2).stream()
                .filter(w -> "SAST_FILES_SKIPPED".equals(w.getCode())).toList();
        assertThat(warn).hasSize(1);
        assertThat(warn.get(0).getMessage()).contains("exceeded_size_limit", SKIP_FILE);
    }

    // --- M8.15 review B2: an IGNORED finding in a skipped file must not be false-solved either. ---

    private static final String DUP_LOSER = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String DUP_WINNER = "opengrep-rules.custom.spring-csrf-disabled";

    private static RawFinding sastRule(String rule, String file, String hash) {
        return RawFinding.sast(rule, file, 10, hash, "HIGH", "ERROR", List.of("CWE-352"));
    }

    private SubIssue byRule(UUID repo, String rule) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(s -> rule.equals(s.getRuleId())).findFirst().orElseThrow();
    }

    /** Scan 1: a D-061 loser + winner in SKIP_FILE → the loser is auto-ignored as DUPLICATE_RULE. */
    private SubIssue seedIgnoredDuplicateLoser(UUID repo) {
        record(repo, List.of(sastRule(DUP_LOSER, SKIP_FILE, "hLoser"), sastRule(DUP_WINNER, SKIP_FILE, "hWinner")),
                List.of());
        SubIssue loser = byRule(repo, DUP_LOSER);
        assertThat(loser.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(loser.getAutoIgnoreReason()).as("held by a re-derivable auto cause (D-083 solvable)")
                .isNotNull();
        return loser;
    }

    @Test
    void anIgnoredFindingInASkippedFileIsFrozenNotSolved() {
        UUID repo = seedRepo();
        SubIssue loser = seedIgnoredDuplicateLoser(repo);

        // Scan 2: nothing reported, SKIP_FILE timed out → the ignored loser must stay IGNORED with no event. Its
        // DUPLICATE_RULE cause cannot be re-derived (the winner did not run either), so without the B2 freeze the
        // unignore path would move it to solved (or open) with an event.
        UUID scan2 = record(repo, List.of(), List.of(new SastSkippedFile(SKIP_FILE, List.of("Timeout"))));

        SubIssue after = subIssues.findByIdAndWorkspaceId(loser.getId(), WORKSPACE).orElseThrow();
        assertThat(after.getStatus()).as("ignored finding in a skipped file is not false-solved (B2)")
                .isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(eventsFor(loser.getId(), scan2)).as("no triage_event for it").isZero();
        assertThat(byRule(repo, DUP_WINNER).getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Autowired IgnoreRuleRepository ignoreRules;

    @Test
    void aRuleIgnoredFindingInASkippedFileIsNotSolvedByTheD083Path() {
        // A rule-scope ignore still derives this scan (desired != null), so an absent finding hits the D-083
        // "solve the ignored orphan" branch — which must skip a finding whose file was not fully analyzed.
        UUID repo = seedRepo();
        ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_RULE, repo, RULE, "accepted risk", "tester"));
        record(repo, List.of(sast(SKIP_FILE, "hashSkip")), List.of());
        SubIssue sub = byFile(repo, SKIP_FILE);
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_IGNORED);

        UUID scan2 = record(repo, List.of(), List.of(new SastSkippedFile(SKIP_FILE, List.of("Timeout"))));

        assertThat(subIssues.findByIdAndWorkspaceId(sub.getId(), WORKSPACE).orElseThrow().getStatus())
                .as("rule-ignored finding in a skipped file is not false-solved (B2)")
                .isEqualTo(SubIssue.STATUS_IGNORED);
        assertThat(eventsFor(sub.getId(), scan2)).isZero();

        // Control: the same finding absent from a FULLY scanned file is solved by D-083.
        UUID scan3 = record(repo, List.of(), List.of());
        assertThat(subIssues.findByIdAndWorkspaceId(sub.getId(), WORKSPACE).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(eventsFor(sub.getId(), scan3)).isEqualTo(1);
    }

    @Test
    void controlTheSameIgnoredFindingInAFullyScannedFileIsSolvedByD083() {
        // Proves the scenario above is not vacuous: without the skip, D-083 solves the absent ignored loser.
        UUID repo = seedRepo();
        SubIssue loser = seedIgnoredDuplicateLoser(repo);

        UUID scan2 = record(repo, List.of(), List.of());

        assertThat(subIssues.findByIdAndWorkspaceId(loser.getId(), WORKSPACE).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(eventsFor(loser.getId(), scan2)).isEqualTo(1);
    }

    @Test
    void aSolvedFindingReDetectedInASkippedFileStillReopens() {
        // SOLVED is deliberately NOT frozen: a rule that did run on the skipped file found it again.
        UUID repo = seedRepo();
        record(repo, List.of(sast(SKIP_FILE, "hashSkip")), List.of());
        record(repo, List.of(), List.of()); // fully scanned, absent → solved
        SubIssue solved = byFile(repo, SKIP_FILE);
        assertThat(solved.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);

        UUID scan3 = record(repo, List.of(sast(SKIP_FILE, "hashSkip")),
                List.of(new SastSkippedFile(SKIP_FILE, List.of("Timeout"))));

        assertThat(subIssues.findByIdAndWorkspaceId(solved.getId(), WORKSPACE).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(eventsFor(solved.getId(), scan3)).isEqualTo(1);
    }

    // --- M8.15 review S1/S2 (owner decision): full parse errors freeze; PartialParsing = scanned, only counted. ---

    private static final String PARTIAL_FILE = "src/main/java/Partial.java";

    @Test
    void aFullParseErrorFreezesLikeATimeout() {
        UUID repo = seedRepo();
        record(repo, List.of(sast(SKIP_FILE, "hashSkip")), List.of());
        SubIssue skip = byFile(repo, SKIP_FILE);

        UUID scan2 = record(repo, List.of(), List.of(new SastSkippedFile(SKIP_FILE, List.of("Syntax error"))));

        assertThat(subIssues.findByIdAndWorkspaceId(skip.getId(), WORKSPACE).orElseThrow().getStatus())
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(eventsFor(skip.getId(), scan2)).isZero();
        assertThat(scanWarnings.findByScanId(scan2)).filteredOn(w -> "SAST_FILES_SKIPPED".equals(w.getCode()))
                .singleElement().satisfies(w -> assertThat(w.getMessage()).contains("Syntax error", SKIP_FILE));
    }

    @Test
    void aPartiallyParsedFileIsNotFrozenAndRaisesNoWarningOnItsOwn() {
        // run-scan.sh never lists a PartialParsing-only file in skippedFiles; it only counts it. Its absent
        // finding therefore SOLVES normally (the file was analyzed), and a count alone raises no warning.
        UUID repo = seedRepo();
        record(repo, List.of(sast(PARTIAL_FILE, "hashPartial")), List.of());
        SubIssue partial = byFile(repo, PARTIAL_FILE);

        UUID scan2 = record(repo, List.of(), List.of(), 1);

        assertThat(subIssues.findByIdAndWorkspaceId(partial.getId(), WORKSPACE).orElseThrow().getStatus())
                .as("a partially parsed file was scanned → its absent finding solves normally")
                .isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(eventsFor(partial.getId(), scan2)).isEqualTo(1);
        assertThat(scanWarnings.findByScanId(scan2)).noneMatch(w -> "SAST_FILES_SKIPPED".equals(w.getCode()));
    }

    @Test
    void theWarningCapsTypesAndMentionsPartialParsingOnlyAsACount() {
        UUID repo = seedRepo();
        record(repo, List.of(sast(SKIP_FILE, "hashSkip")), List.of());

        UUID scan2 = record(repo, List.of(), List.of(new SastSkippedFile(SKIP_FILE,
                List.of("Timeout", "Syntax error", "Other syntax error", "OutOfMemory", "Too many matches",
                        "exceeded_size_limit", "insufficient_permissions"))), 3);

        String msg = scanWarnings.findByScanId(scan2).stream()
                .filter(w -> "SAST_FILES_SKIPPED".equals(w.getCode())).findFirst().orElseThrow().getMessage();
        assertThat(msg).contains("1 file", SKIP_FILE, ", +2 more)", "partially parsed (3)")
                .doesNotContain("Partial.java").doesNotContain("/work").doesNotContain("[").doesNotContain("line");
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
