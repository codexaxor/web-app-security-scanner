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
 * Proves V17 (the manual-ignore model, M7, D-058) applies on top of V16 and is additive: it creates the
 * workspace-scoped {@code ignore_rule} table and adds two NULLABLE columns to {@code sub_issue}
 * ({@code ignored_by_rule_id}, {@code ignored_at}) with no backfill, so every existing row is untouched. The
 * {@code ignored_by_rule_id} FK is {@code ON DELETE SET NULL} — deleting a rule must never delete the finding.
 * V1-V16 are untouched (confirmed by V16's {@code eol_cycle} still being present beneath V17).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class V17MigrationIT {

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
    void v17CreatesIgnoreRuleAsWorkspaceScopedBusinessData() {
        assertThat(tableExists("ignore_rule")).isTrue();
        // Business data (not a D-050 global cache) — it MUST carry workspace_id (domain rule 1).
        assertThat(columnExists("ignore_rule", "workspace_id")).isTrue();
        for (String col : new String[] {"id", "workspace_id", "scope", "repository_id", "match_value", "reason",
                "created_by", "created_at"}) {
            assertThat(columnExists("ignore_rule", col)).as("ignore_rule.%s", col).isTrue();
        }
        for (String scope : new String[] {"subissue", "group", "path", "cve", "rule"}) {
            assertThat(checkAllows("ignore_rule_scope_check", scope)).as("scope %s allowed", scope).isTrue();
        }
    }

    @Test
    void v17AddsTwoNullableSubIssueColumnsWithoutBackfill() {
        assertThat(columnExists("sub_issue", "ignored_by_rule_id")).isTrue();
        assertThat(columnExists("sub_issue", "ignored_at")).isTrue();
        // Additive: nullable, no NOT NULL / default backfill, so existing rows are untouched.
        assertThat(isNullable("sub_issue", "ignored_by_rule_id")).isTrue();
        assertThat(isNullable("sub_issue", "ignored_at")).isTrue();
    }

    @Test
    void ignoredByRuleIdFkIsOnDeleteSetNull() {
        // confdeltype 'n' = SET NULL. Deleting a rule clears the pointer; the finding and its audit trail survive.
        String deleteType = jdbc.queryForObject(
                "select confdeltype from pg_constraint c "
                        + "where c.contype = 'f' and c.conrelid = 'sub_issue'::regclass "
                        + "and c.confrelid = 'ignore_rule'::regclass",
                String.class);
        assertThat(deleteType).isEqualTo("n");
    }

    @Test
    void v1ToV16AreUntouchedBeneathV17() {
        // V16's global cache is still present → the whole V1-V16 chain applied before V17 (no edit, additive).
        assertThat(tableExists("eol_cycle")).isTrue();
        assertThat(tableExists("sub_issue")).isTrue();
        assertThat(tableExists("triage_event")).isTrue();
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

    private boolean isNullable(String table, String column) {
        String nullable = jdbc.queryForObject(
                "select is_nullable from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ? and column_name = ?",
                String.class, table, column);
        return "YES".equals(nullable);
    }
}
