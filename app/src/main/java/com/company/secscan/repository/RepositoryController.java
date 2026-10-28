package com.company.secscan.repository;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.repository.api.BranchView;
import com.company.secscan.repository.api.RepositorySettingsRequest;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.repository.api.SelectRepositoriesRequest;
import com.company.secscan.repository.api.SelectResult;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService service;

    public RepositoryController(RepositoryService service) {
        this.service = service;
    }

    @PostMapping
    public SelectResult select(@Valid @RequestBody SelectRepositoriesRequest req) {
        return service.select(req.connectionId(), req.gitlabProjectIds());
    }

    @GetMapping
    public PagedResponse<RepositoryView> list(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "false") boolean hasOpenIssues,
            @RequestParam(defaultValue = "false") boolean lastScanFailed,
            @RequestParam(defaultValue = "severity") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listSelected(search, includeInactive, hasOpenIssues, lastScanFailed, sort, page, size);
    }

    /** One repository with its rollups (the M8.6 repository page header). 404 if not in this workspace. */
    @GetMapping("/{id}")
    public RepositoryView get(@PathVariable UUID id) {
        return service.getRepository(id);
    }

    /** Updates a repository's settings (sensitivity, include-dev-deps, active, scan branch). OD-15 / D-070 / D-076. */
    @PatchMapping("/{id}")
    public RepositoryView patch(@PathVariable UUID id, @RequestBody RepositorySettingsRequest req) {
        return service.patch(id, req.sensitivity(), req.includeDevDeps(), req.active(), req.scanBranch());
    }

    /** The branches for the "Branch to scan" picker (D-076): server-side {@code search} + pagination. */
    @GetMapping("/{id}/branches")
    public PagedResponse<BranchView> branches(
            @PathVariable UUID id,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listBranches(id, search, page, size);
    }
}
