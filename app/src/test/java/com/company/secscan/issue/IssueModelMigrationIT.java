package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.workspace.Workspace;
import java.util.UUID;
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
 * Proves the M3 issue-model migration (V8) applies to an empty database, adds {@code scan.reconciled_at}, and
 * that the sub-issue fingerprint and group-key uniqueness constraints hold per repository.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class IssueModelMigrationIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

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
    void migrationCreatesIssueModelTablesAndReconciledAtColumn() {
        assertThat(tableExists("issue_group")).isTrue();
        assertThat(tableExists("sub_issue")).isTrue();
        assertThat(tableExists("triage_event")).isTrue();
        assertThat(tableExists("repository_event")).isTrue();
        assertThat(columnExists("scan", "reconciled_at")).isTrue();
    }

    @Test
    void subIssueFingerprintIsUniquePerRepository() {
        UUID repo = seedRepository(301L, "svc/fp");
        UUID group = insertGroup(repo, "secret|aws-access-token");
        insertSubIssue(group, repo, "fp-dup");

        assertThatThrownBy(() -> insertSubIssue(group, repo, "fp-dup"))
                .hasMessageContaining("uq_sub_issue_fingerprint");
    }

    @Test
    void groupKeyIsUniquePerRepository() {
        UUID repo = seedRepository(302L, "svc/gk");
        insertGroup(repo, "secret|aws-access-token");

        assertThatThrownBy(() -> insertGroup(repo, "secret|aws-access-token"))
                .hasMessageContaining("uq_issue_group_key");
    }

    private UUID insertGroup(UUID repoId, String groupKey) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into issue_group (id, workspace_id, repository_id, type, group_key, title) "
                        + "values (?, ?, ?, 'secret', ?, 'Exposed AWS key')",
                id, WORKSPACE, repoId, groupKey);
        return id;
    }

    private void insertSubIssue(UUID groupId, UUID repoId, String fingerprint) {
        jdbc.update("insert into sub_issue (id, workspace_id, group_id, repository_id, type, scanner, "
                        + "fingerprint) values (?, ?, ?, ?, 'secret', 'secret', ?)",
                UUID.randomUUID(), WORKSPACE, groupId, repoId, fingerprint);
    }

    private UUID seedRepository(long projectId, String path) {
        UUID connId = UUID.randomUUID();
        jdbc.update("insert into gitlab_connection "
                        + "(id, workspace_id, base_url, target_type, group_full_path, gitlab_group_id, "
                        + "token_ciphertext) values (?, ?, 'http://localhost', 'group', ?, ?, 'ct')",
                connId, WORKSPACE, "svc-" + projectId, projectId);
        UUID repoId = UUID.randomUUID();
        jdbc.update("insert into repository "
                        + "(id, workspace_id, connection_id, gitlab_project_id, path_with_namespace) "
                        + "values (?, ?, ?, ?, ?)",
                repoId, WORKSPACE, connId, projectId, path);
        return repoId;
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
