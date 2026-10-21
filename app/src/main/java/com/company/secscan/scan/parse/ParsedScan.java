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
                         SecscanConfig config, List<DeclaredScope> declaredScopes, String scopeInventoryStatus) {

    /** The scope inventory ran cleanly; {@code "ok"} means dev/test scope state is authoritative this scan (S3). */
    public static final String SCOPE_STATUS_OK = "ok";

    public ParsedScan {
        scopeInventoryStatus = scopeInventoryStatus == null ? SCOPE_STATUS_OK : scopeInventoryStatus;
    }

    /** True when the dev/test scope inventory failed → the worker freezes prior scope-derived triage (S3). */
    public boolean scopeInventoryFailed() {
        return !SCOPE_STATUS_OK.equals(scopeInventoryStatus);
    }

    public static ParsedScan cloneFailure() {
        return new ParsedScan(true, null, Map.of(), List.of(), List.of(), List.of(), SecscanConfig.absent(),
                List.of(), SCOPE_STATUS_OK);
    }
}
