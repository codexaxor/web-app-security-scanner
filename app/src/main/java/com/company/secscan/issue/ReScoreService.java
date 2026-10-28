package com.company.secscan.issue;

import com.company.secscan.issue.SubIssueScorer.Computation;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-scores OPEN sub-issues after a KEV or EPSS refresh without rescanning (D-049). Recomputes
 * {@code score}/{@code score_breakdown}/{@code in_kev}/{@code epss} via the same {@link SubIssueScorer} that
 * scan-time reconcile uses, then rolls the affected groups up. It writes NO {@code triage_event} and never
 * changes a sub-issue's status or fingerprint — scoring is derived and must never feed the fingerprint (domain
 * rule 2), so a re-score can never mass-solve or reopen anything.
 *
 * <p>B1 (no lost update): it never does a full-entity save of a sub-issue. Each row's four score columns are
 * written by a column-scoped UPDATE guarded with {@code status = 'open'}
 * ({@link SubIssueRepository#updateScoreColumnsIfOpen}), so a row that reconcile solved in the window since this
 * sweep read it is skipped and its status/solved_at/last_seen_scan_id are untouched. The group rollup updates
 * only severity/fix-time/subtitle from a fresh query over currently-OPEN rows, never {@code issue_group.status}.
 *
 * <p>This is a system maintenance sweep across all workspaces (like the intel sync it follows, D-050): it
 * iterates each workspace and loads that workspace's open sub-issues, so every sub-issue/repository/group query
 * is workspace-scoped by the sub-issue's own workspace_id (domain rule 1).
 */
@Profile("api")
@Service
public class ReScoreService {

    private static final Logger log = LoggerFactory.getLogger(ReScoreService.class);

    private final WorkspaceRepository workspaces;
    private final SubIssueRepository subIssues;
    private final RepositoryRepository repositories;
    private final SubIssueScorer scorer;
    private final GroupRollupService rollup;
    private final ObjectMapper objectMapper;

    public ReScoreService(WorkspaceRepository workspaces, SubIssueRepository subIssues,
                          RepositoryRepository repositories, SubIssueScorer scorer, GroupRollupService rollup,
                          ObjectMapper objectMapper) {
        this.workspaces = workspaces;
        this.subIssues = subIssues;
        this.repositories = repositories;
        this.scorer = scorer;
        this.rollup = rollup;
        this.objectMapper = objectMapper;
    }

    /** Re-scores every open sub-issue in every workspace. Returns the number of sub-issues recomputed. */
    @Transactional
    public int rescoreAll() {
        Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
        Set<String> unmappedSecretRules = new TreeSet<>();
        int count = 0;
        for (Workspace ws : workspaces.findAll()) {
            count += rescoreWorkspace(ws.getId(), ws.isEpssAutoIgnoreEnabled(), fixConfig, unmappedSecretRules);
        }
        for (String ruleId : unmappedSecretRules) { // once per rule per run, not per sub-issue (N2)
            log.warn("Unmapped secret rule '{}' scored at the default base 75 (D-045); add it to the rule map.",
                    ruleId);
        }
        log.info("Re-score recomputed {} open sub-issue(s)", count);
        return count;
    }

    /**
     * Re-scores every open sub-issue in ONE workspace (N2: the EPSS toggle must touch only its own workspace,
     * not every tenant). Returns the number recomputed.
     */
    @Transactional
    public int rescoreWorkspace(UUID workspaceId) {
        boolean epssEnabled = workspaces.findById(workspaceId)
                .map(Workspace::isEpssAutoIgnoreEnabled).orElse(false);
        Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
        Set<String> unmapped = new TreeSet<>();
        int count = rescoreWorkspace(workspaceId, epssEnabled, fixConfig, unmapped);
        for (String ruleId : unmapped) {
            log.warn("Unmapped secret rule '{}' scored at the default base 75 (D-045); add it to the rule map.",
                    ruleId);
        }
        log.info("Re-score recomputed {} open sub-issue(s) for workspace {}", count, workspaceId);
        return count;
    }

    /**
     * Re-scores every OPEN sub-issue of ONE repository (the per-repo re-score a sensitivity change triggers,
     * D-070/OD-15, change 5). Uses the SAME guard as {@link #rescoreAll}/{@link #rescoreWorkspace}: a
     * column-scoped {@code updateScoreColumnsIfOpen} (status='open') per row and a status-free group rollup, so
     * it writes NO {@code triage_event} and never changes a status or fingerprint. Returns the number recomputed.
     */
    @Transactional
    public int rescoreRepository(UUID workspaceId, UUID repositoryId) {
        boolean epssEnabled = workspaces.findById(workspaceId)
                .map(Workspace::isEpssAutoIgnoreEnabled).orElse(false);
        Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
        String sensitivity = repositories.findByIdAndWorkspaceId(repositoryId, workspaceId)
                .map(Repository::getSensitivity).orElse("normal");
        List<SubIssue> open = subIssues.findByWorkspaceIdAndRepositoryIdAndStatus(
                workspaceId, repositoryId, SubIssue.STATUS_OPEN);
        Set<UUID> touchedGroups = new HashSet<>();
        Set<String> unmapped = new TreeSet<>();
        for (SubIssue sub : open) {
            Computation c = scorer.compute(sub, sensitivity, epssEnabled);
            if (c.unmappedSecretRule() != null) {
                unmapped.add(c.unmappedSecretRule());
            }
            subIssues.updateScoreColumnsIfOpen(sub.getId(), c.score(), writeBreakdown(c.breakdown()),
                    c.inKev(), c.epss());
            touchedGroups.add(sub.getGroupId());
        }
        for (UUID groupId : touchedGroups) {
            rollup.recomputeScoreRollup(workspaceId, groupId, fixConfig);
        }
        for (String ruleId : unmapped) {
            log.warn("Unmapped secret rule '{}' scored at the default base 75 (D-045); add it to the rule map.",
                    ruleId);
        }
        log.info("Re-score recomputed {} open sub-issue(s) for repository {}", open.size(), repositoryId);
        return open.size();
    }

    /**
     * Re-scores ONE open sub-issue immediately — used when a manual unignore reopens a finding (M8 D-2, D-083):
     * its score must reflect the current KEV/EPSS/sensitivity at once, not wait for the next scan or sweep. Same
     * guard as the sweeps: a column-scoped {@code updateScoreColumnsIfOpen} (status='open'), so it writes NO
     * {@code triage_event} and never changes status or the fingerprint. The CALLER recomputes the group rollup.
     * Returns 1 when re-scored, 0 when the sub is absent or not open.
     */
    @Transactional
    public int rescoreSubIssue(UUID workspaceId, UUID subIssueId) {
        SubIssue sub = subIssues.findByIdAndWorkspaceId(subIssueId, workspaceId).orElse(null);
        if (sub == null || !SubIssue.STATUS_OPEN.equals(sub.getStatus())) {
            return 0;
        }
        boolean epssEnabled = workspaces.findById(workspaceId).map(Workspace::isEpssAutoIgnoreEnabled).orElse(false);
        String sensitivity = repositories.findByIdAndWorkspaceId(sub.getRepositoryId(), workspaceId)
                .map(Repository::getSensitivity).orElse("normal");
        Computation c = scorer.compute(sub, sensitivity, epssEnabled);
        subIssues.updateScoreColumnsIfOpen(sub.getId(), c.score(), writeBreakdown(c.breakdown()), c.inKev(),
                c.epss());
        if (c.unmappedSecretRule() != null) {
            log.warn("Unmapped secret rule '{}' scored at the default base 75 (D-045); add it to the rule map.",
                    c.unmappedSecretRule());
        }
        return 1;
    }

    private int rescoreWorkspace(UUID workspaceId, boolean epssAdjustmentsEnabled, Map<String, Integer> fixConfig,
                                 Set<String> unmappedSink) {
        List<SubIssue> open = subIssues.findByWorkspaceIdAndStatus(workspaceId, SubIssue.STATUS_OPEN);
        Map<UUID, String> sensitivityByRepo = new HashMap<>();
        Set<UUID> touchedGroups = new HashSet<>();
        for (SubIssue sub : open) {
            String sensitivity = sensitivityByRepo.computeIfAbsent(sub.getRepositoryId(),
                    id -> repositories.findByIdAndWorkspaceId(id, workspaceId)
                            .map(Repository::getSensitivity).orElse("normal"));
            // read-only: does not mutate the managed entity. EPSS scoring tiers apply only when the toggle is on.
            Computation c = scorer.compute(sub, sensitivity, epssAdjustmentsEnabled);
            if (c.unmappedSecretRule() != null) {
                unmappedSink.add(c.unmappedSecretRule());
            }
            // Column-scoped UPDATE guarded by status='open' — never a full save, so a row reconcile has solved
            // since we read it is skipped (B1). No triage_event, no status/fingerprint change.
            subIssues.updateScoreColumnsIfOpen(sub.getId(), c.score(), writeBreakdown(c.breakdown()),
                    c.inKev(), c.epss());
            touchedGroups.add(sub.getGroupId());
        }
        for (UUID groupId : touchedGroups) {
            rollup.recomputeScoreRollup(workspaceId, groupId, fixConfig); // never writes issue_group.status
        }
        return open.size();
    }

    private String writeBreakdown(Object breakdown) {
        try {
            return objectMapper.writeValueAsString(breakdown);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize score_breakdown", e);
        }
    }
}
