package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.model.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * M8.14 unit coverage for {@link ScanReaper}: a mocked Docker client proves the container-selection logic (both
 * scanner labels, this instance only, live scans spared, pre-change containers counted not removed) and a real
 * temp work root proves the dir logic (UUID-named direct children only, live dirs spared, token files gone,
 * symlinks never followed) — all offline, in the default build. The live daemon end-to-end proof is
 * {@code ScanReaperIT} ({@code -PliveReaper}).
 */
class ScanReaperTest {

    private static final String MY_INSTANCE = "inst-A";

    private DockerClient docker;
    private ScannerProperties props;
    private ActiveScans activeScans;
    private ScanReaper reaper;
    private RemoveContainerCmd removeCmd;
    private AtomicReference<Map<String, String>> removalFilter;

    @TempDir
    Path workRoot;
    /** This instance's sub-tree, {@code <workRoot>/<instanceId>} (D-102) — the only place the dir phase looks. */
    private Path instanceRoot;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        props = new ScannerProperties();
        props.setInstanceId(MY_INSTANCE);
        props.setWorkRoot(workRoot.toString());
        instanceRoot = workRoot.resolve(MY_INSTANCE);
        try {
            Files.createDirectories(instanceRoot);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        activeScans = new ActiveScans();
        reaper = new ScanReaper(docker, props, activeScans);

        removeCmd = mock(RemoveContainerCmd.class);
        when(docker.removeContainerCmd(anyString())).thenReturn(removeCmd);
        when(removeCmd.withForce(true)).thenReturn(removeCmd);

        removalFilter = new AtomicReference<>();
        // Default: both list calls (mine, then the legacy count) return nothing, so dir-only tests see no
        // containers. Container tests override via stubContainers(...).
        stubContainers(List.of(), List.of());
    }

    /** Stub the two sequential listContainersCmd() calls: first the both-label removal list, then the
     * component-only legacy-count list. Captures the removal list's label filter. */
    private void stubContainers(List<Container> mine, List<Container> allScanner) {
        // Build both cmd mocks BEFORE thenReturn — building a mock nested inside thenReturn's arguments is
        // itself stubbing, which Mockito rejects as unfinished stubbing.
        ListContainersCmd mineCmd = listCmd(mine, removalFilter);
        ListContainersCmd allCmd = listCmd(allScanner, null);
        when(docker.listContainersCmd()).thenReturn(mineCmd, allCmd);
    }

    private ListContainersCmd listCmd(List<Container> result, AtomicReference<Map<String, String>> filterSink) {
        ListContainersCmd cmd = mock(ListContainersCmd.class);
        when(cmd.withShowAll(true)).thenReturn(cmd);
        when(cmd.withLabelFilter(anyMap())).thenAnswer(inv -> {
            if (filterSink != null) {
                filterSink.set(inv.getArgument(0));
            }
            return cmd;
        });
        when(cmd.exec()).thenReturn(result);
        return cmd;
    }

    private static Container container(String id, Map<String, String> labels) {
        Container c = mock(Container.class);
        when(c.getId()).thenReturn(id);
        when(c.getLabels()).thenReturn(labels);
        return c;
    }

    private static Map<String, String> scannerLabels(String instanceId, String scanId) {
        return Map.of("secscan.component", "scanner", "secscan.instance", instanceId, "secscan.scan", scanId);
    }

    // --- containers ---------------------------------------------------------

    @Test
    void removesLeftoverLabelledContainerWhoseScanIsNotLive() {
        String scanId = UUID.randomUUID().toString();
        stubContainers(List.of(container("cid-1", scannerLabels(MY_INSTANCE, scanId))), List.of());

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.containersRemoved()).isEqualTo(1);
        verify(docker).removeContainerCmd("cid-1");
        verify(removeCmd).withForce(true);
        verify(removeCmd).exec();
    }

    @Test
    void keepsContainerForLiveScan() {
        String liveScan = UUID.randomUUID().toString();
        activeScans.markActive(liveScan);
        stubContainers(List.of(container("cid-live", scannerLabels(MY_INSTANCE, liveScan))), List.of());

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.containersRemoved()).isZero();
        verify(docker, never()).removeContainerCmd(anyString());
    }

    @Test
    void filtersRemovalByBothTheScannerAndThisInstanceLabel() {
        stubContainers(List.of(), List.of());

        reaper.reap();

        // The removal list is filtered daemon-side on BOTH labels, so a different instance's container (or an
        // unlabelled pre-change one) is never returned and so can never be removed.
        assertThat(removalFilter.get())
                .containsEntry("secscan.component", "scanner")
                .containsEntry("secscan.instance", MY_INSTANCE);
    }

    @Test
    void countsButNeverRemovesContainersWithoutAnInstanceLabel() {
        // The both-label removal list is empty (a pre-change container lacks the instance label, so the
        // daemon-side filter never returns it); the component-only list reports it for the count.
        Container legacy = container("cid-legacy",
                Map.of("secscan.component", "scanner", "secscan.scan", UUID.randomUUID().toString()));
        stubContainers(List.of(), List.of(legacy));

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.containersRemoved()).isZero();
        verify(docker, never()).removeContainerCmd(anyString());
    }

    @Test
    void skipsAContainerThatHasNoScanLabel() {
        stubContainers(List.of(container("cid-x",
                Map.of("secscan.component", "scanner", "secscan.instance", MY_INSTANCE))), List.of());

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.containersRemoved()).isZero();
        verify(docker, never()).removeContainerCmd(anyString());
    }

    // --- dirs ---------------------------------------------------------------

    @Test
    void removesStaleScanDirIncludingItsTokenFile() throws IOException {
        Path stale = instanceRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(stale.resolve("out"));
        Path token = Files.writeString(stale.resolve("token"), "super-secret-token-value");
        Files.writeString(stale.resolve("out").resolve("result.json"), "{}");

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
        assertThat(Files.exists(token)).isFalse();
    }

    @Test
    void keepsScanDirForLiveJob() throws IOException {
        String live = UUID.randomUUID().toString();
        activeScans.markActive(live);
        Path dir = instanceRoot.resolve(live);
        Files.createDirectories(dir.resolve("out"));

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isZero();
        assertThat(Files.isDirectory(dir)).isTrue();
    }

    @Test
    void ignoresDirsWhoseNameIsNotAScanId() throws IOException {
        Path notUuid = instanceRoot.resolve("betterleaks-cache");
        Files.createDirectories(notUuid);

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isZero();
        assertThat(Files.isDirectory(notUuid)).isTrue();
    }

    @Test
    void doesNotFollowASymlinkNamedLikeAScanDir() throws IOException {
        // A sentinel dir OUTSIDE the work root, holding a file that must survive.
        Path sentinel = Files.createDirectories(workRoot.resolve("reaper-sentinel-" + UUID.randomUUID()));
        Path sentinelFile = Files.writeString(sentinel.resolve("keep.txt"), "do not delete");
        // A symlink inside the work root whose NAME is a valid scan id, pointing at the sentinel dir.
        Path link = instanceRoot.resolve(UUID.randomUUID().toString());
        Files.createSymbolicLink(link, sentinel);

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isZero();
        assertThat(Files.isSymbolicLink(link)).isTrue();       // the link itself is left alone
        assertThat(Files.exists(sentinelFile)).isTrue();        // and nothing behind it was touched
    }

    @Test
    void neverTouchesOldLayoutDirsDirectlyUnderTheWorkRootNorAnotherInstancesSubTree() throws IOException {
        // Pre-D-102 layout: <workRoot>/<scanId> — left for a one-time manual cleanup, never reaped.
        Path oldLayout = Files.createDirectories(workRoot.resolve(UUID.randomUUID().toString()));
        Files.writeString(oldLayout.resolve("token"), "fake-old-token");
        // Another instance sharing the same work root: <workRoot>/<other>/<scanId>.
        Path foreign = Files.createDirectories(workRoot.resolve("inst-B").resolve(UUID.randomUUID().toString()));
        // And one genuine leftover of THIS instance, to prove the sweep did run.
        Path mine = Files.createDirectories(instanceRoot.resolve(UUID.randomUUID().toString()));

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isEqualTo(1);
        assertThat(Files.exists(mine)).isFalse();
        assertThat(Files.isDirectory(oldLayout)).isTrue();
        assertThat(Files.exists(oldLayout.resolve("token"))).isTrue();
        assertThat(Files.isDirectory(foreign)).isTrue();
    }

    @Test
    void aMissingInstanceSubTreeIsNotAFailure() throws IOException {
        Files.delete(instanceRoot);
        Path oldLayout = Files.createDirectories(workRoot.resolve(UUID.randomUUID().toString()));

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.dirsRemoved()).isZero();
        assertThat(Files.isDirectory(oldLayout)).isTrue();
    }

    // --- resilience ---------------------------------------------------------

    @Test
    void aContainerSweepFailureDoesNotStopTheReaperOrTheDirSweep() throws IOException {
        when(docker.listContainersCmd()).thenThrow(new RuntimeException("docker daemon unreachable"));
        Path stale = instanceRoot.resolve(UUID.randomUUID().toString());
        Files.createDirectories(stale);

        AtomicReference<ScanReaper.ReapResult> captured = new AtomicReference<>();
        // The reaper must swallow the Docker failure (the worker keeps running) yet still reap dirs.
        assertThatCode(() -> captured.set(reaper.reap())).doesNotThrowAnyException();

        assertThat(captured.get().containersRemoved()).isZero();
        assertThat(captured.get().dirsRemoved()).isEqualTo(1);
        assertThat(Files.exists(stale)).isFalse();
    }

    @Test
    void aMissingWorkRootIsNotAFailure() {
        props.setWorkRoot(workRoot.resolve("does-not-exist").toString());

        assertThatCode(() -> {
            ScanReaper.ReapResult result = reaper.reap();
            assertThat(result.dirsRemoved()).isZero();
        }).doesNotThrowAnyException();
    }
}
