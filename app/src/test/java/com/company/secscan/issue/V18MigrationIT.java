package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves V18 (SF1, the persisted auto-ignore cause) applies on top of V17 and is additive: it adds ONE NULLABLE
 * {@code sub_issue.auto_ignore_reason} text column with no backfill, so every existing row is untouched. V1-V17
 * are untouched (confirmed by V17's {@code ignore_rule} still present beneath V18).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class V18MigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v18AddsOneNullableColumnWithoutBackfill() {
        assertThat(columnExists("sub_issue", "auto_ignore_reason")).isTrue();
        assertThat(isNullable("sub_issue", "auto_ignore_reason")).isTrue();
    }

    @Test
    void theColumnIsAdditiveWithNoBackfill() {
        // No NOT NULL and no DEFAULT → the migration cannot touch an existing row (it simply gets NULL).
        assertThat(isNullable("sub_issue", "auto_ignore_reason")).isTrue();
        String def = jdbc.queryForObject(
                "select column_default from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'sub_issue' "
                        + "and column_name = 'auto_ignore_reason'", String.class);
        assertThat(def).as("no default → no backfill").isNull();
    }

    @Test
    void v1ToV17AreUntouchedBeneathV18() {
        assertThat(tableExists("ignore_rule")).isTrue();   // V17
        assertThat(tableExists("eol_cycle")).isTrue();     // V16
        assertThat(tableExists("sub_issue")).isTrue();
    }

    private boolean tableExists(String name) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)", Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnExists(String table, String column) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?)",
                Boolean.class, table, column);
        return Boolean.TRUE.equals(exists);
    }

    private boolean isNullable(String table, String column) {
        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?",
                String.class, table, column);
        return "YES".equals(nullable);
    }
}
