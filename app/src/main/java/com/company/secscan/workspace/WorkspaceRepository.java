package com.company.secscan.workspace;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    /** Locks one workspace row (fair-use counting, D-097) for the rest of the transaction. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select w from Workspace w where w.id = :id")
    java.util.Optional<Workspace> lockById(@org.springframework.data.repository.query.Param("id") UUID id);
}
