package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The end-to-end SCA tests that need live network to osv.dev/deps.dev. Tagged {@code live-osv} and excluded
 * from the default build (run with {@code ./gradlew :app:test -PliveOsv --tests "*ScaLiveScanIT"}). They run
 * the real scanner container with {@code local-clone-network=true} so OSV-Scanner can resolve and match, and
 * assert the expected CVE is CONTAINED among the stored SCA sub-issues (not an exact set — advisory contents
 * drift; plan change #10).
 */
@Tag("live-osv")
class ScaLiveScanIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final AtomicLong PROJECT_ID = new AtomicLong(3030);

    static Path workRoot;

    @BeforeAll
    static void initWorkRoot() throws Exception {
        // Set before the context loads: the @DynamicPropertySource supplier below reads it during refresh.
        workRoot = Files.createTempDirectory("secscan-scans-live");
    }

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
        // Let the local file:// clone reach the network so OSV can query deps.dev (test-only).
        registry.add("secscan.scanner.local-clone-network", () -> "true");
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
    SubIssueRepository subIssues;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void directDependencyCveIsFound() throws Exception {
        List<SubIssue> subs = scanFixture("sca-maven");
        // commons-text 1.9 -> CVE-2022-42889 (Text4Shell), a DIRECT dependency.
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getType()).isEqualTo("sca");
            assertThat(s.getPackageName()).isEqualTo("org.apache.commons:commons-text");
            assertThat(s.getVulnId()).isEqualTo("CVE-2022-42889");
        });
    }

    @Test
    void transitiveDependencyCveIsFound() throws Exception {
        List<SubIssue> subs = scanFixture("sca-maven-transitive");
        // commons-lang3 is NOT declared in the pom (only commons-text 1.12.0 is); OSV resolves it transitively
        // in default mode (deps.dev) and reports CVE-2025-48924 — proving transitive resolution end to end.
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getType()).isEqualTo("sca");
            assertThat(s.getPackageName()).isEqualTo("org.apache.commons:commons-lang3");
            assertThat(s.getVulnId()).isEqualTo("CVE-2025-48924");
        });
    }

    /** Builds a throwaway git repo from the named fixture, scans it through the real container, returns subs. */
    private List<SubIssue> scanFixture(String fixtureName) throws Exception {
        Path fixtureRepo = buildFixtureRepo(fixtureName);
        long projectId = PROJECT_ID.incrementAndGet();
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", projectId, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
        return subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
    }

    // --- fixture helpers ---

    private static Path buildFixtureRepo(String fixtureName) throws Exception {
        Path repo = Files.createTempDirectory("secscan-fixture-live");
        copyDir(locateFixture(fixtureName), repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "fixture");
        return repo;
    }

    private static Path locateFixture(String name) {
        for (Path base : List.of(Path.of("fixtures", name), Path.of("../fixtures", name))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/" + name + " from "
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
