package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReasonAssignmentTest {

    @Test
    void manualIgnoreRuleOutranksEveryAutoReason() {
        assertThat(ReasonAssignment.highest(List.of(ReasonCode.LOW_EPSS, ReasonCode.IGNORE_RULE,
                ReasonCode.EXCLUDED_PATH))).contains(ReasonCode.IGNORE_RULE);
    }

    @Test
    void precedenceOrderIsDeclarationOrder() {
        assertThat(ReasonCode.EXCLUDED_PATH.outranks(ReasonCode.SECRET_PLACEHOLDER)).isTrue();
        assertThat(ReasonCode.SECRET_PLACEHOLDER.outranks(ReasonCode.DUPLICATE_RULE)).isTrue();
        assertThat(ReasonCode.DEV_DEPENDENCY.outranks(ReasonCode.LOW_EPSS)).isTrue();
        assertThat(ReasonCode.LOW_EPSS.outranks(ReasonCode.IGNORE_RULE)).isFalse();
    }

    @Test
    void emptyCandidatesYieldNoReason() {
        assertThat(ReasonAssignment.highest(List.of())).isEmpty();
    }

    @Test
    void reasonCodesAreStoredByName() {
        assertThat(ReasonCode.EXCLUDED_PATH.code()).isEqualTo("EXCLUDED_PATH");
        assertThat(ReasonCode.DUPLICATE_RULE.code()).isEqualTo("DUPLICATE_RULE");
    }
}
