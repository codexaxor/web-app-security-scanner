package com.company.secscan.intel;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Store for the global FIRST EPSS cache (D-047/D-050). Keyed on CVE; no workspace scope (global reference data). */
public interface EpssScoreRepository extends JpaRepository<EpssScore, String> {

    /** EPSS rows for the given CVEs — the scorer exposes the max probability among a sub-issue's aliases. */
    List<EpssScore> findByCveIn(Collection<String> cves);
}
