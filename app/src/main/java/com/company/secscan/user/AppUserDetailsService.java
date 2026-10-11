package com.company.secscan.user;

import java.util.Collections;
import java.util.Locale;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Service;

/**
 * Authenticates {@code /api/**} via HTTP Basic against {@code app_user}. No authorities are granted yet;
 * role-based authorization arrives in M10 (D-010).
 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public AppUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        // Emails are case-insensitive; they are stored lowercased (V3), so look up by the lowercased value.
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        AppUser user = users.findByEmail(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("No user for email"));
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .authorities(Collections.emptyList())
                .build();
    }
}
