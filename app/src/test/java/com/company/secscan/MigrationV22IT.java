package com.company.secscan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V22 (M8.7 provider-neutral source connection + repository identity). Proves the migration applies on an empty
 * DB and backfills a seeded pre-V22 DB without changing any id or finding (D-080/D-085; approval items 11-15).
 *
 * <p>Drives Flyway directly (not via Spring) so it can migrate to V21, seed realistic pre-V22 rows, then apply
 * V22 and compare. The four named checks from approval item 14 are individual tests.
 */
@Testcontainers
class MigrationV22IT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    private static final String CELLO = "https://gitlab.celloscope.net";
    private static final String CELLO_HOST = "gitlab.celloscope.net";

    private DataSource ds;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource d = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        d.setDriverClassName("org.postgresql.Driver");
        this.ds = d;
        this.jdbc = new JdbcTemplate(d);
        // Each test starts from a clean schema (Flyway clean, then migrate to the version it needs).
        flyway().clean();
    }

    @AfterEach
    void tearDown() {
        flyway().clean();
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .cleanDisabled(false)
                .load();
    }

    private void migrateTo(String version) {
        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion(version))
                .cleanDisabled(false)
                .load()
                .migrate();
    }

    private void migrateToLatest() {
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load().migrate();
    }

    // ---------------------------------------------------------------------------------------------------------

    @Test
    void appliesOnAnEmptyDatabase() {
        migrateToLatest();

        assertThat(columnExists("gitlab_connection", "provider")).isTrue();
        assertThat(columnExists("gitlab_connection", "host")).isTrue();
        assertThat(columnExists("gitlab_connection", "credential_kind")).isTrue();
        assertThat(columnExists("gitlab_connection", "provider_repo_id")).isTrue();
        assertThat(columnExists("repository", "provider")).isTrue();
        assertThat(columnExists("repository", "host")).isTrue();
        assertThat(columnExists("repository", "provider_repo_id")).isTrue();
        assertThat(indexExists("uq_repository_identity")).isTrue();
        assertThat(indexExists("uq_gitlab_connection_owner")).isTrue();
        assertThat(indexExists("uq_gitlab_connection_repo")).isTrue();
        // gitlab_project_id is now nullable on repository.
        assertThat(isNullable("repository", "gitlab_project_id")).isTrue();
    }

    @Test
    void idsAndFindingsUnchangedAfterBackfill() {
        migrateTo("21");
        UUID ws = seedWorkspace("backfill-ws");
        UUID conn = seedGitlabProjectConnection(ws, CELLO, 42L, "acme/app");
        UUID repo = seedRepository(ws, conn, 42L, "acme/app");
        UUID group = seedIssueGroup(ws, repo, "sca", "sca|cve-1", "commons");
        UUID sub = seedSubIssue(ws, group, repo, "sca", "fp-1");
        seedTriageEvent(ws, sub, group);
        seedIgnoreRule(ws, "cve", "CVE-2022-1");

        long reposBefore = count("repository");
        long subsBefore = count("sub_issue");
        long groupsBefore = count("issue_group");
        long triageBefore = count("triage_event");
        long rulesBefore = count("ignore_rule");

        migrateToLatest();

        // Every id survives unchanged.
        assertThat(count("repository")).isEqualTo(reposBefore);
        assertThat(count("sub_issue")).isEqualTo(subsBefore);
        assertThat(count("issue_group")).isEqualTo(groupsBefore);
        assertThat(count("triage_event")).isEqualTo(triageBefore);
        assertThat(count("ignore_rule")).isEqualTo(rulesBefore);
        assertThat(jdbc.queryForObject("select count(*) from repository where id = ?", Long.class, repo)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from sub_issue where id = ?", Long.class, sub)).isEqualTo(1L);

        // The repository is backfilled as GitLab on its connection's host, identity = the project id as text.
        Map<String, Object> r = jdbc.queryForMap("select provider, host, provider_repo_id, gitlab_project_id "
                + "from repository where id = ?", repo);
        assertThat(r.get("provider")).isEqualTo("gitlab");
        assertThat(r.get("host")).isEqualTo(CELLO_HOST);
        assertThat(r.get("provider_repo_id")).isEqualTo("42");
        assertThat(((Number) r.get("gitlab_project_id")).longValue()).isEqualTo(42L);

        // The connection host is generated from base_url.
        assertThat(jdbc.queryForObject("select host from gitlab_connection where id = ?", String.class, conn))
                .isEqualTo(CELLO_HOST);
        assertThat(jdbc.queryForObject("select provider from gitlab_connection where id = ?", String.class, conn))
                .isEqualTo("gitlab");
        assertThat(jdbc.queryForObject("select credential_kind from gitlab_connection where id = ?", String.class,
                conn)).isEqualTo("token");
    }

    @Test
    void detachedRepoWithNoConnections() {
        migrateTo("21");
        // Workspace A has the only connection (supplies the single global host). Workspace B has a detached
        // repository (connection_id null) and NO connection at all.
        UUID wsA = seedWorkspace("has-conn");
        seedGitlabProjectConnection(wsA, CELLO, 7L, "acme/other");
        UUID wsB = seedWorkspace("detached-only");
        UUID detached = seedRepository(wsB, null, 99L, "orphan/repo");

        migrateToLatest();

        // The detached repo in a connection-less workspace falls back to the single global host.
        assertThat(jdbc.queryForObject("select host from repository where id = ?", String.class, detached))
                .isEqualTo(CELLO_HOST);
        assertThat(jdbc.queryForObject("select provider_repo_id from repository where id = ?", String.class,
                detached)).isEqualTo("99");
    }

    @Test
    void gitlabGroupIdCollisionAcrossHosts() {
        migrateToLatest();
        UUID ws = seedWorkspace("collision-ws");
        // Same numeric group id on two different hosts -> two distinct connections (uniqueness includes host).
        UUID c1 = insertGroupConnectionV22(ws, "https://gitlab.celloscope.net", 500L, "grp/a");
        UUID c2 = insertGroupConnectionV22(ws, "https://gitlab.com", 500L, "grp/a");
        assertThat(c1).isNotEqualTo(c2);
        assertThat(count("gitlab_connection")).isEqualTo(2L);

        // And the same numeric repo id on two hosts -> two distinct repositories (identity includes host).
        UUID r1 = insertRepositoryV22(ws, c1, "gitlab", "gitlab.celloscope.net", "500", 500L, "grp/a-repo");
        UUID r2 = insertRepositoryV22(ws, c2, "gitlab", "gitlab.com", "500", 500L, "grp/a-repo");
        assertThat(r1).isNotEqualTo(r2);
        assertThat(count("repository")).isEqualTo(2L);
    }

    @Test
    void githubConnectionRowAccepted() {
        migrateToLatest();
        UUID ws = seedWorkspace("github-ws");
        // A GitHub single-repo connection (token) + its repository (gitlab_project_id null, identity by github id).
        UUID conn = jdbc.queryForObject(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, provider, credential_kind, "
                        + "token_ciphertext, provider_repo_id, repo_full_name) "
                        + "values (gen_random_uuid(), ?, 'https://github.com', 'repo', 'github', 'token', "
                        + "'ciphertext', '12345', 'octo/app') returning id",
                UUID.class, ws);
        assertThat(jdbc.queryForObject("select host from gitlab_connection where id = ?", String.class, conn))
                .isEqualTo("github.com");

        UUID repo = insertRepositoryV22(ws, conn, "github", "github.com", "12345", null, "octo/app");
        Map<String, Object> r = jdbc.queryForMap(
                "select provider, host, provider_repo_id, gitlab_project_id from repository where id = ?", repo);
        assertThat(r.get("provider")).isEqualTo("github");
        assertThat(r.get("host")).isEqualTo("github.com");
        assertThat(r.get("provider_repo_id")).isEqualTo("12345");
        assertThat(r.get("gitlab_project_id")).isNull();

        // A public repo row carries credential_kind 'none' and no token.
        UUID pub = jdbc.queryForObject(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, provider, credential_kind, "
                        + "provider_repo_id, repo_full_name) "
                        + "values (gen_random_uuid(), ?, 'https://github.com', 'repo', 'github', 'none', "
                        + "'67890', 'public/repo') returning id",
                UUID.class, ws);
        assertThat(jdbc.queryForObject("select token_ciphertext from gitlab_connection where id = ?", String.class,
                pub)).isNull();
    }

    @Test
    void hostBackfillKeepsAnExplicitNonDefaultPort() {
        migrateTo("21");
        UUID ws = seedWorkspace("port-ws");
        UUID conn = seedGitlabProjectConnection(ws, "https://gitlab.example.com:8443", 11L, "p/q");
        UUID repo = seedRepository(ws, conn, 11L, "p/q");
        migrateToLatest();
        assertThat(jdbc.queryForObject("select host from gitlab_connection where id = ?", String.class, conn))
                .isEqualTo("gitlab.example.com:8443");
        assertThat(jdbc.queryForObject("select host from repository where id = ?", String.class, repo))
                .isEqualTo("gitlab.example.com:8443");
    }

    @Test
    void publicConnectionRejectsAToken() {
        migrateToLatest();
        UUID ws = seedWorkspace("bad-public");
        assertThatThrownBy(() -> jdbc.update(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, provider, credential_kind, "
                        + "token_ciphertext, provider_repo_id, repo_full_name) "
                        + "values (gen_random_uuid(), ?, 'https://github.com', 'repo', 'github', 'none', "
                        + "'should-not-be-here', '1', 'o/r')",
                ws))
                .hasMessageContaining("ck_gitlab_connection_token");
    }

    // ---------------------------------------------------------------- seed helpers (pre-V22 schema) ----------

    private UUID seedWorkspace(String name) {
        return jdbc.queryForObject("insert into workspace (id, name) values (gen_random_uuid(), ?) returning id",
                UUID.class, name);
    }

    /** A pre-V22 GitLab single-project connection (no provider/host/credential_kind columns yet). */
    private UUID seedGitlabProjectConnection(UUID ws, String baseUrl, long projectId, String path) {
        return jdbc.queryForObject(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, gitlab_project_id, "
                        + "project_full_path, token_ciphertext) "
                        + "values (gen_random_uuid(), ?, ?, 'project', ?, ?, 'ciphertext') returning id",
                UUID.class, ws, baseUrl, projectId, path);
    }

    private UUID seedRepository(UUID ws, UUID connectionId, long projectId, String path) {
        return jdbc.queryForObject(
                "insert into repository (id, workspace_id, connection_id, gitlab_project_id, path_with_namespace) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?) returning id",
                UUID.class, ws, connectionId, projectId, path);
    }

    private UUID seedIssueGroup(UUID ws, UUID repo, String type, String key, String title) {
        return jdbc.queryForObject(
                "insert into issue_group (id, workspace_id, repository_id, type, group_key, title) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?, ?) returning id",
                UUID.class, ws, repo, type, key, title);
    }

    private UUID seedSubIssue(UUID ws, UUID group, UUID repo, String type, String fp) {
        return jdbc.queryForObject(
                "insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, fingerprint) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?, ?, ?) returning id",
                UUID.class, ws, group, repo, type, type, fp);
    }

    private void seedTriageEvent(UUID ws, UUID sub, UUID group) {
        jdbc.update("insert into triage_event (id, workspace_id, sub_issue_id, group_id, to_status, actor) "
                + "values (gen_random_uuid(), ?, ?, ?, 'open', 'system')", ws, sub, group);
    }

    private void seedIgnoreRule(UUID ws, String scope, String match) {
        jdbc.update("insert into ignore_rule (id, workspace_id, scope, match_value, reason, created_by) "
                + "values (gen_random_uuid(), ?, ?, ?, 'because', 'user')", ws, scope, match);
    }

    // ---------------------------------------------------------------- post-V22 insert helpers ----------------

    private UUID insertGroupConnectionV22(UUID ws, String baseUrl, long groupId, String path) {
        return jdbc.queryForObject(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, provider, credential_kind, "
                        + "token_ciphertext, gitlab_group_id, group_full_path) "
                        + "values (gen_random_uuid(), ?, ?, 'group', 'gitlab', 'token', 'ciphertext', ?, ?) "
                        + "returning id",
                UUID.class, ws, baseUrl, groupId, path);
    }

    private UUID insertRepositoryV22(UUID ws, UUID connectionId, String provider, String host,
                                     String providerRepoId, Long gitlabProjectId, String path) {
        return jdbc.queryForObject(
                "insert into repository (id, workspace_id, connection_id, provider, host, provider_repo_id, "
                        + "gitlab_project_id, path_with_namespace) "
                        + "values (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?) returning id",
                UUID.class, ws, connectionId, provider, host, providerRepoId, gitlabProjectId, path);
    }

    // ---------------------------------------------------------------- schema introspection -------------------

    private long count(String table) {
        Long n = jdbc.queryForObject("select count(*) from " + table, Long.class);
        return n == null ? 0 : n;
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from information_schema.columns where table_schema = 'public' "
                        + "and table_name = ? and column_name = ?)", Boolean.class, table, column));
    }

    private boolean isNullable(String table, String column) {
        return "YES".equals(jdbc.queryForObject(
                "select is_nullable from information_schema.columns where table_schema = 'public' "
                        + "and table_name = ? and column_name = ?", String.class, table, column));
    }

    private boolean indexExists(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from pg_indexes where schemaname = 'public' and indexname = ?)",
                Boolean.class, name));
    }
}
