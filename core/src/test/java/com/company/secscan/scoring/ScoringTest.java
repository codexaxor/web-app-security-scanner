package com.company.secscan.scoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure scoring function (D-044 SCA base, D-045 secret base, KEV/sensitivity adjustments,
 * clamp and bands). No Spring, no I/O.
 */
class ScoringTest {

    private static ScoreInput sca(String maxSeverity, String label, boolean onKev, String sensitivity) {
        return new ScoreInput("sca", maxSeverity, label, null, onKev, sensitivity);
    }

    private static int delta(ScoreResult r, String code) {
        return r.breakdown().stream().filter(l -> l.code().equals(code)).mapToInt(ScoreLine::delta).sum();
    }

    private static boolean has(ScoreResult r, String code) {
        return r.breakdown().stream().anyMatch(l -> l.code().equals(code));
    }

    @Test
    void cvssBaseIsMaxSeverityTimesTen() {
        ScoreResult r = Scoring.score(sca("9.8", "CRITICAL", false, "normal"));
        assertThat(r.score()).isEqualTo(98);
        assertThat(r.baseSource()).isEqualTo("cvss_base");
        assertThat(r.band()).isEqualTo(Scoring.BAND_CRITICAL);
        assertThat(delta(r, "base_cvss")).isEqualTo(98);
    }

    @Test
    void v4OnlyMaxSeverityScoresFromTheNumber() {
        ScoreResult r = Scoring.score(sca("8.7", "HIGH", false, "normal"));
        assertThat(r.score()).isEqualTo(87);
        assertThat(r.band()).isEqualTo(Scoring.BAND_HIGH);
        assertThat(r.baseSource()).isEqualTo("cvss_base");
    }

    @Test
    void noCvssVectorFallsBackToTheHighLabel() {
        ScoreResult r = Scoring.score(sca("", "HIGH", false, "normal"));
        assertThat(r.score()).isEqualTo(80);
        assertThat(r.baseSource()).isEqualTo("label");
        assertThat(has(r, "base_label")).isTrue();
    }

    @Test
    void noCvssFindingScoresFromTheLabelOnly() {
        // CVE-2015-6420 shape: severity null, max_severity "", label HIGH, cwe_ids ["CWE-502"] -> base 80.
        ScoreResult r = Scoring.score(sca("", "HIGH", false, "normal"));
        assertThat(r.score()).isEqualTo(80);
        assertThat(delta(r, "base_label")).isEqualTo(80);
    }

    @Test
    void labelFallbackCoversAllFourSeverities() {
        assertThat(Scoring.score(sca(null, "CRITICAL", false, "normal")).score()).isEqualTo(95);
        assertThat(Scoring.score(sca(null, "HIGH", false, "normal")).score()).isEqualTo(80);
        assertThat(Scoring.score(sca(null, "MODERATE", false, "normal")).score()).isEqualTo(55);
        assertThat(Scoring.score(sca(null, "LOW", false, "normal")).score()).isEqualTo(25);
    }

    @Test
    void onKevAddsFiveAndRecordsTheReason() {
        ScoreResult r = Scoring.score(sca("5.0", "MODERATE", true, "normal"));
        assertThat(r.score()).isEqualTo(55); // 50 + 5
        assertThat(delta(r, "on_kev")).isEqualTo(Scoring.KEV_DELTA);
    }

    @Test
    void sensitivityHighAddsFiveLowSubtractsTen() {
        ScoreResult high = Scoring.score(sca("5.0", "MODERATE", false, "high"));
        assertThat(high.score()).isEqualTo(55);
        assertThat(delta(high, "sensitivity_high")).isEqualTo(5);

        ScoreResult low = Scoring.score(sca("5.0", "MODERATE", false, "low"));
        assertThat(low.score()).isEqualTo(40);
        assertThat(delta(low, "sensitivity_low")).isEqualTo(-10);

        ScoreResult normal = Scoring.score(sca("5.0", "MODERATE", false, "normal"));
        assertThat(has(normal, "sensitivity_high")).isFalse();
        assertThat(has(normal, "sensitivity_low")).isFalse();
    }

    @Test
    void clampsAtOneHundred() {
        ScoreResult r = Scoring.score(sca("9.8", "CRITICAL", true, "normal")); // 98 + 5 = 103
        assertThat(r.score()).isEqualTo(100);
        assertThat(has(r, "clamp")).isTrue();
        assertThat(r.breakdown().stream().mapToInt(ScoreLine::delta).sum()).isEqualTo(100);
    }

    @Test
    void clampsAtOne() {
        ScoreResult r = Scoring.score(sca("0.1", "LOW", false, "low")); // base 1, -10 -> -9 -> clamp 1
        assertThat(r.score()).isEqualTo(1);
        assertThat(r.band()).isEqualTo(Scoring.BAND_LOW);
        assertThat(has(r, "clamp")).isTrue();
        assertThat(r.breakdown().stream().mapToInt(ScoreLine::delta).sum()).isEqualTo(1);
    }

    @Test
    void breakdownDeltasAlwaysSumToScore() {
        ScoreResult r = Scoring.score(sca("7.5", "HIGH", true, "high")); // 75 + 5 + 5 = 85
        assertThat(r.score()).isEqualTo(85);
        assertThat(r.breakdown().stream().mapToInt(ScoreLine::delta).sum()).isEqualTo(85);
    }

    @Test
    void secretBaseByRuleId() {
        // SF7: only Betterleaks 1.7.3-verified rule ids are mapped — private-key & aws-access-token (cloud) 90,
        // github-pat & generic-api-key (tokens) 75. No verified password rule id yet, so the 60 bucket is unused.
        assertThat(Scoring.score(secret("private-key")).score()).isEqualTo(90);
        assertThat(Scoring.score(secret("aws-access-token")).score()).isEqualTo(90);
        assertThat(Scoring.score(secret("github-pat")).score()).isEqualTo(75);
        assertThat(Scoring.score(secret("generic-api-key")).score()).isEqualTo(75);
    }

    @Test
    void unmappedSecretRuleDefaultsToSeventyFive() {
        assertThat(Scoring.isMappedSecretRule("totally-unknown-rule")).isFalse();
        assertThat(Scoring.score(secret("totally-unknown-rule")).score()).isEqualTo(75);
        // A rule id dropped as unverified (SF7) now takes the default base and is treated as unmapped.
        assertThat(Scoring.isMappedSecretRule("generic-password")).isFalse();
        assertThat(Scoring.score(secret("generic-password")).score()).isEqualTo(75);
        assertThat(Scoring.isMappedSecretRule("private-key")).isTrue();
    }

    @Test
    void bandsMatchSpec() {
        assertThat(Scoring.band(90)).isEqualTo(Scoring.BAND_CRITICAL);
        assertThat(Scoring.band(100)).isEqualTo(Scoring.BAND_CRITICAL);
        assertThat(Scoring.band(89)).isEqualTo(Scoring.BAND_HIGH);
        assertThat(Scoring.band(70)).isEqualTo(Scoring.BAND_HIGH);
        assertThat(Scoring.band(69)).isEqualTo(Scoring.BAND_MEDIUM);
        assertThat(Scoring.band(40)).isEqualTo(Scoring.BAND_MEDIUM);
        assertThat(Scoring.band(39)).isEqualTo(Scoring.BAND_LOW);
        assertThat(Scoring.band(1)).isEqualTo(Scoring.BAND_LOW);
    }

    private static ScoreInput secret(String ruleId) {
        return new ScoreInput("secret", null, null, ruleId, false, "normal");
    }
}
