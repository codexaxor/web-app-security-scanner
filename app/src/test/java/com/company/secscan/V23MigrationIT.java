package com.company.secscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V23 (M8.10 multi-tenant sign-up, D-093/D-094/D-097/D-098). Proves the migration applies on an empty DB and on a
 * seeded pre-V23 DB: every existing user becomes active; exactly the earliest real admin of Default becomes the
 * operator (never a demo user, never a second one); {@code app_user.workspace_id} stays NOT NULL; the
 * {@code signup_request} lifecycle constraints and the pending-email uniqueness hold; the fair-use partial index
 * exists. Drives Flyway directly (like {@link MigrationV22IT}) so it can stop at V22, seed, then apply V23.
 */
@Testcontainers
class V23MigrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final UUID DEFAULT_WS = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource d = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        d.setDriverClassName("org.postgresql.Driver");
        this.ds = d;
        this.jdbc = new JdbcTemplate(d);
        flyway(null).clean();
    }

    @AfterEach
    void tearDown() {
        flyway(null).clean();
    }

    private Flyway flyway(String target) {
        var config = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) {
            config.target(MigrationVersion.fromVersion(target));
        }
        return config.load();
    }

    // ------------------------------------------------------------------------------------------------ empty DB

    @Test
    void appliesOnAnEmptyDatabase() {
        flyway(null).migrate();

        assertThat(columnExists("app_user", "status")).isTrue();
        assertThat(columnExists("app_user", "is_operator")).isTrue();
        assertThat(columnExists("app_user", "status_changed_at")).isTrue();
        assertThat(columnExists("app_user", "status_changed_by")).isTrue();
        assertThat(isNullable("app_user", "workspace_id")).as("app_user.workspace_id stays NOT NULL").isFalse();
        assertThat(tableExists("signup_request")).isTrue();
        assertThat(indexExists("uq_app_user_single_operator")).isTrue();
        assertThat(indexExists("uq_signup_request_pending_email")).isTrue();
        assertThat(indexExists("idx_scan_job_workspace_running")).isTrue();
        // review N-1: the new foreign keys are indexed
        assertThat(indexExists("idx_signup_request_decided_by")).isTrue();
        assertThat(indexExists("idx_signup_request_workspace")).isTrue();
        assertThat(indexExists("idx_app_user_status_changed_by")).isTrue();
        assertThat(count("select count(*) from app_user where is_operator")).isZero();
    }

    // ------------------------------------------------------------------------------------------------ backfill

    @Test
    void backfillMakesEveryUserActiveAndOnlyTheEarliestRealAdminOfDefaultTheOperator() {
        flyway("22").migrate();
        UUID other = UUID.randomUUID();
        jdbc.update("insert into workspace (id, name) values (?, 'Other')", other);
        UUID demo = seedUser(DEFAULT_WS, "admin", "admin", "2026-01-01T00:00:00Z");          // demo: no '@'
        UUID first = seedUser(DEFAULT_WS, "first@example.com", "admin", "2026-01-02T00:00:00Z");
        UUID second = seedUser(DEFAULT_WS, "second@example.com", "admin", "2026-01-03T00:00:00Z");
        UUID member = seedUser(DEFAULT_WS, "member@example.com", "member", "2026-01-01T12:00:00Z");
        UUID elsewhere = seedUser(other, "early@example.com", "admin", "2025-01-01T00:00:00Z"); // not Default

        flyway(null).migrate();

        assertThat(count("select count(*) from app_user where status <> 'active'")).isZero();
        assertThat(isOperator(first)).isTrue();
        assertThat(isOperator(second)).isFalse();
        assertThat(isOperator(demo)).as("a demo (non-email) user is never the operator").isFalse();
        assertThat(isOperator(member)).isFalse();
        assertThat(isOperator(elsewhere)).isFalse();
        assertThat(count("select count(*) from app_user where is_operator")).isEqualTo(1);
    }

    @Test
    void backfillPromotesNobodyWhenNoRealAdminExists() {
        flyway("22").migrate();
        seedUser(DEFAULT_WS, "admin", "admin", "2026-01-01T00:00:00Z");                // demo only
        seedUser(DEFAULT_WS, "viewer@example.com", "viewer", "2026-01-02T00:00:00Z");

        flyway(null).migrate();

        assertThat(count("select count(*) from app_user where is_operator")).isZero();
    }

    // ------------------------------------------------------------------------------------------------ app_user

    @Test
    void aSecondOperatorIsRejectedByTheDatabase() {
        flyway(null).migrate();
        UUID a = seedUser(DEFAULT_WS, "a@example.com", "admin", "2026-01-01T00:00:00Z");
        UUID b = seedUser(DEFAULT_WS, "b@example.com", "admin", "2026-01-02T00:00:00Z");
        jdbc.update("update app_user set is_operator = true where id = ?", a);

        assertThatThrownBy(() -> jdbc.update("update app_user set is_operator = true where id = ?", b))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theOperatorCannotBeDisabledAndStatusIsConstrained() {
        flyway(null).migrate();
        UUID a = seedUser(DEFAULT_WS, "a@example.com", "admin", "2026-01-01T00:00:00Z");
        jdbc.update("update app_user set is_operator = true where id = ?", a);

        assertThatThrownBy(() -> jdbc.update("update app_user set status = 'disabled' where id = ?", a))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update app_user set status = 'pending' where id = ?", a))
                .as("pending is a signup_request state, not an app_user state")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into app_user (id, email, password_hash, role) "
                + "values (gen_random_uuid(), 'x@example.com', 'h', 'member')"))
                .as("workspace_id stays NOT NULL")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------------------------------------ signup_request

    @Test
    void onePendingRequestPerEmailCaseInsensitivelyAndARejectedEmailMaySignUpAgain() {
        flyway(null).migrate();
        UUID op = seedUser(DEFAULT_WS, "op@example.com", "admin", "2026-01-01T00:00:00Z");
        UUID first = pending("new@example.com");

        assertThatThrownBy(() -> pending("new@example.com"))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("update signup_request set status = 'rejected', password_hash = null, decided_at = now(), "
                + "decided_by = ? where id = ?", op, first);
        UUID again = pending("new@example.com");
        assertThat(again).isNotEqualTo(first);
    }

    @Test
    void decisionShapeIsEnforced() {
        flyway(null).migrate();
        UUID op = seedUser(DEFAULT_WS, "op@example.com", "admin", "2026-01-01T00:00:00Z");
        UUID req = pending("p@example.com");

        // Approved needs a workspace and a cleared hash.
        assertThatThrownBy(() -> jdbc.update("update signup_request set status = 'approved', decided_at = now(), "
                + "decided_by = ?, password_hash = null where id = ?", op, req))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("update signup_request set status = 'approved', decided_at = now(), "
                + "decided_by = ?, workspace_id = ? where id = ?", op, DEFAULT_WS, req))
                .as("the hash must be cleared once decided")
                .isInstanceOf(DataIntegrityViolationException.class);
        // Pending needs a hash.
        assertThatThrownBy(() -> jdbc.update("update signup_request set password_hash = null where id = ?", req))
                .isInstanceOf(DataIntegrityViolationException.class);
        // Email lowercase, workspace name 1–80.
        assertThatThrownBy(() -> jdbc.update("insert into signup_request (id, email, password_hash, "
                + "requested_workspace_name) values (gen_random_uuid(), 'Up@Example.com', 'h', 'ws')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("insert into signup_request (id, email, password_hash, "
                + "requested_workspace_name) values (gen_random_uuid(), 'x@example.com', 'h', '   ')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // The valid approved shape is accepted.
        jdbc.update("update signup_request set status = 'approved', decided_at = now(), decided_by = ?, "
                + "workspace_id = ?, password_hash = null where id = ?", op, DEFAULT_WS, req);
        assertThat(jdbc.queryForObject("select status from signup_request where id = ?", String.class, req))
                .isEqualTo("approved");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private UUID seedUser(UUID ws, String email, String role, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into app_user (id, workspace_id, email, password_hash, role, created_at) "
                + "values (?, ?, ?, 'hash', ?, ?::timestamptz)", id, ws, email, role, createdAt);
        return id;
    }

    private UUID pending(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into signup_request (id, email, password_hash, requested_workspace_name) "
                + "values (?, ?, 'hash', 'Acme')", id, email);
        return id;
    }

    private boolean isOperator(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select is_operator from app_user where id = ?",
                Boolean.class, id));
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private boolean tableExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from information_schema.tables "
                + "where table_schema = 'public' and table_name = ?)", Boolean.class, name));
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from information_schema.columns "
                + "where table_schema = 'public' and table_name = ? and column_name = ?)", Boolean.class,
                table, column));
    }

    private boolean isNullable(String table, String column) {
        return "YES".equals(jdbc.queryForObject("select is_nullable from information_schema.columns "
                + "where table_schema = 'public' and table_name = ? and column_name = ?", String.class,
                table, column));
    }

    private boolean indexExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pg_indexes where schemaname = 'public' and indexname = ?)",
                Boolean.class, name));
    }
}
