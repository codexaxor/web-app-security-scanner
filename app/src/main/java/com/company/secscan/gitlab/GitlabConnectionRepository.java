package com.company.secscan.gitlab;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitlabConnectionRepository extends JpaRepository<GitlabConnection, UUID> {

    List<GitlabConnection> findByWorkspaceId(UUID workspaceId);

    Optional<GitlabConnection> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<GitlabConnection> findByWorkspaceIdAndGitlabGroupId(UUID workspaceId, long gitlabGroupId);

    Optional<GitlabConnection> findByWorkspaceIdAndGitlabProjectId(UUID workspaceId, long gitlabProjectId);
}
