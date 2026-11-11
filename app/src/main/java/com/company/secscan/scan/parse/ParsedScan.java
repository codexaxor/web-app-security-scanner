package com.company.secscan.scan.parse;

import com.company.secscan.finding.RawFinding;
import java.util.List;
import java.util.Map;

/**
 * The parsed result of one scanner-container run: the cloned commit, tool versions, each scanner's outcome and
 * the normalized findings. {@link #cloneFailed()} means the container could not clone the repository (no commit
 * was scanned) — the worker treats that as "no access", distinct from a scanner that ran and failed.
 */
public record ParsedScan(boolean cloneFailed, String commit, Map<String, String> toolVersions,
                         List<ScannerTaskResult> tasks, List<RawFinding> findings, List<ScanWarning> warnings,
                         SecscanConfig config, List<DeclaredScope> declaredScopes, String scopeInventoryStatus,
                         List<String> unreadableScopeManifests, List<String> languages,
                         List<SastSkippedFile> sastSkippedFiles, int sastPartiallyParsedFiles) {

    /** The scope inventory ran cleanly; {@code "ok"} means dev/test scope state is authoritative this scan (S3). */
    public static final String SCOPE_STATUS_OK = "ok";

    public ParsedScan {
        scopeInventoryStatus = scopeInventoryStatus == null ? SCOPE_STATUS_OK : scopeInventoryStatus;
        unreadableScopeManifests = unreadableScopeManifests == null ? List.of()
                : List.copyOf(unreadableScopeManifests);
        // M8.5 (D-081): the content-detected languages; empty when detection failed or found none (the worker
        // then leaves repository.languages unchanged).
        languages = languages == null ? List.of() : List.copyOf(languages);
        // M8.15 S6 follow-up (D-104) + review S2: files Opengrep could not fully analyze (Timeout, full parse error,
        // size limit, unreadable, …) — the worker freezes their SAST findings and raises SAST_FILES_SKIPPED. Paths +
        // normalized error-type names only (D-053/S1).
        sastSkippedFiles = sastSkippedFiles == null ? List.of() : List.copyOf(sastSkippedFiles);
        // M8.15 review S2: files Opengrep only PARTIALLY parsed (PartialParsing) — analyzed, so NOT frozen; counted
        // only, for the "partially parsed (N)" mention in SAST_FILES_SKIPPED.
        sastPartiallyParsedFiles = Math.max(0, sastPartiallyParsedFiles);
    }

    /** 12-arg (no partially-parsed count) for callers predating the M8.15 review S2 split. */
    public ParsedScan(boolean cloneFailed, String commit, Map<String, String> toolVersions,
                      List<ScannerTaskResult> tasks, List<RawFinding> findings, List<ScanWarning> warnings,
                      SecscanConfig config, List<DeclaredScope> declaredScopes, String scopeInventoryStatus,
                      List<String> unreadableScopeManifests, List<String> languages,
                      List<SastSkippedFile> sastSkippedFiles) {
        this(cloneFailed, commit, toolVersions, tasks, findings, warnings, config, declaredScopes,
                scopeInventoryStatus, unreadableScopeManifests, languages, sastSkippedFiles, 0);
    }

    /** True when the dev/test scope inventory failed GLOBALLY → the worker freezes all scope-derived triage (S3). */
    public boolean scopeInventoryFailed() {
        return !SCOPE_STATUS_OK.equals(scopeInventoryStatus);
    }

    public static ParsedScan cloneFailure() {
        return new ParsedScan(true, null, Map.of(), List.of(), List.of(), List.of(), SecscanConfig.absent(),
                List.of(), SCOPE_STATUS_OK, List.of(), List.of(), List.of());
    }
}
