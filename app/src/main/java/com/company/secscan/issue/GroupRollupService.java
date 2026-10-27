package com.company.secscan.issue;

import com.company.secscan.intel.FixTimeConfig;
import com.company.secscan.intel.FixTimeConfigRepository;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scoring.CweDictionary;
import com.company.secscan.scoring.FixTime;
import com.company.secscan.taxonomy.Level2Taxonomy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Recomputes a group's derived status (M3) and its M5 rollup — {@code severity_score} = max open sub-issue
 * score, {@code fix_time_minutes} (see {@link #fixTimeMinutes}), and (sca/sast/iac) the subtitle from the worst
 * open sub-issue's CWE phrase (SPEC "Issue lifecycle"/"Grouping"/"Fix time"). Two write shapes:
 * <ul>
 *   <li>scan-time reconcile ({@link #recompute}) owns {@code status} and persists the group via a normal
 *       entity save (status + the three derived columns together);</li>
 *   <li>the api re-score / sensitivity path ({@link #recomputeScoreRollup}) must NEVER write {@code status}
 *       (B1) and must not clobber a concurrent reconcile, so it writes ONLY the derived columns through a
 *       column-scoped {@code UPDATE … WHERE id = ? AND workspace_id = ? AND status = 'open'} (SF-2,
 *       {@link IssueGroupRepository#updateScoreRollupIfOpen}) — a full-entity save would resurrect a stale
 *       {@code status}/score onto a group another transaction has already flipped to ignored/solved.</li>
 * </ul>
 * Writes no triage_event and never changes a sub-issue.
 */
@Service
public class GroupRollupService {

    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;
    private final FixTimeConfigRepository fixTimeConfig;

    public GroupRollupService(IssueGroupRepository groups, SubIssueRepository subIssues,
                              FixTimeConfigRepository fixTimeConfig) {
        this.groups = groups;
        this.subIssues = subIssues;
        this.fixTimeConfig = fixTimeConfig;
    }

    /** The tunable fix-time config as a map; loaded once per reconcile/re-score pass. */
    public Map<String, Integer> loadFixTimeConfig() {
        Map<String, Integer> config = new HashMap<>();
        for (FixTimeConfig c : fixTimeConfig.findAll()) {
            config.put(c.getCategory(), c.getMinutes());
        }
        return config;
    }

    /** Recomputes status, severity, fix-time and subtitle for one group (scan-time reconcile). */
    public void recompute(UUID workspaceId, UUID groupId, Map<String, Integer> fixConfig) {
        rollup(workspaceId, groupId, fixConfig, true);
    }

    /**
     * Recomputes ONLY severity, fix-time and subtitle for one group, from a fresh query over the currently-OPEN
     * sub-issues — never {@code status} (B1). The re-score job uses this: status is owned by reconcile after a
     * scan, so a maintenance re-score must never flip a group open/solved/ignored.
     */
    public void recomputeScoreRollup(UUID workspaceId, UUID groupId, Map<String, Integer> fixConfig) {
        rollup(workspaceId, groupId, fixConfig, false);
    }

    private void rollup(UUID workspaceId, UUID groupId, Map<String, Integer> fixConfig, boolean updateStatus) {
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndGroupId(workspaceId, groupId);
        List<SubIssue> open = subs.stream().filter(s -> SubIssue.STATUS_OPEN.equals(s.getStatus())).toList();
        Integer severity = open.stream().map(SubIssue::getScore).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);

        groups.findByIdAndWorkspaceId(groupId, workspaceId).ifPresent(g -> {
            Integer fixTime = fixTimeMinutes(g.getType(), open, fixConfig);
            String subtitle = subtitleFor(g.getType(), worstOpen(open));
            if (updateStatus) {
                // Reconcile owns status: persist status + derived columns together via a normal save.
                g.setStatus(deriveStatus(subs, open));
                g.setSeverityScore(severity);
                g.setFixTimeMinutes(fixTime);
                if (subtitle != null) {
                    g.setSubtitle(subtitle);
                }
                // M8.5 (D-083): the level-2 category + optional OWASP 2025 name, derived from a representative
                // sub-issue's stored fields (all subs in a group share the categorizing fields). Descriptive,
                // never a status/fingerprint input.
                SubIssue rep = worstOpen(open);
                if (rep == null && !subs.isEmpty()) {
                    rep = subs.get(0);
                }
                if (rep != null) {
                    Level2Taxonomy.Category cat = Level2Taxonomy.getInstance().of(rep.getType(), rep.getFilePath(),
                            rep.getRuleId(), rep.getCwe(), rep.getPackageName());
                    g.setLevel2Category(cat.level2());
                    g.setOwaspCategory(cat.owasp());
                    // D-083: refresh the IaC title from the latest Checkov check_name (carried in advisory_summary),
                    // so groups created before this behaviour pick up the human title on their next scan. Title is
                    // descriptive; the fingerprint is unchanged.
                    if (ScanFinding.TYPE_IAC.equals(g.getType()) && rep.getAdvisorySummary() != null) {
                        g.setTitle(rep.getAdvisorySummary());
                    }
                } else {
                    // N-2: a group with no sub-issues still gets its type's fallback category (never left null).
                    Level2Taxonomy.Category cat = Level2Taxonomy.getInstance()
                            .of(g.getType(), null, null, null, null);
                    g.setLevel2Category(cat.level2());
                    g.setOwaspCategory(cat.owasp());
                }
                groups.save(g);
            } else {
                // Score-only path (SF-2): column-scoped UPDATE guarded by status='open'. Never writes status and
                // cannot resurrect a score onto a group a concurrent reconcile has flipped to ignored/solved.
                groups.updateScoreRollupIfOpen(groupId, workspaceId, severity, fixTime, subtitle);
            }
        });
    }

    private static String deriveStatus(List<SubIssue> subs, List<SubIssue> open) {
        boolean allIgnored = !subs.isEmpty()
                && subs.stream().allMatch(s -> SubIssue.STATUS_IGNORED.equals(s.getStatus()));
        return !open.isEmpty() ? IssueGroup.STATUS_OPEN
                : allIgnored ? IssueGroup.STATUS_IGNORED : IssueGroup.STATUS_SOLVED;
    }

    /**
     * Group fix time in minutes from the OPEN sub-issues (D-052). SCA is remediated by ONE dependency upgrade, so
     * its fix time is the <em>largest</em> single category among the open sub-issues (a major bump if any open
     * vuln needs one, else patch/minor) — NOT the sum, which would multiply one upgrade by the number of CVEs on
     * the package. Every other type (one fix per finding) keeps the sum of its open sub-issues' fix times.
     * Returns null when nothing is open.
     */
    private static Integer fixTimeMinutes(String groupType, List<SubIssue> open, Map<String, Integer> fixConfig) {
        if (open.isEmpty()) {
            return null;
        }
        if (ScanFinding.TYPE_SCA.equals(groupType)) {
            // SCA: one upgrade clears the group → the single worst category, never the sum (D-052).
            return open.stream().mapToInt(s -> perSubMinutes(s, fixConfig)).max().orElse(0);
        }
        // Every other type: one fix per finding → the sum of the open sub-issues (SHOULD-FIX 4).
        return open.stream().mapToInt(s -> perSubMinutes(s, fixConfig)).sum();
    }

    /** Fix-time minutes for one sub-issue: SAST is categorized by its CWE (injection vs default), every other
     * type by {@link FixTime#minutesFor} (SPEC "Fix time"; M6). */
    private static int perSubMinutes(SubIssue s, Map<String, Integer> fixConfig) {
        if (ScanFinding.TYPE_SAST.equals(s.getType())) {
            return FixTime.minutes(FixTime.sastCategory(s.getCwe()), fixConfig);
        }
        return FixTime.minutesFor(s.getType(), s.getInstalledVersion(), s.getFixedVersion(), fixConfig);
    }

    /** The worst (highest-scored) open sub-issue, or null when none is open; nulls sort lowest, ties keep first. */
    private static SubIssue worstOpen(List<SubIssue> open) {
        SubIssue worst = null;
        for (SubIssue s : open) {
            int score = s.getScore() == null ? -1 : s.getScore();
            int best = worst == null ? -2 : (worst.getScore() == null ? -1 : worst.getScore());
            if (worst == null || score > best) {
                worst = s;
            }
        }
        return worst;
    }

    /**
     * Subtitle for a group type (change #4): sca/sast/iac from the worst open sub-issue's CWE phrase (first cwe
     * id, else the advisory summary); secret/eol keep their SPEC title (null → caller leaves the subtitle as is).
     * The subtitle is also left unchanged (null returned) when the worst open sub-issue has neither a known CWE
     * phrase nor an advisory summary, so an enrichment gap never blanks an existing subtitle (N8).
     */
    private static String subtitleFor(String groupType, SubIssue worst) {
        if (worst == null) {
            return null;
        }
        return switch (groupType) {
            case ScanFinding.TYPE_SCA, ScanFinding.TYPE_SAST ->
                    CweDictionary.getInstance().phrase(worst.getCwe(), worst.getAdvisorySummary());
            // D-083: IaC subtitle is the check_id — the title is the human check_name, so the subtitle must NOT
            // repeat it (advisory_summary now holds the check_name for IaC).
            case ScanFinding.TYPE_IAC -> worst.getRuleId();
            default -> null;
        };
    }
}
