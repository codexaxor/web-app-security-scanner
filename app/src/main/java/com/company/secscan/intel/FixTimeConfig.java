package com.company.secscan.intel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Tunable fix-time minutes for one remediation category (SPEC "Fix time"). A global config table with no
 * {@code workspace_id} (D-050), seeded in V13. The category keys match {@code com.company.secscan.scoring.FixTime}
 * (SCA_PATCH_MINOR / SCA_MAJOR / SECRET_ROTATION / …). SAST/IaC/EOL rows exist but are unused until M6.
 */
@Entity
@Table(name = "fix_time_config")
public class FixTimeConfig {

    @Id
    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "minutes", nullable = false)
    private int minutes;

    protected FixTimeConfig() {
        // for JPA
    }

    public FixTimeConfig(String category, int minutes) {
        this.category = category;
        this.minutes = minutes;
    }

    public String getCategory() {
        return category;
    }

    public int getMinutes() {
        return minutes;
    }
}
