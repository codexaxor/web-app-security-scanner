package com.company.secscan.issue;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SF-2: the score-only group rollup ({@link GroupRollupService#recomputeScoreRollup}, as used by the re-score /
 * sensitivity path) must never resurrect a score/status onto a group that a concurrent reconcile/ignore has
 * flipped to ignored. The write is a column-scoped {@code UPDATE … WHERE status = 'open'}; this test interleaves
 * two real transactions with a latch so T2 commits the ignore BETWEEN T1 reading the group open and T1 issuing
 * its guarded UPDATE — and asserts the UPDATE matches 0 rows, leaving status=ignored and severity_score=null.
 */
class GroupScoreRollupRaceIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    GroupRollupService rollup;
    @Autowired
    IssueGroupRepository groups;
    @Autowired
    SubIssueRepository subIssues;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    PlatformTransactionManager txManager;

    @Test
    void scoreOnlyRollupDoesNotResurrectAScoreOntoAConcurrentlyIgnoredGroup() throws Exception {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-race", 9901L, "ct", "read_api,read_repository", null));
        UUID repoId = repositories.save(new Repository(WORKSPACE, conn.getId(), 9901L, "svc/race", "main",
                List.of("Java"))).getId();
        IssueGroup g = groups.save(new IssueGroup(WORKSPACE, repoId, "sca", "sca|race", "pkg", null));
        SubIssue open = new SubIssue(WORKSPACE, g.getId(), repoId, "sca", "sca", "fp-open", null);
        open.setScore(80);
        open = subIssues.save(open);
        rollup.recompute(WORKSPACE, g.getId(), Map.of()); // severity 80, status open
        UUID gid = g.getId();
        UUID openId = open.getId();
        assertThat(groups.findById(gid).orElseThrow().getSeverityScore()).isEqualTo(80);

        CountDownLatch t1HasRead = new CountDownLatch(1);
        CountDownLatch t2Committed = new CountDownLatch(1);
        TransactionTemplate tx = new TransactionTemplate(txManager);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> t1 = pool.submit(() -> tx.execute(status -> {
                IssueGroup read = groups.findByIdAndWorkspaceId(gid, WORKSPACE).orElseThrow();
                assertThat(read.getStatus()).isEqualTo(IssueGroup.STATUS_OPEN);
                t1HasRead.countDown();
                await(t2Committed);
                // Only now (after T2 committed the ignore) does the score-only path issue its guarded UPDATE.
                return groups.updateScoreRollupIfOpen(gid, WORKSPACE, 80, 0, null);
            }));

            await(t1HasRead);
            pool.submit(() -> tx.executeWithoutResult(s -> {
                SubIssue sub = subIssues.findById(openId).orElseThrow();
                sub.ignore("IGNORE_RULE", null);
                subIssues.save(sub);
                rollup.recompute(WORKSPACE, gid, Map.of()); // status→ignored, severity→null, committed on return
            })).get(10, SECONDS);
            t2Committed.countDown();

            assertThat(t1.get(10, SECONDS)).isZero(); // 0 rows: the status='open' guard rejected the stale write
        } finally {
            pool.shutdownNow();
        }

        IssueGroup after = groups.findByIdAndWorkspaceId(gid, WORKSPACE).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(IssueGroup.STATUS_IGNORED);
        assertThat(after.getSeverityScore()).isNull();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
