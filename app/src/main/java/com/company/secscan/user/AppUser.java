package com.company.secscan.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A login. Roles exist as a column (admin/member/viewer) but are not yet enforced — authorization by role
 * arrives in M10 (see {@code docs/DECISIONS.md} D-010).
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_DISABLED = "disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "role", nullable = false)
    private String role;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    /** {@code active} or {@code disabled} (V23, D-098). A disabled user cannot sign in. */
    @Column(name = "status", nullable = false)
    private String status = STATUS_ACTIVE;

    /** The platform operator (D-094): at most one, always active (DB-enforced, V23). */
    @Column(name = "is_operator", nullable = false)
    private boolean operator;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "status_changed_by")
    private UUID statusChangedBy;

    protected AppUser() {
        // for JPA
    }

    public AppUser(UUID workspaceId, String email, String passwordHash, String role) {
        this.workspaceId = workspaceId;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /** Resets the stored password hash and role — used by the idempotent demo-mode re-bootstrap (D-091). */
    public void updateCredentials(String passwordHash, String role) {
        this.passwordHash = passwordHash;
        this.role = role;
    }

    /**
     * Makes this user the platform operator (D-094). Callers must first check that no operator exists — the
     * single-operator unique index (V23) rejects a second one rather than letting it happen silently.
     */
    public void promoteToOperator() {
        if (!STATUS_ACTIVE.equals(status)) {
            throw new IllegalStateException("Only an active user can become the operator");
        }
        this.operator = true;
    }

    public UUID getId() {
        return id;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRole() {
        return role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getStatus() {
        return status;
    }

    public boolean isActive() {
        return STATUS_ACTIVE.equals(status);
    }

    public boolean isOperator() {
        return operator;
    }

    public Instant getStatusChangedAt() {
        return statusChangedAt;
    }

    public UUID getStatusChangedBy() {
        return statusChangedBy;
    }
}
