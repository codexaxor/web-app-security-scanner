package com.company.secscan.scan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link Scan}. Every lookup is scoped by workspace (CLAUDE.md domain rule 1). */
public interface ScanRepository extends JpaRepository<Scan, UUID> {

    Optional<Scan> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Page<Scan> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId, Pageable pageable);

    /** In-flight scans for a repository (queued or running): used to avoid enqueuing a duplicate. */
    List<Scan> findByWorkspaceIdAndRepositoryIdAndStatusIn(UUID workspaceId, UUID repositoryId,
                                                           List<String> statuses);
}
