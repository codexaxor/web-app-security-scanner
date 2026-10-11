package com.company.secscan.gitlab;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.gitlab.api.ConnectionRequest;
import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.gitlab.api.ConnectionView;
import com.company.secscan.gitlab.api.ProjectView;
import com.company.secscan.repository.RepositoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/gitlab")
public class GitLabController {

    private final GitLabConnectionService connectionService;
    private final RepositoryService repositoryService;

    public GitLabController(GitLabConnectionService connectionService, RepositoryService repositoryService) {
        this.connectionService = connectionService;
        this.repositoryService = repositoryService;
    }

    /** Tests a connection without saving. Returns {@code ok=false} with a message on failure (HTTP 200). */
    @PostMapping("/connection/test")
    public ConnectionTestResult test(@Valid @RequestBody ConnectionRequest req) {
        return connectionService.test(req.baseUrl(), req.token(), req.groupPath());
    }

    /** Tests and, on success, saves the connection (token encrypted). */
    @PostMapping("/connection")
    public ConnectionTestResult save(@Valid @RequestBody ConnectionRequest req) {
        return connectionService.save(req.baseUrl(), req.token(), req.groupPath());
    }

    @GetMapping("/connection")
    public ConnectionView current() {
        return connectionService.current().map(ConnectionView::of)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No GitLab connection is configured yet."));
    }

    @GetMapping("/projects")
    public PagedResponse<ProjectView> projects(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return repositoryService.listProjects(page, size);
    }
}
