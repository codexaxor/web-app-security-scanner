package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.HostConfig;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Proves the scanner container is configured with the spec's hardening and that the token never leaks. */
class ScannerContainerConfigTest {

    private static ScannerLaunchSpec spec(boolean networkDisabled, Path tokenFile) {
        return spec(networkDisabled, tokenFile, null);
    }

    private static ScannerLaunchSpec spec(boolean networkDisabled, Path tokenFile, Path localSource) {
        return new ScannerLaunchSpec("scan-123", "secscan-scanner:test",
                "https://gitlab.example/ns/app.git", "main", 1,
                Path.of("/srv/secscan/scans/scan-123/out"), tokenFile, localSource,
                "2g", 4L * 1024 * 1024 * 1024, 2.0, 512, networkDisabled, "1000:1000",
                Duration.ofMinutes(30), "oauth2");
    }

    @Test
    void appliesContainerHardening() {
        HostConfig hc = ScannerContainerConfig.buildHostConfig(spec(false, null));

        assertThat(hc.getReadonlyRootfs()).isTrue();
        assertThat(hc.getCapDrop()).contains(Capability.ALL);
        assertThat(hc.getSecurityOpts()).contains("no-new-privileges");
        assertThat(hc.getPidsLimit()).isEqualTo(512L);
        assertThat(hc.getMemory()).isEqualTo(4L * 1024 * 1024 * 1024);
        // memorySwap == memory disables swap, so the memory limit is a hard ceiling.
        assertThat(hc.getMemorySwap()).isEqualTo(4L * 1024 * 1024 * 1024);
        assertThat(hc.getNanoCPUs()).isEqualTo(2_000_000_000L);
        assertThat(hc.getTmpFs().get("/work")).contains("noexec").contains("nosuid").contains("size=2g");
        // The SAST engine's exec-able tmpfs is isolated from /work (the repo is never on it) and is still
        // nosuid/nodev and size-capped (D-053 / D-004).
        // The SAST exec tmpfs is owned by the scan uid:gid and mode 0700 (not world-writable), nosuid/nodev,
        // size-capped (D-056 / review 0700 nit).
        assertThat(hc.getTmpFs().get("/opt/sast-exec"))
                .contains("rw,exec,").doesNotContain("noexec")
                .contains("nosuid").contains("nodev").contains("size=512m")
                .contains("mode=0700").contains("uid=1000").contains("gid=1000").doesNotContain("mode=1777");
    }

    @org.junit.jupiter.api.Test
    void execMountFallsBackToWorldWritableWhenUserIsNotANumericPair() {
        assertThat(ScannerContainerConfig.execMountOpts("1000:1000"))
                .contains("mode=0700").contains("uid=1000").contains("gid=1000");
        assertThat(ScannerContainerConfig.execMountOpts("root")).contains("mode=1777").doesNotContain("uid=");
        assertThat(ScannerContainerConfig.execMountOpts(null)).contains("mode=1777");
    }

    @Test
    void mountsOutReadWriteAndNeverTheDockerSocket() {
        HostConfig hc = ScannerContainerConfig.buildHostConfig(spec(false, null));

        List<Bind> binds = List.of(hc.getBinds());
        assertThat(binds).anySatisfy(b -> {
            assertThat(b.getVolume().getPath()).isEqualTo("/out");
            assertThat(b.getAccessMode()).isEqualTo(AccessMode.rw);
        });
        assertThat(binds).noneSatisfy(b -> assertThat(b.getPath()).contains("docker.sock"));
        assertThat(binds).noneSatisfy(b -> assertThat(b.getVolume().getPath()).contains("docker.sock"));
    }

    @Test
    void mountsTokenFileReadOnlyAndKeepsItOutOfEnv() {
        Path tokenFile = Path.of("/srv/secscan/scans/scan-123/token");
        ScannerLaunchSpec s = spec(false, tokenFile);

        List<Bind> binds = List.of(ScannerContainerConfig.buildHostConfig(s).getBinds());
        assertThat(binds).anySatisfy(b -> {
            assertThat(b.getVolume().getPath()).isEqualTo("/run/secrets/gitlab-token");
            assertThat(b.getAccessMode()).isEqualTo(AccessMode.ro);
        });

        List<String> env = ScannerContainerConfig.buildEnv(s);
        assertThat(env).containsExactlyInAnyOrder(
                "SCAN_CLONE_URL=https://gitlab.example/ns/app.git", "SCAN_BRANCH=main", "SCAN_CLONE_DEPTH=1",
                "SCAN_CLONE_USER=oauth2");
        assertThat(env).noneSatisfy(e -> assertThat(e.toLowerCase()).contains("token"));
    }

    @Test
    void buildEnvCarriesTheGitHubCloneAuthUserNeverTheToken() {
        ScannerLaunchSpec s = new ScannerLaunchSpec("scan-gh", "secscan-scanner:test",
                "https://github.com/octo/app.git", "main", 1, Path.of("/out"), Path.of("/t"), null,
                "2g", 1L, 1.0, 256, false, "1000:1000", Duration.ofMinutes(30), "x-access-token");
        List<String> env = ScannerContainerConfig.buildEnv(s);
        assertThat(env).contains("SCAN_CLONE_USER=x-access-token");
        assertThat(env).noneSatisfy(e -> assertThat(e.toLowerCase()).contains("token=")); // the user is not the token
    }

    @Test
    void mountsLocalSourceReadOnlyWhenSet() {
        Path src = Path.of("/srv/secscan/scans/scan-123/source");
        List<Bind> binds = List.of(ScannerContainerConfig.buildHostConfig(spec(true, null, src)).getBinds());
        assertThat(binds).anySatisfy(b -> {
            assertThat(b.getVolume().getPath()).isEqualTo("/src");
            assertThat(b.getAccessMode()).isEqualTo(AccessMode.ro);
        });
    }

    @Test
    void disablesNetworkForOfflineRuns() {
        assertThat(ScannerContainerConfig.buildHostConfig(spec(true, null)).getNetworkMode()).isEqualTo("none");
        assertThat(ScannerContainerConfig.buildHostConfig(spec(false, null)).getNetworkMode()).isEqualTo("bridge");
    }

    @Test
    void labelsIdentifyTheScan() {
        assertThat(ScannerContainerConfig.labels("scan-123"))
                .containsEntry("secscan.component", "scanner")
                .containsEntry("secscan.scan", "scan-123");
    }
}
