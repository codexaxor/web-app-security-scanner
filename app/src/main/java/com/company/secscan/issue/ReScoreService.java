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
