package com.company.secscan.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A tenant. In single-tenant mode there is exactly one, seeded by {@code V2__default_workspace.sql}.
 * The {@code settings} jsonb column exists in the table but is not mapped yet (added in a later milestone).
 */
@Entity
@Table(name = "workspace")
public class Workspace {

    /** Fixed id of the seeded single-tenant workspace (see {@code V2__default_workspace.sql}). */
    public static final UUID DEFAULT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    protected Workspace() {
        // for JPA
    }

    public Workspace(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
