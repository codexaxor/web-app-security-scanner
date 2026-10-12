package com.company.secscan.gitlab;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.api.ConnectionRemovalResult;
import com.company.secscan.gitlab.api.ConnectionRequest;
import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.gitlab.api.ConnectionView;
import com.company.secscan.gitlab.api.ProjectView;
import com.company.secscan.gitlab.api.UpdateConnectionRequest;
import com.company.secscan.repository.RepositoryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gitlab/connections")
public class GitLabController {

    private final GitLabConnectionService connectionService;
    private final RepositoryService repositoryService;

    public GitLabController(GitLabConnectionService connectionService, RepositoryService repositoryService) {
        this.connectionService = connectionService;
        this.repositoryService = repositoryService;
    }

    /** Tests a connection without saving. Returns {@code ok=false} with a message on failure (HTTP 200). */
    @PostMapping("/test")
    public ConnectionTestResult test(@Valid @RequestBody ConnectionRequest req) {
        return connectionService.test(req.baseUrl(), req.token(), req.targetType(), req.path());
    }

    /** Tests and, on success, creates a new connection (token encrypted). */
    @PostMapping
    public ConnectionTestResult create(@Valid @RequestBody ConnectionRequest req) {
        return connectionService.create(req.baseUrl(), req.token(), req.targetType(), req.path());
    }

    /** Rotates an existing connection's token (same target). */
    @PutMapping("/{id}")
    public ConnectionTestResult update(@PathVariable UUID id, @Valid @RequestBody UpdateConnectionRequest req) {
        return connectionService.update(id, req.baseUrl(), req.token(), req.path());
    }

    @GetMapping
    public PagedResponse<ConnectionView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<ConnectionView> views = connectionService.list().stream().map(ConnectionView::of).toList();
        return PagedResponse.fromList(views, page, size);
    }

    /** Removes a connection; its repositories are reassigned or deactivated, never deleted (D-020). */
    @DeleteMapping("/{id}")
    public ConnectionRemovalResult delete(@PathVariable UUID id) {
        return connectionService.delete(id);
    }

    @GetMapping("/{id}/projects")
    public PagedResponse<ProjectView> projects(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return repositoryService.listProjects(id, page, size);
    }
}
