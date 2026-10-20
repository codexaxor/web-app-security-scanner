package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.intel.EolCycle;
import com.company.secscan.intel.EolCycleRepository;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.intel.KevEntryRepository;
import com.company.secscan.scoring.ScoreLine;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Unit-proves that re-scoring a frozen/uncovered EOL sub-issue does NOT change its score (review fix): with no
 * eol_cycle coverage the status cannot be determined, so the scorer must preserve the last score rather than
 * silently drop a past-EOL 75 to the ending-soon floor 45.
 */
class SubIssueScorerTest {

    private final KevEntryRepository kev = Mockito.mock(KevEntryRepository.class);
    private final EpssScoreRepository epss = Mockito.mock(EpssScoreRepository.class);
    private final EolCycleRepository eol = Mockito.mock(EolCycleRepository.class);
    private final SubIssueScorer scorer = new SubIssueScorer(kev, epss, eol);

    private static SubIssue eolSub(int score) {
        SubIssue s = new SubIssue(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                RawFinding.TYPE_EOL, RawFinding.SCANNER_EOL, "fp", UUID.randomUUID());
        s.setPackageName("spring-boot");
        s.setInstalledVersion("2.1");
        s.setScore(score);
        s.setScoreBreakdown(List.of(new ScoreLine("base_eol", "past end of life", score)));
        return s;
    }

    @Test
    void reScoringAnUncoveredEolSubPreservesItsScore() {
        when(eol.findByProductAndCycle("spring-boot", "2.1")).thenReturn(Optional.empty()); // coverage gone

        SubIssueScorer.Computation c = scorer.compute(eolSub(75), "normal");

        assertThat(c.score()).isEqualTo(75); // preserved, NOT dropped to 45
        assertThat(c.breakdown()).anyMatch(l -> l.code().equals("base_eol") && l.delta() == 75);
    }

    @Test
    void reScoringACoveredEolSubRecomputesFromTheCache() {
        when(eol.findByProductAndCycle("spring-boot", "2.1")).thenReturn(Optional.of(
                new EolCycle("spring-boot", "2.1", true, LocalDate.of(2019, 1, 1), "2.1.18", null, null,
                        false, false)));

        SubIssueScorer.Computation c = scorer.compute(eolSub(45), "normal"); // stale 45 → recompute to 75

        assertThat(c.score()).isEqualTo(75); // past-EOL base
    }
}
