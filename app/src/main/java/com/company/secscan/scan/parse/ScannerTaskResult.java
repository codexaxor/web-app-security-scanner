package com.company.secscan.scan.parse;

/**
 * One scanner's outcome as reported in the scanner container's {@code result.json} manifest.
 * {@code noPackages} is set only by SCA when OSV-Scanner found nothing to scan (exit 128): the task is
 * {@code ok} but it is NOT authoritative to close findings this scan (D-034, plan change #1).
 */
public record ScannerTaskResult(String name, String status, Integer exitCode, Long durationMs,
                                String findingsFile, boolean noPackages) {

    public boolean succeeded() {
        return "ok".equals(status);
    }

    /** A successful scanner may close its missing findings only when it actually had packages to analyze. */
    public boolean closesFindings() {
        return succeeded() && !noPackages;
    }
}
