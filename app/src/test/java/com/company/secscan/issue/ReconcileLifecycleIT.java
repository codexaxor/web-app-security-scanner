package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanService;
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
 * The M3 "Done when", end to end against the real hardened scanner container (built by {@code buildScannerImage})
 * over a fixture repository with multiple commits, cloned offline via {@code file://}:
 *
 * <ol>
 *   <li>commit 1 has the fake AWS key &rarr; scan &rarr; the secret sub-issue is <b>open</b>;</li>
 *   <li>commit 2 removes it &rarr; scan &rarr; the sub-issue is <b>solved</b>;</li>
 *   <li>commit 3 re-adds it &rarr; scan &rarr; the <b>same</b> sub-issue is <b>reopened</b> (stable fingerprint).</li>
 * </ol>
 *
 * Each transition writes a system {@code triage_event}, and no duplicate sub-issue is ever created.
 */
class ReconcileLifecycleIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private static final String WITH_KEY = """
            app.name=demo-service
            aws.accessKeyId=AKIA3K7HB9QO2PX5TL4D
            aws.secretAccessKey=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            aws.region=us-east-1
            """;
    private static final String WITHOUT_KEY = """
            app.name=demo-service
            aws.region=us-east-1
            """;

    static Path workRoot;
    static Path fixtureRepo;
    static Path propsFile;

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-reconcile-it");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-reconcile-it");
        Files.createDirectories(fixtureRepo.resolve("config"));
        propsFile = fixtureRepo.resolve("config/application.properties");
        Files.writeString(propsFile, WITH_KEY);
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        commitAll("commit 1: key present");
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
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    TriageEventRepository events;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    @Test
    void secretLifecycleOpenSolvedReopenedAcrossThreeCommits() throws Exception {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 101L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        // --- commit 1: key present -> open ---
        runScan(repo.getId());
        List<SubIssue> afterFirst = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
        assertThat(afterFirst).singleElement()
                .satisfies(s -> assertThat(s.getType()).isEqualTo("secret"));
        SubIssue sub = afterFirst.get(0);
        UUID subId = sub.getId();
        assertThat(sub.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
        assertThat(sub.getRuleId()).isEqualTo("aws-access-token");
        assertThat(groups.findAll()).singleElement()
                .satisfies(g -> assertThat(g.getStatus()).isEqualTo("open"));
        assertThat(reasons(subId)).containsExactly(TriageEvent.REASON_SEEN);

        // --- commit 2: key removed -> solved ---
        Files.writeString(propsFile, WITHOUT_KEY);
        commitAll("commit 2: key removed");
        runScan(repo.getId());
        List<SubIssue> afterSecond = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
        assertThat(afterSecond).singleElement().satisfies(s -> {
            assertThat(s.getId()).isEqualTo(subId); // no duplicate
            assertThat(s.getStatus()).isEqualTo(SubIssue.STATUS_SOLVED);
            assertThat(s.getSolvedAt()).isNotNull();
        });
        assertThat(groups.findAll()).singleElement()
                .satisfies(g -> assertThat(g.getStatus()).isEqualTo("solved"));
        assertThat(reasons(subId)).containsExactly(
                TriageEvent.REASON_SEEN,
                TriageEvent.REASON_SOLVED_NOT_DETECTED);

        // --- commit 3: key re-added -> reopened (same fingerprint, same sub-issue) ---
        Files.writeString(propsFile, WITH_KEY);
        commitAll("commit 3: key re-added");
        runScan(repo.getId());
        List<SubIssue> afterThird = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId());
        assertThat(afterThird).singleElement().satisfies(s -> {
            assertThat(s.getId()).isEqualTo(subId); // reopened, not a new row
            assertThat(s.getStatus()).isEqualTo(SubIssue.STATUS_OPEN);
            assertThat(s.getSolvedAt()).isNull();
        });
        assertThat(reasons(subId)).containsExactly(
                TriageEvent.REASON_SEEN,
                TriageEvent.REASON_SOLVED_NOT_DETECTED,
                TriageEvent.REASON_REOPENED);

        // Every scan was reconciled in its own committed transaction.
        assertThat(scans.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId(),
                org.springframework.data.domain.PageRequest.of(0, 10)).getContent())
                .allSatisfy(s -> assertThat(s.getReconciledAt()).isNotNull());
    }

    private void runScan(UUID repoId) {
        ScanView scan = scanService.startManual(repoId);
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));
        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);
    }

    private List<String> reasons(UUID subIssueId) {
        return events.findBySubIssueIdOrderByAtAsc(subIssueId).stream()
                .map(TriageEvent::getReasonCode).toList();
    }

    // --- fixture git helpers ---

    private static void commitAll(String message) throws IOException, InterruptedException {
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", message);
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
