package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Static guard over the REAL {@code scanner-image/run-scan.sh}: the OSV-Scanner invocation must carry the
 * pinned {@code --config} (D-037) so an in-repo {@code osv-scanner.toml} cannot suppress or downgrade a
 * vulnerability. Runs in the DEFAULT build (no network), so a dropped flag fails the build here —
 * {@code OsvConfigInjectionIT} (live-osv) proves the behaviour, this proves the flag is present.
 */
class OsvCommandTest {

    private static final String SCRIPT = readScript();

    @Test
    void osvScanIsPassedThePinnedConfig() {
        assumeTrue(SCRIPT != null, "run-scan.sh not found");

        int call = SCRIPT.indexOf("osv-scanner scan source");
        assertThat(call).as("osv-scanner scan source invocation present").isGreaterThanOrEqualTo(0);
        String invocation = SCRIPT.substring(call, SCRIPT.indexOf("OSV_EXIT=$?", call));

        assertThat(invocation).contains("--config").contains("$OSV_CONFIG");
        // The pinned config path is defined and points at the baked file.
        assertThat(SCRIPT).contains("OSV_CONFIG=/etc/secscan/osv-scanner.toml");
    }

    private static String readScript() {
        for (Path base : List.of(Path.of("scanner-image/run-scan.sh"), Path.of("../scanner-image/run-scan.sh"))) {
            if (Files.isRegularFile(base)) {
                try {
                    return Files.readString(base);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
        return null;
    }
}
