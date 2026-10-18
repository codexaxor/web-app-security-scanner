package com.company.secscan.issue;

import com.company.secscan.intel.FixTimeConfig;
import com.company.secscan.intel.FixTimeConfigRepository;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scoring.CweDictionary;
import com.company.secscan.scoring.FixTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Recomputes a group's derived status (M3) and its M5 rollup — {@code severity_score} = max open sub-issue
 * score, {@code fix_time_minutes} = sum of open sub-issues' fix times, and (sca/sast/iac) the subtitle from the
 * worst open sub-issue's CWE phrase (SPEC "Issue lifecycle"/"Grouping"/"Fix time"). Shared by scan-time
 * reconcile and the api re-score job so both roll groups up identically. Writes no triage_event and never
 * changes a sub-issue.
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

    /** Recomputes status, severity, fix-time and subtitle for one group. */
    public void recompute(UUID workspaceId, UUID groupId, Map<String, Integer> fixConfig) {
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndGroupId(workspaceId, groupId);
        boolean anyOpen = subs.stream().anyMatch(s -> SubIssue.STATUS_OPEN.equals(s.getStatus()));
        boolean allIgnored = !subs.isEmpty()
                && subs.stream().allMatch(s -> SubIssue.STATUS_IGNORED.equals(s.getStatus()));
        String status = anyOpen ? IssueGroup.STATUS_OPEN
                : allIgnored ? IssueGroup.STATUS_IGNORED : IssueGroup.STATUS_SOLVED;

        List<SubIssue> open = subs.stream().filter(s -> SubIssue.STATUS_OPEN.equals(s.getStatus())).toList();
        Integer severity = open.stream().map(SubIssue::getScore).filter(Objects::nonNull)
                .max(Integer::compareTo).orElse(null);
        Integer fixMinutes = open.isEmpty() ? null : open.stream()
                .mapToInt(s -> FixTime.minutesFor(s.getType(), s.getInstalledVersion(), s.getFixedVersion(),
                        fixConfig))
                .sum();

        groups.findByIdAndWorkspaceId(groupId, workspaceId).ifPresent(g -> {
            g.setStatus(status);
            g.setSeverityScore(severity);
            g.setFixTimeMinutes(fixMinutes);
            String subtitle = subtitleFor(g.getType(), worstOpen(open));
            if (subtitle != null) {
                g.setSubtitle(subtitle);
            }
            groups.save(g);
        });
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
     */
    private static String subtitleFor(String groupType, SubIssue worst) {
        if (worst == null) {
            return null;
        }
        return switch (groupType) {
            case ScanFinding.TYPE_SCA, ScanFinding.TYPE_SAST, ScanFinding.TYPE_IAC ->
                    CweDictionary.getInstance().phrase(worst.getCwe(), worst.getAdvisorySummary());
            default -> null;
        };
    }
}
