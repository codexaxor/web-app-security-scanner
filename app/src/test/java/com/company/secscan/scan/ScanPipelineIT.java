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
 * task) against the synthetic {@code fixtures/secrets-aws} repository, cloned offline over {@code file://} with
 * {@code network=none}. The token one-time-file + https clone path is verified in the manual real-GitLab run
 * (see the M2 report / D-021).
 */
class ScanPipelineIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    // sha256 of the fixture's fake key AKIA3K7HB9QO2PX5TL4D (computed in the scanner container, not here).
    private static final String EXPECTED_HASH =
            "91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d";
    private static final String RAW_KEY = "AKIA3K7HB9QO2PX5TL4D";

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
        assertThat(finished.getToolVersions()).containsKey("gitleaks");

        List<ScanFinding> stored = findings.findByScanId(scan.id());
        assertThat(stored).singleElement().satisfies(f -> {
            assertThat(f.getType()).isEqualTo(ScanFinding.TYPE_SECRET);
            assertThat(f.getRuleId()).isEqualTo("aws-access-token");
            assertThat(f.getFilePath()).isEqualTo("config/application.properties");
            assertThat(f.getSecretHash()).isEqualTo(EXPECTED_HASH);
            assertThat(f.getSecretPreview()).isEqualTo("AKIA");
            // The raw secret is never stored (CLAUDE.md security rules).
            assertThat(f.getSecretHash()).isNotEqualTo(RAW_KEY);
            assertThat(f.getSecretPreview()).isNotEqualTo(RAW_KEY);
        });

        assertThat(tasks.findByScanId(scan.id())).singleElement()
                .satisfies(t -> assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_OK));
        assertThat(jobs.findByScanId(scan.id()).orElseThrow().getState()).isEqualTo(ScanJob.STATE_DONE);

        // Container is gone afterwards.
        assertThat(docker.listContainersCmd().withShowAll(true)
                .withLabelFilter(Map.of("secscan.scan", scan.id().toString())).exec()).isEmpty();
        // The per-scan workspace (and its token file) was wiped.
        assertThat(Files.exists(workRoot.resolve(scan.id().toString()))).isFalse();
    }

    // --- fixture helpers ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/secrets-aws"), Path.of("../fixtures/secrets-aws"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/secrets-aws from " + Path.of("").toAbsolutePath());
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
