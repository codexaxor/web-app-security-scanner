package com.company.secscan.triage;

import com.company.secscan.finding.RawFinding;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether an ignore rule (D-058) matches a finding. Pure, no Spring: the app maps its {@code ignore_rule}
 * row to a {@link Rule} and its {@code sub_issue} to a {@link Finding}.
 *
 * <p>Scopes (SPEC "Manual ignore scopes"):
 * <ul>
 *   <li>{@code subissue} — the single finding. For SAST this matches on {@code (rule_id, file_path)}, snippet-
 *       independent (D-062), so a re-fingerprinted finding for the same rule+file stays ignored; for every other
 *       type it matches on the fingerprint.</li>
 *   <li>{@code group} — the group key.</li>
 *   <li>{@code path} — a repo-relative path prefix.</li>
 *   <li>{@code cve} — the vuln id or any CVE alias.</li>
 *   <li>{@code rule} — the rule id (all files).</li>
 * </ul>
 * A rule with a null {@code repositoryId} is global (every repository in the workspace); otherwise it applies
 * only to its own repository. Workspace scoping is enforced by the caller's query (domain rule 1).
 */
public final class IgnoreMatcher {

    /** The subset of an {@code ignore_rule} the match needs. */
    public record Rule(String scope, java.util.UUID repositoryId, String matchValue) {
    }

    /** The subset of a {@code sub_issue} the match needs. */
    public record Finding(String type, java.util.UUID repositoryId, String fingerprint, String groupKey,
                          String filePath, String ruleId, String vulnId, List<String> cveAliases) {
    }

    /** The separator used to encode a SAST subissue rule's {@code ruleId|filePath} match value (D-062). */
    public static final String SAST_SUBISSUE_SEP = "|";

    private IgnoreMatcher() {
    }

    /** The {@code match_value} for a {@code subissue}-scope rule targeting {@code finding} (SAST: rule|file; D-062). */
    public static String subIssueMatchValue(Finding finding) {
        if (RawFinding.TYPE_SAST.equals(finding.type())) {
            return finding.ruleId() + SAST_SUBISSUE_SEP + PathExclusion.normalize(finding.filePath());
        }
        return finding.fingerprint();
    }

    /** True when {@code rule} applies to {@code finding}'s repository (global rule, or same repository). */
    public static boolean appliesToRepository(Rule rule, Finding finding) {
        return rule.repositoryId() == null || rule.repositoryId().equals(finding.repositoryId());
    }

    /** True when {@code rule} matches {@code finding} (repository scope AND the per-scope value). */
    public static boolean matches(Rule rule, Finding finding) {
        if (!appliesToRepository(rule, finding)) {
            return false;
        }
        String value = rule.matchValue();
        if (value == null) {
            return false;
        }
        return switch (rule.scope()) {
            case IgnoreRuleScopes.SUBISSUE -> matchesSubIssue(value, finding);
            case IgnoreRuleScopes.GROUP -> value.equals(finding.groupKey());
            case IgnoreRuleScopes.PATH -> matchesPathPrefix(value, finding.filePath());
            case IgnoreRuleScopes.CVE -> matchesCve(value, finding);
            case IgnoreRuleScopes.RULE -> value.equals(finding.ruleId());
            default -> false;
        };
    }

    private static boolean matchesSubIssue(String value, Finding finding) {
        return value.equals(subIssueMatchValue(finding));
    }

    private static boolean matchesPathPrefix(String prefixRaw, String filePath) {
        String prefix = PathExclusion.normalize(prefixRaw);
        String path = PathExclusion.normalize(filePath);
        if (prefix.isEmpty() || path.isEmpty()) {
            return false;
        }
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }

    private static boolean matchesCve(String cve, Finding finding) {
        String target = cve.toUpperCase(Locale.ROOT);
        if (finding.vulnId() != null && finding.vulnId().toUpperCase(Locale.ROOT).equals(target)) {
            return true;
        }
        if (finding.cveAliases() != null) {
            for (String alias : finding.cveAliases()) {
                if (alias != null && alias.toUpperCase(Locale.ROOT).equals(target)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Scope string constants — mirror of {@code IgnoreRule.SCOPE_*} (kept in core so the matcher is Spring-free). */
    public static final class IgnoreRuleScopes {
        public static final String SUBISSUE = "subissue";
        public static final String GROUP = "group";
        public static final String PATH = "path";
        public static final String CVE = "cve";
        public static final String RULE = "rule";

        private IgnoreRuleScopes() {
        }
    }
}
