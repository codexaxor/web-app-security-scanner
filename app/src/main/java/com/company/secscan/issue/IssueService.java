package com.company.secscan.issue;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.api.Pagination;
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
    private final com.company.secscan.scan.ScanRepository scans;
    private final com.company.secscan.gitlab.GitlabConnectionRepository connections;

    public IssueService(IssueGroupRepository groups, SubIssueRepository subIssues,
                        TriageEventRepository triageEvents, IgnoreRuleRepository ignoreRules,
                        RepositoryRepository repositories, com.company.secscan.scan.ScanRepository scans,
                        com.company.secscan.gitlab.GitlabConnectionRepository connections) {
        this.groups = groups;
        this.subIssues = subIssues;
        this.triageEvents = triageEvents;
        this.ignoreRules = ignoreRules;
        this.repositories = repositories;
        this.scans = scans;
        this.connections = connections;
    }

    /** The Feed (OD-14 item 3): view (focus/all), optional band, type and repository filters, includeInactive. */
    @Transactional(readOnly = true)
    public PagedResponse<IssueGroupView> listGroups(UUID repositoryId, String type, String category, String view,
                                                    String band, boolean includeInactive, int page, int size) {
        return listGroups(repositoryId, type, category, null, view, band, includeInactive, page, size);
    }

    /** As above, plus an optional free-text {@code search} on the group title/subtitle (M8.6 repo Issues tab). */
    @Transactional(readOnly = true)
    public PagedResponse<IssueGroupView> listGroups(UUID repositoryId, String type, String category, String search,
                                                    String view, String band, boolean includeInactive, int page,
                                                    int size) {
        int[] range = bandRange(band);
        var pageResult = groups.searchFeed(WORKSPACE, repositoryId, blankToNull(type), blankToNull(category),
                likeOrNull(search), statusesFor(view), range == null ? null : range[0],
                range == null ? null : range[1], includeInactive, Pagination.of(page, size));
        Map<UUID, Repository> repos = repoById();
        Map<UUID, long[]> counts = findingCounts(pageResult.getContent());
        Map<UUID, Object[]> locations = locationStats(pageResult.getContent());
        // Feed rows omit fixedVersions (an SCA-only, per-group detail shown in the side panel, not the table).
        return PagedResponse.fromPage(pageResult.map(g -> toView(g, repos, counts, locations, List.of())));
    }

    /** Distinct level-2 categories present in the feed scope, each with its group count (category filter). */
    @Transactional(readOnly = true)
    public List<CategoryCount> listCategories(UUID repositoryId, String view, boolean includeInactive) {
        return groups.categoryCounts(WORKSPACE, repositoryId, statusesFor(view), includeInactive).stream()
                .map(r -> new CategoryCount((String) r[0], ((Number) r[1]).longValue()))
                .toList();
    }

    /** One distinct level-2 category and how many groups carry it (category filter option). */
    public record CategoryCount(String category, long count) {
    }

    /**
     * A blank/null search → no filter; otherwise a lower-cased {@code %term%} for a case-insensitive LIKE. The
     * LIKE wildcards {@code \ % _} inside the user's term are escaped (N3) so a literal "%" or "_" matches itself
     * rather than acting as a wildcard; the query pairs this with {@code escape '\'}. The escape character {@code \}
     * is escaped first, before the wildcards it would otherwise double-escape.
     */
    private static String likeOrNull(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String escaped = search.trim().toLowerCase(java.util.Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }

    /** Build a view with its open/ignored/solved sub-issue counts (0,0,0 when the group has no sub-issue). */
    private IssueGroupView toView(IssueGroup g, Map<UUID, Repository> repos, Map<UUID, long[]> counts,
                                  Map<UUID, Object[]> locations, List<String> fixedVersions) {
        long[] c = counts.getOrDefault(g.getId(), new long[3]);
        Repository repo = repos.get(g.getRepositoryId());
        String path = repo == null ? null : repo.getPathWithNamespace();
        String branch = repo == null ? null : repo.effectiveBranch();   // D-076: the branch this repo scans
        Object[] loc = locations.get(g.getId());
        String primaryLocation = loc == null ? null : (String) loc[1];
        long locationCount = loc == null ? 0 : ((Number) loc[2]).longValue();
        return IssueGroupView.of(g, path, branch, c[0], c[1], c[2], fixedVersions, primaryLocation, locationCount);
    }

    /** Per-group [primaryFilePath, distinctLocationCount] for a feed page, in one query (no N+1). */
    private Map<UUID, Object[]> locationStats(List<IssueGroup> pageGroups) {
        if (pageGroups.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = pageGroups.stream().map(IssueGroup::getId).toList();
        Map<UUID, Object[]> out = new HashMap<>();
        for (Object[] row : subIssues.locationStatsByGroupIds(WORKSPACE, ids)) {
            out.put((UUID) row[0], row);
        }
        return out;
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
        // SF-7: the distinct fixed versions across this group's OPEN sub-issues (SQL, workspace-scoped, nulls
        // excluded). The panel renders "upgrade to <v>" for one, "see each location" for several, nothing for none.
        List<String> fixedVersions = subIssues.findOpenFixedVersions(WORKSPACE, groupId);
        return toView(g, repoById(), findingCounts(List.of(g)), locationStats(List.of(g)), fixedVersions);
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
        // "Solved · 7d" counts genuine solves only — a BRANCH_CHANGED close (a branch switch, not a fix) is
        // excluded (D-076). (The exclusion is a no-op for the auto-ignored bucket, where BRANCH_CHANGED never
        // appears.)
        long solved = triageEvents.countDistinctSubIssuesByTransitionSince(WORKSPACE,
                SubIssue.STATUS_SOLVED, null, TriageEvent.REASON_BRANCH_CHANGED,
                repositoryId, typeFilter, includeInactive, since);
        long autoIgnored = triageEvents.countDistinctSubIssuesByTransitionSince(WORKSPACE,
                SubIssue.STATUS_IGNORED, "system", null, repositoryId, typeFilter, includeInactive, since);
        return new FeedSummaryView(bands, total, newFindings, solved, autoIgnored);
    }

    /** One group's sub-issues, with the holding ignore rule's fields populated (OD-14 item 6). 404 if not in ws. */
    @Transactional(readOnly = true)
    public PagedResponse<SubIssueView> listSubIssues(UUID groupId, int page, int size) {
        IssueGroup grp = groups.findByIdAndWorkspaceId(groupId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such issue group."));
        var pageResult = subIssues.findByWorkspaceIdAndGroupId(WORKSPACE, groupId,
                PageRequest.of(Math.max(0, page), Math.max(1, size)));
        Map<UUID, IgnoreRule> rules = rulesFor(pageResult.getContent());
        // D-084: host (from the repo's connection) + per-finding commit (from its last-seen scan) are the only
        // inputs, with the stored project path, the UI uses to build a file/commit deep link. Both may be null.
        String host = sourceHostOf(grp.getRepositoryId());
        Map<UUID, String> commitByScan = commitShas(pageResult.getContent());
        return PagedResponse.fromPage(pageResult.map(s ->
                SubIssueView.of(s, s.getIgnoredByRuleId() == null ? null : rules.get(s.getIgnoredByRuleId()),
                        host, s.getLastSeenScanId() == null ? null : commitByScan.get(s.getLastSeenScanId()))));
    }

    /** The source-host authority (host[:port]) of a repository's connection, or null if detached/unparseable. */
    private String sourceHostOf(UUID repositoryId) {
        Repository repo = repositories.findByIdAndWorkspaceId(repositoryId, WORKSPACE).orElse(null);
        if (repo == null || repo.getConnectionId() == null) {
            return null;
        }
        var conn = connections.findByIdAndWorkspaceId(repo.getConnectionId(), WORKSPACE).orElse(null);
        return conn == null ? null : authority(conn.getBaseUrl());
    }

    private static String authority(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        try {
            java.net.URI u = java.net.URI.create(baseUrl);
            String h = u.getHost();
            if (h == null) {
                return null;
            }
            return u.getPort() == -1 ? h : h + ":" + u.getPort();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The commit SHA of each sub-issue's last-seen scan, keyed by scan id (workspace-scoped). */
    private Map<UUID, String> commitShas(List<SubIssue> subs) {
        List<UUID> scanIds = subs.stream().map(SubIssue::getLastSeenScanId).filter(Objects::nonNull).distinct()
                .toList();
        if (scanIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new HashMap<>();
        for (var scan : scans.findByWorkspaceIdAndIdIn(WORKSPACE, scanIds)) {
            out.put(scan.getId(), scan.getCommitSha());
        }
        return out;
    }

    /** One sub-issue's status history, newest first (OD-14 item 5). 404 if the sub-issue is not in this workspace. */
    @Transactional(readOnly = true)
    public PagedResponse<TriageEventView> subIssueEvents(UUID subIssueId, int page, int size) {
        subIssues.findByIdAndWorkspaceId(subIssueId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sub-issue."));
        var pageResult = triageEvents.findByWorkspaceIdAndSubIssueIdOrderByAtDesc(WORKSPACE, subIssueId,
                PageRequest.of(Math.max(0, page), Math.max(1, size)));
        // Resolve the branch of each event's scan so the UI can render a BRANCH_CHANGED event in plain words
        // (D-076). The scan ids come from this workspace's own events, so they are already workspace-scoped.
        Map<UUID, String> branchByScan = scanBranches(pageResult.getContent());
        return PagedResponse.fromPage(pageResult.map(e ->
                TriageEventView.of(e, e.getScanId() == null ? null : branchByScan.get(e.getScanId()))));
    }

    private Map<UUID, String> scanBranches(List<TriageEvent> events) {
        List<UUID> scanIds = events.stream().map(TriageEvent::getScanId).filter(Objects::nonNull).distinct().toList();
        if (scanIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> byScan = new java.util.HashMap<>();
        for (var scan : scans.findByWorkspaceIdAndIdIn(WORKSPACE, scanIds)) {   // B-1: workspace-scoped (rule 1)
            byScan.put(scan.getId(), scan.getBranch());
        }
        return byScan;
    }

    // --- internals ---

    private Map<UUID, Repository> repoById() {
        return repositories.findByWorkspaceId(WORKSPACE).stream()
                .collect(Collectors.toMap(Repository::getId, Function.identity()));
    }

    private Map<UUID, IgnoreRule> rulesFor(List<SubIssue> subs) {
        List<UUID> ruleIds = subs.stream().map(SubIssue::getIgnoredByRuleId).filter(Objects::nonNull).distinct()
                .toList();
        if (ruleIds.isEmpty()) {
            return Map.of();
        }
        // Workspace-scoped in SQL (N-1, domain rule 1) — never load another tenant's rule row at all.
        return ignoreRules.findByWorkspaceIdAndIdIn(WORKSPACE, ruleIds).stream()
                .collect(Collectors.toMap(IgnoreRule::getId, Function.identity()));
    }

    private static List<String> statusesFor(String view) {
        return "all".equalsIgnoreCase(view) ? ALL_STATUSES : FOCUS_STATUSES;
    }

    /**
     * The inclusive [lo, hi] score range for a band name, or null for no band filter (blank). An unknown
     * non-blank band is a client error → 400 (N-6), never silently treated as "all".
     */
    private static int[] bandRange(String band) {
        if (!StringUtils.hasText(band)) {
            return null;
        }
        return switch (band.toLowerCase()) {
            case "critical" -> new int[] {90, 100};
            case "high" -> new int[] {70, 89};
            case "medium" -> new int[] {40, 69};
            case "low" -> new int[] {1, 39};
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown severity band. Use critical, high, medium or low.");
        };
    }

    private static long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }
}
