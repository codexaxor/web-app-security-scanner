package com.company.secscan.intel;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
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
 * Offline tests for the EOL sync (D-055). A local WireMock serves the endoflife.date v1 product JSON, so the
 * default {@code ./gradlew build} never touches the network (the live shape is validated by {@code LiveEolIT}).
 * Covers the v1 parse + field mapping, the per-product upsert, and the NEVER-WIPE guarantee (BLOCKER 6): a
 * failing product keeps its existing rows while other products still sync.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
class EolSyncIT {

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
        registry.add("secscan.intel.eol-base-url", () -> WIREMOCK.baseUrl());
        registry.add("secscan.intel.eol-products", () -> "spring-boot,nodejs");
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
    }

    @Autowired
    EolSyncService eolSync;
    @Autowired
    EolCycleRepository eolCycles;

    @BeforeEach
    void reset() {
        eolCycles.deleteAllInBatch();
        WIREMOCK.resetAll();
    }

    private static void stub(String slug, String body) {
        WIREMOCK.stubFor(get(urlEqualTo("/products/" + slug + "/"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private static final String SPRING_BOOT = """
        {"schema_version":"1.2.0","result":{"name":"spring-boot","releases":[
          {"name":"2.1","releaseDate":"2018-10-30","isEol":true,"eolFrom":"2019-10-01","isLts":false,
           "isMaintained":false,"latest":{"name":"2.1.18","date":"2020-08-01"}},
          {"name":"3.4","releaseDate":"2024-11-21","isEol":false,"eolFrom":"2025-12-01","isLts":false,
           "isMaintained":true,"latest":{"name":"3.4.0","date":"2024-11-21"}}
        ]}}""";
    private static final String NODEJS = """
        {"schema_version":"1.2.0","result":{"name":"nodejs","releases":[
          {"name":"18","releaseDate":"2022-04-19","isEol":false,"eolFrom":"2025-04-30","isLts":true,
           "isMaintained":true,"latest":{"name":"18.20.0","date":"2024-03-26"}}
        ]}}""";

    @Test
    void parsesV1ReleasesAndUpserts() {
        stub("spring-boot", SPRING_BOOT);
        stub("nodejs", NODEJS);

        int loaded = eolSync.refresh();

        assertThat(loaded).isEqualTo(3);
        EolCycle sb21 = eolCycles.findByProductAndCycle("spring-boot", "2.1").orElseThrow();
        assertThat(sb21.isEol()).isTrue();
        assertThat(sb21.getEolFrom()).isEqualTo(java.time.LocalDate.of(2019, 10, 1));
        assertThat(sb21.getLatest()).isEqualTo("2.1.18");
        assertThat(eolCycles.findByProductAndCycle("nodejs", "18")).isPresent();
    }

    @Test
    void aFailingProductKeepsItsExistingRowsWhileOthersSync() {
        // First, a healthy sync populates both products.
        stub("spring-boot", SPRING_BOOT);
        stub("nodejs", NODEJS);
        eolSync.refresh();
        assertThat(eolCycles.count()).isEqualTo(3);

        // Now spring-boot fails (500) but nodejs changes. The never-wipe guard keeps spring-boot's rows.
        WIREMOCK.resetAll();
        WIREMOCK.stubFor(get(urlEqualTo("/products/spring-boot/"))
                .willReturn(aResponse().withStatus(500)));
        stub("nodejs", NODEJS.replace("18.20.0", "18.20.4"));

        eolSync.refresh();

        // spring-boot rows survived (never wiped on a failed fetch, BLOCKER 6)...
        assertThat(eolCycles.findByProductAndCycle("spring-boot", "2.1")).isPresent();
        assertThat(eolCycles.findByProductAndCycle("spring-boot", "3.4")).isPresent();
        // ...and nodejs was upserted to the new latest.
        assertThat(eolCycles.findByProductAndCycle("nodejs", "18").orElseThrow().getLatest())
                .isEqualTo("18.20.4");
    }
}
