package com.company.secscan.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the CWE -> phrase lookup (D-048): first-id rule, unknown -> advisory-summary fallback. */
class CweDictionaryTest {

    private final CweDictionary cwe = CweDictionary.getInstance();

    @Test
    void resolvesAKnownCwe() {
        assertThat(cwe.lookup("CWE-94")).isEqualTo("Code injection can lead to arbitrary code execution");
    }

    @Test
    void singleCweIdYieldsItsPhrase() {
        assertThat(cwe.phrase(List.of("CWE-94"), null))
                .isEqualTo("Code injection can lead to arbitrary code execution");
    }

    @Test
    void multipleCweIdsPickTheFirstInArrayOrder() {
        // Change #4: pick the FIRST id in OSV order (not "worst", which is undefined for CWEs).
        assertThat(cwe.phrase(List.of("CWE-20", "CWE-502"), null))
                .isEqualTo("Improper input validation");
    }

    @Test
    void emptyListFallsBackToTheAdvisorySummary() {
        assertThat(cwe.phrase(List.of(), "Arbitrary code execution in Apache Commons Text"))
                .isEqualTo("Arbitrary code execution in Apache Commons Text");
        assertThat(cwe.phrase(null, "summary")).isEqualTo("summary");
    }

    @Test
    void unknownFirstIdFallsBackToTheAdvisorySummary() {
        assertThat(cwe.phrase(List.of("CWE-9999999"), "fallback summary")).isEqualTo("fallback summary");
    }

    @Test
    void resolvesDeserializationCweForTheNoCvssFinding() {
        // CVE-2015-6420 carries CWE-502.
        assertThat(cwe.lookup("CWE-502"))
                .isEqualTo("Deserialization of untrusted data can lead to remote code execution");
    }

    @Test
    void coversAtLeastTheFixtureCweCountWithHeadroom() {
        assertThat(cwe.size()).isGreaterThanOrEqualTo(57);
    }
}
