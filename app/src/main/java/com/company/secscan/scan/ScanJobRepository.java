package com.company.secscan.scan;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data store and the PostgreSQL job queue (D-003). {@link #claimable} and {@link #staleLeases} use
 * {@code FOR UPDATE SKIP LOCKED} so that several workers can poll concurrently without blocking each other;
 * both must run inside a transaction and the caller mutates the returned rows within that same transaction.
 */
public interface ScanJobRepository extends JpaRepository<ScanJob, UUID> {

    Optional<ScanJob> findByScanId(UUID scanId);

    /**
     * Locks and returns up to {@code limit} runnable queued jobs (ordered oldest-first), skipping rows another
     * worker already holds. The caller leases them within the same transaction.
     */
    @Query(value = "select * from scan_job where state = 'queued' and run_after <= now() "
            + "order by run_after for update skip locked limit :limit", nativeQuery = true)
    List<ScanJob> claimable(@Param("limit") int limit);

    /**
     * Locks running jobs whose lease is older than {@code threshold} (a worker died or hung). The caller
     * requeues or fails them within the same transaction.
     */
    @Query(value = "select * from scan_job where state = 'running' and locked_at < :threshold "
            + "for update skip locked", nativeQuery = true)
    List<ScanJob> staleLeases(@Param("threshold") Instant threshold);
}
