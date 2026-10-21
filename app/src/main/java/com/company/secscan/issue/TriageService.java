package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.IgnoreRuleView;
import com.company.secscan.issue.api.IgnoredItemView;
import com.company.secscan.triage.IgnoreMatcher;
import com.company.secscan.workspace.Workspace;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Manual triage (M7, D-058): create/remove ignore rules (five scopes) and list the Ignored view. Applying a rule
 * ignores every matching finding and removing it unignores the ones it held — both through the issue model, each
 * with a {@code triage_event} (domain rules 2-4). A matching active rule keeps a finding ignored when seen again
 * (hard rule 6). Workspace-scoped (domain rule 1). The same {@link IgnoreMatcher} is used here and at reconcile.
 *
 * <p>Manual unignore applies to MANUAL ignores (review change #10): cause-based auto-ignores clear via their
 * cause at reconcile, not through this service.
 */
@Service
public class TriageService {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    /** The acting user placeholder until M10 wires an authenticated principal (OD-9). Never a secret. */
    static final String MANUAL_ACTOR = "user";

    private final IgnoreRuleRepository ignoreRules;
    private final SubIssueRepository subIssues;
    private final IssueGroupRepository groups;
    private final TriageEventRepository events;
    private final GroupRollupService rollup;
    private final TriageStatusResolver statusResolver;

    public TriageService(IgnoreRuleRepository ignoreRules, SubIssueRepository subIssues,
                         IssueGroupRepository groups, TriageEventRepository events, GroupRollupService rollup,
                         TriageStatusResolver statusResolver) {
        this.ignoreRules = ignoreRules;
        this.subIssues = subIssues;
        this.groups = groups;
        this.events = events;
        this.rollup = rollup;
        this.statusResolver = statusResolver;
    }

    /** Create a generic rule (path / cve / rule, global or per-repo; also subissue/group given an explicit value). */
    @Transactional
    public IgnoreRuleView createRule(String scope, UUID repositoryId, String matchValue, String reason) {
        validate(scope, matchValue, reason);
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(WORKSPACE, scope, repositoryId, matchValue.trim(),
                reason.trim(), MANUAL_ACTOR));
        applyRule(rule);
        return IgnoreRuleView.of(rule);
    }

    /** Ignore one sub-issue (subissue scope). The match value is fingerprint, or rule|file for SAST (D-062). */
    @Transactional
    public IgnoreRuleView ignoreSubIssue(UUID subIssueId, String reason) {
        SubIssue sub = subIssues.findById(subIssueId)
                .filter(s -> WORKSPACE.equals(s.getWorkspaceId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sub-issue."));
        requireReason(reason);
        String matchValue = IgnoreMatcher.subIssueMatchValue(toFinding(sub, groupKeyOf(sub.getGroupId())));
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_SUBISSUE,
                sub.getRepositoryId(), matchValue, reason.trim(), MANUAL_ACTOR));
        applyRule(rule);
        return IgnoreRuleView.of(rule);
    }

    /** Ignore a whole group (group scope). */
    @Transactional
    public IgnoreRuleView ignoreGroup(UUID groupId, String reason) {
        IssueGroup group = groups.findByIdAndWorkspaceId(groupId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such issue group."));
        requireReason(reason);
        IgnoreRule rule = ignoreRules.save(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_GROUP,
                group.getRepositoryId(), group.getGroupKey(), reason.trim(), MANUAL_ACTOR));
        applyRule(rule);
        return IgnoreRuleView.of(rule);
    }

    /** Remove a rule and unignore the findings it held (each returns to the reconcile-computed status). */
    @Transactional
    public void removeRule(UUID ruleId) {
        IgnoreRule rule = ignoreRules.findByWorkspaceIdAndId(WORKSPACE, ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such ignore rule."));
        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndIgnoredByRuleId(WORKSPACE, ruleId)) {
            String newStatus = statusResolver.resolve(sub);
            sub.unignore(newStatus);
            subIssues.save(sub);
            events.save(TriageEvent.user(WORKSPACE, sub.getId(), SubIssue.STATUS_IGNORED, newStatus,
                    TriageEvent.REASON_UNIGNORED));
            touched.add(sub.getGroupId());
        }
        ignoreRules.delete(rule);
        recomputeGroups(touched);
    }

    @Transactional(readOnly = true)
    public PagedResponse<IgnoreRuleView> listRules(int page, int size) {
        return PagedResponse.fromPage(ignoreRules
                .findByWorkspaceId(WORKSPACE, PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(IgnoreRuleView::of));
    }

    @Transactional(readOnly = true)
    public PagedResponse<IgnoredItemView> listIgnored(int page, int size) {
        return PagedResponse.fromPage(subIssues
                .findByWorkspaceIdAndStatus(WORKSPACE, SubIssue.STATUS_IGNORED,
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(sub -> IgnoredItemView.of(sub, ruleFor(sub))));
    }

    // --- internals ---

    private void applyRule(IgnoreRule rule) {
        List<SubIssue> candidates = rule.getRepositoryId() == null
                ? subIssues.findByWorkspaceId(WORKSPACE)
                : subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, rule.getRepositoryId());
        Map<UUID, String> groupKeys = groupKeyMap(candidates);
        IgnoreMatcher.Rule ruleRef = new IgnoreMatcher.Rule(rule.getScope(), rule.getRepositoryId(),
                rule.getMatchValue());
        Set<UUID> touched = new HashSet<>();
        for (SubIssue sub : candidates) {
            if (SubIssue.STATUS_IGNORED.equals(sub.getStatus())) {
                continue; // already ignored (hard rule 6) — leave it and its holding rule as they are
            }
            if (IgnoreMatcher.matches(ruleRef, toFinding(sub, groupKeys.get(sub.getGroupId())))) {
                String from = sub.getStatus();
                sub.ignore(com.company.secscan.triage.ReasonCode.IGNORE_RULE.code(), rule.getId());
                subIssues.save(sub);
                events.save(TriageEvent.user(WORKSPACE, sub.getId(), from, SubIssue.STATUS_IGNORED,
                        com.company.secscan.triage.ReasonCode.IGNORE_RULE.code()));
                touched.add(sub.getGroupId());
            }
        }
        recomputeGroups(touched);
    }

    private void recomputeGroups(Set<UUID> touched) {
        if (touched.isEmpty()) {
            return;
        }
        Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
        for (UUID groupId : touched) {
            rollup.recompute(WORKSPACE, groupId, fixConfig);
        }
    }

    private IgnoreMatcher.Finding toFinding(SubIssue sub, String groupKey) {
        return new IgnoreMatcher.Finding(sub.getType(), sub.getRepositoryId(), sub.getFingerprint(), groupKey,
                sub.getFilePath(), sub.getRuleId(), sub.getVulnId(), sub.getCveAliases());
    }

    private Map<UUID, String> groupKeyMap(List<SubIssue> subs) {
        Set<UUID> ids = new HashSet<>();
        for (SubIssue s : subs) {
            ids.add(s.getGroupId());
        }
        Map<UUID, String> out = new HashMap<>();
        groups.findAllById(ids).forEach(g -> out.put(g.getId(), g.getGroupKey()));
        return out;
    }

    private String groupKeyOf(UUID groupId) {
        return groups.findById(groupId).map(IssueGroup::getGroupKey).orElse(null);
    }

    private IgnoreRule ruleFor(SubIssue sub) {
        return sub.getIgnoredByRuleId() == null ? null
                : ignoreRules.findById(sub.getIgnoredByRuleId()).orElse(null);
    }

    private void validate(String scope, String matchValue, String reason) {
        if (scope == null || !List.of(IgnoreRule.SCOPE_SUBISSUE, IgnoreRule.SCOPE_GROUP, IgnoreRule.SCOPE_PATH,
                IgnoreRule.SCOPE_CVE, IgnoreRule.SCOPE_RULE).contains(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown ignore scope.");
        }
        if (matchValue == null || matchValue.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A match value is required.");
        }
        requireReason(reason);
    }

    private void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A reason is required for every ignore.");
        }
    }
}
