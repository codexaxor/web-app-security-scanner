package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.crypto.SecretFingerprinter;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.IssueGroupRepository;
import com.company.secscan.issue.SubIssue;
import com.company.secscan.issue.SubIssueRepository;
import com.company.secscan.issue.api.SubIssueView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Regression for the M3 manual-check blocker: a multi-line secret (PEM private key) must NOT leak a fragment of
 * every line into {@code secret_preview}. Runs the real hardened scanner container over a repository containing
 * a private key generated at test time (never committed), and asserts the stored preview is at most 4 chars and
 * single-line, the hash is over the whole secret (not one line), and no part of the key reaches the preview,
 * hash, log excerpt or the API.
 */
class SecretPreviewLeakIT extends AbstractScanWorkerIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    // The all-zero 32-byte fingerprint key matches AbstractScanWorkerIT's SECSCAN_FINGERPRINT_KEY.
    private static final SecretFingerprinter FP = new SecretFingerprinter(new byte[32]);

    static Path workRoot;
    static Path fixtureRepo;
    static String pem;
    static String keyBodySample; // a distinctive chunk of the key that must never leak

    @DynamicPropertySource
    static void scannerProps(DynamicPropertyRegistry registry) {
        registry.add("secscan.scanner.work-root", () -> workRoot.toString());
    }

    @BeforeAll
    static void buildFixtureRepo() throws Exception {
        workRoot = Files.createTempDirectory("secscan-scans-pem-it");
        fixtureRepo = Files.createTempDirectory("secscan-fixture-pem-it");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        byte[] pkcs8 = kpg.generateKeyPair().getPrivate().getEncoded();
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(pkcs8);
        // No trailing newline: gitleaks' private-key match is then exactly this block, so the hash is predictable.
        pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";
        keyBodySample = b64.replace("\n", "").substring(20, 60);

        Files.createDirectories(fixtureRepo.resolve("config"));
        Files.writeString(fixtureRepo.resolve("config/id_rsa"), pem);
        git(fixtureRepo, "init", "-q", "-b", "main");
        git(fixtureRepo, "config", "user.email", "test@example.com");
        git(fixtureRepo, "config", "user.name", "Test");
        git(fixtureRepo, "add", "-A");
        git(fixtureRepo, "commit", "-q", "-m", "pem fixture");
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
    ScanTaskRepository tasks;
    @Autowired
    ScanFindingRepository findings;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    ObjectMapper objectMapper;

    @Test
    void multiLinePemKeyPreviewIsSafeAndTheHashIsOverTheWholeSecret() throws Exception {
        GitlabConnection conn = connections.save(GitlabConnection.forProject(
                WORKSPACE, "file://" + fixtureRepo.toAbsolutePath(), "svc/app", 101L, "ct",
                "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 101L, "svc/app", "main",
                List.of("Java")));

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));
        assertThat(scans.findById(scan.id()).orElseThrow().getStatus()).isEqualTo(Scan.STATUS_COMPLETED);

        List<ScanFinding> stored = findings.findByScanId(scan.id());
        assertThat(stored).as("gitleaks should flag the PEM private key").isNotEmpty();

        // EVERY stored finding has a safe preview: at most 4 chars, no newline/whitespace.
        for (ScanFinding f : stored) {
            assertThat(f.getSecretPreview()).isNotNull();
            assertThat(f.getSecretPreview()).hasSizeLessThanOrEqualTo(4);
            assertThat(f.getSecretPreview().chars().anyMatch(Character::isWhitespace)).isFalse();
        }

        ScanFinding pk = stored.stream().filter(f -> "private-key".equals(f.getRuleId())).findFirst()
                .orElseThrow(() -> new AssertionError("no private-key finding; rules: "
                        + stored.stream().map(ScanFinding::getRuleId).toList()));

        // The hash is the keyed HMAC of the sha256 of the WHOLE secret, not of a single line. The scanner's
        // match is exactly the PEM block; command substitution may keep or drop a trailing newline, so accept
        // either, but it must NOT be the hash of just the first line.
        String whole = expectedHash(pem);
        String wholeNl = expectedHash(pem + "\n");
        String firstLineOnly = expectedHash("-----BEGIN PRIVATE KEY-----");
        assertThat(pk.getSecretHash()).isIn(whole, wholeNl).isNotEqualTo(firstLineOnly);

        // No part of the key leaks into the stored preview, hash, log excerpt, or the API view.
        ScanTask task = tasks.findByScanId(scan.id()).get(0);
        String logExcerpt = task.getLogExcerpt() == null ? "" : task.getLogExcerpt();
        assertThat(logExcerpt).doesNotContain(keyBodySample);
        assertThat(pk.getSecretPreview()).doesNotContain(keyBodySample);
        assertThat(pk.getSecretHash()).doesNotContain(keyBodySample);

        SubIssue sub = subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId()).stream()
                .filter(s -> "private-key".equals(s.getRuleId())).findFirst().orElseThrow();
        String apiJson = objectMapper.writeValueAsString(SubIssueView.of(sub));
        assertThat(apiJson)
                .doesNotContain(keyBodySample)
                .doesNotContain(pk.getSecretHash()); // the hash itself is never exposed by the API
        assertThat(sub.getSecretPreview()).hasSizeLessThanOrEqualTo(4);
    }

    private static String expectedHash(String secret) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(secret.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return FP.hash(hex.toString());
    }

    private static void git(Path dir, String... args) throws Exception {
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
