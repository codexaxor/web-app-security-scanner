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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves D-037 for SCA: a scanned repo cannot suppress its own vulnerabilities. The {@code sca-osv-ignore}
 * fixture (lodash@4.17.4) ships an in-repo {@code osv-scanner.toml} that ignores {@code CVE-2019-10744}. SecScan
 * passes a pinned {@code --config}, so OSV-Scanner ignores the repo config and the vulnerability is STILL
 * reported (verified against v2.7.0: honouring the repo config drops it, the pinned --config brings it back).
 *
 * <p>Needs live network to osv.dev/deps.dev, so it is tagged {@code live-osv} and excluded from the default
 * build, like {@link ScaLiveScanIT} (run with {@code ./gradlew :app:test -PliveOsv --tests "*OsvConfigInjectionIT"}).
 */
@Tag("live-osv")
class OsvConfigInjectionIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;

    @BeforeAll
    static void initWorkRoot() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-osvinject");
    }

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
        // Let the local file:// clone reach the network so OSV can query deps.dev/osv.dev (test-only).
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
    void inRepoOsvScannerTomlIsIgnoredAndTheVulnerabilityIsStillReported() throws Exception {
        Path fixtureRepo = buildFixtureRepo();
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 303L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 303L, "svc/app", "main",
                List.of("JavaScript")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);

        // The in-repo osv-scanner.toml tried to ignore CVE-2019-10744; it is reported anyway (D-037).
        List<SubIssue> subs = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getType()).isEqualTo("sca");
            assertThat(s.getPackageName()).isEqualTo("lodash");
            assertThat(s.getVulnId()).isEqualTo("CVE-2019-10744");
        });
    }

    // --- fixture helpers (self-contained, mirroring ScaLiveScanIT) ---

    private static Path buildFixtureRepo() throws Exception {
        Path repo = Files.createTempDirectory("secscan-fixture-osvinject");
        copyDir(locateFixture(), repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "test@example.com");
        git(repo, "config", "user.name", "Test");
        git(repo, "add", "-A");
        git(repo, "commit", "-q", "-m", "fixture");
        return repo;
    }

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/sca-osv-ignore"), Path.of("../fixtures/sca-osv-ignore"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/sca-osv-ignore from "
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
