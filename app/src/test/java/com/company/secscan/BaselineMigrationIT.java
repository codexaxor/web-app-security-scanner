package com.company.secscan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
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
 * Proves two of the three M0 "Done when" criteria: the Flyway migrations apply to an empty database, and a
 * Testcontainers-backed Spring context boots against real PostgreSQL 16.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class BaselineMigrationIT {

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

    @Autowired
    WorkspaceRepository workspaces;

    @Test
    void migrationsCreateBaselineTables() {
        assertThat(tableExists("workspace")).isTrue();
        assertThat(tableExists("app_user")).isTrue();
    }

    @Test
    void defaultWorkspaceIsSeeded() {
        Workspace defaultWorkspace = workspaces.findById(Workspace.DEFAULT_ID).orElseThrow();
        assertThat(defaultWorkspace.getName()).isEqualTo("Default");
    }

    @Test
    void workspaceSupportsManyConnectionsOfDifferentTargets() {
        // V5 dropped the one-connection-per-workspace constraint: a group and a project may coexist.
        insertGroupConnection(42L, "services");
        insertProjectConnection(102L, "services/ib-keycloak");

        Integer count = jdbc.queryForObject(
                "select count(*) from gitlab_connection where workspace_id = ?", Integer.class,
                Workspace.DEFAULT_ID);
        assertThat(count).isEqualTo(2);
    }

    @Test
    void checkConstraintRejectsAGroupRowWithoutGroupColumns() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "insert into gitlab_connection (id, workspace_id, base_url, target_type, token_ciphertext) "
                        + "values (?, ?, 'http://localhost', 'group', 'ct')",
                java.util.UUID.randomUUID(), Workspace.DEFAULT_ID))
                .hasMessageContaining("ck_gitlab_connection_target");
    }

    private void insertGroupConnection(long groupId, String fullPath) {
        jdbc.update("insert into gitlab_connection "
                        + "(id, workspace_id, base_url, target_type, group_full_path, gitlab_group_id, "
                        + "token_ciphertext) values (?, ?, 'http://localhost', 'group', ?, ?, 'ct')",
                java.util.UUID.randomUUID(), Workspace.DEFAULT_ID, fullPath, groupId);
    }

    private void insertProjectConnection(long projectId, String fullPath) {
        jdbc.update("insert into gitlab_connection "
                        + "(id, workspace_id, base_url, target_type, gitlab_project_id, project_full_path, "
                        + "token_ciphertext) values (?, ?, 'http://localhost', 'project', ?, ?, 'ct')",
                java.util.UUID.randomUUID(), Workspace.DEFAULT_ID, projectId, fullPath);
    }

    @Test
    void workspaceRoundTrips() {
        Workspace saved = workspaces.saveAndFlush(new Workspace("Acme"));
        assertThat(saved.getId()).isNotNull();

        Workspace reloaded = workspaces.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("Acme");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    private boolean tableExists(String name) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)",
                Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }
}
