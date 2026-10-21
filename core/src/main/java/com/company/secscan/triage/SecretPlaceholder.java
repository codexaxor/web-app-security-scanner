package com.company.secscan.triage;

import java.util.Locale;
import java.util.Set;

/**
 * Decides whether a secret finding is a placeholder, not a real credential — the
 * {@link ReasonCode#SECRET_PLACEHOLDER} auto triage (SPEC: {@code changeme}, {@code example}, {@code *.example}
 * files; D-059). Pure, no I/O.
 *
 * <p>The raw secret never leaves the scanner container (D-027), so detection uses only what the finding carries:
 * the file path and the masked preview (the first 4 characters, or {@code ****} for a short value). Deliberately
 * conservative — a false placeholder match would hide a real secret — so only well-known placeholder file shapes
 * and preview prefixes are matched.
 */
public final class SecretPlaceholder {

    /** Path segments that mark an example/sample/template location. */
    private static final Set<String> PLACEHOLDER_SEGMENTS =
            Set.of("example", "examples", "sample", "samples", "template", "templates");

    /** Masked-preview prefixes (first 4 chars, lower-cased) of common placeholder values. */
    private static final Set<String> PLACEHOLDER_PREVIEWS =
            Set.of("chan" /* changeme */, "exam" /* example */, "plac" /* placeholder */, "****" /* masked short */);

    private SecretPlaceholder() {
    }

    /** True when the file path OR the masked preview marks this secret as a placeholder. */
    public static boolean isPlaceholder(String filePath, String preview) {
        return isPlaceholderFile(filePath) || isPlaceholderPreview(preview);
    }

    /** True when the file is an {@code *.example} file or sits in an example/sample/template location. */
    public static boolean isPlaceholderFile(String filePath) {
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

    /** True when the masked preview is a known placeholder prefix. */
    public static boolean isPlaceholderPreview(String preview) {
        if (preview == null || preview.isBlank()) {
            return false;
        }
        return PLACEHOLDER_PREVIEWS.contains(preview.toLowerCase(Locale.ROOT));
    }
}
