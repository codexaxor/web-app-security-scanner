package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The worker readiness self-check flips ready based on work-root writability, daemon reachability and image. */
class ScannerEnvironmentCheckTest {

    private static ScannerProperties props(Path workRoot) {
        ScannerProperties p = new ScannerProperties();
        p.setWorkRoot(workRoot.toString());
        p.setImage("secscan-scanner:test");
        return p;
    }

    @Test
    void readyWhenWorkRootWritableDaemonUpAndImagePresent(@TempDir Path workRoot) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS); // ping/inspect return null => ok
        ScannerEnvironmentCheck check = new ScannerEnvironmentCheck(docker, props(workRoot));

        check.check();

        assertThat(check.isReady()).isTrue();
    }

    @Test
    void notReadyWhenScannerImageMissing(@TempDir Path workRoot) {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(docker.inspectImageCmd(anyString()).exec()).thenThrow(new NotFoundException("no such image"));
        ScannerEnvironmentCheck check = new ScannerEnvironmentCheck(docker, props(workRoot));

        check.check();

        assertThat(check.isReady()).isFalse();
    }
}
