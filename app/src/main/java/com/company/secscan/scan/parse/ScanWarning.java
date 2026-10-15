package com.company.secscan.scan.parse;

/**
 * A scan-health warning parsed from the scanner container's manifest (M4): a coverage gap we surface rather
 * than hide (D-030). {@code code} is a machine code (e.g. {@code MISSING_GRADLE_LOCKFILE},
 * {@code MISSING_NUGET_LOCKFILE}, {@code UNRESOLVED_VERSION}, {@code NO_MANIFESTS}); {@code filePath} is the
 * repo-relative build file it concerns, or empty/null when it is repository-wide.
 */
public record ScanWarning(String code, String message, String filePath) {
}
