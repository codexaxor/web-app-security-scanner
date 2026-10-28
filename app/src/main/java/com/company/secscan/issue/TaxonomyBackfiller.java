package com.company.secscan.issue;

import com.company.secscan.taxonomy.Level2Taxonomy;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the level-2 taxonomy (D-083) for issue groups created before V21. Runs once at api startup
 * (via {@link TaxonomyBackfillRunner}) and is idempotent — it touches only groups whose {@code level2_category}
 * is still null, per workspace, through a column-scoped UPDATE (never a status change, never a triage_event).
 * Reconcile sets the category for new/updated groups, so after this runs every group has a category.
 */
@Service
@Profile("api")
public class TaxonomyBackfiller {

    private static final Logger log = LoggerFactory.getLogger(TaxonomyBackfiller.class);

    private final WorkspaceRepository workspaces;
    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;

    public TaxonomyBackfiller(WorkspaceRepository workspaces, IssueGroupRepository groups,
                              SubIssueRepository subIssues) {
        this.workspaces = workspaces;
        this.groups = groups;
        this.subIssues = subIssues;
    }

    /** Fills the level-2 category of every group that lacks one, across all workspaces. Returns the count filled. */
    @Transactional
    public int backfill() {
        int filled = 0;
        Level2Taxonomy tax = Level2Taxonomy.getInstance();
        for (Workspace ws : workspaces.findAll()) {
            UUID workspaceId = ws.getId();
            for (IssueGroup g : groups.findByWorkspaceIdAndLevel2CategoryIsNull(workspaceId)) {
                // Any sub-issue of the group yields the same category (the group's subs share the categorizing
                // fields); the per-workspace query keeps this scoped (domain rule 1).
                List<SubIssue> subs = subIssues.findByWorkspaceIdAndGroupId(workspaceId, g.getId());
                Level2Taxonomy.Category cat;
                if (subs.isEmpty()) {
                    // N-2: an orphaned group (no sub-issues) still gets its TYPE's fallback category, so the
                    // null-count-0 invariant holds for every group — never left null.
                    cat = tax.of(g.getType(), null, null, null, null);
                } else {
                    SubIssue rep = subs.get(0);
                    cat = tax.of(rep.getType(), rep.getFilePath(), rep.getRuleId(), rep.getCwe(),
                            rep.getPackageName());
                }
                filled += groups.backfillTaxonomyIfNull(g.getId(), workspaceId, cat.level2(), cat.owasp());
            }
        }
        if (filled > 0) {
            log.info("Level-2 taxonomy backfill: set the category on {} issue group(s).", filled);
        }
        return filled;
    }
}
