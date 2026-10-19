package com.company.secscan.scoring;

import java.util.Locale;

/**
 * Recognizes repository-relative paths that live under test code, for the SAST test-path −15 adjustment (SPEC,
 * Default On; M6). A finding in test code is lower priority but NOT excluded — this is a score adjustment, not
 * an exclusion (that is M7's {@code EXCLUDED_PATH} auto-triage). Pure and unit-testable.
 *
 * <p>Matches the common conventions across our ecosystems: any {@code test}/{@code tests} directory segment
 * ({@code src/test/**}, {@code **}/test/**, {@code **}/tests/**), and the usual test-file basenames
 * ({@code *Test.java}/{@code *Tests.java}, {@code *Test.cs}/{@code *Tests.cs}, {@code *.spec.*},
 * {@code *.test.*}, {@code *_test.*}, {@code *_spec.*}).
 */
public final class TestPaths {

    private TestPaths() {
    }

    public static boolean isTestPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String p = path.replace('\\', '/');
        String[] segments = p.split("/");
        for (int i = 0; i < segments.length - 1; i++) { // directory segments only
            String seg = segments[i].toLowerCase(Locale.ROOT);
            if (seg.equals("test") || seg.equals("tests")) {
                return true;
            }
        }
        String base = segments[segments.length - 1];
        String lower = base.toLowerCase(Locale.ROOT);
        return base.endsWith("Test.java") || base.endsWith("Tests.java")
                || base.endsWith("Test.cs") || base.endsWith("Tests.cs")
                || lower.contains(".spec.") || lower.contains(".test.")
                || lower.contains("_test.") || lower.contains("_spec.");
    }
}
