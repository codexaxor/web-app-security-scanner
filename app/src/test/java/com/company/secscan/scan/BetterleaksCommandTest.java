package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Static guard over the REAL {@code scanner-image/run-scan.sh}: the Betterleaks invocation must carry the
 * config-lockdown flags (D-037) and must NEVER carry a validation flag (D-036) — validation is OFF so no live
 * provider calls are made, and this test fails the build if {@code --validation} is ever added to the command.
 */
class BetterleaksCommandTest {

    private static final String SCRIPT = readScript();

    @Test
    void secretScanIsLockedDownAndNeverEnablesValidation() {
        assumeTrue(SCRIPT != null, "run-scan.sh not found");

        int call = SCRIPT.indexOf("betterleaks dir \"$CHECKOUT\"");
        assertThat(call).as("betterleaks dir invocation present").isGreaterThanOrEqualTo(0);
        // The invocation and its backslash-continued flag lines, up to the exit-code capture.
        String invocation = SCRIPT.substring(call, SCRIPT.indexOf("GL_EXIT=$?", call));

        // Config lockdown (D-037): pinned config, ignore inline allow comments, baked empty ignore file.
        assertThat(invocation).contains("--config").contains("$BL_CONFIG");
        assertThat(invocation).contains("--ignore-gitleaks-allow");
        assertThat(invocation).contains("--gitleaks-ignore-path").contains("$BL_IGNORE");
        // Distinguish "leaks found" (2) from a real error (1).
        assertThat(invocation).contains("--exit-code 2");

        // Validation OFF (D-036): no --validation in the invocation, and none anywhere in the EXECUTABLE lines
        // (comment lines, which legitimately explain that we never pass it, are stripped first).
        assertThat(invocation).doesNotContain("--validation");
        String codeOnly = SCRIPT.lines()
                .filter(l -> !l.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(codeOnly).doesNotContain("--validation");
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
