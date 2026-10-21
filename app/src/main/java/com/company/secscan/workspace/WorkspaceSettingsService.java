package com.company.secscan.workspace;

import com.company.secscan.issue.GroupRollupService;
import com.company.secscan.issue.ReScoreService;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.TriageEvent;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.issue.TriageStatusResolver;
import com.company.secscan.triage.EpssPolicy;
import com.company.secscan.triage.ReasonCode;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Per-workspace settings (M7): the EPSS auto-ignore toggle (D-060), default off. Toggling it is a real,
 * audited STATUS change (NOT the score-only re-score path): turning it ON auto-ignores open findings with
 * EPSS &lt; 1% and not on KEV as {@link ReasonCode#LOW_EPSS}; turning it OFF clears those LOW_EPSS ignores via
 * their cause, back to the reconcile-computed status. Each change writes a {@code triage_event}. It then
 * re-scores open findings so the scoring-only tiers (−10 / −5) are applied/removed with no triage_event.
 */
@Profile("api")
@Service
public class WorkspaceSettingsService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSettingsService.class);

    private final WorkspaceRepository workspaces;
    private final SubIssueRepository subIssues;
    private final TriageEventRepository events;
    private final GroupRollupService rollup;
    private final TriageStatusResolver statusResolver;
    private final ReScoreService reScore;

    public WorkspaceSettingsService(WorkspaceRepository workspaces, SubIssueRepository subIssues,
                                    TriageEventRepository events, GroupRollupService rollup,
                                    TriageStatusResolver statusResolver, ReScoreService reScore) {
        this.workspaces = workspaces;
        this.subIssues = subIssues;
        this.events = events;
        this.rollup = rollup;
        this.statusResolver = statusResolver;
        this.reScore = reScore;
    }

    @Transactional(readOnly = true)
    public WorkspaceSettings get(UUID workspaceId) {
        return load(workspaceId).getSettings();
    }

    /** Set the EPSS auto-ignore toggle and re-evaluate LOW_EPSS across the workspace (status + scoring). */
    @Transactional
    public WorkspaceSettings setEpssAutoIgnore(UUID workspaceId, boolean enabled) {
        Workspace ws = load(workspaceId);
        WorkspaceSettings settings = ws.getSettings();
        settings.setEpssAutoIgnoreEnabled(enabled);
        ws.setSettings(settings);
        workspaces.save(ws);

        Set<UUID> touched = enabled ? autoIgnoreLowEpss(workspaceId) : clearLowEpss(workspaceId);
        if (!touched.isEmpty()) {
            Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
            for (UUID groupId : touched) {
                rollup.recompute(workspaceId, groupId, fixConfig);
            }
        }
        // Apply/remove the scoring-only EPSS tiers on the still-open findings (no status change, no triage_event).
        reScore.rescoreAll();
        log.info("EPSS auto-ignore {} for workspace {}; {} finding(s) changed status",
                enabled ? "enabled" : "disabled", workspaceId, touched.size());
        return settings;
    }

    private Set<UUID> autoIgnoreLowEpss(UUID workspaceId) {
        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndStatus(workspaceId, SubIssue.STATUS_OPEN)) {
            if (EpssPolicy.isLowEpssAutoIgnore(toDouble(sub.getEpss()), sub.isInKev())) {
                sub.ignore(ReasonCode.LOW_EPSS.code(), null);
                subIssues.save(sub);
                events.save(TriageEvent.system(workspaceId, sub.getId(), SubIssue.STATUS_OPEN,
                        SubIssue.STATUS_IGNORED, ReasonCode.LOW_EPSS.code(), null));
                touched.add(sub.getGroupId());
            }
        }
        return touched;
    }

    private Set<UUID> clearLowEpss(UUID workspaceId) {
        Set<UUID> touched = new HashSet<>();
        List<SubIssue> ignored = subIssues.findByWorkspaceIdAndStatus(workspaceId, SubIssue.STATUS_IGNORED);
        for (SubIssue sub : ignored) {
            if (ReasonCode.LOW_EPSS.code().equals(sub.getIgnoreReason())) {
                String newStatus = statusResolver.resolve(sub);
                sub.unignore(newStatus);
                subIssues.save(sub);
                events.save(TriageEvent.system(workspaceId, sub.getId(), SubIssue.STATUS_IGNORED, newStatus,
                        TriageEvent.REASON_UNIGNORED, null));
                touched.add(sub.getGroupId());
            }
        }
        return touched;
    }

    private Workspace load(UUID workspaceId) {
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such workspace."));
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
