package com.company.secscan.scan.api;

import com.company.secscan.scan.Scan;
import java.time.Instant;
import java.util.UUID;

/** A scan as shown in a list (Feed/Repositories screens come later; this is the API shape). */
public record ScanView(UUID id, UUID repositoryId, String trigger, String status, String commitSha, String branch,
                       Instant startedAt, Instant finishedAt, Instant createdAt) {

    public static ScanView of(Scan s) {
        return new ScanView(s.getId(), s.getRepositoryId(), s.getTrigger(), s.getStatus(), s.getCommitSha(),
                s.getBranch(), s.getStartedAt(), s.getFinishedAt(), s.getCreatedAt());
    }
}
