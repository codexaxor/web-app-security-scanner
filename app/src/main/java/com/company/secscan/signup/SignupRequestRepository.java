package com.company.secscan.signup;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Store for {@link SignupRequest}. <b>Workspace-agnostic by design</b> (CLAUDE.md rule 1 exception, D-098): a
 * pending request has no workspace yet; it is read only by sign-up, the login pending-check and the operator API.
 */
public interface SignupRequestRepository extends JpaRepository<SignupRequest, UUID> {

    /** The (at most one, V23 partial unique index) pending request for a lowercased email. */
    Optional<SignupRequest> findByEmailAndStatus(String email, String status);

    /** The operator's queue (D-094): requests in one status, oldest first, paginated. */
    Page<SignupRequest> findByStatusOrderByCreatedAtAscIdAsc(String status, Pageable pageable);

    /** Locks one request for a decision so two concurrent approve/reject calls cannot both act on it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from SignupRequest r where r.id = :id")
    Optional<SignupRequest> lockById(@Param("id") UUID id);
}
