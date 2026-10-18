package com.company.secscan.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for fix-time category selection (minor vs major semver) and config resolution (SPEC "Fix time"). */
class FixTimeTest {

    private static final Map<String, Integer> CONFIG = Map.of(
            FixTime.SCA_PATCH_MINOR, 15,
            FixTime.SCA_MAJOR, 60,
            FixTime.SECRET_ROTATION, 10,
            FixTime.DEFAULT, 20);

    @Test
    void minorUpgradeIsPatchMinorCategory() {
        assertThat(FixTime.category("sca", "1.9", "1.10.0")).isEqualTo(FixTime.SCA_PATCH_MINOR);
        assertThat(FixTime.category("sca", "3.11", "3.18.0")).isEqualTo(FixTime.SCA_PATCH_MINOR);
        assertThat(FixTime.minutesFor("sca", "1.9", "1.10.0", CONFIG)).isEqualTo(15);
    }

    @Test
    void majorUpgradeIsMajorCategory() {
        assertThat(FixTime.category("sca", "1.9", "2.0")).isEqualTo(FixTime.SCA_MAJOR);
        assertThat(FixTime.minutesFor("sca", "1.9", "2.0", CONFIG)).isEqualTo(60);
    }

    @Test
    void noFixedVersionDefaultsToPatchMinor() {
        assertThat(FixTime.category("sca", "1.9", null)).isEqualTo(FixTime.SCA_PATCH_MINOR);
        assertThat(FixTime.minutesFor("sca", "1.9", null, CONFIG)).isEqualTo(15);
    }

    @Test
    void secretUsesRotation() {
        assertThat(FixTime.category("secret", null, null)).isEqualTo(FixTime.SECRET_ROTATION);
        assertThat(FixTime.minutesFor("secret", null, null, CONFIG)).isEqualTo(10);
    }

    @Test
    void unknownCategoryFallsBackToDefaultThenHardDefault() {
        assertThat(FixTime.minutes("NOPE", CONFIG)).isEqualTo(20); // DEFAULT from config
        assertThat(FixTime.minutes("NOPE", Map.of())).isEqualTo(20); // hard default when no config at all
    }

    @Test
    void majorParsing() {
        assertThat(FixTime.major("1.10.0")).isEqualTo(1);
        assertThat(FixTime.major("2.0")).isEqualTo(2);
        assertThat(FixTime.major("")).isEqualTo(-1);
        assertThat(FixTime.major(null)).isEqualTo(-1);
        assertThat(FixTime.isMajorUpgrade("1.9", "1.10.0")).isFalse();
        assertThat(FixTime.isMajorUpgrade("1.9", "2.0")).isTrue();
    }
}
