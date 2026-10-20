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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M6 Slice 1 done-when: each custom Spring/.NET SAST rule fires on its positive fixture file and is silent on
 * its negative counterpart, via the real Opengrep container. One scan of {@code fixtures/sast-custom}; for every
 * rule we assert a SAST finding whose rule id is {@code opengrep-rules.custom.<id>} on a {@code positive/} path,
 * and no such custom-rule finding on any {@code negative/} path. Runs offline (SAST needs no network);
 * live-opengrep so it is excluded from the default build.
 */
@Tag("live-opengrep")
class CustomSastRulesIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    /** rule id (under opengrep-rules.custom.) → the positive fixture file it must fire on. */
    private static final List<String[]> RULES = List.of(
            new String[] {"spring-csrf-disabled", "positive/SecurityConfig.java"},
            new String[] {"spring-permitall-anyrequest", "positive/SecurityConfig.java"},
            new String[] {"spring-cross-origin-wildcard", "positive/SecurityConfig.java"},
            new String[] {"spring-spel-injection", "positive/Eval.java"},
            new String[] {"spring-actuator-exposed", "positive/application.properties"},
            new String[] {"hardcoded-connection-string", "positive/appsettings.json"},
            new String[] {"aspnet-allowanonymous-admin", "positive/AdminController.cs"});

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-sast");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-sast");
        copyDir(locateFixture("sast-custom"), fixtureRepo);
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
    void everyCustomRuleFiresOnItsPositiveAndIsSilentOnItsNegative() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/sast", 701L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 701L, "svc/sast", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        List<ScanFinding> sast = findings.findByScanId(scan.id()).stream()
                .filter(f -> ScanFinding.TYPE_SAST.equals(f.getType()))
                .toList();

        // Custom-rule findings keyed by the short id, with the set of files each fired on.
        var byRule = sast.stream()
                .filter(f -> f.getRuleId() != null && f.getRuleId().startsWith("opengrep-rules.custom."))
                .collect(Collectors.groupingBy(
                        f -> f.getRuleId().substring("opengrep-rules.custom.".length()),
                        Collectors.mapping(ScanFinding::getFilePath, Collectors.toSet())));

        for (String[] rule : RULES) {
            String id = rule[0];
            String positiveFile = rule[1];
            assertThat(byRule).as("custom rule %s must have fired", id).containsKey(id);
            assertThat(byRule.get(id)).as("%s fires on its positive file", id).contains(positiveFile);
            assertThat(byRule.get(id)).as("%s never fires on a negative file", id)
                    .noneMatch(p -> p != null && p.startsWith("negative/"));
        }
    }

    // --- fixture helpers (self-contained) ---

    private static Path locateFixture(String name) {
        for (Path base : List.of(Path.of("fixtures/" + name), Path.of("../fixtures/" + name))) {
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
