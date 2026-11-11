package com.company.secscan.scan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data store for {@link ScanWarning} (M4 scan-health warnings). */
public interface ScanWarningRepository extends JpaRepository<ScanWarning, UUID> {

    List<ScanWarning> findByScanId(UUID scanId);

    List<ScanWarning> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);

    /** One scan's warnings, paged — the warnings drawer (OD-14 item 2). Workspace-scoped (rule 1). */
    Page<ScanWarning> findByWorkspaceIdAndScanId(UUID workspaceId, UUID scanId, Pageable pageable);

    /** Warning counts for a page of scans, in one query (no N+1). Workspace-scoped. Rows [scanId, count]. */
    @Query("select w.scanId, count(w) from ScanWarning w where w.workspaceId = :ws and w.scanId in :scanIds "
            + "group by w.scanId")
    List<Object[]> countByScanIds(@Param("ws") UUID ws, @Param("scanIds") List<UUID> scanIds);
}
