package com.company.secscan.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data store for {@link RepositoryEvent} (D-028: repository lifecycle audit trail). */
public interface RepositoryEventRepository extends JpaRepository<RepositoryEvent, UUID> {

    List<RepositoryEvent> findByRepositoryIdOrderByAtAsc(UUID repositoryId);
}
