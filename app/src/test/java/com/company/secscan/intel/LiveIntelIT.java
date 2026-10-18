package com.company.secscan.intel;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.IntelRows.EpssRow;
import com.company.secscan.intel.IntelRows.KevFeed;
import com.company.secscan.intel.IntelRows.KevRow;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Validates the REAL CISA KEV and FIRST EPSS feeds — do not rely on WireMock alone (plan-review change #1).
 * Tagged {@code live-intel} and excluded from the default build exactly as {@code live-osv} is; run with
 * {@code ./gradlew :app:test -PliveIntel --tests "*LiveIntelIT"}. These hit the network over TLS (always on)
 * and need no database — they exercise {@link IntelFetcher} directly.
 *
 * <p>The last test intersects our fixtures' CVEs (recorded in {@code /fixtures-cves.txt}) with the live KEV
 * list and reports the overlap; the seeded {@code kev_entry} remains the offline (default-build) done-when
 * proof ({@code ScoringReconcileIT}).
 */
@Tag("live-intel")
class LiveIntelIT {

    private static final Logger log = LoggerFactory.getLogger(LiveIntelIT.class);

    private final IntelFetcher fetcher = new IntelFetcher(new IntelProperties());

    @Test
    void kevFeedLoadsAndMapsRows() {
        KevFeed feed = fetcher.fetchKev();
        assertThat(feed.rows()).hasSizeGreaterThan(500);
        // Every row maps a non-blank cve; the envelope's catalogVersion parsed.
        assertThat(feed.catalogVersion()).isNotBlank();
        assertThat(feed.rows()).allSatisfy(r -> assertThat(r.cve()).startsWith("CVE-"));
        // Log4Shell is a long-standing KEV entry — cveID -> cve, vulnerabilityName -> vulnName, dateAdded mapped.
        KevRow log4shell = feed.rows().stream().filter(r -> r.cve().equals("CVE-2021-44228"))
                .findFirst().orElseThrow();
        assertThat(log4shell.vulnName()).isNotBlank();
        assertThat(log4shell.dateAdded()).isNotNull();
        log.info("Live KEV: {} rows, catalog {}", feed.rows().size(), feed.catalogVersion());
    }

    @Test
    void epssFeedLoadsAndMapsRows() {
        List<EpssRow> rows = fetcher.fetchEpss();
        assertThat(rows).hasSizeGreaterThan(100_000);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.cve()).startsWith("CVE-");
            assertThat(r.epss()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        });
        log.info("Live EPSS: {} rows", rows.size());
    }

    @Test
    void fixtureCvesIntersectedWithLiveKev() {
        Set<String> fixtureCves = loadFixtureCves();
        KevFeed feed = fetcher.fetchKev();
        Set<String> kev = feed.rows().stream().map(KevRow::cve).collect(Collectors.toSet());

        Set<String> intersection = new TreeSet<>(fixtureCves);
        intersection.retainAll(kev);

        log.info("Fixture CVEs: {}; live KEV: {}; intersection ({}): {}",
                fixtureCves.size(), kev.size(), intersection.size(), intersection);

        // If any fixture CVE is on the live KEV list it can drive a live end-to-end done-when; the offline
        // seeded kev_entry (ScoringReconcileIT) proves the criterion regardless of today's live KEV contents.
        if (fixtureCves.isEmpty()) {
            log.warn("No fixture CVE list recorded (/fixtures-cves.txt); run the live-osv fixture sweep to fill it.");
        }
    }

    private static Set<String> loadFixtureCves() {
        Set<String> cves = new TreeSet<>();
        try (InputStream in = LiveIntelIT.class.getResourceAsStream("/fixtures-cves.txt")) {
            if (in == null) {
                return cves;
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String s = line.trim();
                    if (s.startsWith("CVE-")) {
                        cves.add(s);
                    }
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return cves;
    }
}
