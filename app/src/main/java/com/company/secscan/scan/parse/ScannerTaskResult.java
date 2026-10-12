package com.company.secscan.scan.parse;

/** One scanner's outcome as reported in the scanner container's {@code result.json} manifest. */
public record ScannerTaskResult(String name, String status, Integer exitCode, Long durationMs,
                                String findingsFile) {

    public boolean succeeded() {
        return "ok".equals(status);
    }
}
