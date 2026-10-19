package com.company.secscan.intel;

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
 * Proves the M5 scoring migrations (V12 schema, V13 seed, V14 advisory_summary, V15 index) apply to an empty
 * database: the scoring columns exist on {@code sub_issue}/{@code scan_finding}, the three global intel/config
 * tables exist (D-050), the fix-time config is seeded (D-050/V13), and the workspace+status index exists (SF5).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class ScoringMigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v12AddsScoringColumnsToSubIssue() {
        assertThat(columnExists("sub_issue", "score")).isTrue();
        assertThat(columnExists("sub_issue", "score_breakdown")).isTrue();
        assertThat(columnExists("sub_issue", "cvss")).isTrue();
        assertThat(columnExists("sub_issue", "severity_label")).isTrue();
        assertThat(columnExists("sub_issue", "cwe")).isTrue();
        assertThat(columnExists("sub_issue", "epss")).isTrue();
        assertThat(columnExists("sub_issue", "in_kev")).isTrue();
        assertThat(columnExists("sub_issue", "cve_aliases")).isTrue();
    }

    @Test
    void v12AddsOsvEnrichmentColumnsToScanFinding() {
        assertThat(columnExists("scan_finding", "cvss")).isTrue();
        assertThat(columnExists("scan_finding", "severity_label")).isTrue();
        assertThat(columnExists("scan_finding", "cwe")).isTrue();
        assertThat(columnExists("scan_finding", "cve_aliases")).isTrue();
    }

    @Test
    void v12CreatesGlobalIntelAndConfigTables() {
        assertThat(tableExists("kev_entry")).isTrue();
        assertThat(tableExists("epss_score")).isTrue();
        assertThat(tableExists("fix_time_config")).isTrue();
        // D-050: these global caches carry NO workspace_id (the justified exception to domain rule 1).
        assertThat(columnExists("kev_entry", "workspace_id")).isFalse();
        assertThat(columnExists("epss_score", "workspace_id")).isFalse();
        assertThat(columnExists("fix_time_config", "workspace_id")).isFalse();
    }

    @Test
    void v15AddsTheSubIssueWorkspaceStatusIndex() {
        // SF5: additive composite index for the workspace+status lookups (re-score load, rollup re-read, the
        // column-scoped score UPDATE). Applies on an empty DB like every other migration.
        assertThat(indexExists("sub_issue", "idx_sub_issue_workspace_status")).isTrue();
    }

    @Test
    void v13SeedsFixTimeConfig() {
        assertThat(minutesFor("SCA_PATCH_MINOR")).isEqualTo(15);
        assertThat(minutesFor("SCA_MAJOR")).isEqualTo(60);
        assertThat(minutesFor("SECRET_ROTATION")).isEqualTo(10);
        assertThat(minutesFor("DEFAULT")).isEqualTo(20);
    }

    private int minutesFor(String category) {
        return jdbc.queryForObject("select minutes from fix_time_config where category = ?", Integer.class,
                category);
    }

    private boolean indexExists(String table, String index) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from pg_indexes "
                        + "where schemaname = 'public' and tablename = ? and indexname = ?)",
                Boolean.class, table, index);
        return Boolean.TRUE.equals(exists);
    }

    private boolean tableExists(String name) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)",
                Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }

    private boolean columnExists(String table, String column) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?)",
                Boolean.class, table, column);
        return Boolean.TRUE.equals(exists);
    }
}
