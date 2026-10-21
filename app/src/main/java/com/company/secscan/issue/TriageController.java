package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.IgnoreRuleView;
import com.company.secscan.issue.api.IgnoredItemView;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual triage (M7, D-058): ignore (five scopes) and unignore, plus the Ignored view. Follows the existing
 * controller conventions — {@code /api/v1}, record DTOs, {@link PagedResponse} for lists, service-layer
 * workspace scoping. Every ignore requires a reason (SPEC).
 */
@RestController
@RequestMapping("/api/v1")
public class TriageController {

    private final TriageService triage;

    public TriageController(TriageService triage) {
        this.triage = triage;
    }

    /** Body for a generic ignore rule (path / cve / rule, global when repositoryId is null). */
    public record IgnoreRuleRequest(String scope, UUID repositoryId, String matchValue, String reason) {
    }

    /** Body for a scoped ignore convenience (sub-issue / group): only a reason is needed. */
    public record ReasonRequest(String reason) {
    }

    /** Create a generic ignore rule and apply it immediately. */
    @PostMapping("/ignore-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public IgnoreRuleView createRule(@RequestBody IgnoreRuleRequest req) {
        return triage.createRule(req.scope(), req.repositoryId(), req.matchValue(), req.reason());
    }

    /** Ignore a single sub-issue (subissue scope; SAST matches rule+file, D-062). */
    @PostMapping("/sub-issues/{subIssueId}/ignore")
    @ResponseStatus(HttpStatus.CREATED)
    public IgnoreRuleView ignoreSubIssue(@PathVariable UUID subIssueId, @RequestBody ReasonRequest req) {
        return triage.ignoreSubIssue(subIssueId, req.reason());
    }

    /** Ignore a whole issue group (group scope). */
    @PostMapping("/issue-groups/{groupId}/ignore")
    @ResponseStatus(HttpStatus.CREATED)
    public IgnoreRuleView ignoreGroup(@PathVariable UUID groupId, @RequestBody ReasonRequest req) {
        return triage.ignoreGroup(groupId, req.reason());
    }

    /** Remove an ignore rule; the findings it held return to their reconcile-computed status. */
    @DeleteMapping("/ignore-rules/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRule(@PathVariable UUID ruleId) {
        triage.removeRule(ruleId);
    }

    /** List active ignore rules (paginated). */
    @GetMapping("/ignore-rules")
    public PagedResponse<IgnoreRuleView> listRules(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "50") int size) {
        return triage.listRules(page, size);
    }

    /** The Ignored view: every ignored finding with its reason code, rule and creator (paginated). */
    @GetMapping("/ignored")
    public PagedResponse<IgnoredItemView> listIgnored(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "50") int size) {
        return triage.listIgnored(page, size);
    }
}
