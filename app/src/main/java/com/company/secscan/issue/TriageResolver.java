package com.company.secscan.issue;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.triage.DuplicateRuleMap;
import com.company.secscan.triage.EpssPolicy;
import com.company.secscan.triage.IgnoreMatcher;
import com.company.secscan.triage.PathExclusion;
import com.company.secscan.triage.ReasonAssignment;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.triage.SecretPlaceholder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SINGLE desired-ignore-state resolver (D-063, review S5): given a sub-issue, the active ignore rules and the
 * auto-cause context, it computes the one ({@code status}, {@code reason_code}, {@code rule_id}) a finding should
 * hold, with a FIXED precedence — {@link ReasonCode}'s declaration order, applied once via
 * {@link ReasonAssignment#highest} (no duplicate ordering logic). Used by BOTH reconcile ({@link TriageApplier})
 * and the API ({@link TriageService}, {@link com.company.secscan.workspace.WorkspaceSettingsService}), so a
 * finding's ignore state is identical however it is reached.
 *
 * <p>A manual or {@code .secscan.yml} {@code IGNORE_RULE} outranks every auto cause. Among matching rules the
 * holder is the earliest by {@code (created_at, id)} ({@link #RULE_ORDER}). The API cannot see scan-time-only auto
 * causes (config {@code exclude.paths}, declared dev/test scope, duplicate SAST rule), so its context omits them;
 * the path-based causes (default-glob {@code EXCLUDED_PATH}, {@code SECRET_PLACEHOLDER}) and {@code LOW_EPSS} are
 * derivable from persisted state and ARE evaluated there, so removing a rule keeps a finding ignored when such a
 * cause still applies.
 */
public final class TriageResolver {

    /** Rule precedence when several match the same finding: earliest created, then id, is the holder (D-063). */
    static final Comparator<IgnoreRule> RULE_ORDER = Comparator
            .comparing(IgnoreRule::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(IgnoreRule::getId);

    private static final DuplicateRuleMap DUPLICATE_RULES = DuplicateRuleMap.defaults();

    private TriageResolver() {
    }

    /** The desired ignore for a finding: the reason code plus the holding rule id (null for a cause-based auto). */
    public record Desired(String reasonCode, java.util.UUID ruleId) {
    }

    /**
     * The auto-cause inputs available for one resolution. Reconcile supplies them all; the API supplies only the
     * subset derivable from persisted state via {@link #api(boolean)} (path causes + the EPSS toggle).
     */
    public record AutoContext(List<String> configExcludePaths, boolean effectiveIncludeDev, ScopeLookup scopes,
                       Map<String, Set<String>> sastRuleIdsByFile, boolean epssEnabled) {

        public AutoContext {
            configExcludePaths = configExcludePaths == null ? List.of() : List.copyOf(configExcludePaths);
            scopes = scopes == null ? (m, c) -> null : scopes;
            sastRuleIdsByFile = sastRuleIdsByFile == null ? Map.of() : sastRuleIdsByFile;
        }

        /** The API context: no scan-time config/scope/duplicate inputs; EPSS toggle as given. */
        public static AutoContext api(boolean epssEnabled) {
            return new AutoContext(List.of(), false, (m, c) -> null, Map.of(), epssEnabled);
        }
    }

    /** Looks up a dependency coordinate's declared scope in a manifest ({@code "test"}/{@code "dev"}), or null. */
    public interface ScopeLookup {
        String scopeOf(String manifestPath, String coordinate);
    }

    /**
     * The desired ignore for {@code sub}, or null when it should not be ignored. {@code rules} is the active rule
     * set (any order; it is sorted by {@link #RULE_ORDER} here). A matching rule → {@code IGNORE_RULE} held by the
     * earliest rule; otherwise the highest-precedence auto cause.
     */
    public static Desired resolve(SubIssue sub, String groupKey, List<IgnoreRule> rules, AutoContext ctx) {
        IgnoreMatcher.Finding f = finding(sub, groupKey);
        IgnoreRule matched = firstMatchingRule(f, rules);
        List<ReasonCode> candidates = new ArrayList<>();
        if (matched != null) {
            candidates.add(ReasonCode.IGNORE_RULE);
        }
        candidates.addAll(autoReasons(sub, ctx));
        return ReasonAssignment.highest(candidates)
                .map(r -> new Desired(r.code(), r == ReasonCode.IGNORE_RULE ? matched.getId() : null))
                .orElse(null);
    }

    /** The earliest ({@code created_at}, {@code id}) rule that matches {@code f}, or null (D-063 ordering). */
    static IgnoreRule firstMatchingRule(IgnoreMatcher.Finding f, List<IgnoreRule> rules) {
        return rules.stream()
                .filter(r -> IgnoreMatcher.matches(
                        new IgnoreMatcher.Rule(r.getScope(), r.getRepositoryId(), r.getMatchValue()), f))
                .min(RULE_ORDER)
                .orElse(null);
    }

    /** Every auto-ignore cause that applies to {@code sub} given {@code ctx} (order-independent; caller ranks). */
    static List<ReasonCode> autoReasons(SubIssue sub, AutoContext ctx) {
        List<ReasonCode> out = new ArrayList<>();
        // D-064: default dirs + minified rules do NOT apply to secrets; an explicit .secscan.yml path always does.
        boolean applyDefaults = !RawFinding.TYPE_SECRET.equals(sub.getType());
        if (PathExclusion.isExcluded(sub.getFilePath(), ctx.configExcludePaths(), applyDefaults)) {
            out.add(ReasonCode.EXCLUDED_PATH);
        }
        if (RawFinding.TYPE_SECRET.equals(sub.getType()) && SecretPlaceholder.isPlaceholder(sub.getFilePath())) {
            out.add(ReasonCode.SECRET_PLACEHOLDER);
        }
        if (RawFinding.TYPE_SAST.equals(sub.getType()) && DUPLICATE_RULES.shouldIgnore(sub.getRuleId(),
                ctx.sastRuleIdsByFile().getOrDefault(PathExclusion.normalize(sub.getFilePath()), Set.of()))) {
            out.add(ReasonCode.DUPLICATE_RULE);
        }
        if (RawFinding.TYPE_SCA.equals(sub.getType()) && !ctx.effectiveIncludeDev()) {
            String scope = ctx.scopes().scopeOf(sub.getFilePath(), sub.getPackageName());
            if ("test".equals(scope)) {
                out.add(ReasonCode.TEST_SCOPE);
            } else if ("dev".equals(scope)) {
                out.add(ReasonCode.DEV_DEPENDENCY);
            }
        }
        if (ctx.epssEnabled() && EpssPolicy.isLowEpssAutoIgnore(epss(sub.getEpss()), sub.isInKev())) {
            out.add(ReasonCode.LOW_EPSS);
        }
        return out;
    }

    private static IgnoreMatcher.Finding finding(SubIssue sub, String groupKey) {
        return new IgnoreMatcher.Finding(sub.getType(), sub.getRepositoryId(), sub.getFingerprint(), groupKey,
                sub.getFilePath(), sub.getRuleId(), sub.getVulnId(), sub.getCveAliases());
    }

    private static Double epss(java.math.BigDecimal epss) {
        return epss == null ? null : epss.doubleValue();
    }
}
