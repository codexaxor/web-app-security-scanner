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
 * M6 Slice 2 done-when: Checkov's four enabled STATIC frameworks each produce their expected finding. One scan
 * of a repo that bundles the dockerfile / kubernetes / terraform / cloudformation fixtures; each framework is
 * asserted by its {@code CKV_*} rule and resource. (Helm and docker_compose are deliberately not covered —
 * D-054 / D-004: Helm needs the helm binary; Checkov 3.3.29 has no docker_compose framework.) Runs the real
 * container offline; live-checkov so it is excluded from the default build.
 */
@Tag("live-checkov")
class CheckovFrameworkIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-iacfw");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-iacfw");
        copyDir(locateFixture("iac-dockerfile"), fixtureRepo.resolve("dockerfile"));
        copyDir(locateFixture("iac-kubernetes"), fixtureRepo.resolve("kubernetes"));
        copyDir(locateFixture("iac-cloudformation"), fixtureRepo.resolve("cloudformation"));
        copyDir(locateFixture("iac-terraform"), fixtureRepo.resolve("terraform"));
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
    void eachStaticFrameworkProducesItsExpectedFinding() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/iacfw", 710L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 710L, "svc/iacfw", "main",
                List.of("HCL")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        List<ScanFinding> iac = findings.findByScanId(scan.id()).stream()
                .filter(f -> ScanFinding.TYPE_IAC.equals(f.getType()))
                .toList();

        // dockerfile — and the resource is normalized to the clean repo-relative path (no leading slash,
        // trailing dot or double slash), matching file_path, unlike Checkov's raw "/dockerfile/Dockerfile."
        // (review bug: the resource is a frozen IaC fingerprint input).
        assertThat(iac).as("dockerfile").anySatisfy(f -> {
            assertThat(f.getRuleId()).startsWith("CKV_DOCKER_");
            assertThat(f.getFilePath()).isEqualTo("dockerfile/Dockerfile");
            assertThat(f.getPackageName()).isEqualTo("dockerfile/Dockerfile");
            assertThat(f.getPackageName()).doesNotStartWith("/").doesNotEndWith(".").doesNotContain("//");
        });
        // kubernetes
        assertThat(iac).as("kubernetes").anySatisfy(f -> {
            assertThat(f.getRuleId()).startsWith("CKV_K8S_");
            assertThat(f.getPackageName()).isEqualTo("Pod.default.demo");
        });
        // cloudformation
        assertThat(iac).as("cloudformation").anySatisfy(f ->
                assertThat(f.getPackageName()).isEqualTo("AWS::S3::Bucket.DataBucket"));
        // terraform
        assertThat(iac).as("terraform").anySatisfy(f -> {
            assertThat(f.getRuleId()).isEqualTo("CKV_AWS_24"); // SSH open to 0.0.0.0/0
            assertThat(f.getPackageName()).isEqualTo("aws_security_group.wide_open");
        });
    }

    // --- fixture helpers (self-contained) ---

    private static Path locateFixture(String name) {
        for (Path base : List.of(Path.of("fixtures/" + name), Path.of("../fixtures/" + name))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/" + name);
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
