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
 * A Feed row: the group users triage (SPEC data model). Its {@code status} is derived from its sub-issues (open
 * if any sub-issue is open; M3 maintains it). {@code severityScore} and {@code fixTimeMinutes} are populated by
 * M5's scoring. {@code groupKey} is the {@code type|discriminator} string from
 * {@link com.company.secscan.reconcile.Fingerprints}, unique per repository.
 */
@Entity
@Table(name = "issue_group")
public class IssueGroup {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_SOLVED = "solved";
    public static final String STATUS_IGNORED = "ignored";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "group_key", nullable = false)
    private String groupKey;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "subtitle")
    private String subtitle;

    @Column(name = "status", nullable = false)
    private String status = STATUS_OPEN;

    @Column(name = "severity_score")
    private Integer severityScore;

    @Column(name = "fix_time_minutes")
    private Integer fixTimeMinutes;

    @Column(name = "first_seen_at", insertable = false, updatable = false)
    private Instant firstSeenAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected IssueGroup() {
        // for JPA
    }

    public IssueGroup(UUID workspaceId, UUID repositoryId, String type, String groupKey, String title,
                      String subtitle) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.type = type;
        this.groupKey = groupKey;
        this.title = title;
        this.subtitle = subtitle;
        this.status = STATUS_OPEN;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
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

    public String getType() {
        return type;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public String getTitle() {
        return title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    public String getStatus() {
        return status;
    }

    public Integer getSeverityScore() {
        return severityScore;
    }

    public Integer getFixTimeMinutes() {
        return fixTimeMinutes;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
