package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SastLanguageCoverageTest {

    @Test
    void coveredLanguagesAreNeverReported() {
        assertThat(SastLanguageCoverage.uncovered(
                List.of("Java", "Kotlin", "Scala", "JavaScript", "TypeScript",
                        "Python", "Go", "PHP", "Ruby", "C#")))
                .isEmpty();
    }

    @Test
    void markupStyleShellAndIacAreNeverReported() {
        assertThat(SastLanguageCoverage.uncovered(
                List.of("HTML", "CSS", "SCSS", "Sass", "Less", "FreeMarker", "Shell", "Dockerfile", "HCL")))
                .isEmpty();
    }

    @Test
    void reportsSastEligibleLanguagesWithNoPack() {
        assertThat(SastLanguageCoverage.uncovered(List.of("Groovy", "Rust", "C", "C++", "Swift", "PowerShell")))
                .containsExactly("Groovy", "Rust", "C", "C++", "Swift", "PowerShell");
    }

    @Test
    void vueAndSvelteAreUncovered() {
        // M8.15 change #7: measured — JS/TS rules produce no findings on .vue/.svelte <script> (Opengrep 1.31.0).
        assertThat(SastLanguageCoverage.uncovered(List.of("Vue", "Svelte"))).containsExactly("Vue", "Svelte");
    }

    @Test
    void keepsDetectionOrderAndDeduplicates() {
        assertThat(SastLanguageCoverage.uncovered(List.of("Rust", "Java", "Rust", "Vue", "HTML")))
                .containsExactly("Rust", "Vue");
    }

    @Test
    void mixedRepoReportsOnlyTheUncoveredOnes() {
        // a Java/Kotlin service that also ships a Vue frontend and a Rust helper
        assertThat(SastLanguageCoverage.uncovered(
                List.of("Java", "Kotlin", "HTML", "CSS", "Vue", "Rust", "Shell")))
                .containsExactly("Vue", "Rust");
    }

    @Test
    void emptyOrNullDetectionReportsNothing() {
        assertThat(SastLanguageCoverage.uncovered(List.of())).isEmpty();
        assertThat(SastLanguageCoverage.uncovered(null)).isEmpty();
    }
}
