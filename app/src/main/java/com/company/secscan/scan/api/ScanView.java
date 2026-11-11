package com.company.secscan.scan.api;

import com.company.secscan.scan.Scan;
import java.time.Instant;
import java.util.UUID;

/** A scan as shown in a list. {@code warningCount} is populated on the repository Scans tab (M8.6) and 0
 *  elsewhere. */
public record ScanView(UUID id, UUID repositoryId, String trigger, String status, String commitSha, String branch,
                       Instant startedAt, Instant finishedAt, Instant createdAt, long warningCount) {

    public static ScanView of(Scan s) {
        return of(s, 0);
    }

    public static ScanView of(Scan s, long warningCount) {
        return new ScanView(s.getId(), s.getRepositoryId(), s.getTrigger(), s.getStatus(), s.getCommitSha(),
                s.getBranch(), s.getStartedAt(), s.getFinishedAt(), s.getCreatedAt(), warningCount);
    }
}
