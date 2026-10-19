package com.company.secscan.eol;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EolStatusTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);

    @Test
    void isEolFlagIsPastEolRegardlessOfDate() {
        assertThat(EolStatus.of(true, null, TODAY)).isEqualTo(EolStatus.PAST_EOL);
        assertThat(EolStatus.of(true, TODAY.plusYears(5), TODAY)).isEqualTo(EolStatus.PAST_EOL);
    }

    @Test
    void eolDateInThePastOrTodayIsPastEol() {
        assertThat(EolStatus.of(false, TODAY.minusDays(1), TODAY)).isEqualTo(EolStatus.PAST_EOL);
        assertThat(EolStatus.of(false, TODAY, TODAY)).isEqualTo(EolStatus.PAST_EOL);
    }

    @Test
    void eolWithinNinetyDaysIsEndingSoon() {
        assertThat(EolStatus.of(false, TODAY.plusDays(1), TODAY)).isEqualTo(EolStatus.ENDING_SOON);
        assertThat(EolStatus.of(false, TODAY.plusDays(90), TODAY)).isEqualTo(EolStatus.ENDING_SOON);
    }

    @Test
    void eolMoreThanNinetyDaysAwayOrUnknownIsMaintained() {
        assertThat(EolStatus.of(false, TODAY.plusDays(91), TODAY)).isEqualTo(EolStatus.MAINTAINED);
        assertThat(EolStatus.of(false, null, TODAY)).isEqualTo(EolStatus.MAINTAINED);
    }

    @Test
    void isIssueOnlyForPastEolAndEndingSoon() {
        assertThat(EolStatus.isIssue(EolStatus.PAST_EOL)).isTrue();
        assertThat(EolStatus.isIssue(EolStatus.ENDING_SOON)).isTrue();
        assertThat(EolStatus.isIssue(EolStatus.MAINTAINED)).isFalse();
    }
}
