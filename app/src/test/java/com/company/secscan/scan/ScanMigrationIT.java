package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.workspace.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves the M2 scan-engine migration (V6) applies to an empty database, enforces at most one in-flight scan
 * per repository, and that the {@code FOR UPDATE SKIP LOCKED} claim query returns runnable jobs oldest-first.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class ScanMigrationIT {

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
    ScanRepository scans;
    @Autowired
    ScanJobRepository jobs;

    @Test
    void migrationCreatesScanEngineTables() {
        assertThat(tableExists("scan")).isTrue();
        assertThat(tableExists("scan_task")).isTrue();
        assertThat(tableExists("scan_job")).isTrue();
        assertThat(tableExists("scan_finding")).isTrue();
    }

    @Test
    void atMostOneInFlightScanPerRepository() {
        UUID repoId = seedRepository(201L, "svc/one-active");
        scans.saveAndFlush(Scan.manual(Workspace.DEFAULT_ID, repoId));

        Scan second = Scan.manual(Workspace.DEFAULT_ID, repoId);
        second.markRunning();
        assertThatThrownBy(() -> scans.saveAndFlush(second))
                .hasMessageContaining("uq_scan_active_per_repository");
    }

    @Test
    void finishedScansDoNotBlockANewOne() {
        UUID repoId = seedRepository(202L, "svc/finished");
        Scan first = Scan.manual(Workspace.DEFAULT_ID, repoId);
        first.finish(Scan.STATUS_COMPLETED);
        scans.saveAndFlush(first);

        // A completed scan is outside the partial unique index, so a new scan for the same repo is allowed.
        scans.saveAndFlush(Scan.manual(Workspace.DEFAULT_ID, repoId));
        assertThat(scans.findByWorkspaceIdAndRepositoryIdAndStatusIn(
                Workspace.DEFAULT_ID, repoId, List.of(Scan.STATUS_QUEUED))).hasSize(1);
    }

    @Test
    @Transactional
    void claimReturnsRunnableJobsOldestFirstAndSkipsFutureOnes() {
        UUID repoA = seedRepository(203L, "svc/a");
        UUID repoB = seedRepository(204L, "svc/b");
        UUID repoC = seedRepository(205L, "svc/c");

        UUID older = enqueue(repoA, Instant.now().minusSeconds(60));
        UUID newer = enqueue(repoB, Instant.now().minusSeconds(10));
        enqueue(repoC, Instant.now().plusSeconds(600)); // run_after in the future → not yet runnable

        List<ScanJob> claimed = jobs.claimable(10);

        assertThat(claimed).extracting(ScanJob::getId).containsExactly(older, newer);
    }

    private UUID enqueue(UUID repoId, Instant runAfter) {
        Scan scan = scans.saveAndFlush(Scan.manual(Workspace.DEFAULT_ID, repoId));
        ScanJob job = new ScanJob(Workspace.DEFAULT_ID, scan.getId());
        job.requeue(runAfter);
        return jobs.saveAndFlush(job).getId();
    }

    private UUID seedRepository(long projectId, String path) {
        UUID connId = UUID.randomUUID();
        // A distinct group id/path per call so the per-group unique index (V5) is not tripped by the seed.
        jdbc.update("insert into gitlab_connection "
                        + "(id, workspace_id, base_url, target_type, group_full_path, gitlab_group_id, "
                        + "token_ciphertext) values (?, ?, 'http://localhost', 'group', ?, ?, 'ct')",
                connId, Workspace.DEFAULT_ID, "svc-" + projectId, projectId);
        UUID repoId = UUID.randomUUID();
        jdbc.update("insert into repository "
                        + "(id, workspace_id, connection_id, gitlab_project_id, path_with_namespace) "
                        + "values (?, ?, ?, ?, ?)",
                repoId, Workspace.DEFAULT_ID, connId, projectId, path);
        return repoId;
    }

    private boolean tableExists(String name) {
        Boolean exists = jdbc.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)",
                Boolean.class, name);
        return Boolean.TRUE.equals(exists);
    }
}
