package com.company.secscan.issue;

import com.company.secscan.scan.parse.SecscanConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Triage step 9 of reconcile (M7, D-057/D-058/D-059): applies ignores and clears them, always through reconcile
 * and always with a {@code triage_event} (domain rules 2-4). It is the SINGLE place that moves a finding to/from
 * {@code ignored}; both the scan-time path (here) and the API path ({@code TriageService}, S5) use the same
 * matcher so behaviour is identical.
 *
 * <p>For each sub-issue the DESIRED ignore state is recomputed fresh:
 * <ol>
 *   <li>a matching active {@code ignore_rule} (manual or {@code .secscan.yml}-materialized) → {@code IGNORE_RULE}
 *       (ignored_by_rule_id set);</li>
 *   <li>else a cause-based auto reason — currently {@link ReasonCode#EXCLUDED_PATH} (SPEC default globs +
 *       {@code .secscan.yml exclude.paths}). Other auto reasons (DEV_DEPENDENCY/TEST_SCOPE, SECRET_PLACEHOLDER,
 *       LOW_EPSS, DUPLICATE_RULE) are added in later slices via {@link #autoReason}.</li>
 * </ol>
 * Transitions: not-ignored → ignored (event); ignored whose cause/rule is gone → unignore to the reconcile-computed
 * status (event, {@link TriageEvent#REASON_UNIGNORED}); already-ignored-and-still-desired → unchanged (hard rule 6,
 * no event). An ignored finding is never solved.
 *
 * <p>{@code .secscan.yml} CVE ignores are reconciled to the CURRENT file each scan (D-057 revoke lifecycle): new
 * ones are created, revoked ones deleted (the FK is {@code ON DELETE SET NULL}), and the step then re-evaluates.
 */
@Service
public class TriageApplier {

    private static final Logger log = LoggerFactory.getLogger(TriageApplier.class);

    private final IgnoreRuleRepository ignoreRules;
    private final SubIssueRepository subIssues;
    private final IssueGroupRepository groups;
    private final TriageEventRepository events;

    public TriageApplier(IgnoreRuleRepository ignoreRules, SubIssueRepository subIssues,
                         IssueGroupRepository groups, TriageEventRepository events) {
        this.ignoreRules = ignoreRules;
        this.subIssues = subIssues;
        this.groups = groups;
        this.events = events;
    }

    /**
     * Applies triage for one scan and returns the groups whose rollup must be recomputed. {@code incomingFps} are
     * the fingerprints seen in this scan; {@code solvableScanners} are the scanners authoritative to close findings
     * (so a cleared ignore returns to the correct open/solved — a failed/absent scanner closes nothing, so OPEN).
     */
    public Set<UUID> applyAtReconcile(UUID workspaceId, UUID repositoryId, UUID scanId, TriageContext triage,
                                      Set<String> incomingFps, Set<String> solvableScanners,
                                      Map<String, Set<String>> sastRuleIdsByFile) {
        materializeSecscanCveRules(workspaceId, repositoryId, triage.config());

        List<IgnoreRule> rules = ignoreRules.findByWorkspaceId(workspaceId);
        Map<UUID, String> groupKeyById = new HashMap<>();
        for (IssueGroup g : groups.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)) {
            groupKeyById.put(g.getId(), g.getGroupKey());
        }

        TriageResolver.AutoContext ctx = autoContext(triage, sastRuleIdsByFile);
        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)) {
            if (SubIssue.STATUS_SOLVED.equals(sub.getStatus())) {
                continue; // N4: auto-ignore evaluates only non-solved findings — a solved finding stays solved
            }
            TriageResolver.Desired desired =
                    TriageResolver.resolve(sub, groupKeyById.get(sub.getGroupId()), rules, ctx);
            boolean ignored = SubIssue.STATUS_IGNORED.equals(sub.getStatus());
            if (desired != null) {
                if (!ignored) {
                    String from = sub.getStatus();
                    sub.ignore(desired.reasonCode(), desired.ruleId());
                    subIssues.save(sub);
                    events.save(TriageEvent.system(workspaceId, sub.getId(), from, SubIssue.STATUS_IGNORED,
                            desired.reasonCode(), scanId));
                    touched.add(sub.getGroupId());
                } else if (hasIgnoreChanged(sub, desired)) {
                    // Still ignored (hard rule 6) but the holding rule/reason changed — refresh quietly, no event.
                    sub.ignore(desired.reasonCode(), desired.ruleId());
                    subIssues.save(sub);
                }
            } else if (ignored) {
                String newStatus = reconcileStatus(sub, incomingFps, solvableScanners);
                sub.unignore(newStatus);
                subIssues.save(sub);
                events.save(TriageEvent.system(workspaceId, sub.getId(), SubIssue.STATUS_IGNORED, newStatus,
                        TriageEvent.REASON_UNIGNORED, scanId));
                touched.add(sub.getGroupId());
            }
        }
        return touched;
    }

    /** Reconcile {@code .secscan.yml} CVE ignores to the current file: create new, delete revoked (D-057). */
    private void materializeSecscanCveRules(UUID workspaceId, UUID repositoryId, SecscanConfig config) {
        List<IgnoreRule> existing = ignoreRules.findByWorkspaceIdAndRepositoryIdAndCreatedBy(
                workspaceId, repositoryId, IgnoreRule.CREATED_BY_SECSCAN_YML);
        Map<String, IgnoreRule> byCve = new HashMap<>();
        for (IgnoreRule r : existing) {
            if (IgnoreRule.SCOPE_CVE.equals(r.getScope())) {
                byCve.put(r.getMatchValue(), r);
            }
        }
        Map<String, String> desired = config.present() ? config.ignoreCves() : Map.of();

        for (Map.Entry<String, String> e : desired.entrySet()) {
            IgnoreRule current = byCve.remove(e.getKey());
            if (current == null) {
                ignoreRules.save(new IgnoreRule(workspaceId, IgnoreRule.SCOPE_CVE, repositoryId, e.getKey(),
                        e.getValue(), IgnoreRule.CREATED_BY_SECSCAN_YML));
            }
            // Reason text changes are not re-persisted (the rule's reason is immutable once created; a changed
            // repo reason would require a new rule — kept simple, the CVE match is what suppresses).
        }
        // Whatever remains in byCve is no longer in the file → revoke. ON DELETE SET NULL clears the pointer; the
        // loop above (re-reading active rules) then unignores the affected finding.
        if (!byCve.isEmpty()) {
            ignoreRules.deleteAll(byCve.values());
            ignoreRules.flush();
            log.debug("Revoked {} stale .secscan.yml CVE ignore(s) for repository {}", byCve.size(), repositoryId);
        }
    }

    /**
     * The scan-time auto-cause context (D-063): all causes are available at reconcile — {@code .secscan.yml}
     * exclude paths, the effective include-dev-deps, the per-(manifest,coordinate) scope lookup, this scan's SAST
     * rule-ids-per-file (duplicate-rule), and the workspace EPSS toggle.
     */
    private static TriageResolver.AutoContext autoContext(TriageContext triage,
                                                          Map<String, Set<String>> sastRuleIdsByFile) {
        SecscanConfig config = triage.config();
        List<String> extraPaths = config.present() ? config.excludePaths() : List.of();
        return new TriageResolver.AutoContext(extraPaths, triage.effectiveIncludeDev(), triage::scopeOf,
                sastRuleIdsByFile, triage.epssAutoIgnoreEnabled());
    }

    /** True when the sub is ignored under a different reason/rule than now desired (refresh, no status change). */
    private boolean hasIgnoreChanged(SubIssue sub, TriageResolver.Desired desired) {
        boolean sameReason = desired.reasonCode().equals(sub.getIgnoreReason());
        boolean sameRule = java.util.Objects.equals(desired.ruleId(), sub.getIgnoredByRuleId());
        return !(sameReason && sameRule);
    }

    /** The reconcile-computed status when an ignore is cleared: OPEN if seen now; else SOLVED only if the scanner
     * was authoritative this scan (a failed/absent scanner closes nothing → OPEN, review change #4). */
    private String reconcileStatus(SubIssue sub, Set<String> incomingFps, Set<String> solvableScanners) {
        if (incomingFps.contains(sub.getFingerprint())) {
            return SubIssue.STATUS_OPEN;
        }
        return solvableScanners.contains(sub.getScanner()) ? SubIssue.STATUS_SOLVED : SubIssue.STATUS_OPEN;
    }
}
