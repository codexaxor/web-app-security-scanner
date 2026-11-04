package com.company.secscan.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    /** A clock the test moves by hand. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-05T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    final MutableClock clock = new MutableClock();
    final SlidingWindowLimiter limiter = new SlidingWindowLimiter(5, Duration.ofMinutes(15), 100, clock);

    @Test
    void allowsUpToTheLimitThenBlocksWithARetryAfter() {
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("ip-a")).isZero();
            clock.advance(Duration.ofMinutes(1));
        }
        // 5 events at 10:00..10:04; now 10:05 → the oldest leaves at 10:15 → 600 s.
        assertThat(limiter.tryAcquire("ip-a")).isEqualTo(600);
        assertThat(limiter.retryAfterSeconds("ip-a")).isEqualTo(600);
    }

    @Test
    void theWindowSlides() {
        for (int i = 0; i < 5; i++) {
            limiter.record("k");
        }
        assertThat(limiter.retryAfterSeconds("k")).isPositive();
        clock.advance(Duration.ofMinutes(15));
        assertThat(limiter.retryAfterSeconds("k")).isZero();
    }

    @Test
    void aBlockedTryAcquireDoesNotExtendTheBlock() {
        for (int i = 0; i < 5; i++) {
            limiter.record("k");
        }
        clock.advance(Duration.ofMinutes(10));
        limiter.tryAcquire("k");               // refused, not counted
        clock.advance(Duration.ofMinutes(5));
        assertThat(limiter.tryAcquire("k")).isZero();
    }

    @Test
    void keysAreIndependentAndResettable() {
        for (int i = 0; i < 5; i++) {
            limiter.record("a");
        }
        assertThat(limiter.retryAfterSeconds("a")).isPositive();
        assertThat(limiter.retryAfterSeconds("b")).isZero();
        limiter.reset("a");
        assertThat(limiter.retryAfterSeconds("a")).isZero();
    }

    @Test
    void theNumberOfTrackedKeysIsBounded() {
        SlidingWindowLimiter small = new SlidingWindowLimiter(5, Duration.ofMinutes(15), 3, clock);
        for (int i = 0; i < 10; i++) {
            small.record("ip-" + i);
        }
        assertThat(small.trackedKeys()).isEqualTo(3);
    }

    @Test
    void rejectsNonsenseConfiguration() {
        assertThatThrownBy(() -> new SlidingWindowLimiter(0, Duration.ofMinutes(1), 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowLimiter(1, Duration.ZERO, 1, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
