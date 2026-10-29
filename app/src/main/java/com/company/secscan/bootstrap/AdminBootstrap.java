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
        users.save(admin);
        log.info("Bootstrapped admin user {} in the default workspace.", normalizedEmail);
    }
}
