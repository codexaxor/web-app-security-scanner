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

/**
 * Creates the first admin on startup when {@code app_user} is empty and the bootstrap env vars are set (D-011).
 * The password is never logged or stored in plaintext; only the email is logged.
 * Runs in the api profile only — the worker never needs to create logins.
 */
@Component
@Profile("api")
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

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
        if (users.count() > 0) {
            return;
        }
        if (!StringUtils.hasText(bootstrapEmail) || !StringUtils.hasText(bootstrapPassword)) {
            log.info("No admin user exists and SECSCAN_BOOTSTRAP_ADMIN_* not set; skipping admin bootstrap.");
            return;
        }
        AppUser admin = new AppUser(
                Workspace.DEFAULT_ID,
                bootstrapEmail,
                passwordEncoder.encode(bootstrapPassword),
                "admin");
        users.save(admin);
        log.info("Bootstrapped admin user {} in the default workspace.", bootstrapEmail);
    }
}
