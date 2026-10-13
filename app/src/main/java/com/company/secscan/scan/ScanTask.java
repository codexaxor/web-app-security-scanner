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
 * The outcome of one scanner within a scan. M2 runs only the secret scanner (Gitleaks); SCA/SAST/IaC/EOL add
 * their own rows later. {@code status} is the scanner's own result — {@code ok}, {@code failed} or
 * {@code timed_out} — and is what M3's reconcile consults so a failed scanner never closes its findings.
 */
@Entity
@Table(name = "scan_task")
public class ScanTask {

    public static final String SCANNER_SECRET = "secret";

    public static final String STATUS_OK = "ok";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_TIMED_OUT = "timed_out";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "scanner", nullable = false)
    private String scanner;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "log_excerpt")
    private String logExcerpt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ScanTask() {
        // for JPA
    }

    public ScanTask(UUID workspaceId, UUID scanId, String scanner, String status, Integer exitCode,
                    Long durationMs, String logExcerpt) {
        this.workspaceId = workspaceId;
        this.scanId = scanId;
        this.scanner = scanner;
        this.status = status;
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.logExcerpt = logExcerpt;
    }

    public boolean succeeded() {
        return STATUS_OK.equals(status);
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

    public String getScanner() {
        return scanner;
    }

    public String getStatus() {
        return status;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getLogExcerpt() {
        return logExcerpt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
