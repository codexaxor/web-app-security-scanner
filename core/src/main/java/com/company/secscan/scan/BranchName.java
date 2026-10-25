package com.company.secscan.scan;

/**
 * Validates a git branch name (a branch shorthand, not a full {@code refs/heads/...} ref) using git's own
 * {@code git check-ref-format --branch} rules (D-076, amendment A1/A2). This is a cheap structural safety gate; the
 * AUTHORITATIVE check that a branch may be scanned is "it exists in GitLab for this project" (exact match against
 * the branches API). We therefore reject only what git itself would reject, plus a few hardening extras — we do NOT
 * reject otherwise-valid characters such as {@code + # , _ @} (except the sequence {@code @{}) or unicode letters,
 * so any branch GitLab lists can be chosen.
 *
 * <p>Safety does not depend on the character set (A2): the branch only ever reaches git as a single {@code
 * --branch=<name>} argv element, never a shell string, URL, log line or file path. So a structurally valid name
 * like {@code $(id)} or {@code ;rm} is accepted here (git allows those bytes) and is harmless.
 *
 * <p>git's rules, applied to a branch shorthand:
 * <ul>
 *   <li>not empty;</li>
 *   <li>no slash-separated component begins with {@code .} or ends with {@code .lock};</li>
 *   <li>no {@code ..}; no ASCII control char (&lt; 0x20), DEL (0x7f), space, or any of {@code ~ ^ : ? * [ \};</li>
 *   <li>cannot begin or end with {@code /}, cannot contain {@code //}, cannot end with {@code .};</li>
 *   <li>cannot contain {@code @{}; cannot be the single char {@code @};</li>
 *   <li>(for {@code --branch}) cannot begin with {@code -}.</li>
 * </ul>
 *
 * <p>Hardening extras (A1): reject a leading {@code -}, NUL / newline / carriage-return (already covered by the
 * control-char rule) and any name longer than {@value #MAX_LENGTH} <em>Unicode code points</em> (N-1 — matched to
 * busybox's character-counting {@code ${#}} in the scanner image, not UTF-16 units or bytes).
 *
 * <p>Pure (no Spring, no I/O).
 */
public final class BranchName {

    /** Maximum accepted length, in Unicode code points (A1/N-1). */
    public static final int MAX_LENGTH = 255;

    private BranchName() {
    }

    /** True if {@code name} is a branch name git would accept as a {@code --branch} shorthand (plus A1 extras). */
    public static boolean isValid(String name) {
        if (name == null || name.isEmpty() || name.codePointCount(0, name.length()) > MAX_LENGTH) {
            return false;   // length in code points (N-1), to match busybox ${#} in the scanner image
        }
        if (name.charAt(0) == '-') {           // A1: would look like a git option
            return false;
        }
        if (name.startsWith("/") || name.endsWith("/") || name.endsWith(".")) {
            return false;
        }
        if (name.equals("@")) {
            return false;
        }
        if (name.contains("..") || name.contains("//") || name.contains("@{")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f) {       // control chars incl. NUL, \n, \r, \t; and DEL
                return false;
            }
            switch (c) {
                case ' ', '~', '^', ':', '?', '*', '[', '\\' -> {
                    return false;
                }
                default -> {
                    // allowed, including + # , _ @ and unicode letters
                }
            }
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.charAt(0) == '.' || component.endsWith(".lock")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code name} if valid, else throws {@link IllegalArgumentException}. The message is deliberately
     * generic (it does not echo the rejected value) so nothing odd a caller passed is logged verbatim.
     */
    public static String requireValid(String name) {
        if (!isValid(name)) {
            throw new IllegalArgumentException("Invalid branch name");
        }
        return name;
    }
}
