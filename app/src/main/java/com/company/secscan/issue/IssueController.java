package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
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
    public PagedResponse<IssueGroupView> listGroups(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "focus") String view,
            @RequestParam(required = false) String band,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listGroups(repositoryId, type, category, view, band, includeInactive, page, size);
    }

    /** The Feed summary bar (OD-14 item 4). */
    @GetMapping("/issue-groups/summary")
    public FeedSummaryView summary(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "focus") String view,
            @RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.feedSummary(repositoryId, type, view, includeInactive);
    }

    /** One issue group (the side-panel header). 404 if it is not in this workspace. */
    @GetMapping("/issue-groups/{groupId}")
    public IssueGroupView getGroup(@PathVariable UUID groupId) {
        return service.getGroup(groupId);
    }

    /** One group's sub-issues; paginated server-side. */
    @GetMapping("/issue-groups/{groupId}/sub-issues")
    public PagedResponse<SubIssueView> listSubIssues(
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listSubIssues(groupId, page, size);
    }

    /** One sub-issue's status history (OD-14 item 5). */
    @GetMapping("/sub-issues/{subIssueId}/events")
    public PagedResponse<TriageEventView> events(
            @PathVariable UUID subIssueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.subIssueEvents(subIssueId, page, size);
    }
}
