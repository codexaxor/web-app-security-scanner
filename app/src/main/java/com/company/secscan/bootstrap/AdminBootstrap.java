package com.company.secscan.bootstrap;

import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Creates the first admin on startup when {@code app_user} is empty and the bootstrap env vars are set (D-011).
 * The bootstrap admin is the platform operator (D-094) — promoted only when no operator exists yet.
 * The password is never logged or stored in plaintext; only the email is logged.
 * Runs in the api profile only — the worker never needs to create logins.
 */
@Component
@Profile("api")
@org.springframework.core.annotation.Order(1) // SF-4: the real admin is created BEFORE the demo user
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    /** Reject obviously weak bootstrap passwords. */
    static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String bootstrapEmail;
    private final String bootstrapPassword;

    public AdminBootstrap(
            AppUserRepository users,
            PasswordEncoder passwordEncoder,
            org.springframework.core.env.Environment env) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.bootstrapEmail = env.getProperty("SECSCAN_BOOTSTRAP_ADMIN_EMAIL");
        this.bootstrapPassword = env.getProperty("SECSCAN_BOOTSTRAP_ADMIN_PASSWORD");
    }

    @Override
    public void run(ApplicationArguments args) {
        // SF-4: gate on a REAL (email) user, not on any user — a demo-only DB (username has no '@') still gets a
        // real admin created, so turning demo mode off always leaves a working email login.
        if (users.existsByEmailContaining("@")) {
            promoteBootstrapAdminIfNoOperator();
            return;
        }
        if (!StringUtils.hasText(bootstrapEmail) || !StringUtils.hasText(bootstrapPassword)) {
            log.warn("No admin user exists and SECSCAN_BOOTSTRAP_ADMIN_EMAIL/PASSWORD are not both set; "
                    + "no admin was created. Set both to bootstrap the first admin.");
            return;
        }
        if (bootstrapPassword.length() < MIN_PASSWORD_LENGTH) {
            log.warn("Admin bootstrap password is shorter than {} characters; refusing to create the admin.",
                    MIN_PASSWORD_LENGTH);
            return;
        }
        String normalizedEmail = bootstrapEmail.trim().toLowerCase(Locale.ROOT);
        AppUser admin = new AppUser(
                Workspace.DEFAULT_ID,
                normalizedEmail,
                passwordEncoder.encode(bootstrapPassword),
                "admin");
        // D-094: the bootstrap admin is the platform operator — but only if none exists (never a second one).
        boolean operator = !users.existsByOperatorTrue();
        if (operator) {
            admin.promoteToOperator();
        }
        users.save(admin);
        log.info("Bootstrapped admin user {} in the default workspace{}.", normalizedEmail,
                operator ? " as the platform operator" : "");
    }

    /**
     * An existing database with no operator (e.g. the V23 backfill found no qualifying admin): promote the user
     * named by {@code SECSCAN_BOOTSTRAP_ADMIN_EMAIL} if it is an active real admin. Never when an operator already
     * exists — a second operator is never created silently (D-094). The password env var is not needed here.
     */
    private void promoteBootstrapAdminIfNoOperator() {
        if (users.existsByOperatorTrue()) {
            return;
        }
        if (!StringUtils.hasText(bootstrapEmail)) {
            log.warn("No platform operator exists. Set SECSCAN_BOOTSTRAP_ADMIN_EMAIL to an existing admin's email "
                    + "to promote it.");
            return;
        }
        String normalizedEmail = bootstrapEmail.trim().toLowerCase(Locale.ROOT);
        users.findByEmail(normalizedEmail)
                .filter(u -> u.getEmail().contains("@") && "admin".equals(u.getRole()) && u.isActive())
                .ifPresentOrElse(u -> {
                    u.promoteToOperator();
                    users.save(u);
                    log.info("Promoted admin user {} to platform operator (no operator existed).", normalizedEmail);
                }, () -> log.warn("No platform operator exists and the bootstrap email is not an active admin; "
                        + "no operator was promoted."));
    }
}
