package com.company.secscan.scan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link ScanFinding} (raw, pre-reconcile findings; D-024). */
public interface ScanFindingRepository extends JpaRepository<ScanFinding, UUID> {

    List<ScanFinding> findByScanId(UUID scanId);

    List<ScanFinding> findByWorkspaceIdAndScanId(UUID workspaceId, UUID scanId);

    long countByScanId(UUID scanId);
}
