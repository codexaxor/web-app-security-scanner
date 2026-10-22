package com.company.secscan.issue.api;

import com.company.secscan.issue.IssueGroup;
import java.time.Instant;
import java.util.UUID;

/**
 * An issue group as shown in the Feed (API shape; the UI is M8). Severity and fix time are populated by M5
 * scoring and may be null until then.
 */
public record IssueGroupView(UUID id, UUID repositoryId, String repositoryPath, String type, String title,
                             String subtitle, String status, Integer severityScore, Integer fixTimeMinutes,
                             Instant firstSeenAt, long openCount, long ignoredCount, long solvedCount) {

    public static IssueGroupView of(IssueGroup g, String repositoryPath, long openCount, long ignoredCount,
                                    long solvedCount) {
        return new IssueGroupView(g.getId(), g.getRepositoryId(), repositoryPath, g.getType(), g.getTitle(),
                g.getSubtitle(), g.getStatus(), g.getSeverityScore(), g.getFixTimeMinutes(), g.getFirstSeenAt(),
                openCount, ignoredCount, solvedCount);
    }
}
