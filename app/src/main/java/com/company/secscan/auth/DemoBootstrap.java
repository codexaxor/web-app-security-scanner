package com.company.secscan.auth;

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

/**
 * Creates or updates the demo user at startup when demo mode is ON (D-091), idempotently, with a bcrypt hash —
 * so the demo login works after a restart and reflects a changed {@code SECSCAN_DEMO_PASSWORD}. A WARN is logged
 * at EVERY startup while demo mode is on. The 12-char rule for real users is untouched; the demo user is allowed
 * the relaxed {@link DemoMode#MIN_DEMO_PASSWORD_LENGTH}-char floor. The password is never logged. When demo mode
 * is off this does nothing (and the no-{@code @} lockout in {@code AppUserDetailsService} keeps any leftover demo
 * row from signing in). Runs in the api profile only.
 */
@Component
@Profile("api")
public class DemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoMode demoMode;

    public DemoBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, DemoMode demoMode) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoMode = demoMode;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!demoMode.enabled()) {
            return;
        }
        log.warn("DEMO MODE is ON: the demo user '{}' can sign in with a non-email username. NEVER enable demo "
                + "mode on a shared or production host.", demoMode.username());
        if (!demoMode.hasUsablePassword()) {
            log.warn("Demo mode is on but SECSCAN_DEMO_PASSWORD is missing or shorter than {} characters; the "
                    + "demo user was NOT created.", DemoMode.MIN_DEMO_PASSWORD_LENGTH);
            return;
        }
        String hash = passwordEncoder.encode(demoMode.password());
        users.findByEmail(demoMode.username()).ifPresentOrElse(
                existing -> {
                    existing.updateCredentials(hash, "admin");
                    users.save(existing);
                    log.info("Demo user '{}' updated.", demoMode.username());
                },
                () -> {
                    users.save(new AppUser(Workspace.DEFAULT_ID, demoMode.username(), hash, "admin"));
                    log.info("Demo user '{}' created.", demoMode.username());
                });
    }
}
