package com.company.secscan.intel;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** Store for the global CISA KEV cache (D-046/D-050). Keyed on CVE; no workspace scope (global reference data). */
public interface KevEntryRepository extends JpaRepository<KevEntry, String> {

    /** The CVEs from {@code cves} that are present in the KEV catalog — the KEV match for a sub-issue's aliases. */
    @org.springframework.data.jpa.repository.Query("select k.cve from KevEntry k where k.cve in :cves")
    List<String> findPresentCves(Collection<String> cves);
}
