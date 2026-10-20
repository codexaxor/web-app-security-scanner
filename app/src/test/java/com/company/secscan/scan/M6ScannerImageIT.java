package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M6 Slice 0 smoke: the scanner image runs Opengrep (SAST) and Checkov (IaC) alongside the existing secret/SCA
 * scanners, fully OFFLINE (network disabled), and records their versions and per-scanner tasks. Proves the
 * image is wired end to end before the parsers land (Slices 1-2). Tagged live-opengrep + live-checkov (needs
 * both real tools), so it runs only with {@code -PliveOpengrep -PliveCheckov}; the default build stays lean.
 */
@Tag("live-opengrep")
@Tag("live-checkov")
class M6ScannerImageIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-m6smoke");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-m6smoke");
        copyDir(locateFixture(), fixtureRepo);
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "fixture");
    }

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void imageRunsOpengrepAndCheckovOfflineAndRecordsTheirTasksAndVersions() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/iac", 606L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 606L, "svc/iac", "main",
                List.of("HCL")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        // Every scanner ran ok (Checkov exits 1 on a finding, which run-scan.sh maps to ok) → COMPLETED.
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
        assertThat(finished.getToolVersions())
                .containsKeys("opengrep", "checkov")
                .satisfies(v -> {
                    assertThat(v.get("opengrep")).isNotBlank();
                    assertThat(v.get("checkov")).isNotBlank();
                });

        List<ScanTask> scanTasks = tasks.findByScanId(scan.id());
        assertThat(scanTasks).anySatisfy(t -> {
            assertThat(t.getScanner()).isEqualTo(ScanTask.SCANNER_SAST);
            assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_OK);
        });
        assertThat(scanTasks).anySatisfy(t -> {
            assertThat(t.getScanner()).isEqualTo(ScanTask.SCANNER_IAC);
            assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_OK);
        });
    }

    // --- fixture helpers (self-contained, mirroring BetterleaksConfigInjectionIT) ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/iac-terraform"), Path.of("../fixtures/iac-terraform"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/iac-terraform from "
                + Path.of("").toAbsolutePath());
    }

    private static void copyDir(Path src, Path dest) throws IOException {
        try (var paths = Files.walk(src)) {
            paths.forEach(p -> {
                try {
                    Path target = dest.resolve(src.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        Files.copy(p, target);
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            });
        }
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
    }
}
