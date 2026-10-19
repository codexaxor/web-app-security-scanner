package com.company.secscan.issue;

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
 * Turns the EOL detector's raw candidates into the EOL findings worth persisting, and decides whether the EOL
 * scanner may close missing findings this scan (BLOCKER 2 — an EOL finding must NEVER be false-solved).
 *
 * <p>For each detected {@code (product, cycle)} it consults the global {@code eol_cycle} intel cache (D-055):
 * <ul>
 *   <li>cache row present → {@link EolStatus} decides: past-EOL / ending-soon become findings; still-maintained
 *       is dropped (not an issue) and does not affect solvability.</li>
 *   <li>cache row ABSENT → the status cannot be determined, so the detection is dropped AND the whole EOL
 *       scanner is marked NOT solvable for this scan (closes nothing) with an {@code EOL_COVERAGE_GAP} warning —
 *       mirroring D-034's {@code NO_MANIFESTS} for SCA. This is why a rescan with empty/partial coverage can
 *       never solve an open EOL finding.</li>
 * </ul>
 * When there are no candidates at all, the scanner stays solvable (a clean EOL scan may close stale findings).
 */
@Service
public class EolEnricher {

    private static final Logger log = LoggerFactory.getLogger(EolEnricher.class);

    private final EolCycleRepository eolCycles;

    public EolEnricher(EolCycleRepository eolCycles) {
        this.eolCycles = eolCycles;
    }

    /** The outcome of enrichment: the issue-worthy EOL findings, whether the scanner may close findings, and any
     * coverage-gap warnings to record. */
    public record Result(List<RawFinding> issues, boolean solvable, List<ScanWarning> warnings) {
    }

    public Result enrich(List<RawFinding> eolCandidates) {
        List<RawFinding> issues = new ArrayList<>();
        List<ScanWarning> warnings = new ArrayList<>();
        boolean solvable = true;
        LocalDate today = LocalDate.now();

        for (RawFinding f : eolCandidates) {
            String product = f.packageName();
            String cycle = f.installedVersion();
            Optional<EolCycle> row = eolCycles.findByProductAndCycle(product, cycle);
            if (row.isEmpty()) {
                // Undeterminable: do not guess, do not persist, and the scanner closes nothing this scan.
                solvable = false;
                warnings.add(new ScanWarning("EOL_COVERAGE_GAP",
                        "Detected " + product + " " + cycle + " but its end-of-life status is unknown "
                                + "(no endoflife.date coverage synced); the EOL scanner will not close findings "
                                + "this scan.", f.filePath()));
                continue;
            }
            EolCycle c = row.get();
            String status = EolStatus.of(c.isEol(), c.getEolFrom(), today);
            if (EolStatus.isIssue(status)) {
                issues.add(f);
            }
            // MAINTAINED → not an issue; dropped, solvability unaffected.
        }
        if (!solvable) {
            log.info("EOL coverage gap: {} detection(s) could not be classified; the EOL scanner closes nothing "
                    + "this scan.", warnings.size());
        }
        return new Result(issues, solvable, warnings);
    }
}
