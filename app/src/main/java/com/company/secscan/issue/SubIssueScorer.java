package com.company.secscan.issue;

import com.company.secscan.intel.EpssScore;
import com.company.secscan.intel.EpssScoreRepository;
import com.company.secscan.intel.KevEntryRepository;
import com.company.secscan.scoring.ScoreInput;
import com.company.secscan.scoring.ScoreResult;
import com.company.secscan.scoring.Scoring;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enriches a sub-issue with the intel-dependent scoring fields and the derived score (SPEC "Severity";
 * D-044..D-049). The ONE place both scan-time reconcile and the api re-score job go through, so a stored score
 * and a recomputed score are identical: it resolves KEV ("any CVE alias", D-046) and EPSS (stored only —
 * adjustments are OFF in M5, D-047) from the global caches, then delegates the arithmetic to the pure
 * {@link Scoring#score}. It sets {@code in_kev}, {@code epss}, {@code score} and {@code score_breakdown}; the
 * descriptive fields (cvss/severity_label/cwe/cve_aliases/advisory_summary) are copied from the finding by the
 * caller. Nothing it writes is ever a fingerprint input.
 */
@Service
public class SubIssueScorer {

    private static final Logger log = LoggerFactory.getLogger(SubIssueScorer.class);

    private final KevEntryRepository kevEntries;
    private final EpssScoreRepository epssScores;

    public SubIssueScorer(KevEntryRepository kevEntries, EpssScoreRepository epssScores) {
        this.kevEntries = kevEntries;
        this.epssScores = epssScores;
    }

    /**
     * Recomputes {@code in_kev}, {@code epss}, {@code score} and {@code score_breakdown} on {@code sub} from its
     * persisted inputs and the current KEV/EPSS caches. {@code repoSensitivity} is the owning repository's
     * sensitivity ({@code high}/{@code low}/{@code normal}); the caller supplies it once per repository.
     */
    public void score(SubIssue sub, String repoSensitivity) {
        List<String> cves = candidateCves(sub);
        boolean onKev = !cves.isEmpty() && !kevEntries.findPresentCves(cves).isEmpty();
        BigDecimal epss = maxEpss(cves);

        ScoreInput in = new ScoreInput(sub.getType(), sub.getToolSeverity(), sub.getSeverityLabel(),
                sub.getRuleId(), onKev, repoSensitivity);
        ScoreResult result = Scoring.score(in);

        if ("secret".equals(sub.getType()) && sub.getRuleId() != null
                && !Scoring.isMappedSecretRule(sub.getRuleId())) {
            log.warn("Unmapped secret rule '{}' scored at the default base 75 (D-045); add it to the rule map.",
                    sub.getRuleId());
        }

        sub.setInKev(onKev);
        sub.setEpss(epss);
        sub.setScore(result.score());
        sub.setScoreBreakdown(result.breakdown());
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
