package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Boundary tests for the EPSS policy (D-060): the 1% / 5% / 10% thresholds, LOW_EPSS, and no double-apply. */
class EpssPolicyTest {

    @Test
    void lowEpssAutoIgnoreIsStrictlyBelowOnePercentAndNotKev() {
        assertThat(EpssPolicy.isLowEpssAutoIgnore(0.009, false)).as("0.9% not KEV").isTrue();
        assertThat(EpssPolicy.isLowEpssAutoIgnore(0.01, false)).as("exactly 1% → not <1%").isFalse();
        assertThat(EpssPolicy.isLowEpssAutoIgnore(0.02, false)).as("2%").isFalse();
    }

    @Test
    void kevListedLowEpssIsNeverAutoIgnored() {
        assertThat(EpssPolicy.isLowEpssAutoIgnore(0.0001, true)).isFalse();
    }

    @Test
    void nullEpssNeverAutoIgnoresAndHasNoDelta() {
        assertThat(EpssPolicy.isLowEpssAutoIgnore(null, false)).isFalse();
        assertThat(EpssPolicy.scoreDelta(null, false)).isZero();
        assertThat(EpssPolicy.scoreDelta(null, true)).isZero();
    }

    @Test
    void scoreDeltaHasStrictBandsAndDefersToLowEpssForNonKev() {
        // Review S1 exact assertions. Non-KEV below 1% → 0 (the LOW_EPSS status auto-ignore owns it, no
        // double-dock); the [1%,5%) band → −10; [5%,10%) → −5; ≥10% → 0.
        assertThat(EpssPolicy.scoreDelta(0.0099, false)).as("non-KEV, <1% → LOW_EPSS owns it").isZero();
        assertThat(EpssPolicy.scoreDelta(0.01, false)).as("exactly 1% → −10 tier").isEqualTo(-10);
        assertThat(EpssPolicy.scoreDelta(0.05, false)).as("exactly 5% → −5 tier").isEqualTo(-5);
        assertThat(EpssPolicy.scoreDelta(0.10, false)).as("exactly 10% → no adjustment").isZero();
    }

    @Test
    void kevFindingBelowOnePercentStillGetsTheMinusTenTier() {
        // KEV is never status-auto-ignored, so its score IS docked in the <5% band even below 1% (review S1).
        assertThat(EpssPolicy.scoreDelta(0.0099, true)).isEqualTo(-10);
        assertThat(EpssPolicy.scoreDelta(0.0001, true)).isEqualTo(-10);
    }

    @Test
    void scoreTiersAreStrictBandsThatNeverDoubleApply() {
        // [1%,5%) → −10
        assertThat(EpssPolicy.scoreDelta(0.02, false)).isEqualTo(-10);
        assertThat(EpssPolicy.scoreDelta(0.049, false)).isEqualTo(-10);
        // [5%,10%) → −5 (and NOT also −10 — mutually exclusive)
        assertThat(EpssPolicy.scoreDelta(0.05, false)).isEqualTo(-5);
        assertThat(EpssPolicy.scoreDelta(0.099, false)).isEqualTo(-5);
        // ≥10% → 0
        assertThat(EpssPolicy.scoreDelta(0.10, false)).isZero();
        assertThat(EpssPolicy.scoreDelta(0.5, false)).isZero();
    }

    @Test
    void tierLabelMatchesTheAppliedDelta() {
        assertThat(EpssPolicy.tierLabel(0.02, false)).isEqualTo("EPSS below 5%");
        assertThat(EpssPolicy.tierLabel(0.07, false)).isEqualTo("EPSS between 5% and 10%");
        assertThat(EpssPolicy.tierLabel(0.5, false)).isNull();
        assertThat(EpssPolicy.tierLabel(0.0099, false)).as("non-KEV <1% → no score line").isNull();
        assertThat(EpssPolicy.tierLabel(0.0099, true)).as("KEV <1% → −10 label").isEqualTo("EPSS below 5%");
    }
}
