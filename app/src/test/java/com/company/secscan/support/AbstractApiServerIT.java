package com.company.secscan.support;

import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.user.AppUserRepository;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for api integration tests that need a REAL embedded servlet container (a random port) so container-set
 * response headers — notably the session cookie flags (HttpOnly / Secure / SameSite) — can be asserted over HTTP.
 * MockMvc does not emit those. Real Postgres 16 (JVM-wide singleton, as {@link AbstractGitLabIT}); encryption key
 * configured. The session cookie keeps its production default (Secure ON) so the flag can be asserted; the test
 * drives follow-up requests by re-sending the captured JSESSIONID manually (so Secure-over-http is a non-issue).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
public abstract class AbstractApiServerIT {

    private static final String TEST_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("SECSCAN_ENCRYPTION_KEY", () -> TEST_KEY_B64);
        registry.add("SECSCAN_GITLAB_ALLOWED_HOSTS", () -> "localhost");
    }

    @Autowired
    private RepositoryRepository repositoriesForCleanup;
    @Autowired
    private GitlabConnectionRepository connectionsForCleanup;
    @Autowired
    private AppUserRepository appUsersForCleanup;

    @BeforeEach
    void cleanDatabase() {
        repositoriesForCleanup.deleteAll();
        connectionsForCleanup.deleteAll();
        appUsersForCleanup.deleteAll();
    }
}
