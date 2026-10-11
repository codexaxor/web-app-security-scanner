package com.company.secscan.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves D-011: the admin is bootstrapped from env vars, exactly once, and only when {@code app_user} is empty.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class AdminBootstrapIT {

    static final String ADMIN_EMAIL = "admin@example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_PASSWORD", () -> "s3cret-pw");
    }

    @Autowired
    AppUserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    AdminBootstrap adminBootstrap;

    @Test
    void bootstrapCreatesExactlyOneAdminInDefaultWorkspace() {
        // The runner already ran at startup.
        assertThat(users.count()).isEqualTo(1);

        AppUser admin = users.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.getRole()).isEqualTo("admin");
        assertThat(admin.getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
        // Stored as a bcrypt hash, never the plaintext.
        assertThat(admin.getPasswordHash()).isNotEqualTo("s3cret-pw");
        assertThat(passwordEncoder.matches("s3cret-pw", admin.getPasswordHash())).isTrue();
    }

    @Test
    void bootstrapIsNoOpWhenUsersAlreadyExist() {
        long before = users.count();
        assertThat(before).isEqualTo(1);

        // Running again must not add a second admin.
        adminBootstrap.run(null);

        assertThat(users.count()).isEqualTo(1);
    }
}
