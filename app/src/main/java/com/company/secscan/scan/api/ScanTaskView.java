package com.company.secscan.scan.api;

import com.company.secscan.scan.ScanTask;

/** One scanner's outcome within a scan. */
public record ScanTaskView(String scanner, String status, Integer exitCode, Long durationMs) {

    public static ScanTaskView of(ScanTask t) {
        return new ScanTaskView(t.getScanner(), t.getStatus(), t.getExitCode(), t.getDurationMs());
    }
}
