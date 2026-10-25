package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-076 amendment A6: with {@code scan_branch} unset, each scan uses GitLab's CURRENT default branch, refreshed
 * at enqueue — so a default-branch change is treated like a switch. On a GitLab failure the stored default is
 * used and a scan warning is raised.
 */
class DefaultBranchRefreshIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ScanService scanService;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    TokenCipherHolder cipher;

    private GitLabWireMock gitlab;

    @AfterEach
    void stopGitlab() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    private Repository seedRepo(long projectId) {
        gitlab = new GitLabWireMock();
        String ciphertext = cipher.require().encrypt("glpat-test-token");
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, gitlab.baseUrl(), "svc-" + projectId, projectId, ciphertext,
                "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
    }

    @Test
    void refreshesTheDefaultBranchAtEnqueueAndSnapshotsIt() {
        Repository repo = seedRepo(5201L);
        gitlab.stubProjectById(5201L,
                "{\"id\":5201,\"path_with_namespace\":\"svc/5201\",\"default_branch\":\"release\","
                        + "\"empty_repo\":false}");

        ScanView scan = scanService.startManual(repo.getId());

        assertThat(scan.branch()).isEqualTo("release");                           // snapshotted current default
        assertThat(repositories.findById(repo.getId()).orElseThrow().getDefaultBranch()).isEqualTo("release");
    }

    @Test
    void fallsBackToTheStoredDefaultAndWarnsOnGitLabFailure() {
        Repository repo = seedRepo(5202L);
        // No stub for GET /projects/5202 → WireMock 404 → refresh fails → fallback + warning.

        ScanView scan = scanService.startManual(repo.getId());

        assertThat(scan.branch()).isEqualTo("main");                              // last known stored default
        assertThat(warnings.findByScanId(scan.id())).extracting(ScanWarning::getCode)
                .contains("DEFAULT_BRANCH_UNAVAILABLE");
    }
}
