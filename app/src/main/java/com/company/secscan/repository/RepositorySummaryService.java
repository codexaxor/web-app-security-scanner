package com.company.secscan.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the per-repository rollups shown on the Repositories screen (OD-14 item 1): open counts by severity
 * band, ignored count, the last scan, and that scan's warning count. Every number is computed in SQL (three
 * grouped/aggregate queries), never in the browser, and is workspace-scoped. Returned as a map keyed by
 * repository id so a page of repositories is enriched without an N+1.
 */
@Service
public class RepositorySummaryService {

    /** One repository's rollup. {@code lastScan} is null when the repository has never been scanned. */
    public record RepositorySummary(long openCritical, long openHigh, long openMedium, long openLow,
                                    long ignoredCount, LastScan lastScan, long warningCount) {
        public static RepositorySummary empty() {
            return new RepositorySummary(0, 0, 0, 0, 0, null, 0);
        }
    }

    public record LastScan(UUID id, String status, Instant finishedAt) {
    }

    private final RepositoryRepository repositories;

    public RepositorySummaryService(RepositoryRepository repositories) {
        this.repositories = repositories;
    }

    /** The rollups for every repository in the workspace, keyed by repository id (missing repos → all-zero). */
    @Transactional(readOnly = true)
    public Map<UUID, RepositorySummary> summariesByRepo(UUID workspaceId) {
        Map<UUID, long[]> bands = new HashMap<>(); // [critical, high, medium, low]
        for (Object[] r : repositories.openBandsByRepo(workspaceId)) {
            bands.put((UUID) r[0], new long[] {num(r[1]), num(r[2]), num(r[3]), num(r[4])});
        }
        Map<UUID, Long> ignored = new HashMap<>();
        for (Object[] r : repositories.ignoredCountByRepo(workspaceId)) {
            ignored.put((UUID) r[0], num(r[1]));
        }
        Map<UUID, LastScan> lastScan = new HashMap<>();
        Map<UUID, Long> warnings = new HashMap<>();
        for (Object[] r : repositories.lastScanByRepo(workspaceId)) {
            UUID repoId = (UUID) r[0];
            lastScan.put(repoId, new LastScan((UUID) r[1], (String) r[2], toInstant(r[3])));
            warnings.put(repoId, num(r[4]));
        }

        Map<UUID, RepositorySummary> out = new HashMap<>();
        for (UUID repoId : union(bands.keySet(), ignored.keySet(), lastScan.keySet())) {
            long[] b = bands.getOrDefault(repoId, new long[4]);
            out.put(repoId, new RepositorySummary(b[0], b[1], b[2], b[3],
                    ignored.getOrDefault(repoId, 0L), lastScan.get(repoId),
                    warnings.getOrDefault(repoId, 0L)));
        }
        return out;
    }

    /** The rollup for one repository, never null. */
    public RepositorySummary summaryOrEmpty(Map<UUID, RepositorySummary> all, UUID repoId) {
        return all.getOrDefault(repoId, RepositorySummary.empty());
    }

    private static long num(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }

    private static Instant toInstant(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }

    @SafeVarargs
    private static java.util.Set<UUID> union(java.util.Set<UUID>... sets) {
        java.util.Set<UUID> all = new java.util.HashSet<>();
        for (java.util.Set<UUID> s : sets) {
            all.addAll(s);
        }
        return all;
    }
}
