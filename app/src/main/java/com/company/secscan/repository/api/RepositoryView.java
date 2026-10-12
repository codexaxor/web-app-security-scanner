package com.company.secscan.repository.api;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.repository.Repository;
import java.util.List;
import java.util.UUID;

/**
 * A selected repository, as returned to the UI, including which connection currently reaches it and, when
 * inactive, why.
 */
public record RepositoryView(
        UUID id,
        long gitlabProjectId,
        String pathWithNamespace,
        String defaultBranch,
        List<String> languages,
        boolean active,
        String sensitivity,
        UUID connectionId,
        String connectionTargetType,
        String connectionLabel,
        String inactiveReason) {

    public static RepositoryView of(Repository r, GitlabConnection connection) {
        return new RepositoryView(r.getId(), r.getGitlabProjectId(), r.getPathWithNamespace(),
                r.getDefaultBranch(), r.getLanguages(), r.isActive(), r.getSensitivity(),
                r.getConnectionId(),
                connection == null ? null : connection.getTargetType(),
                connection == null ? null : connection.targetPath(),
                r.getInactiveReason());
    }
}
