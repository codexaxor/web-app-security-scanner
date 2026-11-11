package com.company.secscan.intel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A maintained per-{@code check_id} severity override for IaC findings (D-083 OD-27, D-087, V20). OSS Checkov
 * checks carry no severity, so IaC findings otherwise score base MEDIUM (D-054); a row here scores that check
 * CRITICAL/HIGH/MEDIUM/LOW instead. Scoring-only — it is read by {@link com.company.secscan.issue.SubIssueScorer}
 * at scan time and on re-score, never changes status, and the {@code check_id} is not a fingerprint input.
 *
 * <p>Global config table with no {@code workspace_id} (D-050 family). Joined to workspace-scoped {@code sub_issue}
 * rows at score time; its lookup ({@code findById}) is a documented domain-rule-1 exception.
 */
@Entity
@Table(name = "iac_severity_override")
public class IacSeverityOverride {

    @Id
    @Column(name = "check_id", nullable = false)
    private String checkId;

    @Column(name = "severity", nullable = false)
    private String severity;

    @Column(name = "note")
    private String note;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected IacSeverityOverride() {
        // for JPA
    }

    public IacSeverityOverride(String checkId, String severity, String note) {
        this.checkId = checkId;
        this.severity = severity;
        this.note = note;
    }

    public String getCheckId() {
        return checkId;
    }

    public String getSeverity() {
        return severity;
    }

    public String getNote() {
        return note;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
