package com.company.secscan.repository;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.repository.api.RepositorySettingsRequest;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.repository.api.SelectRepositoriesRequest;
import jakarta.validation.Valid;
import java.util.List;
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
    public List<RepositoryView> select(@Valid @RequestBody SelectRepositoriesRequest req) {
        return service.select(req.connectionId(), req.gitlabProjectIds());
    }

    @GetMapping
    public PagedResponse<RepositoryView> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return service.listSelected(page, size);
    }

    /** Updates a repository's settings (sensitivity, include-dev-deps, active). OD-15 / D-070. */
    @PatchMapping("/{id}")
    public RepositoryView patch(@PathVariable UUID id, @RequestBody RepositorySettingsRequest req) {
        return service.patch(id, req.sensitivity(), req.includeDevDeps(), req.active());
    }
}
