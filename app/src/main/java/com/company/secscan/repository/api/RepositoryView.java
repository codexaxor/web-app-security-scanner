package com.company.secscan.repository.api;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositorySummaryService.LastScan;
import com.company.secscan.repository.RepositorySummaryService.RepositorySummary;
import java.util.List;
import java.util.UUID;

/**
 * A selected repository, as returned to the UI, including which connection currently reaches it and, when
 * inactive, why. The Repositories screen also shows the M8 rollups ({@code openBySeverity}, {@code ignoredCount},
 * {@code lastScan}, {@code warningCount}, OD-14 item 1), all computed server-side in SQL. A freshly selected
 * repository (POST /repositories) has no issues/scans yet, so those are zero/null.
 */
public record RepositoryView(
        UUID id,
        long gitlabProjectId,
        String pathWithNamespace,
        String defaultBranch,
        List<String> languages,
        boolean active,
        String sensitivity,
        boolean includeDevDeps,
        UUID connectionId,
        String connectionTargetType,
        String connectionLabel,
        String inactiveReason,
        OpenBySeverity openBySeverity,
        long ignoredCount,
        LastScan lastScan,
        long warningCount) {

    public record OpenBySeverity(long critical, long high, long medium, long low) {
        static OpenBySeverity of(RepositorySummary s) {
            return new OpenBySeverity(s.openCritical(), s.openHigh(), s.openMedium(), s.openLow());
        }
    }

    /** A just-selected repository with no scans/issues yet (POST /repositories). */
    public static RepositoryView of(Repository r, GitlabConnection connection) {
        return of(r, connection, RepositorySummary.empty());
    }

    public static RepositoryView of(Repository r, GitlabConnection connection, RepositorySummary summary) {
        return new RepositoryView(r.getId(), r.getGitlabProjectId(), r.getPathWithNamespace(),
                r.getDefaultBranch(), r.getLanguages(), r.isActive(), r.getSensitivity(), r.isIncludeDevDeps(),
                r.getConnectionId(),
                connection == null ? null : connection.getTargetType(),
                connection == null ? null : connection.targetPath(),
                r.getInactiveReason(),
                OpenBySeverity.of(summary), summary.ignoredCount(), summary.lastScan(), summary.warningCount());
    }
}
