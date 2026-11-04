package com.company.secscan.signup;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for {@link SignupRequest}. <b>Workspace-agnostic by design</b> (CLAUDE.md rule 1 exception, D-098): a
 * pending request has no workspace yet; it is read only by sign-up, the login pending-check and the operator API.
 */
public interface SignupRequestRepository extends JpaRepository<SignupRequest, UUID> {

    /** The (at most one, V23 partial unique index) pending request for a lowercased email. */
    Optional<SignupRequest> findByEmailAndStatus(String email, String status);
}
