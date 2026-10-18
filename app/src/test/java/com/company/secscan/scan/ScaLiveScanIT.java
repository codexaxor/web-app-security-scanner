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
    ScanWarningRepository warnings;
    @Autowired
    SubIssueRepository subIssues;

    /** Scan id of the most recent {@link #scanFixture} call, so a test can assert on that scan's warnings. */
    private UUID lastScanId;
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

    @Test
    void gradleTransitiveCveIsFoundAndAnchoredToTheBuildFile() throws Exception {
        // M4b "Done when": a Gradle repo WITHOUT a lockfile yields its expected TRANSITIVE CVEs. OSV cannot read
        // build.gradle, so the static resolver (D-039) emits a synthetic Maven pom and OSV resolves it: the
        // direct commons-text:1.12.0 is clean but pulls commons-lang3 (CVE-2025-48924). D-038: the finding
        // anchors to build.gradle, never the synthetic /work path. Gradle findings are in the Maven ecosystem.
        List<SubIssue> subs = scanFixture("sca-gradle");
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getType()).isEqualTo("sca");
            assertThat(s.getPackageName()).isEqualTo("org.apache.commons:commons-lang3");
            assertThat(s.getVulnId()).isEqualTo("CVE-2025-48924");
            assertThat(s.getFilePath()).isEqualTo("build.gradle");
        });
        // No SCA finding may carry a container/synthetic path (D-038).
        assertThat(subs).filteredOn(s -> "sca".equals(s.getType()))
                .allSatisfy(s -> assertThat(s.getFilePath()).doesNotContain("/work").doesNotContain("synthetic"));
        // The test-scope dependency (commons-collections 3.2.1) is OVER-INCLUDED (slice-1 review): we resolve
        // every non-buildSrc configuration and let OSV decide, matching how M4 scans real poms (OSV is
        // scope-agnostic). So it DOES surface, anchored to build.gradle; principled dev/test exclusion is M7.
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getPackageName()).isEqualTo("commons-collections:commons-collections");
            assertThat(s.getFilePath()).isEqualTo("build.gradle");
        });
    }

    @Test
    void bomManagedGradleDependencyIsResolvedAndAnchoredToTheBuildFile() throws Exception {
        // D-041: a Spring Boot + Cloud build with NO inline versions. The resolver imports the Boot and Cloud
        // BOMs into the synthetic pom's <dependencyManagement>, emits the starters version-less, and injects the
        // Boot BOM as a <type>pom> trigger (no inline-versioned dep exists). OSV then resolves the managed
        // versions transitively, e.g. spring-boot-starter-web -> tomcat-embed-core (9.0.60, known CVEs).
        List<SubIssue> subs = scanFixture("sca-gradle-bom");
        assertThat(subs).anySatisfy(s -> {
            assertThat(s.getType()).isEqualTo("sca");
            // A BOM-managed transitive — declared with no version in build.gradle, resolved only via the BOM.
            assertThat(s.getPackageName()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
            assertThat(s.getFilePath()).isEqualTo("build.gradle");
        });
        assertThat(subs).filteredOn(s -> "sca".equals(s.getType()))
                .allSatisfy(s -> assertThat(s.getFilePath()).doesNotContain("/work").doesNotContain("synthetic"));
    }

    @Test
    void multiModuleGradleFindingsEachAnchorToTheirOwnBuildFile() throws Exception {
        // M4b regression (multi-module): a lockfile-less project with TWO build files -> TWO synthetic poms
        // (/work/synthetic/0 and /1). Before the fix the synthetic->build-file map was lost for the second pom,
        // so OSV's findings under both synthetic poms were dropped as UNRESOLVED_PATH and the scan produced 0 SCA
        // issues. Now the map is derived from a per-pom on-disk sidecar, so EACH finding anchors to its OWN
        // module's build.gradle (D-038). A single-pom fixture cannot catch this.
        List<SubIssue> sca = scanFixture("sca-gradle-multimodule").stream()
                .filter(s -> "sca".equals(s.getType())).toList();

        // The 'service' (web) module's UNIQUE transitive (tomcat-embed-core, BOM-managed, no inline version)
        // anchors to service/build.gradle.
        assertThat(sca).anySatisfy(s -> {
            assertThat(s.getPackageName()).isEqualTo("org.apache.tomcat.embed:tomcat-embed-core");
            assertThat(s.getFilePath()).isEqualTo("service/build.gradle");
        });
        // The 'gateway' (non-web) module's transitive (snakeyaml, CVE-2022-1471) anchors to gateway/build.gradle.
        assertThat(sca).anySatisfy(s -> {
            assertThat(s.getPackageName()).isEqualTo("org.yaml:snakeyaml");
            assertThat(s.getVulnId()).isEqualTo("CVE-2022-1471");
            assertThat(s.getFilePath()).isEqualTo("gateway/build.gradle");
        });
        // Both modules' build files are represented among the Gradle findings (both synthetic poms mapped).
        assertThat(sca).extracting(SubIssue::getFilePath)
                .contains("service/build.gradle", "gateway/build.gradle");
        // Correct per-module isolation, not a swapped/merged map: tomcat (web-only) never anchors to the gateway
        // module, and no finding carries a container/synthetic path.
        assertThat(sca).noneMatch(s -> "org.apache.tomcat.embed:tomcat-embed-core".equals(s.getPackageName())
                && "gateway/build.gradle".equals(s.getFilePath()));
        assertThat(sca).allSatisfy(s ->
                assertThat(s.getFilePath()).doesNotContain("/work").doesNotContain("synthetic"));
        // The guard that regressed: with both synthetic poms mapped, NOTHING is dropped as UNRESOLVED_PATH.
        assertThat(warnings.findByScanId(lastScanId))
                .noneMatch(w -> "UNRESOLVED_PATH".equals(w.getCode()));
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
        lastScanId = scan.id();
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
