package com.company.secscan.issue.api;

import com.company.secscan.issue.TriageEvent;
import java.time.Instant;
import java.util.UUID;

/** One status-change audit entry for the side-panel history (OD-14 item 5). */
public record TriageEventView(UUID id, String fromStatus, String toStatus, String actor, String reasonCode,
                              UUID scanId, Instant at) {

    public static TriageEventView of(TriageEvent e) {
        return new TriageEventView(e.getId(), e.getFromStatus(), e.getToStatus(), e.getActor(),
                e.getReasonCode(), e.getScanId(), e.getAt());
    }
}
