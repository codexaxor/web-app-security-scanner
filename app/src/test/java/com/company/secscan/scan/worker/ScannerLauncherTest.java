package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.KillContainerCmd;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.WaitContainerCmd;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the launcher's lifecycle contract (point 5): {@code timedOut} is reported true ONLY for a real wait
 * timeout; any other Docker error surfaces as a failure (rethrown) and never as a timeout; and the container is
 * always removed.
 */
class ScannerLauncherTest {

    private static final String CID = "container-abc";

    private DockerClient docker;
    private StartContainerCmd startCmd;
    private WaitContainerResultCallback waitCallback;
    private RemoveContainerCmd removeCmd;
    private KillContainerCmd killCmd;
    private ScannerLauncher launcher;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);

        CreateContainerCmd createCmd = mock(CreateContainerCmd.class, inv -> {
            // Every fluent with*(...) returns the same cmd; exec() returns the response.
            if (inv.getMethod().getName().equals("exec")) {
                CreateContainerResponse resp = new CreateContainerResponse();
                resp.setId(CID);
                return resp;
            }
            return inv.getMock();
        });
        when(docker.createContainerCmd(anyString())).thenReturn(createCmd);

        startCmd = mock(StartContainerCmd.class);
        when(docker.startContainerCmd(CID)).thenReturn(startCmd);

        WaitContainerCmd waitCmd = mock(WaitContainerCmd.class);
        waitCallback = mock(WaitContainerResultCallback.class);
        when(docker.waitContainerCmd(CID)).thenReturn(waitCmd);
        when(waitCmd.start()).thenReturn(waitCallback);

        // captureLogs: drive the passed callback to completion immediately so it does not block.
        LogContainerCmd logCmd = mock(LogContainerCmd.class);
        when(docker.logContainerCmd(CID)).thenReturn(logCmd);
        when(logCmd.withStdOut(true)).thenReturn(logCmd);
        when(logCmd.withStdErr(true)).thenReturn(logCmd);
        when(logCmd.withTailAll()).thenReturn(logCmd);
        when(logCmd.exec(any())).thenAnswer(inv -> {
            ResultCallback<Frame> cb = inv.getArgument(0);
            cb.onComplete();
            return cb;
        });

        killCmd = mock(KillContainerCmd.class);
        when(docker.killContainerCmd(CID)).thenReturn(killCmd);
        removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(CID)).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        launcher = new ScannerLauncher(docker);
    }

    @Test
    void reportsTimedOutOnlyWhenTheWaitActuallyElapses() throws Exception {
        when(waitCallback.awaitCompletion(anyLong(), any())).thenReturn(false);

        ScannerLauncher.LaunchResult result = launcher.run(spec("1000:1000"));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.exitCode()).isNull();
        verify(killCmd).exec();     // a timeout kills the container
        verify(removeCmd).exec();   // and always removes it
    }

    @Test
    void returnsExitCodeAndNotTimedOutWhenTheContainerFinishes() throws Exception {
        when(waitCallback.awaitCompletion(anyLong(), any())).thenReturn(true);
        when(waitCallback.awaitStatusCode()).thenReturn(2);

        ScannerLauncher.LaunchResult result = launcher.run(spec("1000:1000"));

        assertThat(result.timedOut()).isFalse();
        assertThat(result.exitCode()).isEqualTo(2);
        verify(removeCmd).exec();
    }

    @Test
    void aDockerErrorIsRethrownAndNotReportedAsATimeout() {
        when(startCmd.exec()).thenThrow(new NotFoundException("boom"));

        assertThatThrownBy(() -> launcher.run(spec("1000:1000")))
                .isInstanceOf(NotFoundException.class);
        verify(removeCmd).exec();   // still removed on every path
    }

    @Test
    void refusesToRunAsRoot() {
        assertThatThrownBy(() -> launcher.run(spec("0:0")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("root");
    }

    private static ScannerLaunchSpec spec(String runAsUser) {
        return new ScannerLaunchSpec("scan-1", "img", "file:///src", "main", 1,
                Path.of("/out"), null, Path.of("/src"), "2g", 1L, 1.0, 256, true, runAsUser,
                Duration.ofMinutes(1));
    }
}
