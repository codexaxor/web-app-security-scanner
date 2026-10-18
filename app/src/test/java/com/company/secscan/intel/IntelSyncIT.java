package com.company.secscan.intel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.IntelRows.EpssRow;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Offline tests for the KEV/EPSS sync (D-046/D-047). A local WireMock serves the feeds so the default
 * {@code ./gradlew build} never touches the network; the live-feed shape is validated separately by the
 * {@code @Tag("live-intel")} tests. Covers: KEV JSON parse + field mapping, EPSS gzip-CSV parse (comment line
 * skipped), and the EPSS staged bulk swap (upsert + delete of stale rows, change #3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
class IntelSyncIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");
    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("secscan.intel.kev-url", () -> WIREMOCK.baseUrl() + "/kev.json");
        registry.add("secscan.intel.epss-url", () -> WIREMOCK.baseUrl() + "/epss.csv.gz");
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
    }

    @Autowired
    KevSyncService kevSync;
    @Autowired
    EpssSyncService epssSync;
    @Autowired
    KevEntryRepository kevEntries;
    @Autowired
    EpssScoreRepository epssScores;

    @BeforeEach
    void reset() {
        WIREMOCK.resetAll();
        kevEntries.deleteAllInBatch();
        epssScores.deleteAllInBatch();
    }

    @Test
    void kevRefreshLoadsAndMapsRows() {
        WIREMOCK.stubFor(get(urlEqualTo("/kev.json")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {
                          "catalogVersion": "2026.10.19",
                          "vulnerabilities": [
                            {"cveID":"CVE-2022-42889","vulnerabilityName":"Apache Commons Text RCE","dateAdded":"2023-02-01"},
                            {"cveID":"CVE-2021-44228","vulnerabilityName":"Log4Shell","dateAdded":"2021-12-10"}
                          ]
                        }
                        """)));

        int loaded = kevSync.refresh();

        assertThat(loaded).isEqualTo(2);
        assertThat(kevEntries.count()).isEqualTo(2);
        KevEntry text = kevEntries.findById("CVE-2022-42889").orElseThrow();
        assertThat(text.getVulnName()).isEqualTo("Apache Commons Text RCE");
        assertThat(text.getDateAdded()).isEqualTo(LocalDate.of(2023, 2, 1));
    }

    @Test
    void epssRefreshParsesGzippedCsvAndLoads() {
        String csv = "#model_version:v2026.03.14,score_date:2026-10-19T00:00:00+0000\n"
                + "cve,epss,percentile\n"
                + "CVE-2022-42889,0.944120,0.999000\n"
                + "CVE-2021-44228,0.975000,0.999500\n"
                + "CVE-2000-0001,0.000100,0.010000\n";
        WIREMOCK.stubFor(get(urlEqualTo("/epss.csv.gz")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/gzip").withBody(gzip(csv))));

        int loaded = epssSync.refresh();

        assertThat(loaded).isEqualTo(3);
        assertThat(epssScores.count()).isEqualTo(3);
        EpssScore text = epssScores.findById("CVE-2022-42889").orElseThrow();
        assertThat(text.getEpss()).isEqualByComparingTo("0.944120");
        assertThat(text.getPercentile()).isEqualByComparingTo("0.999000");
    }

    @Test
    void epssLoadSwapsOutStaleRows() {
        epssSync.load(List.of(
                new EpssRow("CVE-2022-42889", new BigDecimal("0.10000"), new BigDecimal("0.50000")),
                new EpssRow("CVE-2000-0001", new BigDecimal("0.00010"), new BigDecimal("0.01000"))));
        assertThat(epssScores.count()).isEqualTo(2);

        // A later snapshot updates one CVE and drops the other -> the swap upserts and deletes the stale row.
        epssSync.load(List.of(
                new EpssRow("CVE-2022-42889", new BigDecimal("0.94412"), new BigDecimal("0.99900"))));

        assertThat(epssScores.count()).isEqualTo(1);
        assertThat(epssScores.findById("CVE-2022-42889").orElseThrow().getEpss())
                .isEqualByComparingTo("0.94412");
        assertThat(epssScores.findById("CVE-2000-0001")).isEmpty();
    }

    private static byte[] gzip(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(s.getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
