package com.company.secscan.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The audit trail: one row per sub-issue status change (domain rule 4). M3 writes system events during
 * reconcile; M7 adds user ignore/unignore events. The lifecycle reason codes are distinct from M7's auto-ignore
 * reason codes.
 */
@Entity
@Table(name = "triage_event")
public class TriageEvent {

    public static final String ACTOR_SYSTEM = "system";
    public static final String ACTOR_USER = "user";

    // Lifecycle (system) reason codes written during reconcile.
    public static final String REASON_SEEN = "SEEN";
    public static final String REASON_REOPENED = "REOPENED";
    public static final String REASON_SOLVED_NOT_DETECTED = "SOLVED_NOT_DETECTED";

    // M7: the reason written when a finding leaves `ignored` because its ignore was removed or its cause cleared
    // (manual unignore, a revoked .secscan.yml rule, or an auto-ignore whose cause is gone). Distinct from the
    // auto-ignore reason codes in core.triage.ReasonCode, which name WHY a finding was ignored.
    public static final String REASON_UNIGNORED = "UNIGNORED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "sub_issue_id")
    private UUID subIssueId;

    @Column(name = "group_id")
    private UUID groupId;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "scan_id")
    private UUID scanId;

    @Column(name = "at", insertable = false, updatable = false)
    private Instant at;

    protected TriageEvent() {
        // for JPA
    }

    private TriageEvent(UUID workspaceId, UUID subIssueId, String fromStatus, String toStatus, String actor,
                        String reasonCode, UUID scanId) {
        this.workspaceId = workspaceId;
        this.subIssueId = subIssueId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.reasonCode = reasonCode;
        this.scanId = scanId;
    }

    /** A system lifecycle event for a sub-issue caused by a scan's reconcile. */
    public static TriageEvent system(UUID workspaceId, UUID subIssueId, String fromStatus, String toStatus,
                                     String reasonCode, UUID scanId) {
        return new TriageEvent(workspaceId, subIssueId, fromStatus, toStatus, ACTOR_SYSTEM, reasonCode, scanId);
    }

    /**
     * A user-initiated triage event for a sub-issue (manual ignore or unignore via the API). Not tied to a scan,
     * so {@code scanId} is null. A scan-time auto-ignore uses {@link #system} instead.
     */
    public static TriageEvent user(UUID workspaceId, UUID subIssueId, String fromStatus, String toStatus,
                                   String reasonCode) {
        return new TriageEvent(workspaceId, subIssueId, fromStatus, toStatus, ACTOR_USER, reasonCode, null);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getSubIssueId() {
        return subIssueId;
    }

    public UUID getGroupId() {
        return groupId;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getToStatus() {
        return toStatus;
    }

    public String getActor() {
        return actor;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public UUID getScanId() {
        return scanId;
    }

    public Instant getAt() {
        return at;
    }
}
