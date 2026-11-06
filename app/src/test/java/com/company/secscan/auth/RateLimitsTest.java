package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * M8.10 review SF-1: login attempts reserve a slot on all three keys atomically before the password check; a
 * success or a correct password for a pending/disabled account gives it back.
 */
class RateLimitsTest {

    final RateLimits limits = new RateLimits(5, 5, 20, 50);

    @Test
    void fiveReservationsPerPairThenBlocked() {
        for (int i = 0; i < 5; i++) {
            assertThat(limits.reserveLogin("a@x.test", "203.0.113.1")).isZero();
        }
        assertThat(limits.reserveLogin("a@x.test", "203.0.113.1")).isPositive();
        assertThat(limits.reserveLogin("a@x.test", "203.0.113.2")).isZero();      // other IP
        assertThat(limits.reserveLogin("b@x.test", "203.0.113.1")).isZero();      // other account
    }

    @Test
    void aBlockedAttemptCountsNothing() {
        for (int i = 0; i < 5; i++) {
            limits.reserveLogin("a@x.test", "203.0.113.1");
        }
        for (int i = 0; i < 30; i++) {
            assertThat(limits.reserveLogin("a@x.test", "203.0.113.1")).isPositive();
        }
        // 5 counted on the IP key, not 35: 15 more accounts' first attempts still fit under the IP limit of 20.
        for (int i = 0; i < 15; i++) {
            assertThat(limits.reserveLogin("u" + i + "@x.test", "203.0.113.1")).isZero();
        }
        assertThat(limits.reserveLogin("late@x.test", "203.0.113.1")).isPositive();
    }

    @Test
    void notAFailureRefundsEveryKeyAndASuccessClearsThePair() {
        for (int i = 0; i < 20; i++) {
            limits.reserveLogin("p@x.test", "203.0.113.9");
            limits.loginNotAFailure("p@x.test", "203.0.113.9");
        }
        assertThat(limits.reserveLogin("p@x.test", "203.0.113.9")).isZero();

        for (int i = 0; i < 4; i++) {
            limits.reserveLogin("s@x.test", "203.0.113.8");
        }
        assertThat(limits.reserveLogin("s@x.test", "203.0.113.8")).isZero();
        limits.loginSucceeded("s@x.test", "203.0.113.8");
        for (int i = 0; i < 5; i++) {
            assertThat(limits.reserveLogin("s@x.test", "203.0.113.8")).isZero();
        }
    }

    @Test
    void aConcurrentBurstGetsAtMostTheLimitThrough() throws Exception {
        int burst = 64;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < burst; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return limits.reserveLogin("burst@x.test", "203.0.113.50");
            }));
        }
        start.countDown();
        int allowed = 0;
        for (Future<Long> f : results) {
            if (f.get() == 0) {
                allowed++;
            }
        }
        pool.shutdown();
        assertThat(allowed).isEqualTo(5);
    }
}
