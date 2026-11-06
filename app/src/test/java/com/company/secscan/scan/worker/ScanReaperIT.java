package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Live-daemon proof for {@link ScanReaper}, the way the existing scanner ITs use Docker: it creates REAL stopped
 * scanner-labelled containers and REAL scan dirs on the host daemon, runs the reaper, and asserts it removes only
 * THIS instance's non-live leftovers — never a live scan's, never another instance's, never an unlabelled
 * pre-change one. Tagged {@code live-reaper} so it is excluded from the default build; run with:
 * {@code ./gradlew :app:test -PliveReaper --tests '*ScanReaperIT*'} (needs a Docker daemon and the scanner image).
 *
 * <p>Each run uses a unique instance id ({@code it-<random>}) and its own temp work root, so a running dev/smoke
 * stack never reaps this test's containers and this test never reaps theirs.
 */
@Tag("live-reaper")
class ScanReaperIT {

    private static final String IMAGE = new ScannerProperties().getImage();

    private DockerClient docker;
    private ScannerProperties props;
    private ActiveScans activeScans;
    private ScanReaper reaper;
    private Path workRoot;
    private final List<String> createdContainers = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(8)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(30))
                .build();
        docker = DockerClientImpl.getInstance(config, httpClient);

        workRoot = Files.createTempDirectory("secscan-reaper-it");
        props = new ScannerProperties();
        props.setInstanceId("it-" + UUID.randomUUID().toString().substring(0, 8));
        props.setWorkRoot(workRoot.toString());
        activeScans = new ActiveScans();
        reaper = new ScanReaper(docker, props, activeScans);
    }

    @AfterEach
    void tearDown() {
        for (String id : createdContainers) {
            try {
                docker.removeContainerCmd(id).withForce(true).exec();
            } catch (RuntimeException ignored) {
                // already gone (reaped or never created)
            }
        }
        deleteRecursively(workRoot);
        try {
            docker.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    @Test
    void reapsOnlyThisInstancesNonLiveLeftovers() throws IOException {
        String leftover = UUID.randomUUID().toString();
        String live = UUID.randomUUID().toString();
        String foreign = UUID.randomUUID().toString();
        String noInstance = UUID.randomUUID().toString();

        // (a) leftover: THIS instance, not live, with a dir + token file → must be removed.
        String leftoverId = createContainer(ScannerContainerConfig.labels(leftover, props.getInstanceId()));
        Path leftoverDir = Files.createDirectories(workRoot.resolve(leftover));
        Files.writeString(leftoverDir.resolve("token"), "super-secret-token");

        // (b) live: THIS instance, registered live, with a dir → must be kept.
        activeScans.markActive(live);
        String liveId = createContainer(ScannerContainerConfig.labels(live, props.getInstanceId()));
        Path liveDir = Files.createDirectories(workRoot.resolve(live));

        // (c) foreign: a DIFFERENT instance id → must be kept.
        String foreignId = createContainer(ScannerContainerConfig.labels(foreign, "it-other-instance"));

        // (d) no instance label (pre-change) → counted only, never removed.
        String noInstanceId = createContainer(
                Map.of("secscan.component", "scanner", "secscan.scan", noInstance));

        ScanReaper.ReapResult result = reaper.reap();

        assertThat(result.containersRemoved()).isEqualTo(1);
        assertThat(result.dirsRemoved()).isEqualTo(1);
        assertThat(exists(leftoverId)).as("this instance's non-live container is removed").isFalse();
        assertThat(Files.exists(leftoverDir)).as("its dir + token file are removed").isFalse();
        assertThat(exists(liveId)).as("a live scan's container is kept").isTrue();
        assertThat(Files.isDirectory(liveDir)).as("a live scan's dir is kept").isTrue();
        assertThat(exists(foreignId)).as("another instance's container is kept").isTrue();
        assertThat(exists(noInstanceId)).as("an unlabelled pre-change container is kept").isTrue();
    }

    private String createContainer(Map<String, String> labels) {
        String id = docker.createContainerCmd(IMAGE).withLabels(labels).exec().getId();
        createdContainers.add(id);
        return id;
    }

    private boolean exists(String id) {
        try {
            docker.inspectContainerCmd(id).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
