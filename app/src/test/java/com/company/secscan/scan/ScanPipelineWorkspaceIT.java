package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScannerLaunchSpec;
import com.company.secscan.scan.worker.ScannerLauncher;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Approval item 6 (M8.10): a scan of a workspace-B repository, through the real worker pipeline (claim → process
 * → parse → reconcile; only the container launch is stubbed), writes its {@code scan_finding}, {@code sub_issue},
 * {@code issue_group}, {@code triage_event} and {@code scan_warning} rows ONLY with B's {@code workspace_id}, and
 * leaves the Default workspace untouched.
 */
class ScanPipelineWorkspaceIT extends AbstractScanWorkerIT {

    private static final List<String> TABLES = List.of("scan", "scan_task", "scan_finding", "sub_issue",
            "issue_group", "triage_event", "scan_warning");

    @MockBean
    ScannerLauncher launcher;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void stubAScanWithASecretAndAWarning() {
        Mockito.when(launcher.run(any())).thenAnswer(inv -> {
            ScannerLaunchSpec spec = inv.getArgument(0);
            try {
                Files.writeString(spec.outDir().resolve("secrets.json"), "[{"
                        + "\"ruleId\":\"aws-access-token\",\"file\":\"config/application.properties\","
                        + "\"startLine\":5,\"secretHash\":\"" + "ab".repeat(32) + "\",\"preview\":\"AKIA\"}]");
                Files.writeString(spec.outDir().resolve("result.json"), "{"
                        + "\"schemaVersion\":1,\"cloneFailed\":false,\"commit\":\"cafebabe\","
                        + "\"toolVersions\":{\"git\":\"2.47.3\",\"gitleaks\":\"8.21.2\"},"
                        + "\"warnings\":[{\"code\":\"MISSING_GRADLE_LOCKFILE\",\"message\":\"no lockfile\","
                        + "\"filePath\":\"build.gradle\"}],"
                        + "\"scanners\":[{\"name\":\"secret\",\"status\":\"ok\",\"exitCode\":2,\"durationMs\":5,"
                        + "\"findingsFile\":\"secrets.json\"}]}");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new ScannerLauncher.LaunchResult(2, false, "stub log");
        });
    }

    @Test
    void aWorkspaceBScanWritesOnlyWorkspaceBRows() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        GitlabConnection conn = connections.save(GitlabConnection.forProject(b, "file:///tmp/secscan-fixture",
                "b/app", 401L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(b, conn.getId(), 401L, "b/app", "main", List.of("Java")));
        long defaultRowsBefore = rowsIn(Workspace.DEFAULT_ID);

        scanService.startManual(b, repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        for (String table : TABLES) {
            assertThat(count(table, b)).as("%s rows in workspace B", table).isPositive();
            assertThat(countOther(table, b)).as("%s rows outside workspace B", table).isZero();
        }
        assertThat(rowsIn(Workspace.DEFAULT_ID)).isEqualTo(defaultRowsBefore).isZero();
    }

    private long count(String table, UUID ws) {
        Long n = jdbc.queryForObject("select count(*) from " + table + " where workspace_id = ?", Long.class, ws);
        return n == null ? 0 : n;
    }

    private long countOther(String table, UUID ws) {
        Long n = jdbc.queryForObject("select count(*) from " + table + " where workspace_id <> ?", Long.class, ws);
        return n == null ? 0 : n;
    }

    private long rowsIn(UUID ws) {
        return TABLES.stream().mapToLong(t -> count(t, ws)).sum();
    }
}
