package com.company.secscan.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * An in-memory sliding-window counter per key (D-098): at most {@code limit} events per {@code window}. Bounded:
 * at most {@code maxKeys} keys are tracked (least-recently-used evicted), so a flood of distinct keys (IPs,
 * emails) cannot exhaust memory. Thread-safe. Pure Java — no Spring — so it is unit-testable with a fixed clock.
 *
 * <p>Usage: {@link #retryAfterSeconds} to check without counting, {@link #record} to count an event (e.g. a failed
 * login), {@link #tryAcquire} to check-and-count in one step (e.g. a sign-up), {@link #reset} to forget a key.
 */
public final class SlidingWindowLimiter {

    private final int limit;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> events;

    public SlidingWindowLimiter(int limit, Duration window, int maxKeys, Clock clock) {
        if (limit < 1 || maxKeys < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("limit, maxKeys and window must be positive");
        }
        this.limit = limit;
        this.window = window;
        this.clock = Objects.requireNonNull(clock);
        this.events = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
                return size() > maxKeys;
            }
        };
    }

    /** 0 when another event is allowed now; otherwise the whole seconds until the oldest event leaves the window. */
    public synchronized long retryAfterSeconds(String key) {
        Deque<Instant> q = pruned(key);
        if (q == null || q.size() < limit) {
            return 0;
        }
        Duration wait = Duration.between(clock.instant(), q.peekFirst().plus(window));
        return Math.max(1, (wait.toMillis() + 999) / 1000);
    }

    /** Counts one event for {@code key}. */
    public synchronized void record(String key) {
        Deque<Instant> q = pruned(key);
        if (q == null) {
            q = new ArrayDeque<>();
            events.put(key, q);
        }
        q.addLast(clock.instant());
        while (q.size() > limit) {
            q.removeFirst();   // only the newest `limit` events can matter
        }
    }

    /** Counts the event and returns 0 if allowed; otherwise counts nothing and returns the retry-after seconds. */
    public synchronized long tryAcquire(String key) {
        long wait = retryAfterSeconds(key);
        if (wait == 0) {
            record(key);
        }
        return wait;
    }

    public synchronized void reset(String key) {
        events.remove(key);
    }

    /** Forgets every key (tests, or an operator restart). */
    public synchronized void clear() {
        events.clear();
    }

    synchronized int trackedKeys() {
        return events.size();
    }

    private Deque<Instant> pruned(String key) {
        Deque<Instant> q = events.get(key);
        if (q == null) {
            return null;
        }
        Instant cutoff = clock.instant().minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.removeFirst();
        }
        if (q.isEmpty()) {
            events.remove(key);
            return null;
        }
        return q;
    }
}
