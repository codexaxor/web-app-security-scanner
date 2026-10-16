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
 * Exercises the REAL {@code nuget_version_unresolved} shell classifier in
 * {@code scanner-image/secscan-lib.sh} (the code the scanner container runs for M4's UNRESOLVED_VERSION
 * warnings, D-031/D-035). A concrete pinned version is "resolved"; an MSBuild property, a version range, a
 * wildcard or an empty value is "unresolved" and must never be guessed. Offline — no Docker, no network.
 */
class NugetVersionScriptTest {

    private static final Path LIB = locateLib();

    @Test
    void concreteVersionsAreResolved() throws Exception {
        assertThat(unresolved("12.0.1")).isFalse();
        assertThat(unresolved("1.2.3-beta.2")).isFalse();
        assertThat(unresolved("8.0.0")).isFalse();
    }

    @Test
    void propertiesRangesWildcardsAndEmptyAreUnresolved() throws Exception {
        assertThat(unresolved("$(SerilogVersion)")).isTrue(); // MSBuild property
        assertThat(unresolved("[1.0,2.0)")).isTrue();          // range
        assertThat(unresolved("(1.0,)")).isTrue();             // range
        assertThat(unresolved("1.*")).isTrue();                // floating wildcard
        assertThat(unresolved("")).isTrue();                   // unset
    }

    /** Returns true when the shell classifier exits 0 (unresolved), false when it exits 1 (concrete). */
    private static boolean unresolved(String version) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(
                "sh", "-c", ". '" + LIB.toAbsolutePath() + "'; nuget_version_unresolved \"$1\"", "sh", version)
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("output: %s", out).isTrue();
        return p.exitValue() == 0;
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
