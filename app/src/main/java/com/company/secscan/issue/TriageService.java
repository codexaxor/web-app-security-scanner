package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.IgnoreRuleView;
import com.company.secscan.issue.api.IgnoredItemView;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.triage.IgnoreMatcher;
import com.company.secscan.triage.ReasonCode;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
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
    private final WorkspaceRepository workspaces;
    private final RepositoryRepository repositories;
    /** Optional: present only in the api profile (ReScoreService is @Profile("api")); worker never unignores. */
    private final org.springframework.beans.factory.ObjectProvider<ReScoreService> reScore;

    public TriageService(IgnoreRuleRepository ignoreRules, SubIssueRepository subIssues,
                         IssueGroupRepository groups, TriageEventRepository events, GroupRollupService rollup,
                         TriageStatusResolver statusResolver, WorkspaceRepository workspaces,
                         RepositoryRepository repositories,
                         org.springframework.beans.factory.ObjectProvider<ReScoreService> reScore) {
        this.ignoreRules = ignoreRules;
        this.subIssues = subIssues;
        this.groups = groups;
        this.events = events;
        this.rollup = rollup;
        this.statusResolver = statusResolver;
        this.workspaces = workspaces;
        this.repositories = repositories;
        this.reScore = reScore;
    }

    /** Create a generic rule (path / cve / rule, global or per-repo; also subissue/group given an explicit value). */
    @Transactional
    public IgnoreRuleView createRule(String scope, UUID repositoryId, String matchValue, String reason) {
        validate(scope, matchValue, reason);
        // N1: a per-repo rule must target a repository IN THIS WORKSPACE — never another tenant's repo.
        if (repositoryId != null && repositories.findByIdAndWorkspaceId(repositoryId, WORKSPACE).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such repository in this workspace.");
        }
        IgnoreRule rule = ignoreRules.saveAndFlush(new IgnoreRule(WORKSPACE, scope, repositoryId, matchValue.trim(),
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
        IgnoreRule rule = ignoreRules.saveAndFlush(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_SUBISSUE,
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
        IgnoreRule rule = ignoreRules.saveAndFlush(new IgnoreRule(WORKSPACE, IgnoreRule.SCOPE_GROUP,
                group.getRepositoryId(), group.getGroupKey(), reason.trim(), MANUAL_ACTOR));
        applyRule(rule);
        return IgnoreRuleView.of(rule);
    }

    /**
     * Remove a rule and RE-RESOLVE each finding it held (D-063, review S5). If another active rule or an auto
     * cause (a path exclusion, a secret placeholder, or LOW_EPSS when the toggle is on — the causes derivable
     * without a scan) still applies, the finding STAYS ignored, quietly re-pointed/re-reasoned with no event;
     * otherwise it unignores to the status from the last scan where its scanner succeeded (never solves on API
     * evidence). Scan-time-only auto causes (config paths, dev/test scope, duplicate rule) are re-applied by the
     * next reconcile.
     */
    @Transactional
    public void removeRule(UUID ruleId) {
        IgnoreRule rule = ignoreRules.findByWorkspaceIdAndId(WORKSPACE, ruleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such ignore rule."));
        // A .secscan.yml-materialized rule is owned by the repo's in-repo config and reconciled by its revoke
        // lifecycle (D-057) — it cannot be deleted through the API (that would resurrect it on the next scan).
        if (rule.isFromSecscanYml()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This ignore is defined in the repository's .secscan.yml; edit .secscan.yml to change it.");
        }
        List<IgnoreRule> others = ignoreRules.findByWorkspaceId(WORKSPACE).stream()
                .filter(r -> !r.getId().equals(ruleId)).toList();
        TriageResolver.AutoContext api = TriageResolver.AutoContext.api(epssAutoIgnoreEnabled());
        Set<UUID> touched = new HashSet<>();
        List<UUID> reopenedToOpen = new java.util.ArrayList<>();
        for (SubIssue sub : subIssues.findByWorkspaceIdAndIgnoredByRuleId(WORKSPACE, ruleId)) {
            TriageResolver.Desired desired =
                    TriageResolver.resolve(sub, groupKeyOf(sub.getGroupId()), others, api);
            if (desired != null) {
                // still ignored under another rule or auto cause → quiet re-point/re-reason, no event
                sub.ignore(desired.reasonCode(), desired.ruleId());
                subIssues.save(sub);
            } else {
                String newStatus = statusResolver.resolve(sub);
                sub.unignore(newStatus);
                subIssues.save(sub);
                events.save(TriageEvent.user(WORKSPACE, sub.getId(), SubIssue.STATUS_IGNORED, newStatus,
                        TriageEvent.REASON_UNIGNORED));
                if (SubIssue.STATUS_OPEN.equals(newStatus)) {
                    reopenedToOpen.add(sub.getId());
                }
                touched.add(sub.getGroupId());
            }
        }
        ignoreRules.delete(rule);
        // D-2 (D-083): re-score each reopened finding immediately so its score reflects current intel without
        // waiting for the next scan/sweep (column-scoped, status='open' guarded → no event, no status change).
        // Done after the unignore loop (the re-score flushes/clears the context) and before the group rollup.
        for (UUID subId : reopenedToOpen) {
            reScore.ifAvailable(rs -> rs.rescoreSubIssue(WORKSPACE, subId));
        }
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
        org.springframework.data.domain.Page<SubIssue> ignored = subIssues.findByWorkspaceIdAndStatus(
                WORKSPACE, SubIssue.STATUS_IGNORED, PageRequest.of(Math.max(0, page), Math.max(1, size)));
        // N1: load the holding rules for the whole page in ONE query (no per-row findById N+1).
        Map<UUID, IgnoreRule> rulesById = rulesFor(ignored.getContent());
        return PagedResponse.fromPage(ignored.map(sub -> IgnoredItemView.of(sub,
                sub.getIgnoredByRuleId() == null ? null : rulesById.get(sub.getIgnoredByRuleId()))));
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
            if (SubIssue.STATUS_SOLVED.equals(sub.getStatus())) {
                continue; // N4: never auto/manually re-ignore a solved finding
            }
            if (!IgnoreMatcher.matches(ruleRef, toFinding(sub, groupKeys.get(sub.getGroupId())))) {
                continue;
            }
            if (SubIssue.STATUS_IGNORED.equals(sub.getStatus())) {
                // D-063: a NEW rule over a finding already ignored under an AUTO cause quietly re-points it to
                // this IGNORE_RULE (no event — it stays ignored). A finding already held by another IGNORE_RULE
                // keeps its earlier holder (created_at ordering), so leave it untouched.
                if (!ReasonCode.IGNORE_RULE.code().equals(sub.getIgnoreReason())) {
                    sub.ignore(ReasonCode.IGNORE_RULE.code(), rule.getId());
                    subIssues.save(sub);
                }
                continue;
            }
            String from = sub.getStatus();
            sub.ignore(ReasonCode.IGNORE_RULE.code(), rule.getId());
            subIssues.save(sub);
            events.save(TriageEvent.user(WORKSPACE, sub.getId(), from, SubIssue.STATUS_IGNORED,
                    ReasonCode.IGNORE_RULE.code()));
            touched.add(sub.getGroupId());
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

    /** Group keys for the given sub-issues, workspace-scoped (N1 — never a cross-tenant group leaks in). */
    private Map<UUID, String> groupKeyMap(List<SubIssue> subs) {
        Set<UUID> ids = new HashSet<>();
        for (SubIssue s : subs) {
            ids.add(s.getGroupId());
        }
        Map<UUID, String> out = new HashMap<>();
        groups.findAllById(ids).forEach(g -> {
            if (WORKSPACE.equals(g.getWorkspaceId())) {
                out.put(g.getId(), g.getGroupKey());
            }
        });
        return out;
    }

    private String groupKeyOf(UUID groupId) {
        return groups.findByIdAndWorkspaceId(groupId, WORKSPACE).map(IssueGroup::getGroupKey).orElse(null);
    }

    /** The workspace EPSS auto-ignore toggle — the one auto cause the API can re-evaluate (D-060/D-063). */
    private boolean epssAutoIgnoreEnabled() {
        return workspaces.findById(WORKSPACE).map(Workspace::isEpssAutoIgnoreEnabled).orElse(false);
    }

    /** The holding rules for a page of ignored sub-issues, workspace-scoped, in one query (N1 — no N+1). */
    private Map<UUID, IgnoreRule> rulesFor(List<SubIssue> subs) {
        Set<UUID> ruleIds = new HashSet<>();
        for (SubIssue s : subs) {
            if (s.getIgnoredByRuleId() != null) {
                ruleIds.add(s.getIgnoredByRuleId());
            }
        }
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        // Workspace-scoped in SQL (N-1, domain rule 1) — never load another tenant's rule row at all.
        Map<UUID, IgnoreRule> out = new HashMap<>();
        for (IgnoreRule r : ignoreRules.findByWorkspaceIdAndIdIn(WORKSPACE, ruleIds)) {
            out.put(r.getId(), r);
        }
        return out;
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
