package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.triage.IgnoreMatcher.Finding;
import com.company.secscan.triage.IgnoreMatcher.Rule;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IgnoreMatcherTest {

    private static final UUID REPO_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID REPO_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    private static Finding sca(String fingerprint, String vulnId, List<String> aliases) {
        return new Finding("sca", REPO_A, fingerprint, "sca|commons-text", "pom.xml", null, vulnId, aliases);
    }

    private static Finding sast(String fingerprint, String ruleId, String filePath) {
        return new Finding("sast", REPO_A, fingerprint, "sast|" + ruleId, filePath, ruleId, null, null);
    }

    @Test
    void subIssueScopeMatchesFingerprintForNonSast() {
        Finding f = sca("fp-1", "CVE-2022-42889", List.of());
        assertThat(IgnoreMatcher.matches(new Rule("subissue", null, "fp-1"), f)).isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("subissue", null, "fp-other"), f)).isFalse();
    }

    @Test
    void sastSubIssueScopeMatchesRuleAndFileSnippetIndependent() {
        // D-062: the match value is rule|file; a re-fingerprinted finding (new snippet hash → new fingerprint)
        // for the same rule+file still matches.
        Finding original = sast("sast-fp-old", "opengrep-rules.custom.spring-csrf-disabled", "src/Security.java");
        String value = IgnoreMatcher.subIssueMatchValue(original);
        assertThat(value).isEqualTo("opengrep-rules.custom.spring-csrf-disabled|src/Security.java");

        Finding reFingerprinted = sast("sast-fp-NEW", "opengrep-rules.custom.spring-csrf-disabled", "src/Security.java");
        assertThat(IgnoreMatcher.matches(new Rule("subissue", null, value), reFingerprinted)).isTrue();

        Finding differentFile = sast("x", "opengrep-rules.custom.spring-csrf-disabled", "src/Other.java");
        assertThat(IgnoreMatcher.matches(new Rule("subissue", null, value), differentFile)).isFalse();
    }

    @Test
    void groupScopeMatchesGroupKey() {
        Finding f = sca("fp", "CVE-1", List.of());
        assertThat(IgnoreMatcher.matches(new Rule("group", null, "sca|commons-text"), f)).isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("group", null, "sca|other"), f)).isFalse();
    }

    @Test
    void pathScopeMatchesPrefix() {
        Finding f = sast("fp", "r", "src/test/java/FooTest.java");
        assertThat(IgnoreMatcher.matches(new Rule("path", null, "src/test"), f)).isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("path", null, "src/main"), f)).isFalse();
    }

    @Test
    void cveScopeMatchesVulnIdOrAliasCaseInsensitively() {
        Finding f = sca("fp", "GHSA-xxxx", List.of("CVE-2022-42889"));
        assertThat(IgnoreMatcher.matches(new Rule("cve", null, "CVE-2022-42889"), f)).as("alias").isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("cve", null, "cve-2022-42889"), f)).as("case").isTrue();
        Finding g = sca("fp2", "CVE-2021-1", List.of());
        assertThat(IgnoreMatcher.matches(new Rule("cve", null, "CVE-2021-1"), g)).as("vuln id").isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("cve", null, "CVE-9999-9"), g)).isFalse();
    }

    @Test
    void ruleScopeMatchesRuleIdAcrossFiles() {
        Finding f = sast("fp", "opengrep-rules.custom.x", "any/File.java");
        assertThat(IgnoreMatcher.matches(new Rule("rule", null, "opengrep-rules.custom.x"), f)).isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("rule", null, "opengrep-rules.custom.y"), f)).isFalse();
    }

    @Test
    void globalRuleAppliesToAnyRepositoryButPerRepoRuleOnlyToItsOwn() {
        Finding f = sca("fp", "CVE-1", List.of()); // in REPO_A
        assertThat(IgnoreMatcher.matches(new Rule("subissue", null, "fp"), f)).as("global").isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("subissue", REPO_A, "fp"), f)).as("same repo").isTrue();
        assertThat(IgnoreMatcher.matches(new Rule("subissue", REPO_B, "fp"), f)).as("other repo").isFalse();
    }
}
