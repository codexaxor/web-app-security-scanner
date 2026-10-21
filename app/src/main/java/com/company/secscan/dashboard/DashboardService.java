package com.company.secscan.dashboard;

import com.company.secscan.dashboard.DashboardSummaryView.BandCounts;
import com.company.secscan.dashboard.DashboardSummaryView.InactiveLine;
import com.company.secscan.dashboard.DashboardSummaryView.RepoHealth;
import com.company.secscan.dashboard.DashboardSummaryView.ScanHealth;
import com.company.secscan.dashboard.DashboardSummaryView.TypeCount;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.repository.RepositorySummaryService;
import com.company.secscan.repository.RepositorySummaryService.RepositorySummary;
import com.company.secscan.scan.Scan;
import com.company.secscan.workspace.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Dashboard read model (OD-14 item 7). All counts are computed in SQL and workspace-scoped; inactive
 * repositories are excluded from band/type totals by default (change 2) and surfaced separately on the inactive
 * line. Scan health reuses the per-repo last-scan rollup ({@link RepositorySummaryService}).
 */
@Service
public class DashboardService {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final Set<String> UNHEALTHY = Set.of(Scan.STATUS_PARTIAL, Scan.STATUS_FAILED);

    private final IssueGroupRepository groups;
    private final SubIssueRepository subIssues;
    private final RepositoryRepository repositories;
    private final RepositorySummaryService summaries;

    public DashboardService(IssueGroupRepository groups, SubIssueRepository subIssues,
                            RepositoryRepository repositories, RepositorySummaryService summaries) {
        this.groups = groups;
        this.subIssues = subIssues;
        this.repositories = repositories;
        this.summaries = summaries;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryView summary(boolean includeInactive) {
        Object[] gb = first(groups.bandCounts(WORKSPACE, null, null, List.of(IssueGroup.STATUS_OPEN),
                includeInactive));
        BandCounts groupBands = new BandCounts(lng(gb[0]), lng(gb[1]), lng(gb[2]), lng(gb[3]));
        long totalGroups = lng(gb[4]);

        Object[] sb = first(subIssues.openBandCounts(WORKSPACE, includeInactive));
        BandCounts findingBands = new BandCounts(lng(sb[0]), lng(sb[1]), lng(sb[2]), lng(sb[3]));
        long totalFindings = lng(sb[4]);

        List<TypeCount> byType = subIssues.openCountsByType(WORKSPACE, includeInactive).stream()
                .map(r -> new TypeCount((String) r[0], lng(r[1]))).toList();

        ScanHealth scanHealth = scanHealth();
        InactiveLine inactive = new InactiveLine(
                repositories.countByWorkspaceIdAndActive(WORKSPACE, false),
                subIssues.countOpenInInactiveRepos(WORKSPACE));

        return new DashboardSummaryView(groupBands, totalGroups, findingBands, totalFindings, byType,
                scanHealth, inactive);
    }

    /** Active repositories whose last scan is partial/failed or raised warnings. */
    private ScanHealth scanHealth() {
        Map<UUID, RepositorySummary> rollups = summaries.summariesByRepo(WORKSPACE);
        List<RepoHealth> unhealthy = new ArrayList<>();
        for (Repository repo : repositories.findByWorkspaceId(WORKSPACE)) {
            if (!repo.isActive()) {
                continue;
            }
            RepositorySummary s = rollups.get(repo.getId());
            if (s == null || s.lastScan() == null) {
                continue;
            }
            boolean bad = UNHEALTHY.contains(s.lastScan().status()) || s.warningCount() > 0;
            if (bad) {
                unhealthy.add(new RepoHealth(repo.getPathWithNamespace(), s.lastScan().status(),
                        s.warningCount()));
            }
        }
        return new ScanHealth(unhealthy.size(), unhealthy);
    }

    private static Object[] first(List<Object[]> rows) {
        return rows.isEmpty() ? new Object[] {0L, 0L, 0L, 0L, 0L} : rows.get(0);
    }

    private static long lng(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
