package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PathExclusionTest {

    @Test
    void defaultDirsAreExcludedAtAnyDepth() {
        for (String dir : new String[] {"node_modules", "vendor", "dist", "build", "target", "bin", "obj"}) {
            assertThat(PathExclusion.isDefaultExcluded(dir + "/x/y.js")).as(dir + " at root").isTrue();
            assertThat(PathExclusion.isDefaultExcluded("a/b/" + dir + "/y.js")).as(dir + " nested").isTrue();
        }
    }

    @Test
    void minifiedFilesAreExcluded() {
        assertThat(PathExclusion.isDefaultExcluded("web/app.min.js")).isTrue();
        assertThat(PathExclusion.isDefaultExcluded("web/styles.min.css")).isTrue();
        assertThat(PathExclusion.isDefaultExcluded("web/app.js")).isFalse();
    }

    @Test
    void sourcePathsAreNotExcludedByDefault() {
        assertThat(PathExclusion.isDefaultExcluded("src/main/java/App.java")).isFalse();
        assertThat(PathExclusion.isDefaultExcluded("rebuilder/Main.java")).as("substring, not a segment").isFalse();
    }

    @Test
    void extraPrefixesFromConfigAreExcluded() {
        List<String> extra = List.of("src/test/fixtures", "docs/examples");
        assertThat(PathExclusion.isExcluded("src/test/fixtures/Vuln.java", extra)).isTrue();
        assertThat(PathExclusion.isExcluded("src/test/fixtures", extra)).as("exact prefix").isTrue();
        assertThat(PathExclusion.isExcluded("docs/examples/a/b.yml", extra)).isTrue();
        assertThat(PathExclusion.isExcluded("src/main/App.java", extra)).isFalse();
        assertThat(PathExclusion.isExcluded("src/test/fixturesX/App.java", extra)).as("not a path boundary").isFalse();
    }

    @Test
    void normalizationHandlesLeadingSlashAndBackslashAndDotSlash() {
        assertThat(PathExclusion.isDefaultExcluded("/build/x.js")).isTrue();
        assertThat(PathExclusion.isDefaultExcluded("./build/x.js")).isTrue();
        assertThat(PathExclusion.isDefaultExcluded("build\\x.min.js")).isTrue();
    }

    @Test
    void blankOrNullPathIsNeverExcluded() {
        assertThat(PathExclusion.isDefaultExcluded(null)).isFalse();
        assertThat(PathExclusion.isDefaultExcluded("")).isFalse();
    }

    @Test
    void withoutDefaultsOnlyExplicitPrefixesExcludeForSecrets() {
        // D-064: for secrets the default dirs + minified rules are skipped, but an explicit .secscan.yml
        // exclude.paths entry still applies. A leaked key in dist/ or a *.min.js is a real exposure.
        assertThat(PathExclusion.isExcluded("dist/app.js", List.of(), false)).as("default dir skipped").isFalse();
        assertThat(PathExclusion.isExcluded("web/app.min.js", List.of(), false)).as("minified skipped").isFalse();
        assertThat(PathExclusion.isExcluded("dist/app.js", List.of("dist"), false))
                .as("explicit prefix still applies").isTrue();
        // Defaults still fire for non-secret findings (applyDefaults = true).
        assertThat(PathExclusion.isExcluded("dist/app.js", List.of(), true)).isTrue();
    }
}
