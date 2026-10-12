package com.company.secscan.repository;

import com.company.secscan.api.PagedResponse;
import com.company.secscan.repository.api.RepositoryView;
import com.company.secscan.repository.api.SelectRepositoriesRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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
}
