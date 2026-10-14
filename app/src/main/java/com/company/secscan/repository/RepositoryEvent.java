package com.company.secscan.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A persistent record of a repository lifecycle event from connection management (D-028, D-020): a repository
 * reassigned to or detached from a connection on removal, deactivated when no connection can reach it, or
 * confirmed/reattached when a connection is verified. Kept separate from {@code triage_event} (which is the
 * issue-status audit trail). The connection ids are plain uuids, not FKs — the "from" connection is typically
 * deleted at the moment the event is written.
 */
@Entity
@Table(name = "repository_event")
public class RepositoryEvent {

    public static final String REASSIGNED = "REASSIGNED";
    public static final String DETACHED = "DETACHED";
    public static final String DEACTIVATED = "DEACTIVATED";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REATTACHED = "REATTACHED";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "from_connection_id")
    private UUID fromConnectionId;

    @Column(name = "to_connection_id")
    private UUID toConnectionId;

    @Column(name = "reason_code")
    private String reasonCode;

    @Column(name = "detail")
    private String detail;

    @Column(name = "at", insertable = false, updatable = false)
    private Instant at;

    protected RepositoryEvent() {
        // for JPA
    }

    public RepositoryEvent(UUID workspaceId, UUID repositoryId, String eventType, UUID fromConnectionId,
                           UUID toConnectionId, String reasonCode, String detail) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.eventType = eventType;
        this.fromConnectionId = fromConnectionId;
        this.toConnectionId = toConnectionId;
        this.reasonCode = reasonCode;
        this.detail = detail;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getEventType() {
        return eventType;
    }

    public UUID getFromConnectionId() {
        return fromConnectionId;
    }

    public UUID getToConnectionId() {
        return toConnectionId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getAt() {
        return at;
    }
}
