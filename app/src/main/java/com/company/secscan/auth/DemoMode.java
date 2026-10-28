package com.company.secscan.auth;

import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Demo-mode configuration (D-091). Reads {@code SECSCAN_DEMO_MODE} (default false),
 * {@code SECSCAN_DEMO_USERNAME} (default {@code admin}, may be a non-email username) and
 * {@code SECSCAN_DEMO_PASSWORD}. Shared by the startup bootstrap (creates/updates the demo user and logs the
 * WARN), the authentication lockout (when OFF, any identifier without {@code @} is refused — the backstop for a
 * leftover demo row) and the {@code /auth/session} response ({@code demoMode}). Never enable on a shared host.
 */
@Component
public class DemoMode {

    /** The relaxed minimum length allowed ONLY for the demo user's password (real users keep the 12-char rule). */
    public static final int MIN_DEMO_PASSWORD_LENGTH = 4;

    private final boolean enabled;
    private final String username;
    private final String password;

    public DemoMode(Environment env) {
        this.enabled = Boolean.parseBoolean(env.getProperty("SECSCAN_DEMO_MODE", "false"));
        String u = env.getProperty("SECSCAN_DEMO_USERNAME", "admin");
        this.username = u == null ? "admin" : u.trim().toLowerCase(Locale.ROOT);
        this.password = env.getProperty("SECSCAN_DEMO_PASSWORD");
    }

    public boolean enabled() {
        return enabled;
    }

    /** The demo username, already normalized (trimmed, lowercased) the same way logins are. */
    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public boolean hasUsablePassword() {
        return StringUtils.hasText(password) && password.length() >= MIN_DEMO_PASSWORD_LENGTH;
    }

    /**
     * Whether a login identifier is allowed to authenticate given the current demo-mode state. Real identifiers
     * are emails (they contain {@code @}) and are always allowed. A non-email identifier (e.g. {@code admin}) is
     * allowed ONLY while demo mode is ON — so turning demo mode off locks out any leftover demo row (change 3).
     */
    public boolean identifierAllowed(String identifier) {
        if (identifier != null && identifier.contains("@")) {
            return true;
        }
        return enabled;
    }
}
