package com.company.secscan.scan;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link ScanTask}. */
public interface ScanTaskRepository extends JpaRepository<ScanTask, UUID> {

    List<ScanTask> findByScanId(UUID scanId);
}
