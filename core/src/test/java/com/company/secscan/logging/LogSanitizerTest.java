package com.company.secscan.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogSanitizerTest {

    @Test
    void redactsGitlabTokens() {
        String scrubbed = LogSanitizer.scrub("cloning with glpat-ABCdef123456XYZ failed");
        assertThat(scrubbed).doesNotContain("glpat-ABCdef123456XYZ").contains("<redacted>");
    }

    @Test
    void redactsLongBase64AndHexRuns() {
        String basic = "Authorization: Basic b2F1dGgyOmdscGF0LXNlY3JldC12YWx1ZS1oZXJl";
        assertThat(LogSanitizer.scrub(basic)).doesNotContain("b2F1dGgyOmdscGF0").contains("<redacted>");

        String hash = "secretHash=91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d";
        assertThat(LogSanitizer.scrub(hash)).contains("<redacted>");
    }

    @Test
    void keepsOrdinaryShortText() {
        String msg = "clone failed (exit 128): repository not found";
        assertThat(LogSanitizer.scrub(msg)).isEqualTo(msg);
    }

    @Test
    void truncateScrubsBeforeCutting() {
        String token = "glpat-ABCdef123456XYZ";
        String out = LogSanitizer.scrubAndTruncate(token + " tail", 8);
        assertThat(out).doesNotContain(token);
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertThat(LogSanitizer.scrub(null)).isNull();
        assertThat(LogSanitizer.truncate(null, 10)).isNull();
        assertThat(LogSanitizer.scrub("")).isEmpty();
    }
}
