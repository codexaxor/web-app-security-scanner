package com.company.secscan.scan.api;

import java.util.List;
import java.util.Map;

/**
 * A single scan with its per-scanner outcomes and a finding count. The full finding feed (grouped,
 * deduplicated) arrives with M3/M8; M2 exposes only the raw count so the pipeline can be inspected.
 */
public record ScanDetailView(ScanView scan, Map<String, String> toolVersions, List<ScanTaskView> tasks,
                             long findingCount) {
}
