package com.company.secscan.support;

import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanJobRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTaskRepository;
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
 * Base for GitLab integration tests: real Postgres 16 and a configured encryption key. The container is a
 * JVM-wide singleton (started once, reused across IT classes, torn down by Ryuk at exit) rather than managed by
 * {@code @Testcontainers} — which would stop a shared static container after the first subclass finished.
 * Subclasses start their own {@link GitLabWireMock} and clean the DB between tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("api")
public abstract class AbstractGitLabIT {

    // Obviously-fake fixed 32-byte key for tests.
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
        // The mock GitLab is served on http://localhost:<port>; allow it (loopback may use http, D-018).
        registry.add("SECSCAN_GITLAB_ALLOWED_HOSTS", () -> "localhost");
    }

    @Autowired
    private RepositoryRepository repositoriesForCleanup;
    @Autowired
    private GitlabConnectionRepository connectionsForCleanup;
    @Autowired
    private ScanFindingRepository scanFindingsForCleanup;
    @Autowired
    private ScanTaskRepository scanTasksForCleanup;
    @Autowired
    private ScanJobRepository scanJobsForCleanup;
    @Autowired
    private ScanRepository scansForCleanup;

    /**
     * The container is shared across IT classes, so clean the tables before each test in FK-safe order (scans
     * reference repositories, which reference connections). Runs before each subclass's own {@code @BeforeEach}.
     */
    @BeforeEach
    void cleanDatabase() {
        scanFindingsForCleanup.deleteAllInBatch();
        scanTasksForCleanup.deleteAllInBatch();
        scanJobsForCleanup.deleteAllInBatch();
        scansForCleanup.deleteAllInBatch();
        repositoriesForCleanup.deleteAllInBatch();
        connectionsForCleanup.deleteAllInBatch();
    }
}
