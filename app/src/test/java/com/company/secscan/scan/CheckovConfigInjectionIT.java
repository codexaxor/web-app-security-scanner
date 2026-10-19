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
 * Proves D-037 for IaC: a scanned repo cannot suppress its own Checkov findings, two ways at once. The
 * {@code iac-suppressed} fixture (a) inline-skips {@code CKV_AWS_21} with {@code #checkov:skip=} and (b) ships a
 * {@code .checkov.yaml} that tries to {@code skip-check} {@code CKV_AWS_23}. SecScan recovers the inline-skipped
 * check from {@code skipped_checks[]} and deletes the in-repo {@code .checkov.yaml} from the clone, so BOTH
 * still surface as findings. Runs the real container offline.
 */
@Tag("live-checkov")
class CheckovConfigInjectionIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-iacinj");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-iacinj");
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
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void inlineSkipIsRecoveredAndInRepoCheckovConfigIsIgnored() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/iacinj", 711L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 711L, "svc/iacinj", "main",
                List.of("HCL")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        List<String> iacRules = findings.findByScanId(scan.id()).stream()
                .filter(f -> ScanFinding.TYPE_IAC.equals(f.getType()))
                .map(ScanFinding::getRuleId)
                .toList();

        // The inline #checkov:skip=CKV_AWS_21 is RECOVERED from skipped_checks and promoted.
        assertThat(iacRules).as("inline-skipped check recovered").contains("CKV_AWS_21");
        // The repo's .checkov.yaml tried to skip CKV_AWS_23; it was deleted from the clone, so it still fires.
        assertThat(iacRules).as("in-repo .checkov.yaml ignored").contains("CKV_AWS_23");
    }

    // --- fixture helpers (self-contained) ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/iac-suppressed"), Path.of("../fixtures/iac-suppressed"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/iac-suppressed");
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
