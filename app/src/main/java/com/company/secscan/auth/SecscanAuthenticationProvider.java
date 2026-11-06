package com.company.secscan.auth;

import com.company.secscan.signup.SignupRequest;
import com.company.secscan.signup.SignupRequestRepository;
import java.util.Locale;
import java.util.Optional;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one authentication path for the JSON login and HTTP Basic (D-092, D-098):
 * <ol>
 *   <li>bcrypt against {@code app_user} (Spring's {@link DaoAuthenticationProvider}, which already equalises the
 *       timing of an unknown user); a matching password for a DISABLED user → {@link AccountInactiveException}
 *       (checked AFTER the password);</li>
 *   <li>on a bad credential: if the email has a PENDING {@code signup_request} and the password matches it →
 *       {@link AccountPendingException}; otherwise the same {@link BadCredentialsException}. A dummy bcrypt runs
 *       when there is no pending request, so every failed login costs the same two hash checks (no timing
 *       oracle for "this email has a pending sign-up").</li>
 * </ol>
 * Login failures are rate limited per (account, IP), per IP and per account ({@link RateLimits}): a slot is
 * reserved atomically before the password check and refunded on success or on a correct password for a
 * pending/disabled account (neither is a failure). The {@code signup_request} lookup is workspace-agnostic by design (pre-tenant, CLAUDE.md rule 1 / D-098).
 */
public class SecscanAuthenticationProvider implements AuthenticationProvider {

    private final DaoAuthenticationProvider dao;
    private final SignupRequestRepository signups;
    private final PasswordEncoder encoder;
    private final String dummyHash;
    private final RateLimits limits;
    private final ClientIpResolver clientIp;

    public SecscanAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder,
                                         SignupRequestRepository signups, RateLimits limits,
                                         ClientIpResolver clientIp) {
        this.dao = new DaoAuthenticationProvider();
        this.dao.setUserDetailsService(users);
        this.dao.setPasswordEncoder(encoder);
        this.dao.setPostAuthenticationChecks(user -> {
            if (user instanceof SecscanPrincipal p && !p.active()) {
                throw new AccountInactiveException();
            }
        });
        this.signups = signups;
        this.encoder = encoder;
        this.dummyHash = encoder.encode("timing-equaliser-not-a-password");
        this.limits = limits;
        this.clientIp = clientIp;
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String email = authentication.getName() == null ? ""
                : authentication.getName().trim().toLowerCase(Locale.ROOT);
        String ip = currentClientIp();
        // D-098 + review SF-1: reserve a slot on every failure key ATOMICALLY before the password check, so a burst
        // of concurrent attempts cannot all pass a check-then-record race. Refunded when it was not a failure.
        long wait = ip == null ? 0 : limits.reserveLogin(email, ip);
        if (wait > 0) {
            throw new LoginRateLimitedException(wait);
        }
        try {
            Authentication ok = dao.authenticate(authentication);
            if (ip != null) {
                limits.loginSucceeded(email, ip);
            }
            return ok;
        } catch (AccountInactiveException inactive) {
            if (ip != null) {
                limits.loginNotAFailure(email, ip);   // correct password for a disabled account: not a failure
            }
            throw inactive;
        } catch (BadCredentialsException bad) {
            Object credentials = authentication.getCredentials();
            String password = credentials == null ? "" : credentials.toString();
            Optional<SignupRequest> pending = email.contains("@")
                    ? signups.findByEmailAndStatus(email, SignupRequest.STATUS_PENDING)
                    : Optional.empty();
            String hash = pending.map(SignupRequest::getPasswordHash).orElse(dummyHash);
            boolean matches = encoder.matches(password, hash);
            if (pending.isPresent() && matches) {
                if (ip != null) {
                    limits.loginNotAFailure(email, ip);   // correct password: not a failure
                }
                throw new AccountPendingException();
            }
            throw bad;   // the reservation stays counted as the failure
        }
    }

    /** The client IP of the current HTTP request (null outside a request, e.g. a direct service call). */
    private String currentClientIp() {
        if (org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes attrs) {
            return clientIp.resolve(attrs.getRequest());
        }
        return null;
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
