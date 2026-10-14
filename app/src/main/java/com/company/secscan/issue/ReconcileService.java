package com.company.secscan.issue;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.reconcile.Reconciler;
import com.company.secscan.reconcile.Reconciler.Existing;
import com.company.secscan.reconcile.Reconciler.Incoming;
import com.company.secscan.reconcile.Reconciler.Plan;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a scan's raw {@link ScanFinding}s into the deduplicated issue model (SPEC "Issue lifecycle", CLAUDE.md
 * domain rules 2-4). Runs in the same short transaction that stored the findings (called from
 * {@code ScanStateService.recordSuccess}), so a worker crash never leaves findings unreconciled; it does no
 * network I/O. The decision itself is the pure {@link Reconciler}; this class only loads/saves entities and
 * writes the audit trail.
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    private final ScanRepository scans;
    private final ScanTaskRepository tasks;
    private final ScanFindingRepository findings;
    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;
    private final TriageEventRepository events;
    private final SecretFingerprinterHolder fingerprinter;
    private final Reconciler reconciler = new Reconciler();

    public ReconcileService(ScanRepository scans, ScanTaskRepository tasks, ScanFindingRepository findings,
                            IssueGroupRepository groups, SubIssueRepository subIssues, TriageEventRepository events,
                            SecretFingerprinterHolder fingerprinter) {
        this.scans = scans;
        this.tasks = tasks;
        this.findings = findings;
        this.groups = groups;
        this.subIssues = subIssues;
        this.events = events;
        this.fingerprinter = fingerprinter;
    }

    /**
     * Reconciles one scan's findings into {@code issue_group}/{@code sub_issue}. Idempotent (a second run is a
     * no-op) and guarded against stale, out-of-order scans. Always stamps {@code scan.reconciled_at}.
     */
    @Transactional
    public void reconcile(UUID scanId) {
        Scan scan = scans.findById(scanId).orElseThrow();
        if (scan.getReconciledAt() != null) {
            return; // already reconciled (idempotent)
        }
        // Stale guard: a newer scan of this repository has already been applied — do not let this older view
        // re-open or re-solve anything. Mark it reconciled (processed) and stop.
        if (scans.existsByWorkspaceIdAndRepositoryIdAndReconciledAtIsNotNullAndCreatedAtGreaterThan(
                scan.getWorkspaceId(), scan.getRepositoryId(), scan.getCreatedAt())) {
            log.info("Scan {} is stale (a newer scan of repository {} is already reconciled); skipping reconcile.",
                    scanId, scan.getRepositoryId());
            scan.markReconciled();
            scans.save(scan);
            return;
        }

        UUID workspaceId = scan.getWorkspaceId();
        UUID repositoryId = scan.getRepositoryId();

        // Scanners that ran OK this scan may close their missing findings; a failed/absent scanner closes none.
        Set<String> solvableScanners = new HashSet<>();
        for (ScanTask t : tasks.findByScanId(scanId)) {
            if (t.succeeded()) {
                solvableScanners.add(t.getScanner());
            }
        }

        List<ScanFinding> scanFindings = findings.findByScanId(scanId);
        Map<String, ScanFinding> findingByFp = new HashMap<>();
        List<Incoming> incoming = new ArrayList<>();
        for (ScanFinding f : scanFindings) {
            String fp = fingerprintOf(f);
            // First finding wins if two normalize to the same fingerprint within one scan (dedup within a scan).
            if (findingByFp.putIfAbsent(fp, f) == null) {
                incoming.add(new Incoming(fp, f.getScanner()));
            }
        }

        List<SubIssue> existingSubs = subIssues.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId);
        Map<String, SubIssue> subByFp = new HashMap<>();
        for (SubIssue s : existingSubs) {
            subByFp.put(s.getFingerprint(), s);
        }

        Set<String> frozenFingerprints = secretKeyGuard(existingSubs);

        List<Existing> existing = new ArrayList<>(existingSubs.size());
        for (SubIssue s : existingSubs) {
            existing.add(new Existing(s.getFingerprint(), s.getScanner(), s.getStatus()));
        }

        Plan plan = reconciler.reconcile(incoming, existing, solvableScanners, frozenFingerprints);

        Set<UUID> touchedGroups = new HashSet<>();
        for (String fp : plan.newOpen()) {
            ScanFinding f = findByFp(findingByFp, fp);
            IssueGroup group = upsertGroup(workspaceId, repositoryId, f);
            SubIssue sub = new SubIssue(workspaceId, group.getId(), repositoryId, f.getType(), f.getScanner(),
                    fp, scanId);
            copyDescriptive(f, sub);
            subIssues.save(sub);
            events.save(TriageEvent.system(workspaceId, sub.getId(), null, SubIssue.STATUS_OPEN,
                    TriageEvent.REASON_SEEN, scanId));
            touchedGroups.add(group.getId());
        }
        for (String fp : plan.reopened()) {
            SubIssue sub = subByFp.get(fp);
            sub.reopen(scanId);
            copyDescriptive(findByFp(findingByFp, fp), sub);
            subIssues.save(sub);
            events.save(TriageEvent.system(workspaceId, sub.getId(), SubIssue.STATUS_SOLVED,
                    SubIssue.STATUS_OPEN, TriageEvent.REASON_REOPENED, scanId));
            touchedGroups.add(sub.getGroupId());
        }
        for (String fp : plan.seen()) {
            SubIssue sub = subByFp.get(fp);
            sub.recordSeen(scanId);
            copyDescriptive(findByFp(findingByFp, fp), sub);
            subIssues.save(sub); // no status change, no event
        }
        for (String fp : plan.solved()) {
            SubIssue sub = subByFp.get(fp);
            sub.solve();
            subIssues.save(sub);
            events.save(TriageEvent.system(workspaceId, sub.getId(), SubIssue.STATUS_OPEN,
                    SubIssue.STATUS_SOLVED, TriageEvent.REASON_SOLVED_NOT_DETECTED, scanId));
            touchedGroups.add(sub.getGroupId());
        }

        for (UUID groupId : touchedGroups) {
            recomputeGroupStatus(workspaceId, groupId);
        }

        scan.markReconciled();
        scans.save(scan);
        log.debug("Scan {} reconciled: {} new, {} reopened, {} solved, {} seen", scanId,
                plan.newOpen().size(), plan.reopened().size(), plan.solved().size(), plan.seen().size());
    }

    /**
     * D-029: a changed {@code SECSCAN_FINGERPRINT_KEY} re-fingerprints every secret, so a stored secret
     * sub-issue whose {@code key_id} differs from the current one is a <em>superseded</em> identity. Rather than
     * freeze the whole secret scanner (which would also stop current-key secrets reconciling), this partitions:
     * secret sub-issues under the current key id reconcile normally (solve/reopen), while those under any other
     * key id are frozen — never solved, never reopened, no event — by returning their fingerprints as the
     * reconciler's frozen set. A single error names only the count and the key id(s) (both non-reversible),
     * never a secret. Operators must clean up the old-key rows manually (bulk ignore arrives in M7); the key
     * must never change once secrets are stored.
     *
     * @return the fingerprints to freeze this scan (empty when the key is unchanged or unconfigured)
     */
    private Set<String> secretKeyGuard(List<SubIssue> existingSubs) {
        if (!fingerprinter.isConfigured()) {
            return Set.of();
        }
        String currentKeyId = fingerprinter.require().keyId();
        Set<String> frozen = new HashSet<>();
        Set<String> staleKeyIds = new TreeSet<>();
        for (SubIssue s : existingSubs) {
            if (ScanFinding.TYPE_SECRET.equals(s.getType())
                    && s.getKeyId() != null && !currentKeyId.equals(s.getKeyId())) {
                frozen.add(s.getFingerprint());
                staleKeyIds.add(s.getKeyId());
            }
        }
        if (!frozen.isEmpty()) {
            log.error("SECSCAN_FINGERPRINT_KEY has changed: {} secret sub-issue(s) were fingerprinted under key "
                    + "id(s) {} but the current key id is {}. Those rows are frozen (never solved or reopened) to "
                    + "avoid mass-solving re-fingerprinted secrets; secrets under the current key reconcile "
                    + "normally. Clean up the old-key rows manually (bulk ignore arrives in M7); the key must "
                    + "never change once secrets are stored (D-029).", frozen.size(), staleKeyIds, currentKeyId);
        }
        return frozen;
    }

    private IssueGroup upsertGroup(UUID workspaceId, UUID repositoryId, ScanFinding f) {
        String groupKey = Fingerprints.groupKey(f.getType(), discriminator(f));
        return groups.findByWorkspaceIdAndRepositoryIdAndGroupKey(workspaceId, repositoryId, groupKey)
                .orElseGet(() -> groups.save(new IssueGroup(workspaceId, repositoryId, f.getType(), groupKey,
                        title(f), null)));
    }

    /** The grouping dimension per type (SPEC). M2 produces only secrets; the rest are exercised in M4/M6. */
    private static String discriminator(ScanFinding f) {
        return switch (f.getType()) {
            case ScanFinding.TYPE_SCA -> f.getPackageName();
            case ScanFinding.TYPE_SAST, ScanFinding.TYPE_SECRET, ScanFinding.TYPE_IAC -> f.getRuleId();
            case ScanFinding.TYPE_EOL -> f.getPackageName();
            default -> f.getRuleId();
        };
    }

    private static String title(ScanFinding f) {
        if (ScanFinding.TYPE_SECRET.equals(f.getType())) {
            return "Exposed " + f.getRuleId();
        }
        // SCA/SAST/IaC/EOL get plain-language titles with their scanners (M4/M6); a safe default for now.
        return f.getRuleId() != null ? f.getRuleId() : f.getPackageName();
    }

    /** Computes a finding's fingerprint from its stored fields (domain rule 2, never the line number). */
    private static String fingerprintOf(ScanFinding f) {
        return switch (f.getType()) {
            case ScanFinding.TYPE_SCA -> Fingerprints.sca(f.getPackageName(), f.getVulnId(), f.getFilePath());
            case ScanFinding.TYPE_SAST -> Fingerprints.sast(f.getRuleId(), f.getFilePath(), f.getSnippetHash());
            case ScanFinding.TYPE_SECRET -> Fingerprints.secret(f.getRuleId(), f.getFilePath(), f.getSecretHash());
            case ScanFinding.TYPE_IAC -> Fingerprints.iac(f.getRuleId(), f.getFilePath(), f.getPackageName());
            case ScanFinding.TYPE_EOL -> Fingerprints.eol(f.getPackageName(), f.getInstalledVersion(),
                    f.getFilePath());
            default -> throw new IllegalStateException("Unknown finding type: " + f.getType());
        };
    }

    private void copyDescriptive(ScanFinding f, SubIssue sub) {
        sub.setVulnId(f.getVulnId());
        sub.setRuleId(f.getRuleId());
        sub.setPackageName(f.getPackageName());
        sub.setInstalledVersion(f.getInstalledVersion());
        sub.setFixedVersion(f.getFixedVersion());
        sub.setFilePath(f.getFilePath());
        sub.setLine(f.getLine());
        sub.setSnippetHash(f.getSnippetHash());
        sub.setSecretHash(f.getSecretHash());
        sub.setSecretPreview(f.getSecretPreview());
        sub.setToolSeverity(f.getToolSeverity());
        if (ScanFinding.TYPE_SECRET.equals(f.getType()) && fingerprinter.isConfigured()) {
            sub.setKeyId(fingerprinter.require().keyId());
        }
    }

    private void recomputeGroupStatus(UUID workspaceId, UUID groupId) {
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndGroupId(workspaceId, groupId);
        boolean anyOpen = subs.stream().anyMatch(s -> SubIssue.STATUS_OPEN.equals(s.getStatus()));
        boolean allIgnored = !subs.isEmpty()
                && subs.stream().allMatch(s -> SubIssue.STATUS_IGNORED.equals(s.getStatus()));
        String status = anyOpen ? IssueGroup.STATUS_OPEN
                : allIgnored ? IssueGroup.STATUS_IGNORED : IssueGroup.STATUS_SOLVED;
        groups.findByIdAndWorkspaceId(groupId, workspaceId).ifPresent(g -> {
            g.setStatus(status);
            groups.save(g);
        });
    }

    private static ScanFinding findByFp(Map<String, ScanFinding> findingByFp, String fp) {
        ScanFinding f = findingByFp.get(fp);
        if (f == null) {
            throw new IllegalStateException("No scan finding for fingerprint " + fp);
        }
        return f;
    }
}
