package com.company.secscan.repository.api;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** Select (activate) the given GitLab projects, reached through the named connection, as repositories to scan. */
public record SelectRepositoriesRequest(
        @NotNull UUID connectionId,
        @NotEmpty List<Long> gitlabProjectIds) {
}
