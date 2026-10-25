package com.company.secscan.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * One scan: a single repository scanned at a single commit. Its lifecycle is {@code queued → running →
 * completed | partial | failed}. Once the clone succeeds the scan has run, so (SPEC step 12) it is
 * {@code completed} when every scanner succeeded and {@code partial} when any scanner failed or timed out;
 * {@code failed} is reserved for a scan that could not run at all (clone failure, container timeout, missing
 * result, or an infrastructure/config error). A failed scanner never closes its findings (M3's reconcile).
 */
@Entity
@Table(name = "scan")
public class Scan {

    public static final String TRIGGER_SCHEDULE = "schedule";
    public static final String TRIGGER_COMMIT = "commit";
    public static final String TRIGGER_MANUAL = "manual";

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "trigger", nullable = false)
    private String trigger;

    @Column(name = "commit_sha")
    private String commitSha;

    // The branch actually cloned for this scan, snapshotted at enqueue and never re-read later (D-076). Null for
    // legacy rows with no known branch.
    @Column(name = "branch")
    private String branch;

    @Column(name = "status", nullable = false)
    private String status = STATUS_QUEUED;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tool_versions", nullable = false, columnDefinition = "jsonb")
    private Map<String, String> toolVersions = new LinkedHashMap<>();

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    // A short machine code explaining a FAILED scan (e.g. HOST_NOT_ALLOWED, SCANNER_TIMEOUT), for the UI/audit;
    // null for non-failed scans. Set via failWith(...). (V10)
    @Column(name = "fail_reason")
    private String failReason;

    // DB default `now()`; @Generated(INSERT) makes Hibernate read the value back after insert so a POST that
    // returns the just-saved scan carries a non-null createdAt (OD-14 item 8 — the not-refreshed-after-insert bug).
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Scan() {
        // for JPA
    }

    private Scan(UUID workspaceId, UUID repositoryId, String trigger, String branch) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.trigger = trigger;
        this.branch = branch;
        this.status = STATUS_QUEUED;
    }

    /** A manually triggered scan (Start Scan button) cloning {@code branch} (snapshotted at enqueue, D-076). */
    public static Scan manual(UUID workspaceId, UUID repositoryId, String branch) {
        return new Scan(workspaceId, repositoryId, TRIGGER_MANUAL, branch);
    }

    /** A manually triggered scan with no known branch (legacy/test convenience). */
    public static Scan manual(UUID workspaceId, UUID repositoryId) {
        return new Scan(workspaceId, repositoryId, TRIGGER_MANUAL, null);
    }

    public void markRunning() {
        this.status = STATUS_RUNNING;
        this.startedAt = Instant.now();
    }

    /** Records the commit actually cloned by the scanner. */
    public void recordCommit(String commitSha) {
        this.commitSha = commitSha;
    }

    public void recordToolVersions(Map<String, String> versions) {
        this.toolVersions = versions == null ? new LinkedHashMap<>() : new LinkedHashMap<>(versions);
    }

    /** Terminal status after the pipeline runs: completed (all scanners ok), partial (some failed), or failed. */
    public void finish(String terminalStatus) {
        this.status = terminalStatus;
        this.finishedAt = Instant.now();
    }

    /** Marks the scan {@code failed} and records a short machine code for why (persisted for the UI/audit). */
    public void failWith(String failReason) {
        this.status = STATUS_FAILED;
        this.failReason = failReason;
        this.finishedAt = Instant.now();
    }

    /** Returns the scan to {@code queued} after a retryable failure, so its requeued job can run it again. */
    public void markQueuedForRetry() {
        this.status = STATUS_QUEUED;
    }

    /** Stamps the moment this scan's findings were reconciled into the issue model (same transaction). */
    public void markReconciled() {
        this.reconciledAt = Instant.now();
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

    public String getTrigger() {
        return trigger;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public String getBranch() {
        return branch;
    }

    public String getStatus() {
        return status;
    }

    public Map<String, String> getToolVersions() {
        return toolVersions;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getFailReason() {
        return failReason;
    }

    public Instant getReconciledAt() {
        return reconciledAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
