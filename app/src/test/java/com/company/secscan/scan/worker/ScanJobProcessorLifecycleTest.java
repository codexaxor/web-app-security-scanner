package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabHostPolicy;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryConnectionGuard;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanJob;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.parse.ScannerOutputParser;
import com.company.secscan.source.ProviderRegistry;
import com.company.secscan.source.SourceCredentials;
import com.github.dockerjava.api.exception.NotFoundException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * {@link ScanJobProcessor}'s workspace / live-registration lifecycle against a stubbed launcher (no Docker, no DB):
 * an offline file:// fixture connection so no token, host check or GitLab call is involved.
 */
class ScanJobProcessorLifecycleTest {

    static final String INSTANCE = "inst-L";

    @TempDir
    Path workRoot;

    final UUID workspaceId = UUID.randomUUID();
    final UUID jobId = UUID.randomUUID();
    final UUID scanId = UUID.randomUUID();
    final UUID repoId = UUID.randomUUID();
    final UUID connId = UUID.randomUUID();

    ScanStateService state;
    ScannerLauncher launcher;
    ActiveScans activeScans;
    ScanJobProcessor processor;
    /** Whether the scan dir still existed when the processor released the scan (must be false). */
    final AtomicBoolean dirExistedAtRelease = new AtomicBoolean(true);

    @BeforeEach
    void setUp() {
        ScanJobRepository jobs = mock(ScanJobRepository.class);
        ScanRepository scans = mock(ScanRepository.class);
        RepositoryRepository repositories = mock(RepositoryRepository.class);
        GitlabConnectionRepository connections = mock(GitlabConnectionRepository.class);

        ScanJob job = mock(ScanJob.class);
        when(job.getId()).thenReturn(jobId);
        when(job.getWorkspaceId()).thenReturn(workspaceId);
        when(job.getScanId()).thenReturn(scanId);
        Scan scan = mock(Scan.class);
        when(scan.getId()).thenReturn(scanId);
        when(scan.getRepositoryId()).thenReturn(repoId);
        Repository repo = mock(Repository.class);
        when(repo.getId()).thenReturn(repoId);
        when(repo.getConnectionId()).thenReturn(connId);
        when(repo.getDefaultBranch()).thenReturn("main");
        when(repo.getProvider()).thenReturn("gitlab");
        GitlabConnection conn = mock(GitlabConnection.class);
        when(conn.getId()).thenReturn(connId);
        when(conn.getBaseUrl()).thenReturn(workRoot.resolve("fixture-src").toUri().toString());

        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(scans.findByIdAndWorkspaceId(scanId, workspaceId)).thenReturn(Optional.of(scan));
        when(repositories.findByIdAndWorkspaceId(repoId, workspaceId)).thenReturn(Optional.of(repo));
        when(connections.findByIdAndWorkspaceId(connId, workspaceId)).thenReturn(Optional.of(conn));

        ScannerProperties props = new ScannerProperties();
        props.setWorkRoot(workRoot.toString());
        props.setInstanceId(INSTANCE);
        props.setAllowLocalFileClone(true);

        state = mock(ScanStateService.class);
        launcher = mock(ScannerLauncher.class);
        activeScans = new ActiveScans() {
            @Override
            public synchronized void clear(String id) {
                // Record what the reaper could see at the instant the scan stops being live.
                dirExistedAtRelease.set(Files.exists(scanDir()));
                super.clear(id);
            }
        };
        processor = new ScanJobProcessor(scans, jobs, repositories, connections, mock(SourceCredentials.class),
                mock(GitLabClient.class), mock(RepositoryConnectionGuard.class), launcher,
                mock(ScannerOutputParser.class), state, props, mock(GitLabHostPolicy.class),
                mock(ProviderRegistry.class), activeScans);
    }

    Path scanDir() {
        return workRoot.resolve(INSTANCE).resolve(scanId.toString());
    }

    // --- review S1: a leftover dir of this scan ---------------------------------------------------------

    @Test
    void aLeftoverDirOfADeadRunIsReplacedAndTheScanRuns() throws Exception {
        Files.createDirectories(scanDir().resolve("out"));
        Files.writeString(scanDir().resolve("token"), "glpat-EXAMPLE-stale");
        when(launcher.run(any())).thenAnswer(inv -> {
            // The fresh workspace no longer carries the dead run's token file.
            assertThat(scanDir().resolve("token")).doesNotExist();
            return new ScannerLauncher.LaunchResult(0, false, "");
        });

        processor.process(jobId);

        verify(launcher).run(any());
        verify(state, never()).failPermanently(any(), any(), any(), anyString(), anyString());
        assertThat(scanDir()).doesNotExist();          // cleaned up after the run
        assertThat(activeScans.isActive(scanId.toString())).isFalse();
    }

    @Test
    void aDirHeldByAnotherLiveRunIsLeftAloneAndTheJobIsRetriedNotFailedPermanently() throws Exception {
        activeScans.markActive(scanId.toString());     // another run of this scan is still in flight
        Path marker = Files.writeString(Files.createDirectories(scanDir().resolve("out")).resolve("partial.json"),
                "{}");

        processor.process(jobId);

        verify(launcher, never()).run(any());
        verify(state).failOrRequeue(eq(workspaceId), eq(jobId), eq(scanId), anyString());
        verify(state, never()).failPermanently(any(), any(), any(), anyString(), anyString());
        assertThat(marker).exists();
        assertThat(activeScans.holders(scanId.toString())).as("only the other run's hold remains").isEqualTo(1);
    }

    // --- review S5: the scan is live for exactly the span of its dir + container ------------------------

    /** How the stubbed launcher ends. */
    enum LaunchOutcome { NORMAL_RETURN, TIMEOUT, RUNTIME_EXCEPTION, IMAGE_NOT_FOUND }

    @ParameterizedTest
    @EnumSource(LaunchOutcome.class)
    void scanIsLiveDuringLaunchAndReleasedAfterwards(LaunchOutcome outcome) {
        AtomicBoolean liveDuringRun = new AtomicBoolean();
        AtomicBoolean dirDuringRun = new AtomicBoolean();
        when(launcher.run(any())).thenAnswer(inv -> {
            liveDuringRun.set(activeScans.isActive(scanId.toString()));
            dirDuringRun.set(Files.isDirectory(scanDir()));
            return switch (outcome) {
                case NORMAL_RETURN -> new ScannerLauncher.LaunchResult(0, false, "");
                case TIMEOUT -> new ScannerLauncher.LaunchResult(null, true, "");
                case RUNTIME_EXCEPTION -> throw new IllegalArgumentException("boom");
                case IMAGE_NOT_FOUND -> throw new NotFoundException("no such image");
            };
        });

        processor.process(jobId);

        verify(launcher).run(any());
        assertThat(liveDuringRun).as("live while the container runs").isTrue();
        assertThat(dirDuringRun).as("the workspace existed while live").isTrue();
        assertThat(activeScans.isActive(scanId.toString())).as("released afterwards").isFalse();
        assertThat(dirExistedAtRelease).as("workspace already gone at the moment of release").isFalse();
        assertThat(scanDir()).doesNotExist();
    }

    @Test
    void scanIsReleasedEvenWhenAnExceptionEscapesProcess() {
        doThrow(new IllegalStateException("db down")).when(state).markRunning(workspaceId, scanId);

        assertThatThrownBy(() -> processor.process(jobId)).isInstanceOf(IllegalStateException.class);

        assertThat(activeScans.isActive(scanId.toString())).isFalse();
        verify(launcher, never()).run(any());
    }
}
