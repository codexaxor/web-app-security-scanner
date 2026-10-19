package com.company.secscan.intel;

import org.springframework.data.jpa.repository.JpaRepository;

/** Store for the global fix-time config (D-050), seeded in V13. Keyed on category; no workspace scope. */
public interface FixTimeConfigRepository extends JpaRepository<FixTimeConfig, String> {
}
