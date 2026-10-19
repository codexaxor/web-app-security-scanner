package com.company.secscan.finding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Proves the SAST snippet hash is stable under cosmetic edits (so a reindent/reflow does not churn the
 * fingerprint) yet changes when the matched code changes — and carries no line number.
 */
class SnippetNormalizerTest {

    @Test
    void indentationAndTrailingWhitespaceDoNotChangeTheHash() {
        String a = "    http.csrf().disable();";
        String b = "http.csrf().disable();   ";
        String c = "\t\t\thttp.csrf().disable();"; // deeper indentation only
        assertThat(SnippetNormalizer.normalizedHash(a)).isEqualTo(SnippetNormalizer.normalizedHash(b));
        assertThat(SnippetNormalizer.normalizedHash(a)).isEqualTo(SnippetNormalizer.normalizedHash(c));
    }

    @Test
    void blankLinesAreDroppedButRealLinesAreKept() {
        String a = "if (x) {\n\n  doThing();\n}";
        String b = "if (x) {\n  doThing();\n}";
        assertThat(SnippetNormalizer.normalizedHash(a)).isEqualTo(SnippetNormalizer.normalizedHash(b));
    }

    @Test
    void aRealCodeChangeChangesTheHash() {
        assertThat(SnippetNormalizer.normalizedHash("http.csrf().disable();"))
                .isNotEqualTo(SnippetNormalizer.normalizedHash("http.csrf().enable();"));
    }

    @Test
    void nullOrBlankSnippetHashesTheEmptyStringNotNull() {
        assertThat(SnippetNormalizer.normalize(null)).isEmpty();
        assertThat(SnippetNormalizer.normalizedHash(null))
                .isEqualTo(SnippetNormalizer.normalizedHash("   \n  "));
    }
}
