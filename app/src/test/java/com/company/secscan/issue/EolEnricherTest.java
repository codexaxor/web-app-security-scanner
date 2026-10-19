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
 * Unit-proves the EOL enricher's gate (BLOCKER 2): only determinable issues are kept, and a detection with no
 * eol_cycle coverage makes the whole EOL scanner non-solvable (so reconcile can never false-solve it).
 */
class EolEnricherTest {

    private final EolCycleRepository repo = Mockito.mock(EolCycleRepository.class);
    private final EolEnricher enricher = new EolEnricher(repo);

    private static RawFinding candidate(String product, String cycle) {
        return RawFinding.eol(product, cycle, "pom.xml");
    }

    @Test
    void pastEolCandidateIsKeptAndScannerStaysSolvable() {
        when(repo.findByProductAndCycle("spring-boot", "2.1"))
                .thenReturn(Optional.of(new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 1, 1),
                        "2.1.18", null, null, false, false)));

        EolEnricher.Result r = enricher.enrich(List.of(candidate("spring-boot", "2.1")));

        assertThat(r.issues()).hasSize(1);
        assertThat(r.solvable()).isTrue();
        assertThat(r.warnings()).isEmpty();
    }

    @Test
    void maintainedCandidateIsDroppedButScannerStaysSolvable() {
        when(repo.findByProductAndCycle("nodejs", "22"))
                .thenReturn(Optional.of(new EolCycle("nodejs", "22", false, LocalDate.now().plusYears(2),
                        "22.1.0", null, null, true, true)));

        EolEnricher.Result r = enricher.enrich(List.of(candidate("nodejs", "22")));

        assertThat(r.issues()).isEmpty();
        assertThat(r.solvable()).isTrue(); // determinable (just not an issue) → still solvable
    }

    @Test
    void candidateWithNoCoverageDropsAndMakesTheScannerNotSolvable() {
        when(repo.findByProductAndCycle("keycloak", "99")).thenReturn(Optional.empty());

        EolEnricher.Result r = enricher.enrich(List.of(candidate("keycloak", "99")));

        assertThat(r.issues()).isEmpty();
        assertThat(r.solvable()).isFalse(); // BLOCKER 2: cannot classify → closes nothing this scan
        assertThat(r.warnings()).singleElement()
                .satisfies(w -> assertThat(w.code()).isEqualTo("EOL_COVERAGE_GAP"));
    }

    @Test
    void noCandidatesLeavesTheScannerSolvable() {
        EolEnricher.Result r = enricher.enrich(List.of());
        assertThat(r.issues()).isEmpty();
        assertThat(r.solvable()).isTrue();
    }
}
