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
 * Proves V16 (the eol_cycle intel table, M6) applies to an empty database and is a global cache with no
 * workspace_id (D-050), with the v1-derived columns. Additive only; V1-V15 untouched. The sub_issue/issue_group/
 * scan_finding type CHECK constraints already allow 'eol' (V6/V8), so no business-table change is needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class EolMigrationIT {

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
    void v16CreatesEolCycleAsAGlobalCacheWithTheV1Columns() {
        assertThat(tableExists("eol_cycle")).isTrue();
        // D-050: a global reference cache — NO workspace_id.
        assertThat(columnExists("eol_cycle", "workspace_id")).isFalse();
        for (String col : new String[] {"product", "cycle", "is_eol", "eol_from", "latest", "latest_date",
                "release_date", "is_lts", "is_maintained", "synced_at"}) {
            assertThat(columnExists("eol_cycle", col)).as("eol_cycle.%s", col).isTrue();
        }
    }

    @Test
    void eolTypeIsAllowedByTheIssueModelCheckConstraints() {
        // The new-type findings are persisted against these constraints, so confirm 'eol' is accepted
        // (added back in V6/V8) — no V16 change was needed for it.
        assertThat(checkAllows("sub_issue_type_check", "eol")).isTrue();
        assertThat(checkAllows("issue_group_type_check", "eol")).isTrue();
        assertThat(checkAllows("scan_finding_type_check", "eol")).isTrue();
    }

    private boolean checkAllows(String constraint, String value) {
        String def = jdbc.queryForObject(
                "select pg_get_constraintdef(oid) from pg_constraint where conname = ?",
                String.class, constraint);
        return def != null && def.contains("'" + value + "'");
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
}
