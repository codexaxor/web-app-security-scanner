package com.company.secscan.repository;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
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
 * Lists the connected group's projects for selection, and persists the chosen repositories. Archived and
 * marked-for-deletion projects are excluded; empty repositories are listed but not selectable.
 */
@Service
public class RepositoryService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryService.class);
    private static final java.util.UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final GitLabClient client;
    private final GitlabConnectionRepository connections;
    private final RepositoryRepository repositories;
    private final TokenCipherHolder cipher;

    public RepositoryService(GitLabClient client, GitlabConnectionRepository connections,
                             RepositoryRepository repositories, TokenCipherHolder cipher) {
        this.client = client;
        this.connections = connections;
        this.repositories = repositories;
        this.cipher = cipher;
    }

    /** Lists selectable projects from the connected group, paginated server-side. */
    @Transactional(readOnly = true)
    public PagedResponse<ProjectView> listProjects(int page, int size) {
        GitlabConnection conn = requireConnection();
        String token = cipher.require().decrypt(conn.getTokenCiphertext());

        List<GitLabProject> visible = client.listGroupProjects(conn.getBaseUrl(), token, conn.getGitlabGroupId())
                .stream()
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

    /** Selects (activates) the given projects, refreshing their metadata from GitLab. */
    @Transactional
    public List<RepositoryView> select(List<Long> gitlabProjectIds) {
        GitlabConnection conn = requireConnection();
        String token = cipher.require().decrypt(conn.getTokenCiphertext());
        Map<Long, GitLabProject> byId = client.listGroupProjects(conn.getBaseUrl(), token, conn.getGitlabGroupId())
                .stream().collect(Collectors.toMap(GitLabProject::id, Function.identity()));

        List<RepositoryView> result = new ArrayList<>();
        for (Long id : Set.copyOf(gitlabProjectIds)) {
            GitLabProject p = byId.get(id);
            if (p == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Project " + id + " is not in the connected group.");
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
                        r.setActive(true);
                        r.refreshFromGitlab(p.pathWithNamespace(), p.defaultBranch(), languages);
                        return r;
                    })
                    .orElseGet(() -> new Repository(WORKSPACE, conn.getId(), id, p.pathWithNamespace(),
                            p.defaultBranch(), languages));
            result.add(RepositoryView.of(repositories.save(repo)));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public PagedResponse<RepositoryView> listSelected(int page, int size) {
        return PagedResponse.fromPage(
                repositories.findByWorkspaceId(WORKSPACE, PageRequest.of(Math.max(0, page), Math.max(1, size)))
                        .map(RepositoryView::of));
    }

    private GitlabConnection requireConnection() {
        return connections.findByWorkspaceId(WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "No GitLab connection is configured yet."));
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
