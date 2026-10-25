package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.TriageEventRepository;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanService;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** PATCH /repositories/{id}: validation, activation lifecycle, scan-on-inactive, and 0-triage_event for settings
 *  (OD-15 / D-070, change 1). */
class RepositorySettingsIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService service;
    @Autowired
    ScanService scanService;
    @Autowired
    ScanRepository scans;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    TriageEventRepository events;

    private Repository seedRepo(long projectId) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-" + projectId, projectId, "ct",
                "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), projectId, "svc/" + projectId, "main",
                List.of("Java")));
    }

    @Test
    void rejectsAnUnknownSensitivityWith400() {
        Repository repo = seedRepo(9101L);
        assertThatThrownBy(() -> service.patch(repo.getId(), "paranoid", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void returns404ForARepositoryOutsideTheWorkspace() {
        assertThatThrownBy(() -> service.patch(UUID.randomUUID(), "high", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void deactivateThenReactivateRoundTrip() {
        Repository repo = seedRepo(9102L);
        RepositoryView off = service.patch(repo.getId(), null, null, false);
        assertThat(off.active()).isFalse();
        assertThat(off.inactiveReason()).isEqualTo("DEACTIVATED_BY_USER");

        RepositoryView on = service.patch(repo.getId(), null, null, true);
        assertThat(on.active()).isTrue();
        assertThat(on.inactiveReason()).isNull();
    }

    @Test
    void reactivatingADetachedRepositoryIsRejectedWith409AndLeavesItUnchanged() {
        Repository repo = seedRepo(9103L);
        // Simulate the repo losing its connection (D-020): detached + a non-user inactive reason.
        repo.detachAndDeactivate("NO_ACCESSIBLE_CONNECTION");
        repositories.save(repo);

        assertThatThrownBy(() -> service.patch(repo.getId(), null, null, true))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        Repository after = repositories.findById(repo.getId()).orElseThrow();
        assertThat(after.isActive()).isFalse();
        assertThat(after.getConnectionId()).isNull();
        assertThat(after.getInactiveReason()).isEqualTo("NO_ACCESSIBLE_CONNECTION");
    }

    @Test
    void scanOnAnInactiveRepositoryIsRejectedWith409AndCreatesNoScan() {
        Repository repo = seedRepo(9104L);
        service.patch(repo.getId(), null, null, false); // deactivate
        long before = scans.count();

        assertThatThrownBy(() -> scanService.startManual(repo.getId()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(scans.count()).isEqualTo(before); // no scan/job row created
    }

    @Test
    void changingIncludeDevDepsSavesButWritesNoTriageEvent() {
        Repository repo = seedRepo(9105L);
        long before = events.count();
        RepositoryView v = service.patch(repo.getId(), null, true, null);
        assertThat(v.includeDevDeps()).isTrue();
        assertThat(repositories.findById(repo.getId()).orElseThrow().isIncludeDevDeps()).isTrue();
        assertThat(events.count()).isEqualTo(before); // no API-time triage (D-063 carve-out not widened)
    }
}
