package com.company.secscan.workspace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The per-workspace settings stored in {@code workspace.settings} (jsonb). M7 adds the EPSS toggle (D-060),
 * default OFF. A mutable JavaBean so Hibernate's JSON mapping and Jackson round-trip it cleanly; unknown
 * properties are ignored so older/newer rows deserialize without error.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkspaceSettings {

    private boolean epssAutoIgnoreEnabled = false;

    public boolean isEpssAutoIgnoreEnabled() {
        return epssAutoIgnoreEnabled;
    }

    public void setEpssAutoIgnoreEnabled(boolean epssAutoIgnoreEnabled) {
        this.epssAutoIgnoreEnabled = epssAutoIgnoreEnabled;
    }
}
