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
 * Base for worker-profile integration tests: real Postgres 16, a configured encryption key, and the scheduled
 * claim loop DISABLED so each test drives the pipeline deterministically ({@code claimBatch} then
 * {@code process}). The container is a JVM-wide singleton reused across IT classes (see {@link AbstractGitLabIT}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("worker")
public abstract class AbstractScanWorkerIT {

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
        // Drive the pipeline from the test; no background claiming.
        registry.add("secscan.scanner.polling-enabled", () -> "false");
    }

    @Autowired
    private ScanFindingRepository scanFindingsForCleanup;
    @Autowired
    private ScanTaskRepository scanTasksForCleanup;
    @Autowired
    private ScanJobRepository scanJobsForCleanup;
    @Autowired
    private ScanRepository scansForCleanup;
    @Autowired
    private RepositoryRepository repositoriesForCleanup;
    @Autowired
    private GitlabConnectionRepository connectionsForCleanup;

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
