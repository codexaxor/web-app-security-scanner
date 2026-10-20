package com.company.secscan.eol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the detected cycle is normalized to the feed's own cycle key per product (M6 review BUG 2), generically
 * (match against the product's published cycles) and deterministically. In particular .NET's major.minor detect
 * ("8.0") resolves to the bare-major feed key ("8"), while Spring Boot / Alpine keys are unchanged.
 */
class EolCyclesTest {

    private static final List<String> DOTNET = List.of("5", "6", "7", "8", "9", "10");
    private static final List<String> SPRINGBOOT = List.of("2.1", "2.7", "3.0", "3.4");
    private static final List<String> ALPINE = List.of("3.19", "3.20", "3.21");

    @Test
    void dotnetMajorMinorResolvesToBareMajor() {
        assertThat(EolCycles.match("8.0", DOTNET)).isEqualTo("8");
        assertThat(EolCycles.match("8.0.11", DOTNET)).isEqualTo("8");
        assertThat(EolCycles.match("net6.0", DOTNET)).isEqualTo("6");
    }

    @Test
    void springBootAndAlpineKeysAreUnchanged() {
        assertThat(EolCycles.match("2.1", SPRINGBOOT)).isEqualTo("2.1");
        assertThat(EolCycles.match("2.1.0.RELEASE", SPRINGBOOT)).isEqualTo("2.1");
        assertThat(EolCycles.match("3.21", ALPINE)).isEqualTo("3.21");
        assertThat(EolCycles.match("3.21.2", ALPINE)).isEqualTo("3.21");
    }

    @Test
    void componentWiseMatchDoesNotConfuse2dot1With2dot10() {
        assertThat(EolCycles.match("2.10.1", List.of("2.1", "2.10"))).isEqualTo("2.10");
        assertThat(EolCycles.match("2.1.9", List.of("2.1", "2.10"))).isEqualTo("2.1");
    }

    @Test
    void mostSpecificPublishedCycleWins() {
        assertThat(EolCycles.match("8.0.1", List.of("8", "8.0"))).isEqualTo("8.0");
    }

    @Test
    void noMatchingPublishedCycleReturnsNull() {
        assertThat(EolCycles.match("8.0", List.of("9", "10"))).isNull();
        assertThat(EolCycles.match("8.0", List.of())).isNull();
        assertThat(EolCycles.match(null, DOTNET)).isNull();
    }
}
