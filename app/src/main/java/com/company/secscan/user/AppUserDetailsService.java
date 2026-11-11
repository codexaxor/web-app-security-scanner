package com.company.secscan.user;

import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * Loads {@code app_user} rows for both the JSON session login and HTTP Basic, as a {@code SecscanPrincipal}
 * (workspace + operator, D-093/D-094). The only authority is {@code ROLE_OPERATOR} for the platform operator.
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;
    private final com.company.secscan.auth.DemoMode demoMode;

    public AppUserDetailsService(AppUserRepository users, com.company.secscan.auth.DemoMode demoMode) {
        this.users = users;
        this.demoMode = demoMode;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Emails are case-insensitive; they are stored lowercased (V3), so look up by the lowercased value.
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        // D-091 lockout: when demo mode is OFF, any identifier without '@' is refused — the backstop against a
        // leftover demo row signing in. Real users are emails (they contain '@') and are unaffected.
        if (!demoMode.identifierAllowed(normalized)) {
            throw new UsernameNotFoundException("Identifier not permitted");
        }
        AppUser user = users.findByEmail(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("No user for email"));
        // D-093/D-094: the principal carries the user's workspace and operator flag; status is enforced after the
        // password by SecscanAuthenticationProvider (never before — that would reveal it to a password guesser).
        return new com.company.secscan.auth.SecscanPrincipal(user.getId(), user.getWorkspaceId(), user.getEmail(),
                user.getPasswordHash(), user.isOperator(), user.isActive());
    }
}
