package com.company.secscan.logging;

import java.util.regex.Pattern;

/**
 * Scrubs token-shaped strings from text before it is logged or stored (CLAUDE.md security rules: never log
 * tokens or secret values). Pure, so it is unit-testable without Spring. Conservative by design: it would
 * rather redact a harmless long token-like run (a hash, a base64 blob) than risk leaking a credential.
 */
public final class LogSanitizer {

    // GitLab personal/group access tokens, and any long base64/base64url/hex-ish run (24+ chars).
    private static final Pattern TOKEN_LIKE = Pattern.compile(
            "(?:glpat-[A-Za-z0-9_-]{6,})|(?:[A-Za-z0-9+/_-]{24,}={0,2})");

    private static final String REDACTED = "<redacted>";

    private LogSanitizer() {
    }

    /** Replaces token-shaped substrings with {@value #REDACTED}. Null-safe. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return TOKEN_LIKE.matcher(text).replaceAll(REDACTED);
    }

    /** Truncates to at most {@code maxChars} characters, appending an ellipsis marker when it had to cut. */
    public static String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.max(0, maxChars)) + "…[truncated]";
    }

    /** Scrubs first, then truncates — the order that guarantees no half-redacted token survives the cut. */
    public static String scrubAndTruncate(String text, int maxChars) {
        return truncate(scrub(text), maxChars);
    }
}
