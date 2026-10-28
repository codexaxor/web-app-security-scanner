package com.company.secscan.source;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.api.ConnectionView;
import com.company.secscan.repository.RepositoryService;
import com.company.secscan.repository.api.RepositoryView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Provider-neutral "Sources" API (M8.7 item 9): add a GitHub owner/repo or a public repository, then list and
 * select that connection's repositories. GitLab connections keep {@code /api/v1/gitlab/connections}; all
 * connections (every provider) are listed there too. Tokens are entered only here, never logged (D-014).
 */
@RestController
@RequestMapping("/api/v1/sources")
public class SourceController {

    private final SourceConnectionService sources;
    private final RepositoryService repositoryService;

    public SourceController(SourceConnectionService sources, RepositoryService repositoryService) {
        this.sources = sources;
        this.repositoryService = repositoryService;
    }

    public record AddGitHubOwnerRequest(@NotBlank String ownerLogin, @NotBlank String token) {
    }

    public record AddGitHubRepoRequest(@NotBlank String fullName, @NotBlank String token) {
    }

    public record AddPublicRepoRequest(@NotBlank String url) {
    }

    public record SelectSourceRequest(List<String> providerRepoIds) {
    }

    @PostMapping("/github/owner")
    public ConnectionView addGitHubOwner(@Valid @RequestBody AddGitHubOwnerRequest req) {
        return ConnectionView.of(sources.createGitHubOwner(req.ownerLogin(), req.token()));
    }

    @PostMapping("/github/repo")
    public ConnectionView addGitHubRepo(@Valid @RequestBody AddGitHubRepoRequest req) {
        return ConnectionView.of(sources.createGitHubRepo(req.fullName(), req.token()));
    }

    @PostMapping("/public")
    public ConnectionView addPublicRepo(@Valid @RequestBody AddPublicRepoRequest req) {
        return ConnectionView.of(sources.createPublicRepo(req.url()));
    }

    /** One page of a connection's selectable repositories (GitHub owner / single repo / public). */
    @GetMapping("/connections/{id}/repositories")
    public PagedResponse<SourceRepo> repositories(
            @PathVariable UUID id,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return repositoryService.listSourceRepositories(id, search, page, size);
    }

    /** Selects repositories by the provider's id through a GitHub/public connection. */
    @PostMapping("/connections/{id}/select")
    public List<RepositoryView> select(@PathVariable UUID id, @RequestBody SelectSourceRequest req) {
        return repositoryService.selectSource(id, req.providerRepoIds() == null ? List.of() : req.providerRepoIds());
    }
}
