package com.company.secscan.intel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Parsed-feed value types for the intel sync (D-046/D-047), kept tool-agnostic and free of JPA. */
public final class IntelRows {

    private IntelRows() {
    }

    /** One CISA KEV row as fetched: {@code cveID} → cve, {@code vulnerabilityName} → vulnName, {@code dateAdded}. */
    public record KevRow(String cve, String vulnName, LocalDate dateAdded) {
    }

    /** One FIRST EPSS row as fetched from the bulk CSV. */
    public record EpssRow(String cve, BigDecimal epss, BigDecimal percentile) {
    }

    /** The whole KEV fetch: the catalog version (from the envelope) and its rows. */
    public record KevFeed(String catalogVersion, List<KevRow> rows) {
    }

    /** One endoflife.date v1 release cycle for a product (D-055). Dates are nullable. */
    public record EolRow(String product, String cycle, boolean eol, LocalDate eolFrom, String latest,
                         LocalDate latestDate, LocalDate releaseDate, boolean lts, boolean maintained) {
    }
}
