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
import com.company.secscan.repository.api.BranchView;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.repository.api.SelectResult;
import com.company.secscan.scan.BranchName;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Set<String> SENSITIVITIES = Set.of("low", "normal", "high");
    private static final String DEACTIVATED_BY_USER = "DEACTIVATED_BY_USER";

    private final GitLabClient client;
    private final GitlabConnectionRepository connections;
    private final RepositoryRepository repositories;
    private final RepositoryConnectionGuard guard;
    private final RepositoryUpserter upserter;
    private final TokenCipherHolder cipher;
    private final RepositorySummaryService summaries;
    // api-only bean (@Profile("api")); injected via a provider so the worker, which also creates this service,
    // starts without it. patch() runs only from the api controller, where the provider resolves.
    private final org.springframework.beans.factory.ObjectProvider<com.company.secscan.issue.ReScoreService> reScore;

    public RepositoryService(GitLabClient client, GitlabConnectionRepository connections,
                             RepositoryRepository repositories, RepositoryConnectionGuard guard,
                             RepositoryUpserter upserter, TokenCipherHolder cipher,
                             RepositorySummaryService summaries,
                             org.springframework.beans.factory.ObjectProvider<com.company.secscan.issue.ReScoreService> reScore) {
        this.client = client;
        this.connections = connections;
        this.repositories = repositories;
        this.guard = guard;
        this.upserter = upserter;
        this.cipher = cipher;
        this.summaries = summaries;
        this.reScore = reScore;
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
                    p.notSelectableReason(),   // B2: honest "empty" vs "no access to code"
                    existing != null,
                    existing != null && existing.isActive()));
        }
        return new PagedResponse<>(views, pageOfProjects.page(), pageOfProjects.size(),
                pageOfProjects.totalElements(), pageOfProjects.totalPages());
    }

    /**
     * Selects (activates) the given projects through one connection, refreshing their metadata from GitLab.
     * Not transactional at the method level: each project is persisted in its own transaction (via
     * {@link RepositoryUpserter}) so a concurrent insert of the same project can be recovered (see
     * {@link #upsertWithRaceRecovery}) rather than failing the request. The whole batch is validated first, so a
     * bad project id selects nothing.
     */
    public SelectResult select(UUID connectionId, List<Long> gitlabProjectIds) {
        GitlabConnection conn = requireConnection(connectionId);
        String token = cipher.require().decrypt(conn.getTokenCiphertext());
        Map<Long, GitLabProject> byId = exposedProjects(conn, token).stream()
                .collect(Collectors.toMap(GitLabProject::id, Function.identity()));

        // Which submitted projects were ALREADY repositories before this call (B1 honest counts).
        Set<Long> alreadySelectedBefore = repositories.findByWorkspaceId(WORKSPACE).stream()
                .map(Repository::getGitlabProjectId).collect(Collectors.toSet());

        Set<Long> ids = Set.copyOf(gitlabProjectIds);
        // Validate the whole batch before writing anything, so a bad id selects nothing.
        for (Long id : ids) {
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
        }

        List<RepositoryView> result = new ArrayList<>();
        int added = 0;
        int already = 0;
        for (Long id : ids) {
            GitLabProject p = byId.get(id);
            List<String> languages = safeLanguages(conn, token, id);
            Repository repo = upsertWithRaceRecovery(conn.getId(), id, p, languages);
            result.add(RepositoryView.of(repo, ownerOf(repo)));
            if (alreadySelectedBefore.contains(id)) {
                already++;
            } else {
                added++;
            }
        }
        return new SelectResult(added, already, result);
    }

    /**
     * Persists one selected project, tolerating a concurrent select of the same project (two connections, or a
     * double-submit). The loser of the {@code uq_repository_workspace_project} race gets a
     * {@code DataIntegrityViolationException} from the insert; rather than a 500, we reattach/refresh the row the
     * winner inserted — first-wins ownership is preserved.
     */
    private Repository upsertWithRaceRecovery(UUID connectionId, long projectId, GitLabProject p,
                                              List<String> languages) {
        try {
            return upserter.upsert(connectionId, projectId, p.pathWithNamespace(), p.defaultBranch(), languages);
        } catch (DataIntegrityViolationException race) {
            return upserter.reattach(connectionId, projectId, p.pathWithNamespace(), p.defaultBranch(), languages);
        }
    }

    @Transactional(readOnly = true)
    public PagedResponse<RepositoryView> listSelected(int page, int size) {
        Map<UUID, GitlabConnection> byId = connections.findByWorkspaceId(WORKSPACE).stream()
                .collect(Collectors.toMap(GitlabConnection::getId, Function.identity()));
        Map<UUID, RepositorySummaryService.RepositorySummary> rollups = summaries.summariesByRepo(WORKSPACE);
        return PagedResponse.fromPage(
                repositories.findByWorkspaceId(WORKSPACE, PageRequest.of(Math.max(0, page), Math.max(1, size)))
                        .map(r -> RepositoryView.of(r, byId.get(r.getConnectionId()),
                                summaries.summaryOrEmpty(rollups, r.getId()))));
    }

    private static final int SEARCH_MAX = 100;

    /**
     * One page of a repository's GitLab branches for the "Branch to scan" picker (D-076). {@code search} (≤ 100
     * chars, else 400) is passed to GitLab as a server-side filter; {@code size} is capped at 100. Returns an
     * empty page for a repository with no live GitLab connection (e.g. a local {@code file:} connection). 404 if
     * the repository is not in this workspace.
     */
    public PagedResponse<BranchView> listBranches(UUID repositoryId, String search, int page, int size) {
        Repository repo = requireRepo(repositoryId);
        if (search != null && search.length() > SEARCH_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "search must be at most 100 characters.");
        }
        int pageIndex = Math.max(0, page);
        int perPage = Math.min(Math.max(1, size), 100);
        GitlabConnection conn = repo.getConnectionId() == null ? null : ownerOf(repo);
        if (conn == null || isLocal(conn)) {
            return new PagedResponse<>(List.of(), pageIndex, perPage, 0, 0);
        }
        String token = cipher.require().decrypt(conn.getTokenCiphertext());
        GitLabClient.Paged<com.company.secscan.gitlab.dto.GitLabBranch> paged = client.listBranches(
                conn.getBaseUrl(), token, repo.getGitlabProjectId(), search, pageIndex + 1, perPage);
        List<BranchView> views = paged.items().stream().map(BranchView::of).toList();
        return new PagedResponse<>(views, pageIndex, perPage, paged.totalElements(), paged.totalPages());
    }

    /**
     * Updates a repository's settings (OD-15 / D-070). {@code sensitivity} must be low/normal/high (else 400);
     * a change triggers an immediate per-repo re-score (score-only, 0 triage_event). {@code includeDevDeps} is
     * saved and takes effect at the NEXT scan. Activation (change 1): deactivating sets
     * {@code inactive_reason=DEACTIVATED_BY_USER}; reactivating succeeds only for a user-deactivated repo that
     * still has a connection, else 409. A repository outside the workspace → 404.
     */
    /** Settings patch with no scan-branch change (convenience for callers that only touch other fields). */
    @Transactional
    public RepositoryView patch(UUID repositoryId, String sensitivity, Boolean includeDevDeps, Boolean active) {
        return patch(repositoryId, sensitivity, includeDevDeps, active, null);
    }

    @Transactional
    public RepositoryView patch(UUID repositoryId, String sensitivity, Boolean includeDevDeps, Boolean active,
                                JsonNode scanBranch) {
        Repository repo = repositories.findByIdAndWorkspaceId(repositoryId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such repository."));
        boolean rescoreNeeded = false;

        if (scanBranch != null) {                 // key present in the request body
            applyScanBranch(repo, scanBranch);
            log.info("Repository {} scanBranch set", repositoryId);
        }

        if (sensitivity != null) {
            if (!SENSITIVITIES.contains(sensitivity)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "sensitivity must be one of low, normal, high.");
            }
            rescoreNeeded = !sensitivity.equals(repo.getSensitivity());
            repo.setSensitivity(sensitivity);
            log.info("Repository {} sensitivity set", repositoryId);
        }
        if (includeDevDeps != null) {
            repo.setIncludeDevDeps(includeDevDeps); // takes effect at the next scan (no API-time triage, D-063)
            log.info("Repository {} includeDevDeps set", repositoryId);
        }
        if (active != null) {
            applyActivation(repo, active);
            log.info("Repository {} active set", repositoryId);
        }
        repositories.save(repo);

        if (rescoreNeeded) {
            // Immediate, score-only re-score through the existing D-049 path: 0 triage_event, no status change.
            reScore.getObject().rescoreRepository(WORKSPACE, repositoryId);
        }
        GitlabConnection conn = repo.getConnectionId() == null ? null : ownerOf(repo);
        RepositorySummaryService.RepositorySummary summary =
                summaries.summaryOrEmpty(summaries.summariesByRepo(WORKSPACE), repositoryId);
        return RepositoryView.of(repo, conn, summary);
    }

    /**
     * Applies a {@code scanBranch} change (D-076). JSON {@code null} resets to the default branch; a string sets
     * the branch after validating its name (git ref-format) and existence in GitLab (the authoritative check);
     * any other JSON type is a 400. Setting a non-null branch on an inactive repository is a 409.
     */
    private void applyScanBranch(Repository repo, JsonNode scanBranch) {
        if (scanBranch.isNull()) {
            repo.setScanBranch(null);             // follow the GitLab default branch again
            return;
        }
        if (!scanBranch.isTextual()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "scanBranch must be a string or null.");
        }
        if (!repo.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Set the scan branch while the repository is active.");
        }
        String branch = scanBranch.asText();
        if (!BranchName.isValid(branch)) {
            throw new GitLabException(GitLabError.BRANCH_NOT_FOUND, "Not a valid branch name.");
        }
        GitlabConnection conn = repo.getConnectionId() == null ? null : ownerOf(repo);
        if (conn != null && !isLocal(conn)) {
            // Authoritative existence check: GitLab 404 → BRANCH_NOT_FOUND (→ 400). Token never logged.
            String token = cipher.require().decrypt(conn.getTokenCiphertext());
            client.getBranch(conn.getBaseUrl(), token, repo.getGitlabProjectId(), branch);
        }
        repo.setScanBranch(branch);
    }

    private Repository requireRepo(UUID repositoryId) {
        return repositories.findByIdAndWorkspaceId(repositoryId, WORKSPACE)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such repository."));
    }

    private static boolean isLocal(GitlabConnection conn) {
        return conn.getBaseUrl() != null && conn.getBaseUrl().startsWith("file:");
    }

    private void applyActivation(Repository repo, boolean active) {
        if (!active) {
            if (repo.isActive()) {
                repo.deactivate(DEACTIVATED_BY_USER);
            }
            return;
        }
        if (repo.isActive()) {
            return; // already active — idempotent
        }
        // Reactivation is allowed only for a user-deactivated repository that still has a connection; any other
        // inactive_reason (e.g. NO_ACCESSIBLE_CONNECTION) or a detached repo must be re-selected via a connection.
        if (!DEACTIVATED_BY_USER.equals(repo.getInactiveReason()) || repo.getConnectionId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This repository cannot be reactivated here; re-select the project via a connection.");
        }
        repo.activate();
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
