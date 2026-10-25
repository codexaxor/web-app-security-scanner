package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * D-076: the scanner clones the REQUESTED branch (not the default). A fixture repo has a fake secret on {@code
 * dev} only; with {@code scan_branch=dev} the real hardened scanner (cloned {@code --branch=dev --single-branch})
 * must find that dev-only secret and record the dev HEAD commit — proving the chosen branch was cloned.
 */
class BranchCloneScanIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private static final String NO_KEY = "app.name=demo\napp.region=us-east-1\n";
    private static final String WITH_KEY =
            "app.name=demo\ngithub.token=ghp_0123456789abcdefABCDEF0123456789wXyZ\napp.region=us-east-1\n";

    static Path workRoot;
    static Path fixtureRepo;
    static String devHead;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-branchclone-it");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-branchclone-it");
        Files.createDirectories(fixtureRepo.resolve("config"));
        Path props = fixtureRepo.resolve("config/application.properties");
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        Files.writeString(props, NO_KEY);                 // main: no secret
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "main: no key");
        git(fixtureRepo, "checkout", "-q", "-b", "dev");
        Files.writeString(props, WITH_KEY);               // dev: a fake secret
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "dev: key");
        devHead = gitOut(fixtureRepo, "rev-parse", "dev").trim();
        git(fixtureRepo, "checkout", "-q", "main");        // leave the repo on main
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
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    @Test
    void clonesTheConfiguredBranchNotTheDefault() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 101L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));
        repo.setScanBranch("dev");                         // scan dev, not the default main
        repositories.save(repo);

        ScanView scan = scanService.startManual(repo.getId());
        assertThat(scan.branch()).isEqualTo("dev");
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
        assertThat(finished.getCommitSha()).isEqualTo(devHead);          // dev HEAD, not main
        assertThat(subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()))
                .anySatisfy(s -> assertThat(s.getType()).isEqualTo("secret"));   // dev-only secret → dev was cloned
    }

    // --- git helpers (mirror ReconcileLifecycleIT) ---

    private static String gitOut(Path dir, String... args) throws IOException, InterruptedException {
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + out);
        }
        return out;
    }

    private static void git(Path dir, String... args) throws IOException, InterruptedException {
        gitOut(dir, args);
    }
}
