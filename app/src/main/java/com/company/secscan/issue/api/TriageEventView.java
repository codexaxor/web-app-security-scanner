package com.company.secscan.issue.api;

import com.company.secscan.issue.TriageEvent;
import java.time.Instant;
import java.util.UUID;

/**
 * One status-change audit entry for the side-panel history (OD-14 item 5). {@code branch} is the branch of the
 * event's scan (D-076), so the UI can render a BRANCH_CHANGED event in plain words ("Closed — branch changed
 * (now scanning dev)"); null when the event has no scan or branch.
 */
public record TriageEventView(UUID id, String fromStatus, String toStatus, String actor, String reasonCode,
                              UUID scanId, String branch, Instant at) {

    public static TriageEventView of(TriageEvent e) {
        return of(e, null);
    }

    public static TriageEventView of(TriageEvent e, String branch) {
        return new TriageEventView(e.getId(), e.getFromStatus(), e.getToStatus(), e.getActor(),
                e.getReasonCode(), e.getScanId(), branch, e.getAt());
    }
}
