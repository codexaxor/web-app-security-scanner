package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.bootstrap.AdminBootstrap;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * M8.8 SF-4 (+ SF-2 untouched): AdminBootstrap creates the real admin when no EMAIL user exists — not merely
 * when the table is empty — and runs before DemoBootstrap. So a demo-only database still gets a real admin, and
 * turning demo mode off always leaves a working email login. DemoBootstrap never disturbs an existing real user.
 */
class BootstrapOrderingIT extends AbstractGitLabIT {

    private static final String ADMIN_EMAIL = "bootadmin@test.local";

    @DynamicPropertySource
    static void bootstrapEnv(DynamicPropertyRegistry registry) {
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_PASSWORD", () -> "bootstrap-pw-12");
        registry.add("SECSCAN_DEMO_MODE", () -> "true");
        registry.add("SECSCAN_DEMO_USERNAME", () -> "demoadmin");
        registry.add("SECSCAN_DEMO_PASSWORD", () -> "demo-pass");
    }

    @Autowired
    AdminBootstrap adminBootstrap;
    @Autowired
    DemoBootstrap demoBootstrap;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;

    @Test
    void bothBootstrapsCreateARealAdminAndADemoUser() {
        users.deleteAll();
        adminBootstrap.run(null);
        demoBootstrap.run(null);

        assertThat(users.findByEmail(ADMIN_EMAIL)).isPresent().get()
                .satisfies(a -> assertThat(a.getRole()).isEqualTo("admin"));
        assertThat(users.findByEmail("demoadmin")).isPresent();
    }

    @Test
    void aDemoOnlyDatabaseStillGetsARealAdmin() {   // SF-4: gate on an EMAIL user, not on any user
        users.deleteAll();
        users.save(new AppUser(Workspace.DEFAULT_ID, "demoadmin", encoder.encode("demo-pass"), "admin"));

        adminBootstrap.run(null);

        assertThat(users.findByEmail(ADMIN_EMAIL)).as("real admin created despite the demo-only DB").isPresent();
    }

    @Test
    void demoBootstrapLeavesAnExistingRealUserUntouched() {   // SF-2
        users.deleteAll();
        AppUser real = users.save(new AppUser(Workspace.DEFAULT_ID, "real@corp.com",
                encoder.encode("a-real-password"), "member"));
        String hashBefore = real.getPasswordHash();

        demoBootstrap.run(null);

        AppUser after = users.findByEmail("real@corp.com").orElseThrow();
        assertThat(after.getPasswordHash()).isEqualTo(hashBefore);   // hash unchanged
        assertThat(after.getRole()).isEqualTo("member");             // role unchanged
        assertThat(users.findByEmail("demoadmin")).isPresent();      // demo user still created
    }
}
