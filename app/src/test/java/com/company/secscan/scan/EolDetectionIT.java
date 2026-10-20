package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.EolCycle;
import com.company.secscan.intel.EolCycleRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.reconcile.Fingerprints;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M6 Slice 3 done-when: an old Spring Boot project raises an EOL issue whose fingerprint carries the product and
 * detected cycle. The static detector finds {@code spring-boot 2.1} (and {@code eclipse-temurin 8}) in the
 * fixture; with the eol_cycle cache seeded as past-EOL, the worker keeps it as a finding. This slice asserts
 * DETECTION + the product/cycle fingerprint only — the base-75 score lands in Slice 4 (SHOULD-FIX 5). Runs the
 * real container offline; live-eol.
 */
@Tag("live-eol")
class EolDetectionIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-eol");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-eol");
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
    ScanFindingRepository findings;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    EolCycleRepository eolCycles;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @BeforeEach
    void seedEolCycle() {
        eolCycles.deleteAllInBatch();
        // Seed the detected cycles as past-EOL so the enricher keeps them (and the scanner stays solvable).
        eolCycles.save(new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 10, 1), "2.1.18", null,
                null, false, false));
        eolCycles.save(new EolCycle("eclipse-temurin", "8", true, LocalDate.of(2026, 11, 1), "8u432", null,
                null, true, false));
    }

    @Test
    void oldSpringBootRaisesAnEolIssueWithProductAndCycleInTheFingerprint() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/legacy", 720L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 720L, "svc/legacy", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        List<ScanFinding> eol = findings.findByScanId(scan.id()).stream()
                .filter(f -> ScanFinding.TYPE_EOL.equals(f.getType()))
                .toList();
        assertThat(eol).anySatisfy(f -> {
            assertThat(f.getPackageName()).isEqualTo("spring-boot");  // product
            assertThat(f.getInstalledVersion()).isEqualTo("2.1");     // detected cycle
            assertThat(f.getFilePath()).isEqualTo("pom.xml");
        });

        // The sub-issue's fingerprint is exactly product | cycle | filePath (line-free).
        String expected = Fingerprints.eol("spring-boot", "2.1", "pom.xml");
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .anySatisfy(s -> {
                    assertThat(s.getType()).isEqualTo(ScanFinding.TYPE_EOL);
                    assertThat(s.getFingerprint()).isEqualTo(expected);
                });
    }

    // --- fixture helpers (self-contained) ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/eol-springboot-old"),
                Path.of("../fixtures/eol-springboot-old"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/eol-springboot-old");
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
