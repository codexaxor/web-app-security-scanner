package com.company.secscan.intel;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Store for the global EOL intel cache (D-055/D-050). Keyed on (product, cycle); no workspace scope (global
 * reference data). Read by the worker's EOL enricher and the scorer; written weekly by {@code EolSyncService}.
 */
public interface EolCycleRepository extends JpaRepository<EolCycle, EolCycle.Key> {

    Optional<EolCycle> findByProductAndCycle(String product, String cycle);

    /** All cached cycles for a product — the published cycle keys the detector's cycle is normalized against. */
    List<EolCycle> findByProduct(String product);
}
