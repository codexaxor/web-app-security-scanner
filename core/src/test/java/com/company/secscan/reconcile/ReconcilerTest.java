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
    void aFrozenFingerprintIsNeverSolvedReopenedOrSeen() {
        // A superseded identity (e.g. a secret under a replaced fingerprint key, D-029): untouched entirely.
        // Missing from a scan whose scanner succeeded: still not solved.
        Plan missing = reconciler.reconcile(
                List.of(),
                List.of(new Existing("frozen-fp", SECRET, Existing.STATUS_OPEN)),
                Set.of(SECRET),
                Set.of("frozen-fp"));
        assertThat(missing.solved()).isEmpty();

        // Present and matching a solved row: not reopened, not even seen; it appears in no bucket.
        Plan present = reconciler.reconcile(
                List.of(new Incoming("frozen-fp", SECRET)),
                List.of(new Existing("frozen-fp", SECRET, Existing.STATUS_SOLVED)),
                Set.of(SECRET),
                Set.of("frozen-fp"));
        assertThat(present.newOpen()).isEmpty();
        assertThat(present.reopened()).isEmpty();
        assertThat(present.seen()).isEmpty();
        assertThat(present.solved()).isEmpty();
    }

    @Test
    void afterAKeyChangeCurrentKeyRowsReconcileWhileFrozenRowsStayUntouched() {
        // old-fp is frozen (old key). new-fp-reopen matches an incoming (reopened); new-fp-gone is missing
        // from a solvable scan (solved). The frozen row is neither solved nor reopened.
        Plan plan = reconciler.reconcile(
                List.of(new Incoming("new-fp-reopen", SECRET)),
                List.of(new Existing("old-fp", SECRET, Existing.STATUS_OPEN),
                        new Existing("new-fp-reopen", SECRET, Existing.STATUS_SOLVED),
                        new Existing("new-fp-gone", SECRET, Existing.STATUS_OPEN)),
                Set.of(SECRET),
                Set.of("old-fp"));
        assertThat(plan.reopened()).containsExactly("new-fp-reopen");
        assertThat(plan.solved()).containsExactly("new-fp-gone");
        assertThat(plan.newOpen()).isEmpty();
        assertThat(plan.seen()).isEmpty();
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
