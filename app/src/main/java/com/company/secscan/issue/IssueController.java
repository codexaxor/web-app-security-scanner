package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.auth.CurrentWorkspace;
import com.company.secscan.issue.api.FeedSummaryView;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.issue.api.TriageEventView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The M8 Feed and side-panel read API (OD-14 items 3–6). */
@RestController
@RequestMapping("/api/v1")
public class IssueController {

    private final IssueService service;

    public IssueController(IssueService service) {
        this.service = service;
    }

    /** The Feed (OD-14 item 3): view=focus|all, optional band, type and repository filters, includeInactive. */
    @GetMapping("/issue-groups")
    public PagedResponse<IssueGroupView> listGroups(@CurrentWorkspace UUID workspaceId, 
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String provider,
            @RequestParam(defaultValue = "focus") String view,
            @RequestParam(required = false) String band,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listGroups(workspaceId, repositoryId, type, category, search, provider, view, band,
                includeInactive,
                page, size);
    }

    /** Distinct level-2 categories in the feed scope (the category-filter options). Small aggregation, like the
     *  summary endpoint — not a paginated business list. */
    @GetMapping("/issue-groups/categories")
    public java.util.List<IssueService.CategoryCount> categories(@CurrentWorkspace UUID workspaceId, 
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(defaultValue = "focus") String view,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.listCategories(workspaceId, repositoryId, view, includeInactive);
    }

    /** The Feed summary bar (OD-14 item 4). */
    @GetMapping("/issue-groups/summary")
    public FeedSummaryView summary(@CurrentWorkspace UUID workspaceId, 
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "focus") String view,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.feedSummary(workspaceId, repositoryId, type, view, includeInactive);
    }

    /** One issue group (the side-panel header). 404 if it is not in this workspace. */
    @GetMapping("/issue-groups/{groupId}")
    public IssueGroupView getGroup(@CurrentWorkspace UUID workspaceId, @PathVariable UUID groupId) {
        return service.getGroup(workspaceId, groupId);
    }

    /** One group's sub-issues; paginated server-side. */
    @GetMapping("/issue-groups/{groupId}/sub-issues")
    public PagedResponse<SubIssueView> listSubIssues(@CurrentWorkspace UUID workspaceId, 
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listSubIssues(workspaceId, groupId, page, size);
    }

    /** One sub-issue's status history (OD-14 item 5). */
    @GetMapping("/sub-issues/{subIssueId}/events")
    public PagedResponse<TriageEventView> events(@CurrentWorkspace UUID workspaceId, 
            @PathVariable UUID subIssueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.subIssueEvents(workspaceId, subIssueId, page, size);
    }
}
