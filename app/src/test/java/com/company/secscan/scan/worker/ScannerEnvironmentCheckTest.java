package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.secscan.config.SecretFingerprinterHolder;
import com.company.secscan.crypto.SecretFingerprinter;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker readiness self-check flips ready based on work-root writability, daemon reachability, the scanner
 * image, and the secret fingerprint key (D-027).
 */
class ScannerEnvironmentCheckTest {

    private static final SecretFingerprinterHolder CONFIGURED = SecretFingerprinterHolder.of(
            SecretFingerprinter.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));

    private static ScannerProperties props(Path workRoot) {
        ScannerProperties p = new ScannerProperties();
        p.setWorkRoot(workRoot.toString());
        p.setImage("secscan-scanner:test");
        return p;
    }

    @Test
    void readyWhenWorkRootWritableDaemonUpImagePresentAndKeyConfigured(@TempDir Path workRoot) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS); // ping/inspect return null => ok
        ScannerEnvironmentCheck check = new ScannerEnvironmentCheck(docker, props(workRoot), CONFIGURED);

        check.check();

        assertThat(check.isReady()).isTrue();
    }

    @Test
    void notReadyWhenScannerImageMissing(@TempDir Path workRoot) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.inspectImageCmd(anyString()).exec()).thenThrow(new NotFoundException("no such image"));
        ScannerEnvironmentCheck check = new ScannerEnvironmentCheck(docker, props(workRoot), CONFIGURED);

        check.check();

        assertThat(check.isReady()).isFalse();
    }

    @Test
    void notReadyWhenFingerprintKeyMissing(@TempDir Path workRoot) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ScannerEnvironmentCheck check = new ScannerEnvironmentCheck(docker, props(workRoot),
                SecretFingerprinterHolder.unconfigured());

        check.check();

        assertThat(check.isReady()).isFalse();
    }
}
