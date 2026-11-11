package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.TriageEvent;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.scan.worker.ScannerProperties;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * M8.15 S4 (D-104), approved change #2: a rescan that turns {@code --taint-intrafile} ON must NOT churn the
 * findings a prior OFF scan already produced. We scan {@code fixtures/sast-taint} (existing-pack Java/JS
 * taint-rule hits) on the SAME commit twice — first with taint OFF, then ON — through the real worker +
 * reconcile pipeline, and assert:
 * <ul>
 *   <li>the ON finding set is a SUPERSET of OFF (taint is additive);</li>
 *   <li>every fingerprint seen in BOTH scans is byte-identical and keeps the SAME {@code sub_issue} row
 *       (fingerprint excludes the line number, so inter-procedural taint cannot re-fingerprint a matched hit);</li>
 *   <li>the ON rescan writes ZERO triage_events for those matched findings (no status change);</li>
 *   <li>taint-only NEW findings are allowed — they are listed (and are the only sub-issues the rescan opens).</li>
 * </ul>
 * live-opengrep (real Opengrep container), offline; excluded from the default build. The toggle is driven by
 * flipping {@link ScannerProperties#setTaintIntrafile(boolean)} between the two scans.
 */
@Tag("live-opengrep")
class TaintRescanStabilityIT extends AbstractScanWorkerIT {

    private static final UUID WS = Workspace.DEFAULT_ID;
    private static final String JAVA_SQLI = "opengrep-rules.java.lang.security.audit.sqli.jdbc-sqli";
    private static final String JS_TAINTED_SQL =
            "opengrep-rules.javascript.express.security.injection.tainted-sql-string";

    static Path workRoot;
    static Path fixtureRepo;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-taint");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-taint");
        copyDir(locateFixture("sast-taint"), fixtureRepo);
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "fixture");
    }

    @Autowired ScanService scanService;
    @Autowired ScanClaimer claimer;
    @Autowired ScanJobProcessor processor;
    @Autowired SubIssueRepository subIssues;
    @Autowired TriageEventRepository triageEvents;
    @Autowired GitlabConnectionRepository connections;
    @Autowired RepositoryRepository repositories;
    @Autowired ScannerProperties scannerProps;

    @Test
    void taintRescanKeepsMatchedFingerprintsStableAndOpensOnlyTaintNewFindings() {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WS, "file://" + fixtureRepo.toAbsolutePath(), "svc/taint", 801L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WS, conn.getId(), 801L, "svc/taint", "main",
                List.of("Java")));

        // --- scan #1: taint OFF ---
        scannerProps.setTaintIntrafile(false);
        runScan(repo.getId());
        Map<String, UUID> offByFp = sastFingerprintToId(repo.getId());
        Set<String> fpOff = offByFp.keySet();

        // --- scan #2: taint ON, same commit ---
        scannerProps.setTaintIntrafile(true);
        ScanView scan2 = runScan(repo.getId());
        Map<String, UUID> onByFp = sastFingerprintToId(repo.getId());
        Set<String> fpOn = onByFp.keySet();

        // fixture guard: the existing-pack Java + JS taint hits must be present (OFF already).
        Set<String> rulesOff = sastRules(repo.getId(), fpOff);
        assertThat(rulesOff).as("existing-pack Java/JS taint-rule hits present with taint OFF")
                .contains(JAVA_SQLI, JS_TAINTED_SQL);

        // 1. ON ⊇ OFF (taint is additive; nothing the OFF scan found disappears).
        assertThat(fpOn).as("ON finding set is a superset of OFF").containsAll(fpOff);

        // 2. every matched fingerprint kept the SAME sub_issue row (no re-fingerprinting / churn).
        for (String fp : fpOff) {
            assertThat(onByFp.get(fp)).as("matched fingerprint keeps its sub_issue id: %s", fp)
                    .isEqualTo(offByFp.get(fp));
        }

        // 3. taint-only NEW fingerprints (allowed, listed).
        Set<String> taintOnly = fpOn.stream().filter(fp -> !fpOff.contains(fp))
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        // 4. the ON rescan wrote ZERO triage_events for matched findings; its only events open the taint-new ones.
        List<TriageEvent> scan2events = triageEvents.findAll().stream()
                .filter(e -> scan2.id().equals(e.getScanId()))
                .toList();
        List<UUID> matchedIds = fpOff.stream().map(offByFp::get).toList();
        long matchedEvents = scan2events.stream().filter(e -> matchedIds.contains(e.getSubIssueId())).count();

        // report block (pasted into the S4 report; live test)
        System.out.println("TAINT-STABILITY: offFindings=" + fpOff.size()
                + " onFindings=" + fpOn.size()
                + " matched=" + fpOff.size()
                + " matchedTriageEventsOnRescan=" + matchedEvents
                + " scan2TriageEvents=" + scan2events.size());
        for (String fp : taintOnly) {
            SubIssue s = subIssues.findByIdAndWorkspaceId(onByFp.get(fp), WS).orElseThrow();
            System.out.println("TAINT-STABILITY: taint-only-new rule=" + s.getRuleId()
                    + " file=" + s.getFilePath());
        }

        assertThat(matchedEvents).as("no triage_event for any matched finding on the taint-ON rescan").isZero();
        assertThat(taintOnly).as("the fixture's cross-function sink yields >=1 taint-only finding").isNotEmpty();
        // every event the rescan DID write is an open of a taint-only-new sub-issue.
        Set<UUID> taintOnlyIds = taintOnly.stream().map(onByFp::get).collect(Collectors.toSet());
        assertThat(scan2events).allSatisfy(e ->
                assertThat(taintOnlyIds).as("rescan triage_event is for a taint-only-new finding")
                        .contains(e.getSubIssueId()));
    }

    private ScanView runScan(UUID repoId) {
        ScanView scan = scanService.startManual(WS, repoId);
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));
        return scan;
    }

    /** SAST sub-issues in the repo, as fingerprint -> sub_issue id (fingerprints are unique per repo). */
    private Map<String, UUID> sastFingerprintToId(UUID repoId) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WS, repoId).stream()
                .filter(s -> "sast".equals(s.getType()))
                .collect(Collectors.toMap(SubIssue::getFingerprint, SubIssue::getId, (a, b) -> a));
    }

    private Set<String> sastRules(UUID repoId, Set<String> fps) {
        return subIssues.findByWorkspaceIdAndRepositoryId(WS, repoId).stream()
                .filter(s -> "sast".equals(s.getType()))
                .filter(s -> fps.contains(s.getFingerprint()))
                .map(SubIssue::getRuleId)
                .collect(Collectors.toSet());
    }

    // --- fixture helpers (mirrors CustomSastRulesIT) ---

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
