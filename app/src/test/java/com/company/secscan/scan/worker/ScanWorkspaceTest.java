package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;

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
        try (ScanWorkspace ws = ScanWorkspace.create(workRoot, "scan-abc")) {
            root = ws.root();
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
}
