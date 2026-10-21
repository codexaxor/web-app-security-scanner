package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.api.ScanWarningView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** OD-14 item 2: GET /scans/{id}/warnings (paged) and the workspace-wide GET /scans. */
class ScanWarningsApiIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ScanService service;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private Repository seedRepo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + id, id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), id, "svc/" + id, "main", List.of("Java")));
    }

    @Test
    void listsAScansWarningsPagedAndTheWorkspaceWideScans() {
        Repository repo = seedRepo(9401L);
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo.getId()));
        for (int i = 0; i < 3; i++) {
            warnings.save(new ScanWarning(WORKSPACE, repo.getId(), scan.getId(),
                    "MISSING_GRADLE_LOCKFILE", "Missing Gradle lockfile " + i, "build.gradle"));
        }

        PagedResponse<ScanWarningView> firstPage = service.listWarnings(scan.getId(), 0, 2);
        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.content().get(0).code()).isEqualTo("MISSING_GRADLE_LOCKFILE");

        PagedResponse<ScanView> all = service.listAll(0, 50);
        assertThat(all.content()).extracting(ScanView::id).contains(scan.getId());
    }

    @Test
    void warningsForAnUnknownScanReturn404() {
        assertThatThrownBy(() -> service.listWarnings(UUID.randomUUID(), 0, 50))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
