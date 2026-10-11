package com.company.secscan.repository;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A GitLab project the workspace has selected for scanning. Identified by {@code gitlabProjectId} (stable across
 * renames), unique per workspace.
 */
@Entity
@Table(name = "repository")
public class Repository {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "connection_id", nullable = false)
    private UUID connectionId;

    @Column(name = "gitlab_project_id", nullable = false)
    private long gitlabProjectId;

    @Column(name = "path_with_namespace", nullable = false)
    private String pathWithNamespace;

    @Column(name = "default_branch")
    private String defaultBranch;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "sensitivity", nullable = false)
    private String sensitivity = "normal";

    @Column(name = "include_dev_deps", nullable = false)
    private boolean includeDevDeps = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "languages", nullable = false, columnDefinition = "jsonb")
    private List<String> languages = new ArrayList<>();

    @Column(name = "last_scanned_sha")
    private String lastScannedSha;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Repository() {
        // for JPA
    }

    public Repository(UUID workspaceId, UUID connectionId, long gitlabProjectId, String pathWithNamespace,
                      String defaultBranch, List<String> languages) {
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.gitlabProjectId = gitlabProjectId;
        this.pathWithNamespace = pathWithNamespace;
        this.defaultBranch = defaultBranch;
        this.languages = languages == null ? new ArrayList<>() : new ArrayList<>(languages);
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public void refreshFromGitlab(String pathWithNamespace, String defaultBranch, List<String> languages) {
        this.pathWithNamespace = pathWithNamespace;
        this.defaultBranch = defaultBranch;
        this.languages = languages == null ? new ArrayList<>() : new ArrayList<>(languages);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public UUID getConnectionId() {
        return connectionId;
    }

    public long getGitlabProjectId() {
        return gitlabProjectId;
    }

    public String getPathWithNamespace() {
        return pathWithNamespace;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public boolean isActive() {
        return active;
    }

    public String getSensitivity() {
        return sensitivity;
    }

    public boolean isIncludeDevDeps() {
        return includeDevDeps;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public String getLastScannedSha() {
        return lastScannedSha;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
