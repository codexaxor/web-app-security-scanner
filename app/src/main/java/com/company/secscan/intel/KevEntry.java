package com.company.secscan.intel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One CISA KEV catalog row (D-046), keyed on the CVE. A global intel cache with no {@code workspace_id} (D-050):
 * KEV is public, CVE-keyed reference data joined to workspace-scoped sub-issues by CVE. Loaded daily by
 * {@code KevSyncJob} (api profile). A sub-issue whose vuln id or any {@code cve_alias} appears here gets +5.
 */
@Entity
@Table(name = "kev_entry")
public class KevEntry {

    @Id
    @Column(name = "cve", nullable = false)
    private String cve;

    @Column(name = "vuln_name")
    private String vulnName;

    @Column(name = "date_added")
    private LocalDate dateAdded;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();

    protected KevEntry() {
        // for JPA
    }

    public KevEntry(String cve, String vulnName, LocalDate dateAdded) {
        this.cve = cve;
        this.vulnName = vulnName;
        this.dateAdded = dateAdded;
        this.syncedAt = Instant.now();
    }

    public String getCve() {
        return cve;
    }

    public String getVulnName() {
        return vulnName;
    }

    public LocalDate getDateAdded() {
        return dateAdded;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
