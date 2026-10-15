package com.company.secscan.scan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link ScanWarning} (M4 scan-health warnings). */
public interface ScanWarningRepository extends JpaRepository<ScanWarning, UUID> {

    List<ScanWarning> findByScanId(UUID scanId);

    List<ScanWarning> findByWorkspaceIdAndRepositoryId(UUID workspaceId, UUID repositoryId);
}
