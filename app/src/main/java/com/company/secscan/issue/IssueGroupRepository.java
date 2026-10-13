package com.company.secscan.issue;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link IssueGroup}. */
public interface IssueGroupRepository extends JpaRepository<IssueGroup, UUID> {

    Optional<IssueGroup> findByWorkspaceIdAndRepositoryIdAndGroupKey(UUID workspaceId, UUID repositoryId,
                                                                     String groupKey);

    /**
     * Server-side paginated, workspace-scoped feed with optional repository/type/status filters (a {@code null}
     * filter matches all). Ordered newest-first by first-seen (severity-ordered feed lands with M5 scoring).
     */
    @Query("""
            select g from IssueGroup g
            where g.workspaceId = :workspaceId
              and (:repositoryId is null or g.repositoryId = :repositoryId)
              and (:type is null or g.type = :type)
              and (:status is null or g.status = :status)
            order by g.firstSeenAt desc, g.id desc
            """)
    Page<IssueGroup> search(@Param("workspaceId") UUID workspaceId,
                            @Param("repositoryId") UUID repositoryId,
                            @Param("type") String type,
                            @Param("status") String status,
                            Pageable pageable);

    Optional<IssueGroup> findByIdAndWorkspaceId(UUID id, UUID workspaceId);
}
