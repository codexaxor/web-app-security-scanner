package com.company.secscan.scan.worker;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.github.dockerjava.api.DockerClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Worker readiness self-check (point 3): on startup and every 60s, verifies the scan work root exists and is
 * writable by the worker user, the Docker daemon is reachable, and the scanner image is present. While any check
 * fails the worker is "not ready" and {@link ScanPoller} does not claim jobs — the process stays up and keeps
 * rechecking. One clear error is logged on the transition to not-ready (not every tick).
 */
@Component
@Profile("worker")
@ConditionalOnProperty(name = "secscan.scanner.polling-enabled", havingValue = "true", matchIfMissing = true)
public class ScannerEnvironmentCheck {

    private static final Logger log = LoggerFactory.getLogger(ScannerEnvironmentCheck.class);

    private final DockerClient docker;
    private final ScannerProperties props;
    private final SecretFingerprinterHolder fingerprinter;
    private final AtomicBoolean ready = new AtomicBoolean(false);
    private volatile boolean loggedNotReady = false;

    public ScannerEnvironmentCheck(DockerClient docker, ScannerProperties props,
                                   SecretFingerprinterHolder fingerprinter) {
        this.docker = docker;
        this.props = props;
        this.fingerprinter = fingerprinter;
    }

    public boolean isReady() {
        return ready.get();
    }

    @Scheduled(fixedDelayString = "${secscan.scanner.env-check-interval-ms:60000}", initialDelay = 0)
    public void check() {
        List<String> problems = new ArrayList<>();
        checkWorkRoot(problems);
        checkDaemon(problems);
        checkImage(problems);
        checkFingerprintKey(problems);

        boolean nowReady = problems.isEmpty();
        boolean wasReady = ready.getAndSet(nowReady);
        if (nowReady) {
            if (!wasReady) {
                log.info("Scanner environment ready; the worker will claim scan jobs.");
            }
            loggedNotReady = false;
        } else if (wasReady || !loggedNotReady) {
            // Log the clear error once per not-ready episode, not every 60s tick.
            loggedNotReady = true;
            log.error("Scanner environment not ready; the worker will NOT claim scan jobs until fixed:\n  - {}",
                    String.join("\n  - ", problems));
        }
    }

    private void checkWorkRoot(List<String> problems) {
        Path root = Path.of(props.getWorkRoot());
        Path probe = null;
        try {
            Files.createDirectories(root);
            probe = root.resolve(".secscan-write-probe-" + UUID.randomUUID());
            Files.writeString(probe, "ok");
        } catch (Exception e) {
            problems.add("scan work root " + root + " is not writable by the worker user ("
                    + currentUser() + "): " + e.getMessage()
                    + " — create it and chown it to the worker user (see README).");
        } finally {
            if (probe != null) {
                try {
                    Files.deleteIfExists(probe);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private void checkDaemon(List<String> problems) {
        try {
            docker.pingCmd().exec();
        } catch (RuntimeException e) {
            problems.add("Docker daemon is not reachable: " + e.getMessage()
                    + " — is the socket mounted and the worker in the docker group (DOCKER_GID)?");
        }
    }

    private void checkImage(List<String> problems) {
        try {
            docker.inspectImageCmd(props.getImage()).exec();
        } catch (RuntimeException e) {
            problems.add("scanner image '" + props.getImage() + "' is not present on the daemon — build it: "
                    + "docker build -t " + props.getImage() + " scanner-image/");
        }
    }

    /**
     * The secret fingerprint key (D-027) must be present and valid before the worker claims jobs. An invalid
     * key already fails startup (FingerprintConfig); a missing/empty one would let the worker start and only
     * fail when a secret finding is stored (M2 manual run) — reported as not-ready here instead.
     */
    private void checkFingerprintKey(List<String> problems) {
        if (!fingerprinter.isConfigured()) {
            problems.add("SECSCAN_FINGERPRINT_KEY is not set (D-027); secret findings cannot be stored. "
                    + "Generate one with: openssl rand -base64 32");
        }
    }

    private static String currentUser() {
        return System.getProperty("user.name", "?");
    }
}
