package com.company.secscan.intel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One FIRST EPSS row (D-047): exploitation probability and percentile for a CVE. A global intel cache with no
 * {@code workspace_id} (D-050). Loaded daily by {@code EpssSyncService} (via {@code IntelSyncScheduler}, api
 * profile). The value is stored and
 * exposed on the sub-issue (with the {@code (c) FIRST.org} attribution), but EPSS score adjustments stay OFF in
 * M5 (an M7 toggle) — the scorer never reads EPSS.
 */
@Entity
@Table(name = "epss_score")
public class EpssScore {

    @Id
    @Column(name = "cve", nullable = false)
    private String cve;

    @Column(name = "epss", nullable = false)
    private BigDecimal epss;

    @Column(name = "percentile")
    private BigDecimal percentile;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();

    protected EpssScore() {
        // for JPA
    }

    public EpssScore(String cve, BigDecimal epss, BigDecimal percentile) {
        this.cve = cve;
        this.epss = epss;
        this.percentile = percentile;
        this.syncedAt = Instant.now();
    }

    public String getCve() {
        return cve;
    }

    public BigDecimal getEpss() {
        return epss;
    }

    public BigDecimal getPercentile() {
        return percentile;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }
}
