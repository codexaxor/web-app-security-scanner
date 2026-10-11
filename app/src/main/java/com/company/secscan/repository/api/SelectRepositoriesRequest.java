package com.company.secscan.repository.api;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** Select (activate) the given GitLab projects as repositories to scan. */
public record SelectRepositoriesRequest(@NotEmpty List<Long> gitlabProjectIds) {
}
