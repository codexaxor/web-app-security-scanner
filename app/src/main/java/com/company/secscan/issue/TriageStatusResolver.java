package com.company.secscan.issue;

import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Computes the reconcile-equivalent status a finding returns to when its ignore is removed via the API — without
 * a rescan (D-058). The basis is the repository's most recently reconciled scan:
 * <ul>
 *   <li>the finding was SEEN in it (its {@code last_seen_scan_id} is that scan) → {@code open};</li>
 *   <li>otherwise it is {@code solved} ONLY if that scan's scanner was authoritative to close findings
 *       (succeeded AND closes_findings); a failed/absent scanner closes nothing → {@code open} (review change #4).</li>
 * </ul>
 * With no reconciled scan yet, the finding (which exists) is {@code open}.
 */
@Service
public class TriageStatusResolver {

    private final ScanRepository scans;
    private final ScanTaskRepository tasks;

    public TriageStatusResolver(ScanRepository scans, ScanTaskRepository tasks) {
        this.scans = scans;
        this.tasks = tasks;
    }

    public String resolve(SubIssue sub) {
        Optional<Scan> latest = scans
                .findFirstByWorkspaceIdAndRepositoryIdAndReconciledAtIsNotNullOrderByReconciledAtDesc(
                        sub.getWorkspaceId(), sub.getRepositoryId());
        if (latest.isEmpty()) {
            return SubIssue.STATUS_OPEN;
        }
        Scan scan = latest.get();
        if (scan.getId().equals(sub.getLastSeenScanId())) {
            return SubIssue.STATUS_OPEN;
        }
        boolean scannerSolvable = tasks.findByScanId(scan.getId()).stream()
                .anyMatch(t -> t.getScanner().equals(sub.getScanner()) && t.succeeded() && t.closesFindings());
        return scannerSolvable ? SubIssue.STATUS_SOLVED : SubIssue.STATUS_OPEN;
    }
}
