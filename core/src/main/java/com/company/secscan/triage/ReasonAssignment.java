package com.company.secscan.triage;

import java.util.Collection;
import java.util.Optional;

/**
 * Picks the single reason code to record when more than one could apply to a finding in one reconcile pass
 * (D-059). Pure, no Spring. Precedence is {@link ReasonCode}'s declaration order (a manual/`.secscan.yml`
 * {@link ReasonCode#IGNORE_RULE} outranks every cause-based auto-ignore).
 */
public final class ReasonAssignment {

    private ReasonAssignment() {
    }

    /** The highest-precedence reason among the candidates, or empty when there are none. */
    public static Optional<ReasonCode> highest(Collection<ReasonCode> candidates) {
        ReasonCode best = null;
        for (ReasonCode c : candidates) {
            if (c != null && (best == null || c.outranks(best))) {
                best = c;
            }
        }
        return Optional.ofNullable(best);
    }
}
