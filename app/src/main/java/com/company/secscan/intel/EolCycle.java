package com.company.secscan.intel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One endoflife.date release cycle (D-055), keyed on (product, cycle). A global intel cache with no
 * {@code workspace_id} (D-050): public reference data joined to workspace-scoped EOL sub-issues. Loaded weekly
 * by {@code EolSyncService} (api profile, per-product upsert — never a full wipe). The worker's EOL enricher
 * reads it to decide whether a detected cycle is actually past-EOL / ending soon; the scorer reads it for the
 * EOL base (Slice 4). Field names mirror the v1 API release object.
 */
@Entity
@Table(name = "eol_cycle")
@IdClass(EolCycle.Key.class)
public class EolCycle {

    @Id
    @Column(name = "product", nullable = false)
    private String product;

    @Id
    @Column(name = "cycle", nullable = false)
    private String cycle;

    @Column(name = "is_eol", nullable = false)
    private boolean eol;

    @Column(name = "eol_from")
    private LocalDate eolFrom;

    @Column(name = "latest")
    private String latest;

    @Column(name = "latest_date")
    private LocalDate latestDate;

    @Column(name = "release_date")
    private LocalDate releaseDate;

    @Column(name = "is_lts", nullable = false)
    private boolean lts;

    @Column(name = "is_maintained", nullable = false)
    private boolean maintained;

    @Column(name = "synced_at", nullable = false)
    private Instant syncedAt = Instant.now();

    protected EolCycle() {
        // for JPA
    }

    public EolCycle(String product, String cycle, boolean eol, LocalDate eolFrom, String latest,
                    LocalDate latestDate, LocalDate releaseDate, boolean lts, boolean maintained) {
        this.product = product;
        this.cycle = cycle;
        this.eol = eol;
        this.eolFrom = eolFrom;
        this.latest = latest;
        this.latestDate = latestDate;
        this.releaseDate = releaseDate;
        this.lts = lts;
        this.maintained = maintained;
        this.syncedAt = Instant.now();
    }

    public String getProduct() {
        return product;
    }

    public String getCycle() {
        return cycle;
    }

    public boolean isEol() {
        return eol;
    }

    public LocalDate getEolFrom() {
        return eolFrom;
    }

    public String getLatest() {
        return latest;
    }

    public LocalDate getLatestDate() {
        return latestDate;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public boolean isLts() {
        return lts;
    }

    public boolean isMaintained() {
        return maintained;
    }

    public Instant getSyncedAt() {
        return syncedAt;
    }

    /** Overwrites the mutable intel fields on an upsert (keeps the (product, cycle) identity). */
    public void update(boolean eol, LocalDate eolFrom, String latest, LocalDate latestDate, LocalDate releaseDate,
                       boolean lts, boolean maintained) {
        this.eol = eol;
        this.eolFrom = eolFrom;
        this.latest = latest;
        this.latestDate = latestDate;
        this.releaseDate = releaseDate;
        this.lts = lts;
        this.maintained = maintained;
        this.syncedAt = Instant.now();
    }

    /** Composite primary key (product, cycle). */
    public static final class Key implements Serializable {
        private String product;
        private String cycle;

        public Key() {
        }

        public Key(String product, String cycle) {
            this.product = product;
            this.cycle = cycle;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(product, key.product) && Objects.equals(cycle, key.cycle);
        }

        @Override
        public int hashCode() {
            return Objects.hash(product, cycle);
        }
    }
}
