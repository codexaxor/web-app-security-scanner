package com.company.secscan.issue;

import com.company.secscan.eol.EolCycles;
import com.company.secscan.eol.EolStatus;
import com.company.secscan.finding.RawFinding;
import com.company.secscan.intel.EolCycle;
import com.company.secscan.intel.EolCycleRepository;
import com.company.secscan.scan.parse.ScanWarning;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns the EOL detector's raw candidates into the EOL findings worth persisting (M6; review BUG 1/BUG 2).
 *
 * <p>For each detected {@code (product, detectedCycle)} it (BUG 2) normalizes the detected cycle to the feed's
 * own published cycle key for that product via {@link EolCycles} — so .NET "8.0" resolves to "8" while Spring
 * Boot "2.1" stays "2.1" — then classifies the normalized cycle with {@link EolStatus}:
 * <ul>
 *   <li>past-EOL / ending-soon &rarr; a finding, carrying the NORMALIZED cycle (the frozen fingerprint input);</li>
 *   <li>still-maintained &rarr; dropped (covered, not an issue);</li>
 *   <li>no published cycle matches (uncovered) &rarr; dropped with an {@code EOL_COVERAGE_GAP} warning.</li>
 * </ul>
 *
 * <p>BUG 1 — never-false-solve is now PER (product, cycle), not scanner-wide: this enricher only decides which
 * INCOMING detections become findings. Whether an EXISTING open EOL finding may be solved is decided per
 * fingerprint in {@code ReconcileService} (an uncovered one is frozen like a D-029 secret), so one uncovered
 * product (e.g. an unsynced .NET) never blocks solving a fully-covered product (Spring Boot, Alpine) in the same
 * scan. The EOL {@code scan_task.closes_findings} stays per-scanner (= scanner succeeded).
 */
@Service
public class EolEnricher {

    private static final Logger log = LoggerFactory.getLogger(EolEnricher.class);

    private final EolCycleRepository eolCycles;

    public EolEnricher(EolCycleRepository eolCycles) {
        this.eolCycles = eolCycles;
    }

    /** The issue-worthy EOL findings (with normalized cycles) and any coverage-gap warnings to record. */
    public record Result(List<RawFinding> issues, List<ScanWarning> warnings) {
    }

    public Result enrich(List<RawFinding> eolCandidates) {
        List<RawFinding> issues = new ArrayList<>();
        List<ScanWarning> warnings = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (RawFinding f : eolCandidates) {
            String product = f.packageName();
            String detectedCycle = f.installedVersion();
            // Normalize the detected cycle to the feed's published cycle key for this product (BUG 2).
            List<String> published = eolCycles.findByProduct(product).stream().map(EolCycle::getCycle).toList();
            String cycle = EolCycles.match(detectedCycle, published);
            if (cycle == null) {
                // Uncovered: no published cycle matches → cannot classify → not persisted, and a gap is shown.
                warnings.add(new ScanWarning("EOL_COVERAGE_GAP",
                        "Detected " + product + " " + detectedCycle + " but its end-of-life status is unknown "
                                + "(no matching endoflife.date cycle synced); not reported this scan.",
                        f.filePath()));
                continue;
            }
            Optional<EolCycle> row = eolCycles.findByProductAndCycle(product, cycle);
            if (row.isEmpty()) {
                continue; // defensive: match said covered but the row vanished — treat as not-an-issue
            }
            EolCycle c = row.get();
            String status = EolStatus.of(c.isEol(), c.getEolFrom(), today);
            if (EolStatus.isIssue(status)) {
                // Persist with the NORMALIZED cycle so the fingerprint (product|cycle|file_path) matches the feed.
                issues.add(RawFinding.eol(product, cycle, f.filePath()));
            }
            // MAINTAINED → covered but not an issue; dropped.
        }
        if (!warnings.isEmpty()) {
            log.info("EOL coverage gap: {} detection(s) had no matching endoflife.date cycle; they are not "
                    + "reported (solving of covered EOL findings is unaffected — review BUG 1).", warnings.size());
        }
        return new Result(issues, warnings);
    }
}
