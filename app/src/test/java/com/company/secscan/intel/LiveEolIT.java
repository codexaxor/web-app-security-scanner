package com.company.secscan.intel;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.intel.IntelRows.EolRow;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Validates the EOL fetcher against the REAL endoflife.date v1 API (D-055), excluded from the default build
 * ({@code @Tag("live-eol")}, run with {@code -PliveEol}). Confirms the live v1 schema still parses — the slugs
 * we rely on resolve and a long-dead Spring Boot cycle is reported as EOL. The offline shape is covered by
 * {@code EolSyncIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Tag("live-eol")
class LiveEolIT {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
    }

    @Autowired
    IntelFetcher fetcher;
    @Autowired
    IntelProperties props;

    @Test
    void springBootCyclesLoadFromTheLiveV1ApiAndADeadCycleIsEol() {
        List<EolRow> rows = fetcher.fetchEol(props.getEolBaseUrl(), "spring-boot");
        assertThat(rows).isNotEmpty();
        // Spring Boot 2.1 has been EOL for years — it must still be reported as such by the live v1 API.
        assertThat(rows).anySatisfy(r -> {
            assertThat(r.cycle()).isEqualTo("2.1");
            assertThat(r.eol()).isTrue();
        });
    }

    @Test
    void everyConfiguredProductSlugResolves() {
        for (String slug : props.getEolProducts()) {
            assertThat(fetcher.fetchEol(props.getEolBaseUrl(), slug))
                    .as("product slug %s resolves on endoflife.date v1", slug).isNotEmpty();
        }
    }
}
