package com.company.secscan.issue;

import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public ReScoreService(WorkspaceRepository workspaces, SubIssueRepository subIssues,
                          RepositoryRepository repositories, SubIssueScorer scorer, GroupRollupService rollup) {
        this.workspaces = workspaces;
        this.subIssues = subIssues;
        this.repositories = repositories;
        this.scorer = scorer;
        this.rollup = rollup;
    }

    /** Re-scores every open sub-issue in every workspace. Returns the number of sub-issues recomputed. */
    @Transactional
    public int rescoreAll() {
        Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
        int count = 0;
        for (Workspace ws : workspaces.findAll()) {
            count += rescoreWorkspace(ws.getId(), fixConfig);
        }
        log.info("Re-score recomputed {} open sub-issue(s)", count);
        return count;
    }

    private int rescoreWorkspace(UUID workspaceId, Map<String, Integer> fixConfig) {
        List<SubIssue> open = subIssues.findByWorkspaceIdAndStatus(workspaceId, SubIssue.STATUS_OPEN);
        Map<UUID, String> sensitivityByRepo = new HashMap<>();
        Set<UUID> touchedGroups = new HashSet<>();
        for (SubIssue sub : open) {
            String sensitivity = sensitivityByRepo.computeIfAbsent(sub.getRepositoryId(),
                    id -> repositories.findByIdAndWorkspaceId(id, workspaceId)
                            .map(Repository::getSensitivity).orElse("normal"));
            scorer.score(sub, sensitivity); // no status/fingerprint change, no triage_event
            subIssues.save(sub);
            touchedGroups.add(sub.getGroupId());
        }
        for (UUID groupId : touchedGroups) {
            rollup.recompute(workspaceId, groupId, fixConfig);
        }
        return open.size();
    }
}
