package com.company.secscan.scan.worker;

import com.company.secscan.logging.LogSanitizer;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.Frame;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Launches one hardened, disposable scanner container and returns its exit code and a scrubbed log excerpt. The
 * container is always removed in a {@code finally} (the done-when requires it to be gone afterwards). The token,
 * when used, reaches the container only as the read-only file named in the spec — never an env var or the
 * command line (D-014). Nothing here runs repository code; the scanner only parses files.
 */
@Component
@Profile("worker")
public class ScannerLauncher {

    private static final Logger log = LoggerFactory.getLogger(ScannerLauncher.class);
    private static final int LOG_EXCERPT_MAX = 4096;

    private final DockerClient docker;

    public ScannerLauncher(DockerClient docker) {
        this.docker = docker;
    }

    /** The result of a scanner-container run: the exit code (null if it timed out) and a safe log excerpt. */
    public record LaunchResult(Integer exitCode, boolean timedOut, String logExcerpt) {
    }

    public LaunchResult run(ScannerLaunchSpec spec) {
        if (spec.runAsUser().startsWith("0:")) {
            // Running the scanner as root would defeat the non-root hardening and need a root-owned /out.
            throw new IllegalStateException("Refusing to run the scanner container as root (uid 0). "
                    + "Run the worker as a non-root user that owns the scan work root.");
        }
        CreateContainerResponse container = docker.createContainerCmd(spec.image())
                .withHostConfig(ScannerContainerConfig.buildHostConfig(spec))
                .withEnv(ScannerContainerConfig.buildEnv(spec))
                .withUser(spec.runAsUser())
                .withLabels(ScannerContainerConfig.labels(spec.scanId()))
                .withNetworkDisabled(spec.networkDisabled())
                .exec();
        String id = container.getId();
        try {
            docker.startContainerCmd(id).exec();
            WaitContainerResultCallback wait = docker.waitContainerCmd(id).start();
            // awaitCompletion returns false iff the wait really elapsed — the ONLY thing we treat as a timeout.
            boolean finished = wait.awaitCompletion(spec.timeout().toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                log.warn("Scanner container for scan {} did not finish within {}s; killing it",
                        spec.scanId(), spec.timeout().toSeconds());
                safelyKill(id);
                return new LaunchResult(null, true, captureLogs(id));
            }
            Integer exitCode = wait.awaitStatusCode();
            return new LaunchResult(exitCode, false, captureLogs(id));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for scanner container for scan {}; killing it", spec.scanId());
            safelyKill(id);
            throw new IllegalStateException("Interrupted while waiting for the scanner container", e);
        } catch (DockerException e) {
            // A real Docker error (daemon/client failure), NOT a wait timeout: a scanner failure, logged
            // clearly and rethrown so the worker records it as such. timedOut is never set for this case.
            log.error("Scanner container for scan {} failed with a Docker error ({}): {}",
                    spec.scanId(), e.getClass().getSimpleName(), e.getMessage());
            throw e;
        } finally {
            safelyRemove(id);
        }
    }

    private String captureLogs(String id) {
        StringBuilder sb = new StringBuilder();
        try {
            docker.logContainerCmd(id)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTailAll()
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            if (sb.length() < LOG_EXCERPT_MAX * 4) {
                                sb.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                            }
                        }
                    })
                    .awaitCompletion(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.debug("Could not read logs for container {}: {}", id, e.getMessage());
        }
        // Scrub token-shaped strings, then truncate to a bounded excerpt (point 6).
        return LogSanitizer.scrubAndTruncate(sb.toString(), LOG_EXCERPT_MAX);
    }

    private void safelyKill(String id) {
        try {
            docker.killContainerCmd(id).exec();
        } catch (RuntimeException e) {
            log.debug("Could not kill container {}: {}", id, e.getMessage());
        }
    }

    private void safelyRemove(String id) {
        try {
            docker.removeContainerCmd(id).withForce(true).exec();
        } catch (RuntimeException e) {
            log.warn("Could not remove scanner container {} (it may already be gone): {}", id, e.getMessage());
        }
    }
}
