package com.company.secscan.auth;

import com.company.secscan.ratelimit.SlidingWindowLimiter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The api's rate limits (D-098; pulled forward from M10), in-memory per api instance:
 * <ul>
 *   <li>sign-up: per client IP, {@code secscan.rate-limit.signup-per-ip} (5) per 15 min — every attempt counts;</li>
 *   <li>login FAILURES (JSON login and HTTP Basic alike): per (account, IP) 5, per IP 20, per account 50 per
 *       15 min. Every attempt reserves a slot on all three keys atomically BEFORE the password is checked (any key
 *       at its limit → blocked, nothing counted); a success or a correct password for a pending/disabled account
 *       refunds it, and a success also clears that (account, IP) pair.</li>
 * </ul>
 * Keys are bounded (LRU) so a flood of distinct IPs/emails cannot exhaust memory.
 */
@Component
public class RateLimits {

    static final Duration WINDOW = Duration.ofMinutes(15);
    static final int MAX_KEYS = 50_000;

    private final SlidingWindowLimiter signupPerIp;
    private final SlidingWindowLimiter loginPerAccountIp;
    private final SlidingWindowLimiter loginPerIp;
    private final SlidingWindowLimiter loginPerAccount;

    public RateLimits(@Value("${secscan.rate-limit.signup-per-ip:5}") int signupPerIp,
                      @Value("${secscan.rate-limit.login-per-account-ip:5}") int loginPerAccountIp,
                      @Value("${secscan.rate-limit.login-per-ip:20}") int loginPerIp,
                      @Value("${secscan.rate-limit.login-per-account:50}") int loginPerAccount) {
        Clock clock = Clock.systemUTC();
        this.signupPerIp = new SlidingWindowLimiter(signupPerIp, WINDOW, MAX_KEYS, clock);
        this.loginPerAccountIp = new SlidingWindowLimiter(loginPerAccountIp, WINDOW, MAX_KEYS, clock);
        this.loginPerIp = new SlidingWindowLimiter(loginPerIp, WINDOW, MAX_KEYS, clock);
        this.loginPerAccount = new SlidingWindowLimiter(loginPerAccount, WINDOW, MAX_KEYS, clock);
    }

    /** Counts a sign-up attempt from {@code ip}; 0 if allowed, else the Retry-After seconds. */
    public long signupAttempt(String ip) {
        return signupPerIp.tryAcquire(ip);
    }

    /**
     * Reserves a login attempt for (account, ip) ATOMICALLY, before the password is checked (M8.10 review SF-1):
     * 0 = allowed and counted against all three keys; otherwise nothing is counted and the Retry-After seconds
     * (longest block wins) are returned. Atomic across the three limiters, so a burst of concurrent attempts can
     * never get more than the limit through to bcrypt. An attempt that turns out not to be a failure gives its
     * reservation back ({@link #loginSucceeded}, {@link #loginNotAFailure}).
     */
    public synchronized long reserveLogin(String account, String ip) {
        long wait = Math.max(loginPerAccountIp.retryAfterSeconds(pair(account, ip)),
                Math.max(loginPerIp.retryAfterSeconds(ip), loginPerAccount.retryAfterSeconds(account)));
        if (wait == 0) {
            loginPerAccountIp.record(pair(account, ip));
            loginPerIp.record(ip);
            loginPerAccount.record(account);
        }
        return wait;
    }

    /** A successful login: refunds its reservation and clears that (account, IP) pair (D-098). */
    public synchronized void loginSucceeded(String account, String ip) {
        loginPerIp.refund(ip);
        loginPerAccount.refund(account);
        loginPerAccountIp.reset(pair(account, ip));
    }

    /** The correct password for a pending/disabled account: not a failure, so the reservation is refunded. */
    public synchronized void loginNotAFailure(String account, String ip) {
        loginPerAccountIp.refund(pair(account, ip));
        loginPerIp.refund(ip);
        loginPerAccount.refund(account);
    }

    /** Test hook: forget every counter (the bean is a singleton shared by a cached test context). */
    public void clear() {
        signupPerIp.clear();
        loginPerAccountIp.clear();
        loginPerIp.clear();
        loginPerAccount.clear();
    }

    private static String pair(String account, String ip) {
        return account + "|" + ip;
    }
}
