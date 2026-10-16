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
 * A scan-health warning recorded for one scan (M4): a coverage gap SecScan surfaces rather than hides (D-030).
 * {@code code} is a machine code ({@code MISSING_GRADLE_LOCKFILE}, {@code MISSING_NUGET_LOCKFILE},
 * {@code UNRESOLVED_VERSION}, {@code NO_MANIFESTS}, {@code UNRESOLVED_PATH}); {@code filePath} is the
 * repo-relative build file, or null when the warning is repository-wide.
 */
@Entity
@Table(name = "scan_warning")
public class ScanWarning {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "scan_id", nullable = false)
    private UUID scanId;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "file_path")
    private String filePath;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected ScanWarning() {
        // for JPA
    }

    public ScanWarning(UUID workspaceId, UUID repositoryId, UUID scanId, String code, String message,
                       String filePath) {
        this.workspaceId = workspaceId;
        this.repositoryId = repositoryId;
        this.scanId = scanId;
        this.code = code;
        this.message = message;
        this.filePath = filePath;
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

    public UUID getScanId() {
        return scanId;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public String getFilePath() {
        return filePath;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
