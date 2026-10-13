package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Exercises the REAL {@code secret_preview} shell function in {@code scanner-image/secscan-lib.sh} (the code
 * the scanner container runs), proving the preview rule: reveal the first 4 characters only when the secret is
 * at least 12 characters long, otherwise fully mask as {@code ****}. Kept in Java so it runs with the suite.
 */
class MaskPreviewScriptTest {

    private static final Path LIB = locateLib();

    @Test
    void revealsFirstFourOnlyForSecretsAtLeastTwelveLong() throws Exception {
        assertThat(preview("AKIA3K7HB9QO2PX5TL4D")).isEqualTo("AKIA"); // 20 chars
        assertThat(preview("abcdefghijkl")).isEqualTo("abcd");          // exactly 12
    }

    @Test
    void masksShortSecretsEntirely() throws Exception {
        assertThat(preview("abcdefghijk")).isEqualTo("****"); // 11 chars → masked
        assertThat(preview("short")).isEqualTo("****");
        assertThat(preview("")).isEqualTo("****");
    }

    private static String preview(String secret) throws IOException, InterruptedException {
        // sh -c '<script>' sh <secret>  →  $1 is the secret inside the script.
        Process p = new ProcessBuilder(
                "sh", "-c", ". '" + LIB.toAbsolutePath() + "'; secret_preview \"$1\"", "sh", secret)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as("secret_preview output: %s", out).isZero();
        return out;
    }

    private static Path locateLib() {
        for (Path base : List.of(Path.of("scanner-image/secscan-lib.sh"),
                Path.of("../scanner-image/secscan-lib.sh"))) {
            if (Files.isRegularFile(base)) {
                return base;
            }
        }
        assumeTrue(false, "secscan-lib.sh not found from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }
}
