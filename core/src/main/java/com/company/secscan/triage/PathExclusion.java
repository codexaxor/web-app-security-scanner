package com.company.secscan.triage;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a finding's file path is in an excluded location — the {@link ReasonCode#EXCLUDED_PATH} auto
 * triage (D-059). Two sources, both pure and line-free (a path is never a fingerprint input):
 * <ul>
 *   <li>SPEC default globs: the directory names {@code node_modules vendor dist build target bin obj} (matched as
 *       any path segment, at any depth) and minified files ({@code *.min.js} / {@code *.min.css});</li>
 *   <li>extra path prefixes from {@code .secscan.yml exclude.paths} (D-057), matched as repo-relative prefixes.</li>
 * </ul>
 * No Spring, no I/O. Paths are normalized with forward slashes and no leading {@code ./} or {@code /}.
 */
public final class PathExclusion {

    /** SPEC default excluded directory names (matched as a path segment at any depth). */
    public static final Set<String> DEFAULT_DIRS =
            Set.of("node_modules", "vendor", "dist", "build", "target", "bin", "obj");

    private PathExclusion() {
    }

    /** True when {@code filePath} is excluded by the SPEC defaults alone (no {@code .secscan.yml} paths). */
    public static boolean isDefaultExcluded(String filePath) {
        return isExcluded(filePath, List.of());
    }

    /**
     * True when {@code filePath} is excluded by the SPEC defaults OR by any of {@code extraPrefixes} (repo-relative
     * prefixes from {@code .secscan.yml}). A null/blank path is never excluded.
     */
    public static boolean isExcluded(String filePath, List<String> extraPrefixes) {
        String path = normalize(filePath);
        if (path.isEmpty()) {
            return false;
        }
        String[] segments = path.split("/");
        for (String seg : segments) {
            if (DEFAULT_DIRS.contains(seg)) {
                return true;
            }
        }
        String last = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (last.endsWith(".min.js") || last.endsWith(".min.css")) {
            return true;
        }
        if (extraPrefixes != null) {
            for (String raw : extraPrefixes) {
                String prefix = normalize(raw);
                if (prefix.isEmpty()) {
                    continue;
                }
                if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Forward slashes; strip a leading {@code ./} or {@code /} and any trailing {@code /}. */
    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim().replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/") && !p.isEmpty()) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }
}
