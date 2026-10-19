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
import com.github.dockerjava.api.DockerClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The M2 "Done when": Start Scan on a fixture with a fake AWS key stores a finding, and the scanner container is
 * gone afterwards. Runs the real, hardened scanner container (built by the {@code buildScannerImage} Gradle
 * task) against the synthetic {@code fixtures/secrets-token} repository, cloned offline over {@code file://} with
 * {@code network=none}. The token one-time-file + https clone path is verified in the manual real-GitLab run
 * (see the M2 report / D-021).
 */
class ScanPipelineIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    // sha256 of the fixture's fake GitHub token ghp_0123…wXyZ (computed in the scanner container, not here).
    private static final String CONTAINER_SHA =
            "e810c94de3e7621be6d97a3d5bd08eadaf66803e591fc7b11afef3e9aa9017b2";
    // What the worker actually stores: HMAC-SHA256(fingerprint key, CONTAINER_SHA) under the test's all-zero
    // 32-byte key (SECSCAN_FINGERPRINT_KEY in AbstractScanWorkerIT) — the bare digest is never persisted (D-027).
    private static final String EXPECTED_STORED_HASH =
            "3de3e8dc1462110d0002325c561e0b4369fd8c28c842069bdb204a5e2d4242b5";
    // The fake GitHub token in the fixture; it must never reach the stored log excerpt (point 7).
    private static final String RAW_KEY = "ghp_0123456789abcdefABCDEF0123456789wXyZ";

    static Path workRoot;
    static Path fixtureRepo;
    static String fixtureCommit;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-it");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-it");
        copyDir(locateFixture(), fixtureRepo);
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "fixture");
        fixtureCommit = gitOut(fixtureRepo, "rev-parse", "HEAD").trim();
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
    ScanJobRepository jobs;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    ScanTaskRepository tasks;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    DockerClient docker;

    @Test
    void scanOfAwsKeyFixtureStoresASecretFindingAndRemovesTheContainer() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 101L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
        assertThat(finished.getCommitSha()).isEqualTo(fixtureCommit);
        // tool_versions records every scanner's version, including OSV-Scanner (plan change #9).
        assertThat(finished.getToolVersions()).containsKeys("betterleaks", "osv-scanner");

        List<ScanFinding> stored = findings.findByScanId(scan.id());
        assertThat(stored).singleElement().satisfies(f -> {
            assertThat(f.getType()).isEqualTo(ScanFinding.TYPE_SECRET);
            assertThat(f.getRuleId()).isEqualTo("github-pat");
            assertThat(f.getFilePath()).isEqualTo("config/application.properties");
            // Stored hash is the keyed HMAC, never the raw key and never the bare container SHA-256 (D-027).
            assertThat(f.getSecretHash()).isEqualTo(EXPECTED_STORED_HASH);
            assertThat(f.getSecretHash()).isNotEqualTo(RAW_KEY).isNotEqualTo(CONTAINER_SHA);
            assertThat(f.getSecretPreview()).isEqualTo("ghp_").isNotEqualTo(RAW_KEY);
        });

        // Five scanners now run in the one container (M6): secrets, OSV-Scanner (SCA), Opengrep (SAST),
        // Checkov (IaC) and the EOL detector. This fixture has no dependency manifests (SCA ok with no packages,
        // exit 128) and no source/IaC/EOL findings, so every scanner is ok and the scan completes.
        List<ScanTask> scanTasks = tasks.findByScanId(scan.id());
        assertThat(scanTasks).extracting(ScanTask::getScanner)
                .containsExactlyInAnyOrder(ScanTask.SCANNER_SECRET, ScanTask.SCANNER_SCA,
                        ScanTask.SCANNER_SAST, ScanTask.SCANNER_IAC, ScanTask.SCANNER_EOL);
        assertThat(scanTasks).allSatisfy(t -> assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_OK));
        ScanTask secretTask = scanTasks.stream()
                .filter(t -> ScanTask.SCANNER_SECRET.equals(t.getScanner())).findFirst().orElseThrow();
        // The stored log excerpt must never contain the raw secret (point 7).
        assertThat(secretTask.getLogExcerpt() == null ? "" : secretTask.getLogExcerpt())
                .doesNotContain(RAW_KEY);
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_DONE);

        // Container is gone afterwards.
        assertThat(docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("secscan.scan", scan.id().toString())).exec()).isEmpty();
        // The per-scan workspace (and its token file) was wiped.
        assertThat(Files.exists(workRoot.resolve(scan.id().toString()))).isFalse();
    }

    // --- fixture helpers ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/secrets-token"), Path.of("../fixtures/secrets-token"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/secrets-token from "
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
        runGit(dir, args);
    }

    private static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        return runGit(dir, args);
    }

    private static String runGit(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }
}
