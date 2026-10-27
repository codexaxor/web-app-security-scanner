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
 * A GitLab connection for a workspace. A workspace may hold several (M1b): each targets either a whole group
 * ({@code targetType == "group"}, carrying {@code gitlabGroupId}/{@code groupFullPath}) or a single project
 * ({@code targetType == "project"}, carrying {@code gitlabProjectId}/{@code projectFullPath}). The access token
 * is stored only as AES-256-GCM ciphertext. Numeric ids are used for all later calls because paths can be
 * renamed.
 */
@Entity
@Table(name = "gitlab_connection")
public class GitlabConnection {

    public static final String TARGET_GROUP = "group";
    public static final String TARGET_PROJECT = "project";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    public static final String PROVIDER_GITLAB = "gitlab";
    public static final String PROVIDER_GITHUB = "github";
    public static final String CREDENTIAL_TOKEN = "token";
    public static final String CREDENTIAL_NONE = "none";
    public static final String TARGET_OWNER = "owner";
    public static final String TARGET_REPO = "repo";

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    // Provider-neutral fields (V22). provider is 'gitlab'|'github'; credentialKind 'token'|'none' (public);
    // host is the authority of base_url, GENERATED in the DB (read-only here). owner/repoFullName/providerRepoId
    // carry GitHub owner and single-repo identity (null for GitLab group/project rows).
    @Column(name = "provider", nullable = false)
    private String provider = PROVIDER_GITLAB;

    @Column(name = "credential_kind", nullable = false)
    private String credentialKind = CREDENTIAL_TOKEN;

    @Column(name = "host", insertable = false, updatable = false)
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    private String host;

    @Column(name = "owner_login")
    private String ownerLogin;

    @Column(name = "repo_full_name")
    private String repoFullName;

    @Column(name = "provider_repo_id")
    private String providerRepoId;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "group_full_path")
    private String groupFullPath;

    @Column(name = "gitlab_group_id")
    private Long gitlabGroupId;

    @Column(name = "gitlab_project_id")
    private Long gitlabProjectId;

    @Column(name = "project_full_path")
    private String projectFullPath;

    @Column(name = "token_ciphertext", nullable = false)
    private String tokenCiphertext;

    @Column(name = "token_scopes")
    private String tokenScopes;

    @Column(name = "token_expires_at")
    private LocalDate tokenExpiresAt;

    @Column(name = "webhook_secret_ciphertext")
    private String webhookSecretCiphertext;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected GitlabConnection() {
        // for JPA
    }

    private GitlabConnection(UUID workspaceId, String baseUrl, String targetType, String tokenCiphertext,
                             String tokenScopes, LocalDate tokenExpiresAt) {
        this.workspaceId = workspaceId;
        this.baseUrl = baseUrl;
        this.targetType = targetType;
        this.tokenCiphertext = tokenCiphertext;
        this.tokenScopes = tokenScopes;
        this.tokenExpiresAt = tokenExpiresAt;
        this.lastVerifiedAt = Instant.now();
    }

    /** A connection targeting a whole group (and its subgroups). */
    public static GitlabConnection forGroup(UUID workspaceId, String baseUrl, String groupFullPath,
                                            long gitlabGroupId, String tokenCiphertext, String tokenScopes,
                                            LocalDate tokenExpiresAt) {
        GitlabConnection c = new GitlabConnection(workspaceId, baseUrl, TARGET_GROUP, tokenCiphertext,
                tokenScopes, tokenExpiresAt);
        c.groupFullPath = groupFullPath;
        c.gitlabGroupId = gitlabGroupId;
        return c;
    }

    /** A connection targeting a single project. */
    public static GitlabConnection forProject(UUID workspaceId, String baseUrl, String projectFullPath,
                                              long gitlabProjectId, String tokenCiphertext, String tokenScopes,
                                              LocalDate tokenExpiresAt) {
        GitlabConnection c = new GitlabConnection(workspaceId, baseUrl, TARGET_PROJECT, tokenCiphertext,
                tokenScopes, tokenExpiresAt);
        c.projectFullPath = projectFullPath;
        c.gitlabProjectId = gitlabProjectId;
        c.providerRepoId = Long.toString(gitlabProjectId);
        return c;
    }

    /** A GitHub owner (user/org) connection: all repositories the fine-grained PAT can see under that owner. */
    public static GitlabConnection forGitHubOwner(UUID workspaceId, String baseUrl, String ownerLogin,
                                                  String tokenCiphertext, String tokenScopes,
                                                  LocalDate tokenExpiresAt) {
        GitlabConnection c = new GitlabConnection(workspaceId, baseUrl, TARGET_OWNER, tokenCiphertext,
                tokenScopes, tokenExpiresAt);
        c.provider = PROVIDER_GITHUB;
        c.ownerLogin = ownerLogin;
        return c;
    }

    /**
     * A single-repository connection: a GitHub repo (token) or a public repo on any provider (credentialKind
     * {@code none}, no token). providerRepoId is the provider's stable numeric id (as text); repoFullName the
     * owner/name display path.
     */
    public static GitlabConnection forSingleRepo(UUID workspaceId, String baseUrl, String provider,
                                                 String credentialKind, String providerRepoId, String repoFullName,
                                                 String tokenCiphertext, String tokenScopes,
                                                 LocalDate tokenExpiresAt) {
        GitlabConnection c = new GitlabConnection(workspaceId, baseUrl, TARGET_REPO, tokenCiphertext,
                tokenScopes, tokenExpiresAt);
        c.provider = provider;
        c.credentialKind = credentialKind;
        c.providerRepoId = providerRepoId;
        c.repoFullName = repoFullName;
        return c;
    }

    /** Rotates the stored token (same target). Marks the connection freshly verified. */
    public void updateToken(String baseUrl, String tokenCiphertext, String tokenScopes, LocalDate tokenExpiresAt) {
        this.baseUrl = baseUrl;
        this.tokenCiphertext = tokenCiphertext;
        this.tokenScopes = tokenScopes;
        this.tokenExpiresAt = tokenExpiresAt;
        this.lastVerifiedAt = Instant.now();
    }

    public boolean isGroup() {
        return TARGET_GROUP.equals(targetType);
    }

    public boolean isProject() {
        return TARGET_PROJECT.equals(targetType);
    }

    /** The project id this connection targets (project mode) or exposes a single target for; else the group id. */
    public Long targetId() {
        return isGroup() ? gitlabGroupId : gitlabProjectId;
    }

    /** Human-readable path of the target, for the UI. */
    public String targetPath() {
        return isGroup() ? groupFullPath : projectFullPath;
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

    public String getProvider() {
        return provider;
    }

    public String getCredentialKind() {
        return credentialKind;
    }

    /** Authority host[:port] of base_url (generated in the DB; null on a just-built, not-yet-persisted entity). */
    public String getHost() {
        return host;
    }

    public String getOwnerLogin() {
        return ownerLogin;
    }

    public String getRepoFullName() {
        return repoFullName;
    }

    public String getProviderRepoId() {
        return providerRepoId;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getGroupFullPath() {
        return groupFullPath;
    }

    public Long getGitlabGroupId() {
        return gitlabGroupId;
    }

    public Long getGitlabProjectId() {
        return gitlabProjectId;
    }

    public String getProjectFullPath() {
        return projectFullPath;
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

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
