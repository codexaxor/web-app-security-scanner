package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScannerProperties;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M8.15 review B3 — the SAST FAILURE path never puts scanned source into the database. Opengrep's
 * {@code --verbose} run log quotes the unparsable source line of a parse error; before the fix run-scan.sh tailed
 * that log to stderr when Opengrep failed, and the worker stores the container's stdout/stderr as each
 * {@code scan_task.log_excerpt}.
 *
 * <p>Runs the REAL worker pipeline (claim → hardened container → parse → persist) on
 * {@code fixtures/sast-fail-canary} (a parse-error line carrying a fake canary) with a TEST-ONLY image derived
 * from the production scanner image: its {@code opengrep} is a shim that runs the REAL Opengrep (so the real
 * verbose log is written in-container) and then exits 2, forcing run-scan.sh's SAST-failed branch. The
 * production image is unchanged. Asserts the SAST task failed, its stored excerpt carries the sanitized
 * summary (non-vacuity: the failure branch ran and was captured), and the canary is in NO row of {@code scan},
 * {@code scan_task}, {@code scan_warning}, {@code scan_finding} or {@code sub_issue}. The in-image
 * {@code sastFailureCanaryInImageTest} proves the canary IS in the in-container verbose log and absent from /out.
 *
 * <p>This class needs its OWN Spring context (a different scanner image), i.e. one more cached context holding a
 * connection pool against the shared Postgres container; the suite is already near its {@code max_connections}.
 * So the context gets a 2-connection pool and is closed after the class ({@code @DirtiesContext}), releasing its
 * connections — otherwise a later IT's context fails with "too many clients already".
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SastFailureCanaryIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CANARY = "L3AKfailCanary8888";
    private static final String FAIL_IMAGE = "secscan-scanner-sastfail-it:local";

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
        registry.add("secscan.scanner.image", () -> FAIL_IMAGE);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    }

    @BeforeAll
    static void setUp() throws Exception {
        buildFailingOpengrepImage();
        workRoot = Files.createTempDirectory("secscan-scans-sastfail-it");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-sastfail-it");
        copyDir(locateFixture(), fixtureRepo);
        run(fixtureRepo, "git", "init", "-q", "-b", "main");
        run(fixtureRepo, "git", "config", "user.email", "test@example.com");
        run(fixtureRepo, "git", "config", "user.name", "Test");
        run(fixtureRepo, "git", "add", "-A");
        run(fixtureRepo, "git", "commit", "-q", "-m", "fixture");
    }

    /** FROM the production scanner image; only {@code opengrep} is wrapped to exit 2 after a real scan. */
    private static void buildFailingOpengrepImage() throws Exception {
        Path ctx = Files.createTempDirectory("secscan-sastfail-image");
        Files.writeString(ctx.resolve("opengrep-shim"),
                "#!/bin/sh\n/usr/local/bin/opengrep.real \"$@\"\nrc=$?\n"
                        + "if [ \"$1\" = \"scan\" ]; then exit 2; fi\nexit $rc\n", StandardCharsets.UTF_8);
        Files.writeString(ctx.resolve("Dockerfile"),
                "FROM " + new ScannerProperties().getImage() + "\n"
                        + "USER 0\n"
                        + "RUN mv /usr/local/bin/opengrep /usr/local/bin/opengrep.real\n"
                        + "COPY opengrep-shim /usr/local/bin/opengrep\n"
                        + "RUN chmod 0555 /usr/local/bin/opengrep\n"
                        + "USER 10001\n", StandardCharsets.UTF_8);
        run(ctx, "docker", "build", "-q", "-t", FAIL_IMAGE, ".");
    }

    @Autowired ScanService scanService;
    @Autowired ScanClaimer claimer;
    @Autowired ScanJobProcessor processor;
    @Autowired ScanTaskRepository tasks;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aFailedSastRunNeverPutsScannedSourceIntoTheDatabase() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/failcanary", 301L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 301L, "svc/failcanary",
                "main", List.of("Python")));

        ScanView scan = scanService.startManual(WORKSPACE, repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        ScanTask sast = tasks.findByScanId(scan.id()).stream()
                .filter(t -> ScanTask.SCANNER_SAST.equals(t.getScanner())).findFirst().orElseThrow();
        assertThat(sast.getStatus()).as("the shim forced Opengrep to fail").isEqualTo(ScanTask.STATUS_FAILED);
        // Non-vacuity: the SAST-failed branch ran and its sanitized summary is what the DB stored.
        assertThat(sast.getLogExcerpt()).contains("opengrep failed (exit 2); error types: ")
                .contains("PartialParsing");
        System.out.println("[SastFailureCanaryIT] stored sast log_excerpt summary line: "
                + sast.getLogExcerpt().lines().filter(l -> l.startsWith("opengrep failed")).findFirst()
                        .orElse("<none>"));

        // The canary (quoted by Opengrep's verbose log in-container) is in NO database row of this scan.
        for (String[] t : List.of(new String[] {"scan", "id"}, new String[] {"scan_task", "scan_id"},
                new String[] {"scan_warning", "scan_id"}, new String[] {"scan_finding", "scan_id"})) {
            List<String> rows = jdbc.queryForList(
                    "SELECT row_to_json(x)::text FROM " + t[0] + " x WHERE " + t[1] + " = ?", String.class,
                    scan.id());
            assertThat(rows).as("rows of %s for the scan", t[0]).noneMatch(r -> r.contains(CANARY));
            System.out.println("[SastFailureCanaryIT] " + t[0] + ": " + rows.size() + " row(s), canary absent");
        }
        List<String> subs = jdbc.queryForList(
                "SELECT row_to_json(x)::text FROM sub_issue x WHERE workspace_id = ? AND repository_id = ?",
                String.class, WORKSPACE, repo.getId());
        assertThat(subs).noneMatch(r -> r.contains(CANARY));
    }

    // --- helpers ---

    private static Path locateFixture() {
        for (Path base : List.of(Path.of("fixtures/sast-fail-canary"), Path.of("../fixtures/sast-fail-canary"))) {
            if (Files.isDirectory(base)) {
                return base;
            }
        }
        throw new IllegalStateException("Could not locate fixtures/sast-fail-canary from "
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
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void run(Path dir, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(600, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }
}
