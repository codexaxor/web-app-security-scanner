package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.issue.api.SubIssueView;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only issue feed (M3). The triage actions (ignore/unignore) and full UI are M7/M8. */
@RestController
@RequestMapping("/api/v1")
public class IssueController {

    private final IssueService service;

    public IssueController(IssueService service) {
        this.service = service;
    }

    /** Lists issue groups, filterable by repository, type and status; paginated server-side. */
    @GetMapping("/issue-groups")
    public PagedResponse<IssueGroupView> listGroups(
            @RequestParam(required = false) UUID repositoryId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listGroups(repositoryId, type, status, page, size);
    }

    /** Lists one group's sub-issues; paginated server-side. */
    @GetMapping("/issue-groups/{groupId}/sub-issues")
    public PagedResponse<SubIssueView> listSubIssues(
            @PathVariable UUID groupId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listSubIssues(groupId, page, size);
    }
}
