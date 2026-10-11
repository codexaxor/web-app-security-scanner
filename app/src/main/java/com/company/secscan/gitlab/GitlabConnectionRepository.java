package com.company.secscan.gitlab;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitlabConnectionRepository extends JpaRepository<GitlabConnection, UUID> {

    Optional<GitlabConnection> findByWorkspaceId(UUID workspaceId);
}
