package com.company.secscan.issue;

import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.intel.KevEntryRepository;
import com.company.secscan.scoring.ScoreInput;
import com.company.secscan.scoring.ScoreLine;
import com.company.secscan.scoring.ScoreResult;
import com.company.secscan.scoring.Scoring;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Enriches a sub-issue with the intel-dependent scoring fields and the derived score (SPEC "Severity";
 * D-044..D-049). The ONE place both scan-time reconcile and the api re-score job go through, so a stored score
 * and a recomputed score are identical: it resolves KEV ("any CVE alias", D-046) and EPSS (stored only —
 * adjustments are OFF in M5, D-047) from the global caches, then delegates the arithmetic to the pure
 * {@link Scoring#score}. It produces {@code in_kev}, {@code epss}, {@code score} and {@code score_breakdown};
 * the descriptive fields (cvss/severity_label/cwe/cve_aliases/advisory_summary) are copied from the finding by
 * the caller. Nothing it produces is ever a fingerprint input.
 *
 * <p>This class does no logging and never decides when to warn about an unmapped secret rule: it surfaces the
 * unmapped rule id on the {@link Computation} and the orchestrator (reconcile per scan, re-score per sweep)
 * logs each distinct rule once per run (N2), not once per sub-issue.
 */
@Service
public class SubIssueScorer {

    private final KevEntryRepository kevEntries;
    private final EpssScoreRepository epssScores;

    public SubIssueScorer(KevEntryRepository kevEntries, EpssScoreRepository epssScores) {
        this.kevEntries = kevEntries;
        this.epssScores = epssScores;
    }

    /**
     * The recomputed scoring outputs for a sub-issue, derived from its persisted inputs and the current
     * KEV/EPSS caches. Pure data — the caller either writes these to the entity ({@link #score}) or to the
     * DB directly (the re-score column-scoped update). {@code unmappedSecretRule} is the Betterleaks rule id
     * that fell through to the default base (D-045), or null; the caller dedupes and logs it once per run.
     */
    public record Computation(boolean inKev, BigDecimal epss, int score, List<ScoreLine> breakdown,
                              String unmappedSecretRule) {
    }

    /**
     * Computes the scoring outputs for {@code sub} WITHOUT mutating it or touching the DB. {@code repoSensitivity}
     * is the owning repository's sensitivity ({@code high}/{@code low}/{@code normal}); the caller supplies it
     * once per repository. Used by the re-score job, which then writes only the score columns of OPEN rows.
     */
    public Computation compute(SubIssue sub, String repoSensitivity) {
        List<String> cves = candidateCves(sub);
        boolean onKev = !cves.isEmpty() && !kevEntries.findPresentCves(cves).isEmpty();
        BigDecimal epss = maxEpss(cves);

        ScoreInput in = new ScoreInput(sub.getType(), sub.getToolSeverity(), sub.getSeverityLabel(),
                sub.getRuleId(), onKev, repoSensitivity);
        ScoreResult result = Scoring.score(in);

        String unmapped = null;
        if ("secret".equals(sub.getType()) && sub.getRuleId() != null
                && !Scoring.isMappedSecretRule(sub.getRuleId())) {
            unmapped = sub.getRuleId();
        }
        return new Computation(onKev, epss, result.score(), result.breakdown(), unmapped);
    }

    /**
     * Recomputes {@code in_kev}, {@code epss}, {@code score} and {@code score_breakdown} on {@code sub} from its
     * persisted inputs and the current KEV/EPSS caches, writing them onto the entity (scan-time reconcile path).
     * Returns the unmapped secret rule id (or null) so the caller can log it once per run (N2).
     */
    public String score(SubIssue sub, String repoSensitivity) {
        Computation c = compute(sub, repoSensitivity);
        sub.setInKev(c.inKev());
        sub.setEpss(c.epss());
        sub.setScore(c.score());
        sub.setScoreBreakdown(c.breakdown());
        return c.unmappedSecretRule();
    }

    /** The CVEs to match against KEV/EPSS: the group's CVE aliases plus the preferred vuln id when it is a CVE. */
    private static List<String> candidateCves(SubIssue sub) {
        List<String> cves = new ArrayList<>();
        if (sub.getCveAliases() != null) {
            cves.addAll(sub.getCveAliases());
        }
        if (sub.getVulnId() != null && sub.getVulnId().startsWith("CVE-") && !cves.contains(sub.getVulnId())) {
            cves.add(sub.getVulnId());
        }
        return cves;
    }

    /** The highest EPSS probability among the sub-issue's candidate CVEs, or null when none is known. */
    private BigDecimal maxEpss(List<String> cves) {
        if (cves.isEmpty()) {
            return null;
        }
        BigDecimal max = null;
        for (EpssScore e : epssScores.findByCveIn(cves)) {
            if (e.getEpss() != null && (max == null || e.getEpss().compareTo(max) > 0)) {
                max = e.getEpss();
            }
        }
        return max;
    }
}
