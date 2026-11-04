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
 * The {@code signup_request} lookup is workspace-agnostic by design (pre-tenant, CLAUDE.md rule 1 / D-098).
 */
public class SecscanAuthenticationProvider implements AuthenticationProvider {

    private final DaoAuthenticationProvider dao;
    private final SignupRequestRepository signups;
    private final PasswordEncoder encoder;
    private final String dummyHash;

    public SecscanAuthenticationProvider(UserDetailsService users, PasswordEncoder encoder,
                                         SignupRequestRepository signups) {
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
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        try {
            return dao.authenticate(authentication);
        } catch (BadCredentialsException bad) {
            String email = authentication.getName() == null ? ""
                    : authentication.getName().trim().toLowerCase(Locale.ROOT);
            Object credentials = authentication.getCredentials();
            String password = credentials == null ? "" : credentials.toString();
            Optional<SignupRequest> pending = email.contains("@")
                    ? signups.findByEmailAndStatus(email, SignupRequest.STATUS_PENDING)
                    : Optional.empty();
            String hash = pending.map(SignupRequest::getPasswordHash).orElse(dummyHash);
            boolean matches = encoder.matches(password, hash);
            if (pending.isPresent() && matches) {
                throw new AccountPendingException();
            }
            throw bad;
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
