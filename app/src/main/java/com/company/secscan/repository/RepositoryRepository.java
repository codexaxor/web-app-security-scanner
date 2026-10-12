package com.company.secscan.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data store for {@link Repository}. (The doubled name is the Spring Data interface for the
 * {@code Repository} entity — the entity carries the domain name from the spec.)
 */
public interface RepositoryRepository extends JpaRepository<Repository, UUID> {

    Page<Repository> findByWorkspaceId(UUID workspaceId, Pageable pageable);

    List<Repository> findByWorkspaceId(UUID workspaceId);

    Optional<Repository> findByWorkspaceIdAndGitlabProjectId(UUID workspaceId, long gitlabProjectId);

    List<Repository> findByWorkspaceIdAndGitlabProjectIdIn(UUID workspaceId, List<Long> gitlabProjectIds);

    List<Repository> findByWorkspaceIdAndConnectionId(UUID workspaceId, UUID connectionId);
}
