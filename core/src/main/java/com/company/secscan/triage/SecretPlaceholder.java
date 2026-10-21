package com.company.secscan.triage;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a secret finding is a placeholder, not a real credential — the
 * {@link ReasonCode#SECRET_PLACEHOLDER} auto triage (SPEC: {@code *.example} files; D-059). Pure, no I/O.
 *
 * <p><b>FILE PATH ONLY</b> (review B1). The decision is made solely from the finding's path shape; the masked
 * preview (the first 4 chars, or {@code ****} for a short value, D-027) is NEVER consulted. A preview is far too
 * weak a signal — {@code ****} is the mask for ANY short/real secret, and a real token can legitimately begin
 * {@code chan}/{@code exam}/{@code plac} — so deciding on it would false-ignore real secrets.
 *
 * <p>Deliberately narrow (review S7): only a {@code *.example} / {@code *.sample} / {@code *.template} file, or a
 * file inside an {@code example/} or {@code examples/} directory, is a placeholder. A real secret in, say,
 * {@code src/main/resources/templates/x.html} is NOT a placeholder.
 *
 * <p>Value-based detection (e.g. the literal {@code changeme}) is DEFERRED (review B1 / D-059 amendment): the raw
 * secret never leaves the scanner container (D-027), so if it is ever added it must be computed IN-CONTAINER and
 * only its boolean verdict may leave — the value must never reach the app.
 */
public final class SecretPlaceholder {

    /** Directory segments that mark an example location (narrowed to example/examples only, review S7). */
    private static final Set<String> PLACEHOLDER_SEGMENTS = Set.of("example", "examples");

    private SecretPlaceholder() {
    }

    /**
     * True when the file path marks this secret as a placeholder: a {@code *.example} / {@code *.sample} /
     * {@code *.template} file, or a file under an {@code example/}/{@code examples/} directory. Path only (B1).
     */
    public static boolean isPlaceholder(String filePath) {
        String path = PathExclusion.normalize(filePath);
        if (path.isEmpty()) {
            return false;
        }
        String[] segments = path.split("/");
        String last = segments[segments.length - 1].toLowerCase(Locale.ROOT);
        if (last.endsWith(".example") || last.endsWith(".sample") || last.endsWith(".template")) {
            return true;
        }
        for (String seg : segments) {
            if (PLACEHOLDER_SEGMENTS.contains(seg.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
