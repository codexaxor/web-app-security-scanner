package com.company.secscan.user;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, UUID> {

    Optional<AppUser> findByEmail(String email);

    /** Whether a real (email) user exists — the AdminBootstrap gate (M8.8 SF-4): a demo user has no '@'. */
    boolean existsByEmailContaining(String fragment);
}
