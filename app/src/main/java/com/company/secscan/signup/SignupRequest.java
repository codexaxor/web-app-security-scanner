package com.company.secscan.signup;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * A self sign-up awaiting the platform operator's decision (D-093, D-098). <b>Pre-tenant</b>: a pending request
 * belongs to no workspace yet, which is why it is its own table and not an {@code app_user} row (that keeps
 * {@code app_user.workspace_id NOT NULL}). The workspace and the active {@code app_user} are created only at
 * approval; {@link #workspaceId} then records which workspace this request produced (traceability, not scoping).
 *
 * <p>The bcrypt {@code password_hash} lives here only while the request is pending; approve and reject clear it
 * (approve first copies it into the new {@code app_user}, in the same transaction). V23 enforces this shape.
 */
@Entity
@Table(name = "signup_request")
public class SignupRequest {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "requested_workspace_name", nullable = false)
    private String requestedWorkspaceName;

    @Column(name = "status", nullable = false)
    private String status = STATUS_PENDING;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "workspace_id")
    private UUID workspaceId;

    protected SignupRequest() {
        // for JPA
    }

    private SignupRequest(String email, String passwordHash, String requestedWorkspaceName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.requestedWorkspaceName = requestedWorkspaceName;
    }

    /** A new pending request. The email is stored lowercased (case-insensitive identity, like V3). */
    public static SignupRequest pending(String email, String passwordHash, String requestedWorkspaceName) {
        Objects.requireNonNull(passwordHash, "passwordHash");
        return new SignupRequest(email.trim().toLowerCase(Locale.ROOT), passwordHash,
                requestedWorkspaceName.trim());
    }

    /**
     * Marks the request approved for the given (just created) workspace and clears the hash. The caller must
     * have copied {@link #getPasswordHash()} into the new {@code app_user} first, in the same transaction.
     */
    public void approve(UUID workspaceId, UUID operatorId, Instant at) {
        requirePending();
        this.status = STATUS_APPROVED;
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
        decide(operatorId, at);
    }

    /** Marks the request rejected and clears the hash. The email may sign up again afterwards. */
    public void reject(UUID operatorId, Instant at) {
        requirePending();
        this.status = STATUS_REJECTED;
        decide(operatorId, at);
    }

    private void decide(UUID operatorId, Instant at) {
        this.decidedBy = Objects.requireNonNull(operatorId, "operatorId");
        this.decidedAt = Objects.requireNonNull(at, "at");
        this.passwordHash = null;
    }

    private void requirePending() {
        if (!STATUS_PENDING.equals(status)) {
            throw new IllegalStateException("Sign-up request is already " + status);
        }
    }

    public boolean isPending() {
        return STATUS_PENDING.equals(status);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getRequestedWorkspaceName() {
        return requestedWorkspaceName;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public UUID getDecidedBy() {
        return decidedBy;
    }

    public UUID getWorkspaceId() {
        return workspaceId;
    }
}
