package com.company.secscan.issue;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.triage.DuplicateRuleMap;
import com.company.secscan.triage.EpssPolicy;
import com.company.secscan.triage.IgnoreMatcher;
import com.company.secscan.triage.PathExclusion;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.triage.SecretPlaceholder;
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
    private final DuplicateRuleMap duplicateRules = DuplicateRuleMap.defaults();

    public TriageApplier(IgnoreRuleRepository ignoreRules, SubIssueRepository subIssues,
                         IssueGroupRepository groups, TriageEventRepository events) {
        this.ignoreRules = ignoreRules;
        this.subIssues = subIssues;
        this.groups = groups;
        this.events = events;
    }

    /** A desired ignore for one sub-issue: the reason code plus the holding rule id (null for a cause-based auto). */
    private record Desired(String reasonCode, UUID ruleId) {
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

        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)) {
            Desired desired = resolveDesired(sub, rules, triage, groupKeyById.get(sub.getGroupId()),
                    sastRuleIdsByFile);
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

    private Desired resolveDesired(SubIssue sub, List<IgnoreRule> rules, TriageContext triage, String groupKey,
                                   Map<String, Set<String>> sastRuleIdsByFile) {
        IgnoreMatcher.Finding f = new IgnoreMatcher.Finding(sub.getType(), sub.getRepositoryId(),
                sub.getFingerprint(), groupKey, sub.getFilePath(), sub.getRuleId(), sub.getVulnId(),
                sub.getCveAliases());
        // A matching ignore_rule (manual or .secscan.yml) wins — IGNORE_RULE (highest precedence).
        for (IgnoreRule r : rules) {
            if (IgnoreMatcher.matches(new IgnoreMatcher.Rule(r.getScope(), r.getRepositoryId(), r.getMatchValue()),
                    f)) {
                return new Desired(ReasonCode.IGNORE_RULE.code(), r.getId());
            }
        }
        ReasonCode auto = autoReason(sub, triage, sastRuleIdsByFile);
        return auto == null ? null : new Desired(auto.code(), null);
    }

    /**
     * The cause-based auto-ignore reason for a sub-issue, or null, in {@link ReasonCode} precedence order (D-059):
     * EXCLUDED_PATH (defaults + {@code .secscan.yml} paths) → SECRET_PLACEHOLDER → DUPLICATE_RULE (a stock SAST
     * rule whose curated winner fired for the same file this scan, D-061) → DEV_DEPENDENCY/TEST_SCOPE (only when
     * effective include-dev-deps is false, and only for a declared scope — unclassifiable deps stay visible) →
     * LOW_EPSS (EPSS &lt; 1% and not KEV, only when the workspace EPSS toggle is on, D-060).
     */
    private ReasonCode autoReason(SubIssue sub, TriageContext triage, Map<String, Set<String>> sastRuleIdsByFile) {
        SecscanConfig config = triage.config();
        List<String> extraPaths = config.present() ? config.excludePaths() : List.of();
        if (PathExclusion.isExcluded(sub.getFilePath(), extraPaths)) {
            return ReasonCode.EXCLUDED_PATH;
        }
        if (RawFinding.TYPE_SECRET.equals(sub.getType())
                && SecretPlaceholder.isPlaceholder(sub.getFilePath())) {
            return ReasonCode.SECRET_PLACEHOLDER;
        }
        if (RawFinding.TYPE_SAST.equals(sub.getType())
                && duplicateRules.shouldIgnore(sub.getRuleId(),
                        sastRuleIdsByFile.getOrDefault(PathExclusion.normalize(sub.getFilePath()), Set.of()))) {
            return ReasonCode.DUPLICATE_RULE;
        }
        if (RawFinding.TYPE_SCA.equals(sub.getType()) && !triage.effectiveIncludeDev()) {
            String scope = triage.scopeOf(sub.getPackageName());
            if ("test".equals(scope)) {
                return ReasonCode.TEST_SCOPE;
            }
            if ("dev".equals(scope)) {
                return ReasonCode.DEV_DEPENDENCY;
            }
        }
        if (triage.epssAutoIgnoreEnabled()
                && EpssPolicy.isLowEpssAutoIgnore(epssValue(sub.getEpss()), sub.isInKev())) {
            return ReasonCode.LOW_EPSS;
        }
        return null;
    }

    private static Double epssValue(java.math.BigDecimal epss) {
        return epss == null ? null : epss.doubleValue();
    }

    /** True when the sub is ignored under a different reason/rule than now desired (refresh, no status change). */
    private boolean hasIgnoreChanged(SubIssue sub, Desired desired) {
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
