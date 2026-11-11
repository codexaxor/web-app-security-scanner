package com.company.secscan.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.signup.SignupRequest;
import com.company.secscan.signup.SignupRequestRepository;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * D-094 / approval item 4: the bootstrap admin is the platform operator, promoted ONLY when no operator exists;
 * a second operator is never created silently (AdminBootstrap guard + the V23 single-operator index). Also a JPA
 * round trip of the pre-tenant {@link SignupRequest} (D-098) against the real V23 schema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
@Testcontainers
class OperatorPromotionIT {

    static final String ADMIN_EMAIL = "operator@example.com";
    static final String ADMIN_PASSWORD = "sufficiently-long-pw";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("secscan.intel.scheduled-sync-enabled", () -> "false");
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_EMAIL", () -> ADMIN_EMAIL);
        registry.add("SECSCAN_BOOTSTRAP_ADMIN_PASSWORD", () -> ADMIN_PASSWORD);
    }

    @Autowired
    AppUserRepository users;

    @Autowired
    SignupRequestRepository signups;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetToTheBootstrappedState() {
        signups.deleteAll();
        jdbc.update("delete from app_user where email <> ?", ADMIN_EMAIL);
        jdbc.update("update app_user set is_operator = true where email = ?", ADMIN_EMAIL);
    }

    @Test
    void theBootstrappedAdminIsTheOneActiveOperator() {
        AppUser admin = users.findByEmail(ADMIN_EMAIL).orElseThrow();
        assertThat(admin.isOperator()).isTrue();
        assertThat(admin.isActive()).isTrue();
        assertThat(operatorCount()).isEqualTo(1);
    }

    @Test
    void reRunningTheBootstrapNeverCreatesASecondOperator() {
        AppUser other = users.save(new AppUser(Workspace.DEFAULT_ID, "second@example.com", "hash", "admin"));

        // Bootstrap pointed at the second admin while an operator exists → no promotion.
        bootstrapFor("second@example.com").run(null);
        new AdminBootstrap(users, encoder, envFor(ADMIN_EMAIL)).run(null);

        assertThat(users.findById(other.getId()).orElseThrow().isOperator()).isFalse();
        assertThat(operatorCount()).isEqualTo(1);
    }

    @Test
    void anExistingAdminIsPromotedOnlyWhenNoOperatorExists() {
        AppUser other = users.save(new AppUser(Workspace.DEFAULT_ID, "second@example.com", "hash", "admin"));
        jdbc.update("update app_user set is_operator = false");

        bootstrapFor("second@example.com").run(null);

        assertThat(users.findById(other.getId()).orElseThrow().isOperator()).isTrue();
        assertThat(operatorCount()).isEqualTo(1);
    }

    @Test
    void theDatabaseRejectsASecondOperatorEvenIfTheGuardIsBypassed() {
        AppUser other = users.save(new AppUser(Workspace.DEFAULT_ID, "second@example.com", "hash", "admin"));
        other.promoteToOperator();

        assertThatThrownBy(() -> users.saveAndFlush(other)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void signupRequestRoundTripsAndApproveClearsTheHash() {
        AppUser operator = users.findByEmail(ADMIN_EMAIL).orElseThrow();
        SignupRequest saved = signups.saveAndFlush(
                SignupRequest.pending("  New@Example.COM ", encoder.encode("a-long-password"), "  Acme  "));

        SignupRequest pending = signups.findByEmailAndStatus("new@example.com", SignupRequest.STATUS_PENDING)
                .orElseThrow();
        assertThat(pending.getId()).isEqualTo(saved.getId());
        assertThat(pending.getRequestedWorkspaceName()).isEqualTo("Acme");
        assertThat(encoder.matches("a-long-password", pending.getPasswordHash())).isTrue();

        pending.approve(Workspace.DEFAULT_ID, operator.getId(), Instant.now());
        signups.saveAndFlush(pending);

        SignupRequest decided = signups.findById(saved.getId()).orElseThrow();
        assertThat(decided.getStatus()).isEqualTo(SignupRequest.STATUS_APPROVED);
        assertThat(decided.getPasswordHash()).isNull();
        assertThat(decided.getDecidedBy()).isEqualTo(operator.getId());
        assertThat(decided.getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
        assertThatThrownBy(() -> decided.reject(operator.getId(), Instant.now()))
                .isInstanceOf(IllegalStateException.class);
    }

    private AdminBootstrap bootstrapFor(String email) {
        return new AdminBootstrap(users, encoder, envFor(email));
    }

    private static MockEnvironment envFor(String email) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("SECSCAN_BOOTSTRAP_ADMIN_EMAIL", email);
        return env;
    }

    private long operatorCount() {
        Long n = jdbc.queryForObject("select count(*) from app_user where is_operator", Long.class);
        return n == null ? 0 : n;
    }
}
