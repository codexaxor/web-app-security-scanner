package com.company.secscan.issue;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * A manual (or .secscan.yml-materialized) ignore rule (D-058). Users triage by scope: this sub-issue, this
 * group, a path prefix (per repo), a CVE (global or per repo), or a rule id (global or per repo). Each requires
 * a {@link #reason}. A {@code .secscan.yml} CVE ignore is materialized as a system rule ({@link #createdBy} =
 * {@link #CREATED_BY_SECSCAN_YML}, D-057). Applying/removing a rule runs through reconcile and writes a
 * {@link TriageEvent} for every status change.
 */
@Entity
@Table(name = "ignore_rule")
public class IgnoreRule {

    public static final String SCOPE_SUBISSUE = "subissue";
    public static final String SCOPE_GROUP = "group";
    public static final String SCOPE_PATH = "path";
    public static final String SCOPE_CVE = "cve";
    public static final String SCOPE_RULE = "rule";

    /** {@link #createdBy} value for a rule materialized from an in-repo {@code .secscan.yml} (D-057). */
    public static final String CREATED_BY_SECSCAN_YML = "secscan.yml";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "scope", nullable = false)
    private String scope;

    /** NULL = a global rule (every repository in the workspace); otherwise scoped to one repository. */
    @Column(name = "repository_id")
    private UUID repositoryId;

    @Column(name = "match_value", nullable = false)
    private String matchValue;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    // DB default `now()`; @Generated(INSERT) reads it back after insert so POST /ignore-rules returns a
    // non-null createdAt (OD-14 item 8).
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected IgnoreRule() {
        // for JPA
    }

    public IgnoreRule(UUID workspaceId, String scope, UUID repositoryId, String matchValue, String reason,
                      String createdBy) {
        this.workspaceId = workspaceId;
        this.scope = scope;
        this.repositoryId = repositoryId;
        this.matchValue = matchValue;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    /** True for a rule materialized from an in-repo {@code .secscan.yml} (D-057 revoke lifecycle). */
    public boolean isFromSecscanYml() {
        return CREATED_BY_SECSCAN_YML.equals(createdBy);
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getScope() {
        return scope;
    }

    public UUID getRepositoryId() {
        return repositoryId;
    }

    public String getMatchValue() {
        return matchValue;
    }

    public String getReason() {
        return reason;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
