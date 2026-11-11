package com.company.secscan.scan.parse;

import java.util.List;

/**
 * One repo-relative file Opengrep could not fully analyze this scan, so its SAST findings are FROZEN (M8.15 S6
 * follow-up, D-104; classification refined in the M8.15 review, S2): a per-rule {@code Timeout}, a FULL parse
 * failure ({@code Syntax error} / {@code Other syntax error}), another analysis error, a size-limit or unreadable
 * skip. A file Opengrep only PARTIALLY parsed ({@code PartialParsing}) is NOT one of these — it was analyzed and
 * reconciles normally; run-scan.sh only counts it ({@code partiallyParsedFiles}). Only the PATH and the error TYPE classifications leave the scanner container — never a
 * message or any source text (D-053/S1). The worker uses these to (a) raise a {@code SAST_FILES_SKIPPED}
 * coverage warning and (b) FREEZE the SAST findings in these files at reconcile, so a partially-analyzed file
 * never false-solves a real finding (domain rule 3: a scanner that did not fully cover a file closes nothing
 * there).
 */
public record SastSkippedFile(String path, List<String> errorTypes) {

    public SastSkippedFile {
        errorTypes = errorTypes == null ? List.of() : List.copyOf(errorTypes);
    }
}
