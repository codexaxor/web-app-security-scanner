package com.company.secscan.workspace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A tenant. In single-tenant mode there is exactly one, seeded by {@code V2__default_workspace.sql}.
 * The {@code settings} jsonb column carries per-workspace tuning — M7 maps it for the EPSS toggle (D-060).
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

    // Initialized so an entity INSERT sends non-null JSON (the column is NOT NULL; V2 seeds the default via SQL).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "settings", columnDefinition = "jsonb")
    private WorkspaceSettings settings = new WorkspaceSettings();

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

    /** Never null to callers: an unset {@code settings} jsonb reads as defaults (EPSS toggle off). */
    public WorkspaceSettings getSettings() {
        return settings == null ? new WorkspaceSettings() : settings;
    }

    public void setSettings(WorkspaceSettings settings) {
        this.settings = settings;
    }

    public boolean isEpssAutoIgnoreEnabled() {
        return getSettings().isEpssAutoIgnoreEnabled();
    }
}
