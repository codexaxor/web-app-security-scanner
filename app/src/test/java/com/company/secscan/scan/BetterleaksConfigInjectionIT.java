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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Proves D-037 for secrets: a scanned repo cannot suppress its own findings. The {@code secrets-suppressed}
 * fixture plants a fake GitHub token and tries to hide it three ways at once — an in-repo {@code .gitleaks.toml}
 * that neutralises the {@code github-pat} rule, an inline {@code gitleaks:allow} comment, and a
 * {@code .gitleaksignore} entry. SecScan runs Betterleaks with a pinned {@code --config},
 * {@code --ignore-gitleaks-allow} and a baked empty {@code --gitleaks-ignore-path}, so all three are ignored
 * and the token is STILL reported. Runs the real hardened container offline (secrets need no network).
 */
class BetterleaksConfigInjectionIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-inject");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-inject");
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
    ScanFindingRepository findings;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void inRepoSuppressorsAreIgnoredAndTheSecretIsStillReported() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 202L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 202L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);

        List<ScanFinding> secrets = findings.findByScanId(scan.id()).stream()
                .filter(f -> ScanFinding.TYPE_SECRET.equals(f.getType()))
                .toList();
        // Every planted occurrence is a github-pat finding despite the in-repo suppressors.
        assertThat(secrets).isNotEmpty().allSatisfy(f -> assertThat(f.getRuleId()).isEqualTo("github-pat"));
        // The .gitleaksignore tried to hide this one (by fingerprint); it is reported anyway.
        assertThat(secrets).anySatisfy(f ->
                assertThat(f.getFilePath()).isEqualTo("config/application.properties"));
        // The inline `gitleaks:allow` tried to hide this one; it is reported anyway.
        assertThat(secrets).anySatisfy(f ->
                assertThat(f.getFilePath()).isEqualTo("config/inline.properties"));
    }

    // --- fixture helpers (self-contained, mirroring ScaLiveScanIT) ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/secrets-suppressed"),
                Path.of("../fixtures/secrets-suppressed"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/secrets-suppressed from "
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
