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
 *       15 min. Any key over its limit blocks the attempt before the password is checked; a success clears that
 *       (account, IP) pair only.</li>
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

    /** 0 if a login attempt for (account, ip) may proceed, else the Retry-After seconds (longest block wins). */
    public long loginBlockedFor(String account, String ip) {
        return Math.max(loginPerAccountIp.retryAfterSeconds(pair(account, ip)),
                Math.max(loginPerIp.retryAfterSeconds(ip), loginPerAccount.retryAfterSeconds(account)));
    }

    public void loginFailed(String account, String ip) {
        loginPerAccountIp.record(pair(account, ip));
        loginPerIp.record(ip);
        loginPerAccount.record(account);
    }

    public void loginSucceeded(String account, String ip) {
        loginPerAccountIp.reset(pair(account, ip));
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
