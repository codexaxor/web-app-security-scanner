package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D-076 amendment A1/A2: branch names are validated with git's own {@code check-ref-format --branch} rules, not a
 * narrow regex. Any character git allows is allowed (so every branch GitLab can list is selectable); safety comes
 * from passing the branch as an argv element, never a shell string (A2), so shell metacharacters that are
 * structurally valid git names are accepted here and are harmless.
 */
class BranchNameValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "main", "dev", "release/1.2", "feature/x-y_z",
            "feat+x", "fix#12", "_tmp", "ünicode",   // A1: +, #, leading _, unicode all allowed
            "a/b/c", "v1.0.0", "fix.lockfile"              // ".lock" only banned as a trailing component suffix
    })
    void accepts(String name) {
        assertThat(BranchName.isValid(name)).as(name).isTrue();
        assertThat(BranchName.requireValid(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "-x", "-u",            // leading dash
            "a..b",                // double dot
            "a//b", "/x", "x/",    // empty component / leading / trailing slash
            "x.lock",              // component ends with .lock
            "a@{b",                // @{
            "@",                   // the single char @
            "a b",                 // space
            "../x",                // leading component is "." + ".."
            ".hidden",             // component begins with .
            "a.",                  // ends with .
            "a~b", "a^b", "a:b", "a?b", "a*b", "a[b", "a\\b"  // git-forbidden characters
    })
    void rejects(String name) {
        assertThat(BranchName.isValid(name)).as(name).isFalse();
        assertThatThrownBy(() -> BranchName.requireValid(name)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmptyNullControlAndNewline() {
        assertThat(BranchName.isValid("")).isFalse();
        assertThat(BranchName.isValid(null)).isFalse();
        assertThat(BranchName.isValid("a\nb")).isFalse();
        assertThat(BranchName.isValid("a\u0000b")).isFalse();
        assertThat(BranchName.isValid("a\tb")).isFalse();
    }

    @Test
    void lengthBoundaryIs255() {
        assertThat(BranchName.isValid("a".repeat(201))).isTrue();     // 201 chars OK (≤ 255)
        assertThat(BranchName.isValid("a".repeat(255))).isTrue();
        assertThat(BranchName.isValid("a".repeat(256))).isFalse();    // > 255 rejected
    }

    @Test
    void shellMetacharNamesAreStructurallyValidAndHarmless() {
        // A2: safety is argv-not-shell, so these structurally valid git names are ACCEPTED (they can never reach a
        // shell). This supersedes the pre-amendment plan that rejected them on character grounds.
        assertThat(BranchName.isValid("$(id)")).isTrue();
        assertThat(BranchName.isValid("`id`")).isTrue();
        assertThat(BranchName.isValid(";rm")).isTrue();
    }
}
