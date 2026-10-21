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
        assertThat(EpssPolicy.scoreDelta(null)).isZero();
    }

    @Test
    void scoreTiersAreStrictBandsThatNeverDoubleApply() {
        // [0,5%) → −10
        assertThat(EpssPolicy.scoreDelta(0.0)).isEqualTo(-10);
        assertThat(EpssPolicy.scoreDelta(0.009)).isEqualTo(-10);
        assertThat(EpssPolicy.scoreDelta(0.049)).isEqualTo(-10);
        // [5%,10%) → −5 (and NOT also −10 — mutually exclusive)
        assertThat(EpssPolicy.scoreDelta(0.05)).isEqualTo(-5);
        assertThat(EpssPolicy.scoreDelta(0.099)).isEqualTo(-5);
        // ≥10% → 0
        assertThat(EpssPolicy.scoreDelta(0.10)).isZero();
        assertThat(EpssPolicy.scoreDelta(0.5)).isZero();
    }

    @Test
    void tierLabelMatchesTheAppliedDelta() {
        assertThat(EpssPolicy.tierLabel(0.02)).isEqualTo("EPSS below 5%");
        assertThat(EpssPolicy.tierLabel(0.07)).isEqualTo("EPSS between 5% and 10%");
        assertThat(EpssPolicy.tierLabel(0.5)).isNull();
    }
}
