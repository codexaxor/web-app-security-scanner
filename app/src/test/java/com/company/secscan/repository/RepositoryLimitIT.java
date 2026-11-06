package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.api.ApiErrorException;
import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.10 item 8 (D-097): at most 25 ACTIVE repositories per workspace — Default included. A selection batch that
 * would exceed it is refused whole (409 REPOSITORY_LIMIT_REACHED, nothing written); reactivation is refused the
 * same way; inactive repositories don't count; other workspaces are unaffected; repositories already above the
 * limit are kept.
 */
class RepositoryLimitIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";
    private static final long GROUP_ID = 42L;
    private static final UUID DEFAULT = Workspace.DEFAULT_ID;

    @Autowired
    GitLabConnectionService connectionService;
    @Autowired
    RepositoryService repositoryService;
    @Autowired
    RepositoryUpserter upserter;
    @Autowired
    RepositoryLimitGuard guard;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    WorkspaceRepository workspaces;

    private GitLabWireMock gitlab;
    private UUID connId;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
        gitlab.stubVersion().stubTokenSelf("token-self.json").stubGroup("services");
        connectionService.create(DEFAULT, gitlab.baseUrl(), TOKEN, "group", "services");
        connId = connections.findByWorkspaceIdAndGitlabGroupId(DEFAULT, GROUP_ID).orElseThrow().getId();
        gitlab.stubProjectsFromSample(GROUP_ID).stubLanguages(101, "{\"Java\":100.0}")
                .stubLanguages(102, "{\"Java\":100.0}");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    /** {@code active} filler repositories (+ {@code inactive} ones) on a dummy connection of {@code ws}. */
    private List<Repository> fill(UUID ws, int active, int inactive) {
        UUID conn = connections.save(GitlabConnection.forProject(ws, "file:///tmp/filler", "f/f", 9L, "ct",
                "read_api", null)).getId();
        List<Repository> out = new java.util.ArrayList<>();
        for (int i = 0; i < active + inactive; i++) {
            Repository r = new Repository(ws, conn, 5000L + i, "f/r" + i, "main", List.of());
            if (i >= active) {
                r.deactivate("DEACTIVATED_BY_USER");
            }
            out.add(repositories.save(r));
        }
        return out;
    }

    @Test
    void theLimitIsTwentyFiveByDefault() {
        assertThat(guard.maxRepositories()).isEqualTo(25);
    }

    @Test
    void aBatchThatWouldExceedTheLimitIsRefusedWholeAndWritesNothing() {
        fill(DEFAULT, 24, 5);                                   // 24 active (+5 inactive that don't count)

        assertThatThrownBy(() -> repositoryService.select(DEFAULT, connId, List.of(101L, 102L)))
                .isInstanceOfSatisfying(ApiErrorException.class, e -> {
                    assertThat(e.code()).isEqualTo(RepositoryLimitGuard.CODE);
                    assertThat(e.status().value()).isEqualTo(409);
                });
        assertThat(repositories.findByWorkspaceIdAndProviderAndHostAndProviderRepoId(DEFAULT, "gitlab",
                "localhost", "101")).isEmpty();

        assertThat(repositoryService.select(DEFAULT, connId, List.of(101L)).added()).isEqualTo(1);   // 25: fits
        assertThat(repositories.countByWorkspaceIdAndActive(DEFAULT, true)).isEqualTo(25);
        // Re-selecting an already active repository takes no new slot.
        assertThat(repositoryService.select(DEFAULT, connId, List.of(101L)).alreadySelected()).isEqualTo(1);
    }

    @Test
    void reactivationIsRefusedAtTheLimit() {
        List<Repository> filler = fill(DEFAULT, 25, 1);
        Repository inactive = filler.get(25);

        assertThatThrownBy(() -> repositoryService.patch(DEFAULT, inactive.getId(), null, null, true, null))
                .isInstanceOfSatisfying(ApiErrorException.class,
                        e -> assertThat(e.code()).isEqualTo(RepositoryLimitGuard.CODE));
        assertThat(repositories.findById(inactive.getId()).orElseThrow().isActive()).isFalse();

        repositoryService.patch(DEFAULT, filler.get(0).getId(), null, null, false, null);       // free a slot
        assertThat(repositoryService.patch(DEFAULT, inactive.getId(), null, null, true, null).active()).isTrue();
    }

    @Test
    void theInTransactionCheckGuardsEveryNewRowAndOtherWorkspacesAreUnaffected() {
        UUID b = workspaces.save(new Workspace("Tenant B")).getId();
        UUID c = workspaces.save(new Workspace("Tenant C")).getId();
        fill(b, 25, 0);
        List<Repository> overLimit = fill(c, 30, 0);              // pre-existing excess is kept

        UUID bConn = connections.save(GitlabConnection.forProject(b, "file:///tmp/b", "b/b", 8L, "ct", "read_api",
                null)).getId();
        assertThatThrownBy(() -> upserter.upsertSource(b, bConn, "gitlab", "localhost", "7001", "b/new", "main",
                List.of())).isInstanceOfSatisfying(ApiErrorException.class,
                        e -> assertThat(e.code()).isEqualTo(RepositoryLimitGuard.CODE));

        assertThat(repositoryService.select(DEFAULT, connId, List.of(101L, 102L)).added()).isEqualTo(2);
        assertThat(repositories.countByWorkspaceIdAndActive(c, true)).isEqualTo(overLimit.size());
    }
}
