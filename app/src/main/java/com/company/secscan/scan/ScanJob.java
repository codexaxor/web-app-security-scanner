package com.company.secscan.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A queue row for one scan (D-003). A worker claims it with {@code SELECT … FOR UPDATE SKIP LOCKED}, takes a
 * lease ({@code locked_by}/{@code locked_at}) and marks it {@code running}; a lease older than the scan timeout
 * plus a margin is reclaimed and the job requeued, up to {@link #MAX_ATTEMPTS} attempts before {@code failed}.
 */
@Entity
@Table(name = "scan_job")
public class ScanJob {

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_DONE = "done";
    public static final String STATE_FAILED = "failed";

    public static final int MAX_ATTEMPTS = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "state", nullable = false)
    private String state = STATE_QUEUED;

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "locked_by")
    private String lockedBy;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "run_after", nullable = false)
    private Instant runAfter = Instant.now();

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ScanJob() {
        // for JPA
    }

    public ScanJob(UUID workspaceId, UUID scanId) {
        this.workspaceId = workspaceId;
        this.scanId = scanId;
        this.state = STATE_QUEUED;
        this.runAfter = Instant.now();
    }

    /** Takes the lease: increments the attempt count, records the worker and time, marks it running. */
    public void lease(String workerId) {
        this.state = STATE_RUNNING;
        this.attempts = this.attempts + 1;
        this.lockedBy = workerId;
        this.lockedAt = Instant.now();
    }

    public void markDone() {
        this.state = STATE_DONE;
        this.lockedBy = null;
        this.lockedAt = null;
    }

    public void markFailed() {
        this.state = STATE_FAILED;
        this.lockedBy = null;
        this.lockedAt = null;
    }

    /** Returns the job to the queue after a stale lease or a retryable failure, with an optional backoff. */
    public void requeue(Instant runAfter) {
        this.state = STATE_QUEUED;
        this.lockedBy = null;
        this.lockedAt = null;
        this.runAfter = runAfter;
    }

    public boolean attemptsExhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getScanId() {
        return scanId;
    }

    public String getState() {
        return state;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public Instant getRunAfter() {
        return runAfter;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
