package com.company.secscan.repository.api;

import com.company.secscan.repository.Repository;
import java.util.List;
import java.util.UUID;

/** A selected repository, as returned to the UI. */
public record RepositoryView(
        UUID id,
        long gitlabProjectId,
        String pathWithNamespace,
        String defaultBranch,
        List<String> languages,
        boolean active,
        String sensitivity) {

    public static RepositoryView of(Repository r) {
        return new RepositoryView(r.getId(), r.getGitlabProjectId(), r.getPathWithNamespace(),
                r.getDefaultBranch(), r.getLanguages(), r.isActive(), r.getSensitivity());
    }
}
