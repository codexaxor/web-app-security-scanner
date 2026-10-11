package com.company.secscan.gitlab;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One GitLab connection per workspace (M1): base URL, the group it scopes to, and the access token stored only
 * as AES-256-GCM ciphertext. The numeric {@code gitlabGroupId} is used for all later calls because group paths
 * can be renamed.
 */
@Entity
@Table(name = "gitlab_connection")
public class GitlabConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "group_full_path", nullable = false)
    private String groupFullPath;

    @Column(name = "gitlab_group_id", nullable = false)
    private long gitlabGroupId;

    @Column(name = "token_ciphertext", nullable = false)
    private String tokenCiphertext;

    @Column(name = "token_scopes")
    private String tokenScopes;

    @Column(name = "token_expires_at")
    private LocalDate tokenExpiresAt;

    @Column(name = "webhook_secret_ciphertext")
    private String webhookSecretCiphertext;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected GitlabConnection() {
        // for JPA
    }

    public GitlabConnection(UUID workspaceId, String baseUrl, String groupFullPath, long gitlabGroupId,
                            String tokenCiphertext, String tokenScopes, LocalDate tokenExpiresAt) {
        this.workspaceId = workspaceId;
        this.baseUrl = baseUrl;
        this.groupFullPath = groupFullPath;
        this.gitlabGroupId = gitlabGroupId;
        this.tokenCiphertext = tokenCiphertext;
        this.tokenScopes = tokenScopes;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public void updateToken(String tokenCiphertext, String tokenScopes, LocalDate tokenExpiresAt) {
        this.tokenCiphertext = tokenCiphertext;
        this.tokenScopes = tokenScopes;
        this.tokenExpiresAt = tokenExpiresAt;
    }

    public void updateGroup(String baseUrl, String groupFullPath, long gitlabGroupId) {
        this.baseUrl = baseUrl;
        this.groupFullPath = groupFullPath;
        this.gitlabGroupId = gitlabGroupId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getGroupFullPath() {
        return groupFullPath;
    }

    public long getGitlabGroupId() {
        return gitlabGroupId;
    }

    public String getTokenCiphertext() {
        return tokenCiphertext;
    }

    public String getTokenScopes() {
        return tokenScopes;
    }

    public LocalDate getTokenExpiresAt() {
        return tokenExpiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
