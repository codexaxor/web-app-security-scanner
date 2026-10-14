package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only queries over the issue model (M3). Every query is workspace-scoped (domain rule 1) and paginated
 * server-side. The Feed/side-panel UI is M8; the severity-ordered feed arrives with M5 scoring (groups are
 * ordered newest-first by first-seen until then).
 */
@Service
public class IssueService {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;

    public IssueService(IssueGroupRepository groups, SubIssueRepository subIssues) {
        this.groups = groups;
        this.subIssues = subIssues;
    }

    /** Lists issue groups with optional repository/type/status filters (blank = no filter). */
    @Transactional(readOnly = true)
    public PagedResponse<IssueGroupView> listGroups(UUID repositoryId, String type, String status, int page,
                                                    int size) {
        return PagedResponse.fromPage(groups
                .search(WORKSPACE, repositoryId, blankToNull(type), blankToNull(status),
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(IssueGroupView::of));
    }

    /** Lists the sub-issues of one group (404 if the group is not in this workspace). */
    @Transactional(readOnly = true)
    public PagedResponse<SubIssueView> listSubIssues(UUID groupId, int page, int size) {
        groups.findByIdAndWorkspaceId(groupId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such issue group."));
        return PagedResponse.fromPage(subIssues
                .findByWorkspaceIdAndGroupId(WORKSPACE, groupId,
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(SubIssueView::of));
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }
}
