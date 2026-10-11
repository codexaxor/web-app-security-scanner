package com.company.secscan.repository;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.gitlab.api.ProjectView;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.workspace.Workspace;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lists a connection's projects for selection, and persists the chosen repositories. Archived and
 * marked-for-deletion projects are excluded; empty repositories are listed but not selectable. A repository is
 * stored once per workspace (keyed on {@code gitlab_project_id}); the first connection to select it owns it, and
 * ownership only moves when that connection is removed (D-020).
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);
    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final GitLabClient client;
    private final GitlabConnectionRepository connections;
    private final RepositoryRepository repositories;
    private final RepositoryConnectionGuard guard;
    private final TokenCipherHolder cipher;

    public RepositoryService(GitLabClient client, GitlabConnectionRepository connections,
                             RepositoryRepository repositories, RepositoryConnectionGuard guard,
                             TokenCipherHolder cipher) {
        this.client = client;
        this.connections = connections;
        this.repositories = repositories;
        this.guard = guard;
        this.cipher = cipher;
    }

    /**
     * Lists selectable projects for one connection (a group's projects, or a single project), paginated. Not
     * transactional at the method level: a failed GitLab call must still let {@link #exposedProjects} commit a
     * provisional deactivation (via {@link RepositoryConnectionGuard}) before the error propagates.
     */
    public PagedResponse<ProjectView> listProjects(UUID connectionId, int page, int size) {
        GitlabConnection conn = requireConnection(connectionId);
        String token = cipher.require().decrypt(conn.getTokenCiphertext());

        List<GitLabProject> visible = exposedProjects(conn, token).stream()
                .filter(p -> !p.archived() && !p.markedForDeletion())
                .sorted(Comparator.comparing(GitLabProject::pathWithNamespace))
                .toList();

        Map<Long, Repository> selected = repositories.findByWorkspaceId(WORKSPACE).stream()
                .collect(Collectors.toMap(Repository::getGitlabProjectId, Function.identity()));

        // Compute the requested page first, then fetch languages only for that page (bounds the extra calls).
        PagedResponse<GitLabProject> pageOfProjects = PagedResponse.fromList(visible, page, size);
        List<ProjectView> views = new ArrayList<>();
        for (GitLabProject p : pageOfProjects.content()) {
            Repository existing = selected.get(p.id());
            views.add(new ProjectView(
                    p.id(),
                    p.pathWithNamespace(),
                    p.defaultBranch(),
                    p.isEmpty() ? List.of() : safeLanguages(conn, token, p.id()),
                    !p.isEmpty(),
                    p.isEmpty() ? "empty repository" : null,
                    existing != null,
                    existing != null && existing.isActive()));
        }
        return new PagedResponse<>(views, pageOfProjects.page(), pageOfProjects.size(),
                pageOfProjects.totalElements(), pageOfProjects.totalPages());
    }

    /** Selects (activates) the given projects through one connection, refreshing their metadata from GitLab. */
    @Transactional
    public List<RepositoryView> select(UUID connectionId, List<Long> gitlabProjectIds) {
        GitlabConnection conn = requireConnection(connectionId);
        String token = cipher.require().decrypt(conn.getTokenCiphertext());
        Map<Long, GitLabProject> byId = exposedProjects(conn, token).stream()
                .collect(Collectors.toMap(GitLabProject::id, Function.identity()));

        List<RepositoryView> result = new ArrayList<>();
        for (Long id : Set.copyOf(gitlabProjectIds)) {
            GitLabProject p = byId.get(id);
            if (p == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Project " + id + " is not reachable through this connection.");
            }
            if (p.archived() || p.markedForDeletion()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Project " + p.pathWithNamespace() + " is archived or scheduled for deletion.");
            }
            if (p.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Project " + p.pathWithNamespace() + " is an empty repository and cannot be scanned.");
            }
            List<String> languages = safeLanguages(conn, token, id);
            Repository repo = repositories.findByWorkspaceIdAndGitlabProjectId(WORKSPACE, id)
                    .map(r -> {
                        // First connection to select a project keeps ownership (dedup, D-020); re-selecting it
                        // just reactivates and refreshes it.
                        r.activate();
                        r.refreshFromGitlab(p.pathWithNamespace(), p.defaultBranch(), languages);
                        return r;
                    })
                    .orElseGet(() -> new Repository(WORKSPACE, conn.getId(), id, p.pathWithNamespace(),
                            p.defaultBranch(), languages));
            result.add(RepositoryView.of(repositories.save(repo), ownerOf(repo)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PagedResponse<RepositoryView> listSelected(int page, int size) {
        Map<UUID, GitlabConnection> byId = connections.findByWorkspaceId(WORKSPACE).stream()
                .collect(Collectors.toMap(GitlabConnection::getId, Function.identity()));
        return PagedResponse.fromPage(
                repositories.findByWorkspaceId(WORKSPACE, PageRequest.of(Math.max(0, page), Math.max(1, size)))
                        .map(r -> RepositoryView.of(r, byId.get(r.getConnectionId()))));
    }

    // --- internals ---

    /** The projects this connection exposes: a group's projects (subgroups included), or its single project. */
    private List<GitLabProject> exposedProjects(GitlabConnection conn, String token) {
        try {
            List<GitLabProject> projects = conn.isProject()
                    ? List.of(client.resolveProject(conn.getBaseUrl(), token, conn.getProjectFullPath()))
                    : client.listGroupProjects(conn.getBaseUrl(), token, conn.getGitlabGroupId());
            // The connection worked: confirm any provisionally reassigned repositories on it.
            guard.confirm(conn.getId());
            return projects;
        } catch (GitLabException e) {
            if (isAccessDenied(e.getError())) {
                // A provisional reassignment target that cannot actually reach its repos: deactivate them
                // (committed independently so it survives this call's propagating error).
                guard.deactivate(conn.getId());
            }
            throw e;
        }
    }

    private static boolean isAccessDenied(GitLabError error) {
        return switch (error) {
            case INVALID_TOKEN, TOKEN_EXPIRED, MISSING_SCOPES,
                    GROUP_NOT_FOUND, PROJECT_NOT_FOUND, NOT_FOUND -> true;
            default -> false;
        };
    }

    private GitlabConnection requireConnection(UUID connectionId) {
        return connections.findByIdAndWorkspaceId(connectionId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such GitLab connection in this workspace."));
    }

    private GitlabConnection ownerOf(Repository repo) {
        return connections.findByIdAndWorkspaceId(repo.getConnectionId(), WORKSPACE).orElse(null);
    }

    /** Languages for one project; a failure for a single project must not fail the whole listing. */
    private List<String> safeLanguages(GitlabConnection conn, String token, long projectId) {
        try {
            return client.projectLanguages(conn.getBaseUrl(), token, projectId);
        } catch (GitLabException e) {
            log.debug("Could not fetch languages for project {}: {}", projectId, e.getError());
            return List.of();
        }
    }
}
