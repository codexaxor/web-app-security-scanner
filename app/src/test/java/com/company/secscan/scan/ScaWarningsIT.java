package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroup;
import com.company.secscan.issue.IssueGroupRepository;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M4 "missing-lockfile warnings appear": runs the real scanner container against the synthetic
 * {@code fixtures/sca-warnings} repo offline ({@code file://}, {@code network=none}). The inventory step emits
 * one warning per build file INDEPENDENT of OSV's result, so the warnings are asserted even though OSV, unable
 * to reach deps.dev offline, fails (exit 127) and the scan is partial. A failed SCA scanner closes nothing, so
 * a pre-existing open SCA issue stays open (domain rule 3). No live network.
 */
class ScaWarningsIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-warn");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-warn");
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
    ScanWarningRepository warnings;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void missingLockfileWarningsAppearPerBuildFileAndAFailedOsvClosesNothing() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 202L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 202L, "svc/app", "main",
                List.of("Java")));

        // A pre-existing open SCA issue whose scanner (sca) will FAIL this scan: it must stay open.
        IssueGroup group = groups.save(new IssueGroup(WORKSPACE, repo.getId(), "sca", "sca|Maven/foo", "foo",
                null));
        SubIssue open = new SubIssue(WORKSPACE, group.getId(), repo.getId(), "sca", ScanTask.SCANNER_SCA,
                Fingerprints.sca("foo", "CVE-2020-1", "pom.xml"), null);
        open = subIssues.save(open);

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        // OSV cannot reach deps.dev offline -> sca failed -> the scan is partial.
        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_PARTIAL);
        assertThat(tasks.findByScanId(scan.id()))
                .filteredOn(t -> ScanTask.SCANNER_SCA.equals(t.getScanner()))
                .singleElement().satisfies(t -> assertThat(t.getStatus()).isEqualTo(ScanTask.STATUS_FAILED));

        // One warning per build file, with the correct repo-relative path (from the inventory step). The
        // lockfile-less .csproj carries BOTH NuGet warnings (D-043): MISSING_NUGET_LOCKFILE and the honest
        // transitive-gap NUGET_NO_LOCKFILE_TRANSITIVE_GAP; they coexist.
        assertThat(warnings.findByScanId(scan.id()))
                .extracting(ScanWarning::getCode, ScanWarning::getFilePath)
                .contains(
                        tuple("MISSING_GRADLE_LOCKFILE", "infra/importer/build.gradle"),
                        tuple("MISSING_NUGET_LOCKFILE", "src/App/App.csproj"),
                        tuple("NUGET_NO_LOCKFILE_TRANSITIVE_GAP", "src/App/App.csproj"),
                        tuple("UNRESOLVED_VERSION", "src/App/App.csproj"));

        // A failed SCA scanner stores no SCA finding and closes nothing: the open issue is still open.
        assertThat(findings.findByScanId(scan.id()))
                .noneMatch(f -> ScanFinding.TYPE_SCA.equals(f.getType()));
        assertThat(subIssues.findById(open.getId()).orElseThrow().getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
    }

    @Test
    void nugetWithLockfileEmitsNeitherNugetWarning() throws Exception {
        // D-043: a .csproj WITH a sibling packages.lock.json gets NEITHER NuGet warning -- OSV reads the lockfile
        // (full transitives); there is no coverage gap to surface. Offline, so OSV itself fails, but the
        // inventory step still runs and (correctly) raises no NuGet warning for this project.
        Path repo = Files.createTempDirectory("secscan-fixture-nuget-lock");
        copyDir(locateFixture("sca-nuget"), repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "fixture");

        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + repo.toAbsolutePath(), "svc/nuget", 203L, "ct",
                "read_api,read_repository", null));
        Repository repository = repositories.save(new Repository(WORKSPACE, conn.getId(), 203L, "svc/nuget",
                "main", List.of("C#")));

        ScanView scan = scanService.startManual(repository.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        assertThat(warnings.findByScanId(scan.id()))
                .extracting(ScanWarning::getCode)
                .doesNotContain("MISSING_NUGET_LOCKFILE", "NUGET_NO_LOCKFILE_TRANSITIVE_GAP");
    }

    // --- fixture helpers ---

    private static Path locateFixture() {
        return locateFixture("sca-warnings");
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
