package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.issue.api.FeedSummaryView;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.issue.api.TriageEventView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.workspace.Workspace;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Read-only queries over the issue model for the M8 Feed, side panel and summary (OD-14). Every query is
 * workspace-scoped (domain rule 1), paginated server-side, and every count is computed in SQL. Semantics:
 * Focus = groups {@code open}; All = {@code open + ignored} (solved is never shown, only counted elsewhere);
 * bands on {@code severity_score} (Critical 90–100 / High 70–89 / Medium 40–69 / Low 1–39); an all-ignored
 * group has a null score → no band. Inactive repositories are excluded unless {@code includeInactive} (change 2).
 */
@Service
public class IssueService {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final List<String> FOCUS_STATUSES = List.of(IssueGroup.STATUS_OPEN);
    private static final List<String> ALL_STATUSES = List.of(IssueGroup.STATUS_OPEN, IssueGroup.STATUS_IGNORED);
    private static final Duration SEVEN_DAYS = Duration.ofDays(7);

    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;
    private final TriageEventRepository triageEvents;
    private final IgnoreRuleRepository ignoreRules;
    private final RepositoryRepository repositories;

    public IssueService(IssueGroupRepository groups, SubIssueRepository subIssues,
                        TriageEventRepository triageEvents, IgnoreRuleRepository ignoreRules,
                        RepositoryRepository repositories) {
        this.groups = groups;
        this.subIssues = subIssues;
        this.triageEvents = triageEvents;
        this.ignoreRules = ignoreRules;
        this.repositories = repositories;
    }

    /** The Feed (OD-14 item 3): view (focus/all), optional band, type and repository filters, includeInactive. */
    @Transactional(readOnly = true)
    public PagedResponse<IssueGroupView> listGroups(UUID repositoryId, String type, String view, String band,
                                                    boolean includeInactive, int page, int size) {
        int[] range = bandRange(band);
        var pageResult = groups.searchFeed(WORKSPACE, repositoryId, blankToNull(type), statusesFor(view),
                range == null ? null : range[0], range == null ? null : range[1], includeInactive,
                PageRequest.of(Math.max(0, page), Math.max(1, size)));
        Map<UUID, String> paths = repoPaths();
        Map<UUID, long[]> counts = findingCounts(pageResult.getContent());
        return PagedResponse.fromPage(pageResult.map(g -> toView(g, paths, counts)));
    }

    /** Build a view with its open/ignored/solved sub-issue counts (0,0,0 when the group has no sub-issue). */
    private IssueGroupView toView(IssueGroup g, Map<UUID, String> paths, Map<UUID, long[]> counts) {
        long[] c = counts.getOrDefault(g.getId(), new long[3]);
        return IssueGroupView.of(g, paths.get(g.getRepositoryId()), c[0], c[1], c[2]);
    }

    /**
     * Per-group sub-issue counts by status for a feed page, in one query (no N+1). The array is
     * [openCount, ignoredCount, solvedCount]. Empty page → empty map.
     */
    private Map<UUID, long[]> findingCounts(List<IssueGroup> pageGroups) {
        if (pageGroups.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = pageGroups.stream().map(IssueGroup::getId).toList();
        Map<UUID, long[]> counts = new HashMap<>();
        for (Object[] row : subIssues.countByGroupIdsAndStatus(WORKSPACE, ids)) {
            long[] c = counts.computeIfAbsent((UUID) row[0], k -> new long[3]);
            long n = ((Number) row[2]).longValue();
            switch ((String) row[1]) {
                case SubIssue.STATUS_OPEN -> c[0] = n;
                case SubIssue.STATUS_IGNORED -> c[1] = n;
                case SubIssue.STATUS_SOLVED -> c[2] = n;
                default -> { /* unknown status: ignored in the breakdown */ }
            }
        }
        return counts;
    }

    /** One group for the side-panel header (M8 S10d). 404 if not in this workspace. */
    @Transactional(readOnly = true)
    public IssueGroupView getGroup(UUID groupId) {
        IssueGroup g = groups.findByIdAndWorkspaceId(groupId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such issue group."));
        return toView(g, repoPaths(), findingCounts(List.of(g)));
    }

    /** The Feed summary bar (OD-14 item 4): band group counts for the view + 7-day finding buckets (change 3). */
    @Transactional(readOnly = true)
    public FeedSummaryView feedSummary(UUID repositoryId, String type, String view, boolean includeInactive) {
        String typeFilter = blankToNull(type);
        List<Object[]> rows = groups.bandCounts(WORKSPACE, repositoryId, typeFilter, statusesFor(view),
                includeInactive);
        Object[] r = rows.isEmpty() ? new Object[] {0L, 0L, 0L, 0L, 0L} : rows.get(0);
        FeedSummaryView.BandCounts bands = new FeedSummaryView.BandCounts(
                lng(r[0]), lng(r[1]), lng(r[2]), lng(r[3]));
        long total = lng(r[4]);

        Instant since = Instant.now().minus(SEVEN_DAYS);
        long newFindings = subIssues.countNewSince(WORKSPACE, repositoryId, typeFilter, includeInactive, since);
        long solved = triageEvents.countDistinctSubIssuesByTransitionSince(WORKSPACE,
                SubIssue.STATUS_SOLVED, null, repositoryId, typeFilter, includeInactive, since);
        long autoIgnored = triageEvents.countDistinctSubIssuesByTransitionSince(WORKSPACE,
                SubIssue.STATUS_IGNORED, "system", repositoryId, typeFilter, includeInactive, since);
        return new FeedSummaryView(bands, total, newFindings, solved, autoIgnored);
    }

    /** One group's sub-issues, with the holding ignore rule's fields populated (OD-14 item 6). 404 if not in ws. */
    @Transactional(readOnly = true)
    public PagedResponse<SubIssueView> listSubIssues(UUID groupId, int page, int size) {
        groups.findByIdAndWorkspaceId(groupId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such issue group."));
        var pageResult = subIssues.findByWorkspaceIdAndGroupId(WORKSPACE, groupId,
                PageRequest.of(Math.max(0, page), Math.max(1, size)));
        Map<UUID, IgnoreRule> rules = rulesFor(pageResult.getContent());
        return PagedResponse.fromPage(pageResult.map(s ->
                SubIssueView.of(s, s.getIgnoredByRuleId() == null ? null : rules.get(s.getIgnoredByRuleId()))));
    }

    /** One sub-issue's status history, newest first (OD-14 item 5). 404 if the sub-issue is not in this workspace. */
    @Transactional(readOnly = true)
    public PagedResponse<TriageEventView> subIssueEvents(UUID subIssueId, int page, int size) {
        subIssues.findByIdAndWorkspaceId(subIssueId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sub-issue."));
        return PagedResponse.fromPage(triageEvents
                .findByWorkspaceIdAndSubIssueIdOrderByAtDesc(WORKSPACE, subIssueId,
                        PageRequest.of(Math.max(0, page), Math.max(1, size)))
                .map(TriageEventView::of));
    }

    // --- internals ---

    private Map<UUID, String> repoPaths() {
        return repositories.findByWorkspaceId(WORKSPACE).stream()
                .collect(Collectors.toMap(Repository::getId, Repository::getPathWithNamespace));
    }

    private Map<UUID, IgnoreRule> rulesFor(List<SubIssue> subs) {
        List<UUID> ruleIds = subs.stream().map(SubIssue::getIgnoredByRuleId).filter(Objects::nonNull).distinct()
                .toList();
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        return ignoreRules.findAllById(ruleIds).stream()
                .filter(r -> WORKSPACE.equals(r.getWorkspaceId()))
                .collect(Collectors.toMap(IgnoreRule::getId, Function.identity()));
    }

    private static List<String> statusesFor(String view) {
        return "all".equalsIgnoreCase(view) ? ALL_STATUSES : FOCUS_STATUSES;
    }

    /** The inclusive [lo, hi] score range for a band name, or null for no band filter (blank/unknown). */
    private static int[] bandRange(String band) {
        if (!StringUtils.hasText(band)) {
            return null;
        }
        return switch (band.toLowerCase()) {
            case "critical" -> new int[] {90, 100};
            case "high" -> new int[] {70, 89};
            case "medium" -> new int[] {40, 69};
            case "low" -> new int[] {1, 39};
            default -> null;
        };
    }

    private static long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }
}
