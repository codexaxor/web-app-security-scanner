package com.company.secscan.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    /** Whether a real (email) user exists — the AdminBootstrap gate (M8.8 SF-4): a demo user has no '@'. */
    boolean existsByEmailContaining(String fragment);

    /**
     * Whether the platform operator exists (D-094). Workspace-agnostic by design: the operator is a platform-level
     * role, and this guard (AdminBootstrap) is what keeps a second operator from ever being created silently.
     */
    boolean existsByOperatorTrue();
}
