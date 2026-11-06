package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-scan workspace: tight directory/token permissions and full cleanup (so a token never outlives a scan). */
class ScanWorkspaceTest {

    @Test
    void createsPrivateDirsAndTokenFileThenCleansUp(@TempDir Path workRoot) throws Exception {
        Path tokenFile;
        Path root;
        try (ScanWorkspace ws = ScanWorkspace.create(workRoot, "inst-1", "scan-abc")) {
            root = ws.root();
            // D-102 layout: <workRoot>/<instanceId>/<scanId>, the instance sub-tree created on demand (0700).
            assertThat(root).isEqualTo(workRoot.toAbsolutePath().normalize().resolve("inst-1").resolve("scan-abc"));
            assertThat(Files.getPosixFilePermissions(root.getParent()))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.getPosixFilePermissions(ws.root()))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.isDirectory(ws.outDir())).isTrue();

            tokenFile = ws.writeToken("glpat-EXAMPLE-not-real");
            assertThat(Files.getPosixFilePermissions(tokenFile))
                    .isEqualTo(PosixFilePermissions.fromString("rw-------"));
            assertThat(Files.readString(tokenFile)).isEqualTo("glpat-EXAMPLE-not-real");
            assertThat(ws.ownerUserSpec()).matches("\\d+:\\d+");
        }
        // close() wiped everything, including the token file.
        assertThat(Files.exists(root)).isFalse();
        assertThat(Files.exists(tokenFile)).isFalse();
    }

    @Test
    void secondScanReusesTheExistingInstanceDir(@TempDir Path workRoot) {
        try (ScanWorkspace a = ScanWorkspace.create(workRoot, "inst-1", "scan-a");
             ScanWorkspace b = ScanWorkspace.create(workRoot, "inst-1", "scan-b")) {
            assertThat(a.root().getParent()).isEqualTo(b.root().getParent());
            assertThat(Files.isDirectory(a.outDir())).isTrue();
            assertThat(Files.isDirectory(b.outDir())).isTrue();
        }
    }

    @Test
    void rejectsAnInstanceIdThatIsNotOnePlainPathSegment(@TempDir Path workRoot) {
        for (String bad : new String[] {"..", ".", "a/b", "../x", "", "a b"}) {
            assertThatThrownBy(() -> ScanWorkspace.instanceRoot(workRoot, bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ScanWorkspace.instanceRoot(workRoot, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replacesALeftoverDirOfADeadRunIncludingItsTokenFile(@TempDir Path workRoot) throws Exception {
        Path leftover = Files.createDirectories(workRoot.resolve("inst-1").resolve("scan-x").resolve("out"))
                .getParent();
        Files.writeString(leftover.resolve("token"), "glpat-EXAMPLE-stale");
        Files.writeString(leftover.resolve("out").resolve("result.json"), "{\"stale\":true}");

        try (ScanWorkspace ws = ScanWorkspace.create(workRoot, "inst-1", "scan-x", true)) {
            assertThat(ws.root()).isEqualTo(leftover.toAbsolutePath().normalize());
            assertThat(Files.exists(ws.root().resolve("token"))).as("stale token gone").isFalse();
            assertThat(Files.exists(ws.outDir().resolve("result.json"))).as("stale output gone").isFalse();
            assertThat(Files.getPosixFilePermissions(ws.root()))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void refusesAnExistingDirWhenNotAllowedToReplaceItAndLeavesItUntouched(@TempDir Path workRoot)
            throws Exception {
        Path held = Files.createDirectories(workRoot.resolve("inst-1").resolve("scan-y").resolve("out"));
        Path marker = Files.writeString(held.resolve("partial.json"), "{}");

        assertThatThrownBy(() -> ScanWorkspace.create(workRoot, "inst-1", "scan-y", false))
                .isInstanceOf(ScanWorkspace.WorkspaceBusyException.class);
        assertThat(marker).exists();
    }
}
