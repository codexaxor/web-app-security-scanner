package com.company.secscan.scan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link ScanTask}. */
public interface ScanTaskRepository extends JpaRepository<ScanTask, UUID> {

    List<ScanTask> findByScanId(UUID scanId);

    /** Workspace-scoped task lookup (domain rule 1) — used by the unignore status resolver. */
    List<ScanTask> findByWorkspaceIdAndScanId(UUID workspaceId, UUID scanId);
}
