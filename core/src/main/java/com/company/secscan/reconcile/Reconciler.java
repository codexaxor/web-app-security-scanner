package com.company.secscan.reconcile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure reconcile decision for one scan (SPEC "Issue lifecycle", CLAUDE.md domain rule 3). Given the
 * findings this scan produced, the sub-issues that already exist for the repository, and which scanners this
 * scan ran successfully, it decides each finding's fate — without touching JPA, so every branch is
 * unit-testable.
 *
 * <p>Rules:
 * <ul>
 *   <li>Incoming fingerprint with no existing sub-issue &rarr; <b>new open</b>.</li>
 *   <li>Incoming fingerprint matching a {@code solved} sub-issue &rarr; <b>reopened</b>.</li>
 *   <li>Incoming fingerprint matching an {@code open} or {@code ignored} sub-issue &rarr; <b>seen</b> (last-seen
 *       bumped, no status change, no event). An ignored finding stays ignored when seen again.</li>
 *   <li>An {@code open} sub-issue whose scanner ran successfully this scan ({@code solvableScanners}) and whose
 *       fingerprint is absent from this scan &rarr; <b>solved</b>. A sub-issue whose scanner failed, timed out
 *       or did not run is left untouched (a failed scanner never closes its findings). An ignored sub-issue is
 *       never solved.</li>
 *   <li>Any fingerprint in {@code frozenFingerprints} is a superseded identity (e.g. a secret fingerprinted
 *       under a now-replaced key; D-029) &rarr; left <b>completely untouched</b>: never solved, reopened or
 *       seen, and it appears in no bucket. Other findings in the same scan reconcile normally.</li>
 * </ul>
 */
public final class Reconciler {

    /** A sub-issue that already exists for the repository. */
    public record Existing(String fingerprint, String scanner, String status) {
        public static final String STATUS_OPEN = "open";
        public static final String STATUS_SOLVED = "solved";
        public static final String STATUS_IGNORED = "ignored";
    }

    /** A finding produced by this scan. */
    public record Incoming(String fingerprint, String scanner) {
    }

    /**
     * The decision, as fingerprint lists the caller maps back to its entities.
     *
     * @param newOpen   incoming fingerprints with no existing sub-issue (create, status open, SEEN event)
     * @param reopened  incoming fingerprints matching a solved sub-issue (set open, REOPENED event)
     * @param seen      incoming fingerprints matching an open/ignored sub-issue (bump last-seen, no event)
     * @param solved    existing open fingerprints missing from this scan whose scanner ran ok (SOLVED event)
     */
    public record Plan(List<String> newOpen, List<String> reopened, List<String> seen, List<String> solved) {
    }

    /**
     * @param incoming            findings this scan produced
     * @param existing            sub-issues already stored for the repository
     * @param solvableScanners    scanners whose task succeeded this scan and may close missing findings
     * @param frozenFingerprints  existing fingerprints to leave completely untouched this scan (superseded
     *                            identities, e.g. secrets under a replaced fingerprint key; D-029)
     */
    public Plan reconcile(List<Incoming> incoming, List<Existing> existing, Set<String> solvableScanners,
                          Set<String> frozenFingerprints) {
        Map<String, Existing> existingByFp = new HashMap<>();
        for (Existing e : existing) {
            existingByFp.put(e.fingerprint(), e);
        }
        Map<String, Incoming> incomingByFp = new HashMap<>();
        for (Incoming i : incoming) {
            incomingByFp.put(i.fingerprint(), i);
        }

        List<String> newOpen = new ArrayList<>();
        List<String> reopened = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Incoming i : incoming) {
            if (frozenFingerprints.contains(i.fingerprint())) {
                continue; // superseded identity: untouched, no event (also never reopened on a chance match)
            }
            Existing e = existingByFp.get(i.fingerprint());
            if (e == null) {
                newOpen.add(i.fingerprint());
            } else if (Existing.STATUS_SOLVED.equals(e.status())) {
                reopened.add(i.fingerprint());
            } else {
                // open or ignored: no status change.
                seen.add(i.fingerprint());
            }
        }

        List<String> solved = new ArrayList<>();
        for (Existing e : existing) {
            if (frozenFingerprints.contains(e.fingerprint())) {
                continue; // superseded identity: never solved.
            }
            if (Existing.STATUS_OPEN.equals(e.status())
                    && solvableScanners.contains(e.scanner())
                    && !incomingByFp.containsKey(e.fingerprint())) {
                solved.add(e.fingerprint());
            }
        }

        return new Plan(newOpen, reopened, seen, solved);
    }
}
