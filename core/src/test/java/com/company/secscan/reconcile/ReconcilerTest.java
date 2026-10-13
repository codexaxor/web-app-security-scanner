package com.company.secscan.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.reconcile.Reconciler.Existing;
import com.company.secscan.reconcile.Reconciler.Incoming;
import com.company.secscan.reconcile.Reconciler.Plan;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReconcilerTest {

    private final Reconciler reconciler = new Reconciler();
    private static final String SECRET = "secret";

    @Test
    void firstSeenFindingIsANewOpen() {
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("fp1", SECRET)),
                List.of(),
                Set.of(SECRET),
                Set.of());
        assertThat(plan.newOpen()).containsExactly("fp1");
        assertThat(plan.reopened()).isEmpty();
        assertThat(plan.seen()).isEmpty();
        assertThat(plan.solved()).isEmpty();
    }

    @Test
    void anOpenFindingMissingFromAScanWhoseScannerSucceededIsSolved() {
        Plan plan = reconciler.reconcile(
                List.of(),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_OPEN)),
                Set.of(SECRET),
                Set.of());
        assertThat(plan.solved()).containsExactly("fp1");
    }

    @Test
    void aMissingFindingWhoseScannerFailedIsNotSolved() {
        // Scanner "secret" did not succeed this scan, so it is absent from solvableScanners: nothing closes.
        Plan plan = reconciler.reconcile(
                List.of(),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_OPEN)),
                Set.of(),
                Set.of());
        assertThat(plan.solved()).isEmpty();
    }

    @Test
    void aSolvedFindingSeenAgainIsReopened() {
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("fp1", SECRET)),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_SOLVED)),
                Set.of(SECRET),
                Set.of());
        assertThat(plan.reopened()).containsExactly("fp1");
        assertThat(plan.newOpen()).isEmpty();
        assertThat(plan.solved()).isEmpty();
    }

    @Test
    void anOpenFindingSeenAgainIsJustSeenNoStatusChange() {
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("fp1", SECRET)),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_OPEN)),
                Set.of(SECRET),
                Set.of());
        assertThat(plan.seen()).containsExactly("fp1");
        assertThat(plan.reopened()).isEmpty();
        assertThat(plan.solved()).isEmpty();
    }

    @Test
    void anIgnoredFindingStaysIgnoredWhetherSeenOrMissing() {
        // Seen again: it is "seen" (last-seen bump), never reopened; no event.
        Plan seen = reconciler.reconcile(
                List.of(new Incoming("fp1", SECRET)),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_IGNORED)),
                Set.of(SECRET),
                Set.of());
        assertThat(seen.seen()).containsExactly("fp1");
        assertThat(seen.reopened()).isEmpty();
        assertThat(seen.solved()).isEmpty();

        // Missing from a scan whose scanner succeeded: still never solved.
        Plan missing = reconciler.reconcile(
                List.of(),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_IGNORED)),
                Set.of(SECRET),
                Set.of());
        assertThat(missing.solved()).isEmpty();
    }

    @Test
    void reopenSuppressedScannerLeavesASolvedMatchUntouched() {
        // Secret fingerprint key changed: a (coincidental) solved match must not be reopened.
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("fp1", SECRET)),
                List.of(new Existing("fp1", SECRET, Existing.STATUS_SOLVED)),
                Set.of(),            // not solvable either
                Set.of(SECRET));     // reopen suppressed
        assertThat(plan.reopened()).isEmpty();
        assertThat(plan.seen()).containsExactly("fp1");
        assertThat(plan.solved()).isEmpty();
    }

    @Test
    void onlyTheMissingOnesAreSolvedWhenSomeFindingsPersist() {
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("keep", SECRET)),
                List.of(new Existing("keep", SECRET, Existing.STATUS_OPEN),
                        new Existing("gone", SECRET, Existing.STATUS_OPEN)),
                Set.of(SECRET),
                Set.of());
        assertThat(plan.seen()).containsExactly("keep");
        assertThat(plan.solved()).containsExactly("gone");
    }
}
