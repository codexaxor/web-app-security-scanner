package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.intel.EolCycle;
import com.company.secscan.intel.EolCycleRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-proves the EOL enricher's per-finding behaviour: it normalizes the detected cycle to the feed's key
 * (review BUG 2) and keeps only covered, issue-worthy findings; an uncovered detection is dropped with an
 * EOL_COVERAGE_GAP warning (it does NOT make the whole scanner unsolvable — BUG 1 moved that to a per-finding
 * freeze in reconcile).
 */
class EolEnricherTest {

    private final EolCycleRepository repo = Mockito.mock(EolCycleRepository.class);
    private final EolEnricher enricher = new EolEnricher(repo);

    private static RawFinding candidate(String product, String detectedCycle) {
        return RawFinding.eol(product, detectedCycle, "pom.xml");
    }

    @Test
    void pastEolCandidateIsKept() {
        EolCycle c = new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 1, 1), "2.1.18", null, null,
                false, false);
        when(repo.findByProduct("spring-boot")).thenReturn(List.of(c));
        when(repo.findByProductAndCycle("spring-boot", "2.1")).thenReturn(Optional.of(c));

        EolEnricher.Result r = enricher.enrich(List.of(candidate("spring-boot", "2.1")));

        assertThat(r.issues()).singleElement().satisfies(f -> {
            assertThat(f.packageName()).isEqualTo("spring-boot");
            assertThat(f.installedVersion()).isEqualTo("2.1");
        });
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void dotnetDetectedMajorMinorIsNormalizedToTheFeedBareMajor() {
        // BUG 2: the detector emits "8.0" but endoflife.date keys .NET as "8"; the kept finding carries "8".
        EolCycle c = new EolCycle("dotnet", "8", true, LocalDate.of(2026, 1, 1), "8.0.11", null, null, false, false);
        when(repo.findByProduct("dotnet")).thenReturn(List.of(c));
        when(repo.findByProductAndCycle("dotnet", "8")).thenReturn(Optional.of(c));

        EolEnricher.Result r = enricher.enrich(List.of(candidate("dotnet", "8.0")));

        assertThat(r.issues()).singleElement().satisfies(f -> assertThat(f.installedVersion()).isEqualTo("8"));
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void maintainedCandidateIsDropped() {
        EolCycle c = new EolCycle("nodejs", "22", false, LocalDate.now().plusYears(2), "22.1.0", null, null,
                true, true);
        when(repo.findByProduct("nodejs")).thenReturn(List.of(c));
        when(repo.findByProductAndCycle("nodejs", "22")).thenReturn(Optional.of(c));

        EolEnricher.Result r = enricher.enrich(List.of(candidate("nodejs", "22")));

        assertThat(r.issues()).isEmpty();
        assertThat(r.warnings()).isEmpty(); // determinable (just not an issue) → no gap
    }

    @Test
    void candidateWithNoMatchingPublishedCycleWarnsAndIsDropped() {
        when(repo.findByProduct("keycloak")).thenReturn(List.of()); // nothing synced for this product

        EolEnricher.Result r = enricher.enrich(List.of(candidate("keycloak", "99")));

        assertThat(r.issues()).isEmpty();
        assertThat(r.warnings()).singleElement()
                .satisfies(w -> assertThat(w.code()).isEqualTo("EOL_COVERAGE_GAP"));
    }

    @Test
    void noCandidatesIsEmpty() {
        EolEnricher.Result r = enricher.enrich(List.of());
        assertThat(r.issues()).isEmpty();
        assertThat(r.warnings()).isEmpty();
    }
}
