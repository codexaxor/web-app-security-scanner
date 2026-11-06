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

    /** Workspace-scoped lookup (domain rule 1): the job is only visible within its own workspace. */
    Optional<ScanJob> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    /**
     * Locks and returns up to {@code limit} runnable queued jobs (ordered oldest-first), skipping rows another
     * worker already holds. The caller leases them within the same transaction.
     */
    // D-097 fair use: at most ONE running job per workspace. A workspace that already has a running job is skipped
    // (its queued jobs wait — never rejected), and at most the oldest runnable queued job of each workspace is
    // claimable, so one tenant's backlog cannot hold the worker. The per-workspace subqueries look across the
    // queue by design (part of the claim trust root, CLAUDE.md rule 1). Callers serialise claims with
    // ScanClaimer's advisory lock so two workers cannot both start a job for the same workspace.
    @Query(value = "select * from scan_job j where j.state = 'queued' and j.run_after <= now() "
            + "and not exists (select 1 from scan_job r where r.workspace_id = j.workspace_id "
            + "                and r.state = 'running') "
            + "and j.id = (select q.id from scan_job q where q.workspace_id = j.workspace_id "
            + "            and q.state = 'queued' and q.run_after <= now() order by q.run_after, q.id limit 1) "
            + "order by j.run_after, j.id for update skip locked limit :limit", nativeQuery = true)
    List<ScanJob> claimable(@Param("limit") int limit);

    /**
     * Serialises claimers for the rest of the transaction (D-097): with the lock held, a claimer sees every job a
     * previous claimer leased (committed before it released the lock), so the per-workspace running check holds.
     */
    @Query(value = "select pg_advisory_xact_lock(:key)::text", nativeQuery = true)
    String lockClaims(@Param("key") long key);

    /**
     * Locks running jobs whose lease is older than {@code threshold} (a worker died or hung). The caller
     * requeues or fails them within the same transaction.
     */
    @Query(value = "select * from scan_job where state = 'running' and locked_at < :threshold "
            + "for update skip locked", nativeQuery = true)
    List<ScanJob> staleLeases(@Param("threshold") Instant threshold);
}
