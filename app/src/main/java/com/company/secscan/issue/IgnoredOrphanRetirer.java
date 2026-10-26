package com.company.secscan.issue;

import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.workspace.Workspace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The OD-19 one-off retire pass (D-073 part 2, D-083): retires stale <em>ignored</em> sub-issues already
 * accumulated — findings absent from their repository's latest reconciled scan on its current effective branch,
 * whose scanner was authoritative (succeeded + closes findings) in that scan, and whose hold is re-derivable
 * (an auto cause, or a group/path/cve/rule-scope rule — never a sub-issue-scope manual ignore; see
 * {@link TriageResolver#isHoldReDerivable}). Going forward, a branch switch retires the same family at scan time
 * with {@code BRANCH_CHANGED} ({@link TriageApplier}); this pass is the retroactive, re-runnable cleanup and
 * writes {@code SOLVED_NOT_DETECTED} against that latest scan.
 *
 * <p>Acts ONLY on the authenticated user's workspace (the single-workspace MVP default — NOT a cross-workspace
 * sweep; admin endpoints are ungated, OD-9). {@code dryRun} (the endpoint default) computes and returns the
 * candidates without changing anything. Idempotent: a real run solves each candidate (status ignored → solved)
 * and writes a {@code triage_event} (actor system); a second run finds nothing new because the rows are now
 * solved. A failed or non-authoritative scanner retires nothing (domain rule 3).
 */
@Service
@Profile("api")
public class IgnoredOrphanRetirer {

    private static final Logger log = LoggerFactory.getLogger(IgnoredOrphanRetirer.class);
    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    private final RepositoryRepository repositories;
    private final ScanRepository scans;
    private final ScanTaskRepository tasks;
    private final SubIssueRepository subIssues;
    private final IgnoreRuleRepository ignoreRules;
    private final IssueGroupRepository groups;
    private final TriageEventRepository events;
    private final GroupRollupService rollup;

    public IgnoredOrphanRetirer(RepositoryRepository repositories, ScanRepository scans, ScanTaskRepository tasks,
                                SubIssueRepository subIssues, IgnoreRuleRepository ignoreRules,
                                IssueGroupRepository groups, TriageEventRepository events,
                                GroupRollupService rollup) {
        this.repositories = repositories;
        this.scans = scans;
        this.tasks = tasks;
        this.subIssues = subIssues;
        this.ignoreRules = ignoreRules;
        this.groups = groups;
        this.events = events;
        this.rollup = rollup;
    }

    /** One retire candidate (what a dry run returns): the sub-issue id, its rule/package id, file and ignore reason. */
    public record Candidate(UUID subIssueId, UUID repositoryId, String rule, String file, String reason) {
    }

    /** The outcome: whether it was a dry run, how many candidates, how many were retired, and the candidate list. */
    public record RetireResult(boolean dryRun, int candidates, int retired, List<Candidate> candidateList) {
    }

    @Transactional
    public RetireResult retire(boolean dryRun) {
        List<Candidate> candidates = new ArrayList<>();
        Set<UUID> touched = new HashSet<>();
        List<IgnoreRule> rules = ignoreRules.findByWorkspaceId(WORKSPACE);

        for (Repository repo : repositories.findByWorkspaceId(WORKSPACE)) {
            String effectiveBranch = repo.effectiveBranch();
            if (effectiveBranch == null) {
                continue; // no branch known yet — nothing to compare against
            }
            Optional<Scan> latestOpt = scans
                    .findFirstByWorkspaceIdAndRepositoryIdAndBranchAndReconciledAtIsNotNullOrderByCreatedAtDesc(
                            WORKSPACE, repo.getId(), effectiveBranch);
            if (latestOpt.isEmpty()) {
                continue;
            }
            Scan latest = latestOpt.get();
            Set<String> authoritative = new HashSet<>();
            for (ScanTask t : tasks.findByWorkspaceIdAndScanId(WORKSPACE, latest.getId())) {
                if (t.succeeded() && t.closesFindings()) {
                    authoritative.add(t.getScanner());
                }
            }
            if (authoritative.isEmpty()) {
                continue; // no authoritative scanner in the latest scan → close nothing (domain rule 3)
            }
            Map<UUID, String> groupKeyById = groupKeys(repo.getId());

            for (SubIssue sub : subIssues.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo.getId())) {
                if (!SubIssue.STATUS_IGNORED.equals(sub.getStatus())) {
                    continue;
                }
                if (!authoritative.contains(sub.getScanner())) {
                    continue; // its scanner was not authoritative in the latest scan
                }
                // Absent from the latest scan ⇔ it was not "seen" there (reconcile bumps last_seen on every
                // incoming fingerprint, ignored ones included), so last_seen points to an earlier scan.
                if (latest.getId().equals(sub.getLastSeenScanId())) {
                    continue; // still present in the latest scan
                }
                if (!TriageResolver.isHoldReDerivable(sub, groupKeyById.get(sub.getGroupId()), rules)) {
                    continue; // a sub-issue-scope manual ignore (or no re-derivable cause) — preserved
                }
                candidates.add(new Candidate(sub.getId(), repo.getId(), sub.getRuleId(), sub.getFilePath(),
                        sub.getIgnoreReason()));
                if (!dryRun) {
                    sub.solve();
                    subIssues.save(sub);
                    events.save(TriageEvent.system(WORKSPACE, sub.getId(), SubIssue.STATUS_IGNORED,
                            SubIssue.STATUS_SOLVED, TriageEvent.REASON_SOLVED_NOT_DETECTED, latest.getId()));
                    touched.add(sub.getGroupId());
                }
            }
        }

        if (!dryRun && !touched.isEmpty()) {
            Map<String, Integer> fixConfig = rollup.loadFixTimeConfig();
            for (UUID groupId : touched) {
                rollup.recompute(WORKSPACE, groupId, fixConfig);
            }
            log.info("OD-19 retire pass solved {} stale ignored orphan(s) across {} group(s).",
                    candidates.size(), touched.size());
        }
        return new RetireResult(dryRun, candidates.size(), dryRun ? 0 : candidates.size(), candidates);
    }

    private Map<UUID, String> groupKeys(UUID repositoryId) {
        Map<UUID, String> out = new HashMap<>();
        for (IssueGroup g : groups.findByWorkspaceIdAndRepositoryId(WORKSPACE, repositoryId)) {
            out.put(g.getId(), g.getGroupKey());
        }
        return out;
    }
}
