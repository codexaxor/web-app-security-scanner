package com.company.secscan.issue;

import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.triage.PathExclusion;
import com.company.secscan.triage.ReasonCode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Triage step 9 of reconcile (M7, D-057/D-058/D-059/D-063): applies ignores and clears them, always through
 * reconcile and always with a {@code triage_event} (domain rules 2-4). It is the SINGLE place the scan-time path
 * moves a finding to/from {@code ignored}; the desired state itself is computed by {@link TriageResolver}, the one
 * resolver shared with the API, so behaviour is identical however a finding is reached.
 *
 * <p>For each non-solved sub-issue (N4): the resolver returns the highest-precedence desired reason/rule — a
 * matching {@code ignore_rule} ({@code IGNORE_RULE}) outranks every cause-based auto reason (EXCLUDED_PATH,
 * SECRET_PLACEHOLDER, DUPLICATE_RULE, DEV_DEPENDENCY, TEST_SCOPE, LOW_EPSS). Transitions: not-ignored → ignored
 * (event); ignored whose cause/rule is gone → unignore to the reconcile-computed status (event,
 * {@link TriageEvent#REASON_UNIGNORED}); already-ignored-and-still-desired → unchanged, or a quiet reason/rule
 * refresh with no event (hard rule 6). An ignored finding is never solved.
 *
 * <p>Separately, every reconcile writes {@code sub.auto_ignore_reason} = the highest AUTO cause (SF1), even when
 * an IGNORE_RULE holds the finding and never as a status change, so the API can keep a finding ignored under a
 * scan-time-only cause it cannot recompute.
 *
 * <p>Freeze (S3/SF2/SF3): a present-but-unusable {@code .secscan.yml}, a failed scope inventory, or a specific
 * unreadable manifest leaves that source's prior ignores untouched (no revoke, no unignore, no new ignore, prior
 * {@code auto_ignore_reason} kept); every other source still runs.
 *
 * <p>{@code .secscan.yml} CVE ignores are reconciled to the CURRENT file each scan (D-057 revoke lifecycle, unless
 * the config is frozen): new ones are created, revoked ones deleted (the FK is {@code ON DELETE SET NULL}).
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
        // Review S3 freeze: a present-but-unusable .secscan.yml or a failed scope inventory must not revoke or
        // unignore anything it governed. A frozen config SKIPS the CVE revoke lifecycle (its materialized rules
        // persist, so their findings stay IGNORE_RULE) and the exclude.paths are dropped from the auto-context so
        // no NEW config-path ignore is created; the unignore guard below then also leaves prior config-/scope-
        // derived ignores untouched. Every other triage source still runs.
        boolean configFrozen = triage.config().frozen();
        // SF2: an unreadable config also freezes dev/test for the whole scan (its include_dev_dependencies — and
        // thus the effective-include-dev — is unknown, so we must not flip scope-derived ignores on a guess).
        boolean globalScopeFrozen = triage.scopeInventoryFailed() || configFrozen;
        Set<String> unreadableManifests = triage.unreadableManifestsNormalized(); // SF3: per-manifest freeze
        if (!configFrozen) {
            materializeSecscanCveRules(workspaceId, repositoryId, triage.config());
        }

        List<IgnoreRule> rules = ignoreRules.findByWorkspaceId(workspaceId);
        Map<UUID, String> groupKeyById = new HashMap<>();
        for (IssueGroup g : groups.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)) {
            groupKeyById.put(g.getId(), g.getGroupKey());
        }

        TriageResolver.AutoContext ctx = autoContext(triage, sastRuleIdsByFile, configFrozen,
                globalScopeFrozen, unreadableManifests);
        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndRepositoryId(workspaceId, repositoryId)) {
            if (SubIssue.STATUS_SOLVED.equals(sub.getStatus())) {
                continue; // N4: auto-ignore evaluates only non-solved findings — a solved finding stays solved
            }
            boolean scopeFrozenForThis = globalScopeFrozen
                    || unreadableManifests.contains(PathExclusion.normalize(sub.getFilePath()));
            // SF1: persist the highest-precedence AUTO cause for this finding every reconcile (no event), even
            // when an IGNORE_RULE holds it — keeping a frozen source's prior value.
            updateAutoIgnoreReason(sub, ctx, configFrozen, scopeFrozenForThis);

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
                } else if (hasIgnoreChanged(sub, desired)
                        && !isFrozenReason(sub, configFrozen, scopeFrozenForThis)) {
                    // Still ignored (hard rule 6) but the holding rule/reason changed — refresh quietly, no event.
                    // Nit: skip the refresh when the CURRENT reason's source is frozen this scan, so an incomplete
                    // recompute never downgrades a frozen-source reason (e.g. TEST_SCOPE → a lower live cause).
                    sub.ignore(desired.reasonCode(), desired.ruleId());
                    subIssues.save(sub);
                }
            } else if (ignored) {
                if (isFrozenReason(sub, configFrozen, scopeFrozenForThis)) {
                    continue; // S3: the source that ignored it is unusable this scan — leave it ignored
                }
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

    /**
     * Writes {@code sub.auto_ignore_reason} to the highest-precedence AUTO cause detected this reconcile (SF1).
     * Never a status change and never a triage_event. When the current stored value came from a source frozen
     * this scan (config → EXCLUDED_PATH, scope → DEV/TEST), it is KEPT — the freeze-adjusted context cannot
     * recompute it, so overwriting would lose it.
     */
    private void updateAutoIgnoreReason(SubIssue sub, TriageResolver.AutoContext ctx, boolean configFrozen,
                                        boolean scopeFrozen) {
        String prior = sub.getAutoIgnoreReason();
        boolean keepPrior = (configFrozen && ReasonCode.EXCLUDED_PATH.code().equals(prior))
                || (scopeFrozen && (ReasonCode.DEV_DEPENDENCY.code().equals(prior)
                        || ReasonCode.TEST_SCOPE.code().equals(prior)));
        if (keepPrior) {
            return;
        }
        String recomputed = TriageResolver.highestAutoReason(sub, ctx);
        if (!Objects.equals(prior, recomputed)) {
            sub.setAutoIgnoreReason(recomputed);
            subIssues.save(sub); // column-only write; no status change, no event
        }
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
                                                          Map<String, Set<String>> sastRuleIdsByFile,
                                                          boolean configFrozen, boolean globalScopeFrozen,
                                                          Set<String> unreadableManifests) {
        SecscanConfig config = triage.config();
        // A frozen config contributes no exclude.paths (S3) — no NEW config-path ignore while it is unusable.
        List<String> extraPaths = (config.present() && !configFrozen) ? config.excludePaths() : List.of();
        // The scope lookup returns null (no new DEV/TEST) when scope is frozen globally (SF2) or for THIS
        // manifest (SF3); otherwise it defers to the per-(manifest,coordinate) declared scope.
        TriageResolver.ScopeLookup scopes = (manifest, coordinate) ->
                (globalScopeFrozen || unreadableManifests.contains(PathExclusion.normalize(manifest)))
                        ? null : triage.scopeOf(manifest, coordinate);
        return new TriageResolver.AutoContext(extraPaths, triage.effectiveIncludeDev(), scopes,
                sastRuleIdsByFile, triage.epssAutoIgnoreEnabled());
    }

    /**
     * True when a currently-ignored finding's reason came from a source frozen this scan (S3/SF2/SF3), so it must
     * be left ignored rather than unignored: a config-derived {@code EXCLUDED_PATH} while the config is frozen, or
     * a scope-derived {@code DEV_DEPENDENCY}/{@code TEST_SCOPE} while scope is frozen for this finding (global
     * failure, unreadable config, or this manifest unreadable). A default-glob EXCLUDED_PATH still resolves to a
     * desired, so it never reaches the unignore branch anyway.
     */
    private static boolean isFrozenReason(SubIssue sub, boolean configFrozen, boolean scopeFrozen) {
        String reason = sub.getIgnoreReason();
        if (configFrozen && ReasonCode.EXCLUDED_PATH.code().equals(reason)) {
            return true;
        }
        return scopeFrozen && (ReasonCode.DEV_DEPENDENCY.code().equals(reason)
                || ReasonCode.TEST_SCOPE.code().equals(reason));
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
