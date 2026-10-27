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

    @Column(name = "connection_id")
    private UUID connectionId;

    // Provider-neutral identity (D-080, V22): workspace + provider + host + provider_repo_id. provider is
    // 'gitlab'|'github'; host is the connection's authority host[:port]; providerRepoId is the provider's stable
    // numeric id as text (= gitlabProjectId for GitLab rows). On a legacy GitLab insert that leaves host null,
    // the V22 trigger fills it from the connection; the upserter sets it explicitly for the select flow.
    @Column(name = "provider", nullable = false, updatable = false)
    private String provider = "gitlab";

    // host is filled by the V22 BEFORE INSERT trigger from the connection (identity never changes after insert),
    // so Hibernate never writes it (insertable/updatable false) and reads it back after insert (@Generated). This
    // also means a whole-entity update never clobbers it with a stale in-memory null.
    @Column(name = "host", nullable = false, insertable = false, updatable = false)
    @org.hibernate.annotations.Generated(event = org.hibernate.generator.EventType.INSERT)
    private String host;

    @Column(name = "provider_repo_id", nullable = false, updatable = false)
    private String providerRepoId;

    // The GitLab project id (null for GitHub rows). Kept for GitLab display / API calls; no longer the identity.
    @Column(name = "gitlab_project_id")
    private Long gitlabProjectId;

    @Column(name = "path_with_namespace", nullable = false)
    private String pathWithNamespace;

    @Column(name = "default_branch")
    private String defaultBranch;

    // The branch to clone for this repository; NULL = follow the GitLab default branch (D-076). The actual
    // cloned branch is snapshotted onto scan.branch at enqueue.
    @Column(name = "scan_branch")
    private String scanBranch;

    // Set true when the last pre-clone check found the configured branch gone in GitLab (A5); cleared when a
    // scan clones successfully or the branch is changed to an existing one (PATCH). Drives the UI "branch
    // missing — choose another" warning (RepositoryView.branchMissing).
    @Column(name = "branch_missing", nullable = false)
    private boolean branchMissing = false;

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

    @Column(name = "inactive_reason")
    private String inactiveReason;

    @Column(name = "reassigned_at")
    private Instant reassignedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Repository() {
        // for JPA
    }

    /**
     * Legacy GitLab constructor (host filled by the V22 trigger from the connection). Kept so existing GitLab
     * call sites are unchanged; provider/provider_repo_id are the GitLab identity derived from the project id.
     */
    public Repository(UUID workspaceId, UUID connectionId, long gitlabProjectId, String pathWithNamespace,
                      String defaultBranch, List<String> languages) {
        this(workspaceId, connectionId, "gitlab", gitlabProjectId, Long.toString(gitlabProjectId),
                pathWithNamespace, defaultBranch, languages);
    }

    /**
     * A GitHub repository: identity is GitHub's stable numeric repository id (as text); gitlab_project_id is null;
     * fullName is the owner/name display path. host is filled by the V22 trigger from the connection.
     */
    public static Repository github(UUID workspaceId, UUID connectionId, long githubRepoId, String fullName,
                                    String defaultBranch, List<String> languages) {
        return new Repository(workspaceId, connectionId, "github", null, Long.toString(githubRepoId), fullName,
                defaultBranch, languages);
    }

    private Repository(UUID workspaceId, UUID connectionId, String provider, Long gitlabProjectId,
                       String providerRepoId, String pathWithNamespace, String defaultBranch,
                       List<String> languages) {
        this.workspaceId = workspaceId;
        this.connectionId = connectionId;
        this.provider = provider;
        this.gitlabProjectId = gitlabProjectId;
        this.providerRepoId = providerRepoId;
        this.pathWithNamespace = pathWithNamespace;
        this.defaultBranch = defaultBranch;
        this.languages = languages == null ? new ArrayList<>() : new ArrayList<>(languages);
    }

    /** Marks the repository active and clears any inactivity / provisional-reassignment state. */
    public void activate() {
        this.active = true;
        this.inactiveReason = null;
        this.reassignedAt = null;
    }

    /** Marks the repository inactive with a reason code (e.g. {@code NO_ACCESSIBLE_CONNECTION}). */
    public void deactivate(String reason) {
        this.active = false;
        this.inactiveReason = reason;
        this.reassignedAt = null;
    }

    /**
     * Detaches the repository from any connection and deactivates it: used when the last connection that could
     * reach it is removed with no replacement. The row is kept (scan history hangs off it from M2).
     */
    public void detachAndDeactivate(String reason) {
        this.connectionId = null;
        deactivate(reason);
    }

    /**
     * Provisionally moves the repository to another connection (connection removal, D-020). It stays active
     * until the next use confirms or refutes access.
     */
    public void reassignTo(UUID connectionId) {
        this.connectionId = connectionId;
        this.reassignedAt = Instant.now();
    }

    /**
     * Reattaches a detached or inactive repository to the connection that just selected it, clearing its
     * inactive/provisional state. Used when a repository that lost its connection (or was deactivated) is
     * selected again through a connection that can reach it.
     */
    public void reattachTo(UUID connectionId) {
        this.connectionId = connectionId;
        activate();
    }

    /** Clears the provisional flag once a use has confirmed the connection can reach this repository. */
    public void confirmConnection() {
        this.reassignedAt = null;
    }

    public boolean isProvisional() {
        return reassignedAt != null;
    }

    /** Records the commit of the most recent successful scan (used by M9's skip-unchanged). */
    public void recordScannedSha(String sha) {
        this.lastScannedSha = sha;
    }

    /** Sets the repository sensitivity ({@code normal}/{@code high}/{@code low}) that drives the M5 score adjustment. */
    public void setSensitivity(String sensitivity) {
        this.sensitivity = sensitivity;
    }

    /** Whether dev/test-scoped dependency findings are included (not auto-ignored). Wired by M7 triage (D-059). */
    public void setIncludeDevDeps(boolean includeDevDeps) {
        this.includeDevDeps = includeDevDeps;
    }

    public void refreshFromGitlab(String pathWithNamespace, String defaultBranch, List<String> languages) {
        this.pathWithNamespace = pathWithNamespace;
        this.defaultBranch = defaultBranch;
        this.languages = languages == null ? new ArrayList<>() : new ArrayList<>(languages);
    }

    /**
     * Content-detected languages refreshed on a successful scan (M8.5, D-081). A null/empty list is ignored —
     * the caller only refreshes when detection produced something, so a detection failure never clears them.
     */
    public void setLanguages(List<String> languages) {
        if (languages != null && !languages.isEmpty()) {
            this.languages = new ArrayList<>(languages);
        }
    }

    /**
     * Sets the branch to scan (D-076). {@code null} = follow the GitLab default branch. The caller validates the
     * name and its existence in GitLab first. Changing the branch clears any stale branch-missing flag.
     */
    public void setScanBranch(String scanBranch) {
        this.scanBranch = scanBranch;
        this.branchMissing = false;
    }

    /** Updates the stored GitLab default branch (A6: on an enqueue-time refresh). No status change. */
    public void updateDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    /** The branch the next scan will clone: the configured scan branch, or the GitLab default when unset. */
    public String effectiveBranch() {
        return scanBranch != null ? scanBranch : defaultBranch;
    }

    /** A5: the configured branch was gone at the last pre-clone check. */
    public void markBranchMissing() {
        this.branchMissing = true;
    }

    /** Clears the branch-missing flag (a successful clone). */
    public void clearBranchMissing() {
        this.branchMissing = false;
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

    /** The GitLab project id, or null for a GitHub repository. */
    public Long getGitlabProjectId() {
        return gitlabProjectId;
    }

    public String getProvider() {
        return provider;
    }

    public String getHost() {
        return host;
    }

    public String getProviderRepoId() {
        return providerRepoId;
    }

    public String getPathWithNamespace() {
        return pathWithNamespace;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public String getScanBranch() {
        return scanBranch;
    }

    public boolean isBranchMissing() {
        return branchMissing;
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

    public String getInactiveReason() {
        return inactiveReason;
    }

    public Instant getReassignedAt() {
        return reassignedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
