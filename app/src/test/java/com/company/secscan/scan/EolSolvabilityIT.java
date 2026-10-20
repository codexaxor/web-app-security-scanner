package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.intel.EolCycle;
import com.company.secscan.intel.EolCycleRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
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
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * BLOCKER 2: an EOL finding is NEVER false-solved. Scan once with the eol_cycle cache populated (the finding
 * opens); then rescan with the cache CLEARED. The detector still finds the cycle, but the worker cannot
 * determine its EOL status, so the EOL scanner is non-solvable that scan (closes_findings=false) and the open
 * EOL finding stays OPEN — it is not marked solved. Runs the real container offline; live-eol.
 */
@Tag("live-eol")
class EolSolvabilityIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-eolsolv");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-eolsolv");
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
    SubIssueRepository subIssues;
    @Autowired
    EolCycleRepository eolCycles;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void aRescanWithEmptyEolCacheDoesNotSolveAnOpenEolFinding() {
        eolCycles.deleteAllInBatch();
        eolCycles.save(new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 10, 1), "2.1.18", null,
                null, false, false));
        eolCycles.save(new EolCycle("eclipse-temurin", "8", true, LocalDate.of(2019, 1, 1), "8u432", null,
                null, true, false));

        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/legacy2", 721L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 721L, "svc/legacy2", "main",
                List.of("Java")));

        // Scan 1: the EOL finding opens.
        runScan(repo.getId());
        SubIssue opened = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> ScanFinding.TYPE_EOL.equals(s.getType()) && "spring-boot".equals(s.getPackageName()))
                .findFirst().orElseThrow();
        assertThat(opened.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // The cache is lost/empty before the next scan (e.g. intel never synced for this product).
        eolCycles.deleteAllInBatch();

        // Scan 2: the detector still finds spring-boot 2.1, but with no cache coverage it cannot be classified,
        // so its fingerprint is FROZEN in reconcile (per-finding guard) and the open finding must NOT be solved.
        runScan(repo.getId());

        SubIssue after = subIssues.findById(opened.getId()).orElseThrow();
        assertThat(after.getStatus()).as("open EOL finding must not be false-solved on a no-coverage rescan")
                .isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(after.getSolvedAt()).isNull();
    }

    @Test
    void aCoveredProductSolvesEvenWhenAnUncoveredProductIsInTheSameScan() throws Exception {
        // review BUG 1: the never-false-solve guard is PER (product,cycle). The cache covers spring-boot (so a
        // disappeared spring-boot finding can solve) but NOT dotnet. A later scan drops spring-boot and adds an
        // uncovered dotnet detection — spring-boot must still SOLVE, and the uncovered dotnet must neither be
        // persisted nor block anything.
        eolCycles.deleteAllInBatch();
        eolCycles.save(new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 10, 1), "2.1.18", null,
                null, false, false));

        Path repoDir = Files.createTempDirectory("secscan-fixture-eolpp");
        Files.writeString(repoDir.resolve("pom.xml"),
                "<project><parent><artifactId>spring-boot-starter-parent</artifactId>"
                        + "<version>2.1.5.RELEASE</version></parent></project>\n");
        git(repoDir, "init", "-q", "-b", "main");
        git(repoDir, "config", "user.email", "test@example.com");
        git(repoDir, "config", "user.name", "Test");
        git(repoDir, "add", "-A");
        git(repoDir, "commit", "-q", "-m", "spring-boot");

        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + repoDir.toAbsolutePath(), "svc/pp", 722L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 722L, "svc/pp", "main",
                List.of("Java")));

        // Scan 1: spring-boot 2.1 (covered) opens.
        runScan(repo.getId());
        SubIssue springBoot = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> ScanFinding.TYPE_EOL.equals(s.getType()) && "spring-boot".equals(s.getPackageName()))
                .findFirst().orElseThrow();
        assertThat(springBoot.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);

        // Repo now drops spring-boot and introduces a .NET project whose cycle is NOT in the cache.
        Files.delete(repoDir.resolve("pom.xml"));
        Files.writeString(repoDir.resolve("App.csproj"),
                "<Project Sdk=\"Microsoft.NET.Sdk\"><PropertyGroup>"
                        + "<TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>\n");
        git(repoDir, "add", "-A");
        git(repoDir, "commit", "-q", "-m", "switch to dotnet");

        // Scan 2: spring-boot is gone (covered → SOLVED); dotnet is uncovered (dropped, never persisted).
        runScan(repo.getId());

        SubIssue after = subIssues.findById(springBoot.getId()).orElseThrow();
        assertThat(after.getStatus()).as("a covered EOL product solves even with an uncovered one present")
                .isEqualTo(SubIssue.STATUS_SOLVED);
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .noneMatch(s -> "dotnet".equals(s.getPackageName())); // uncovered dotnet never became a finding
    }

    private void runScan(UUID repoId) {
        ScanView scan = scanService.startManual(repoId);
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));
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
