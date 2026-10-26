package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.finding.RawFinding;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.reconcile.Fingerprints;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-083 backfill: the api-startup {@link TaxonomyBackfiller} fills the level-2 category of every pre-V21 group
 * (null count → 0) from a representative sub-issue, idempotently.
 */
class TaxonomyBackfillIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired TaxonomyBackfiller backfiller;
    @Autowired IssueGroupRepository groups;
    @Autowired SubIssueRepository subIssues;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;

    private UUID seedRepo() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc", 8000L, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), 8001L, "svc/app", "main",
                List.of("Java"))).getId();
    }

    /** A group with no level-2 category (as a pre-V21 row would be) plus one sub-issue to derive from. */
    private IssueGroup seedUncategorizedGroup(UUID repoId, String type, String fingerprint, String filePath,
                                              String pkg) {
        IssueGroup g = groups.save(new IssueGroup(WORKSPACE, repoId, type,
                Fingerprints.groupKey(type, fingerprint), "title", null));
        SubIssue sub = new SubIssue(WORKSPACE, g.getId(), repoId, type, RawFinding.SCANNER_SCA, fingerprint, null);
        sub.setFilePath(filePath);
        sub.setPackageName(pkg);
        subIssues.save(sub);
        return g;
    }

    @Test
    void startupBackfillSetsEveryGroupsCategoryNullCountZero() {
        UUID repo = seedRepo();
        IssueGroup maven = seedUncategorizedGroup(repo, RawFinding.TYPE_SCA, "fp-maven", "pom.xml", "org.foo:bar");
        IssueGroup npm = seedUncategorizedGroup(repo, RawFinding.TYPE_SCA, "fp-npm", "ui/package-lock.json", "lodash");
        assertThat(maven.getLevel2Category()).isNull();
        assertThat(groups.findByWorkspaceIdAndLevel2CategoryIsNull(WORKSPACE)).hasSize(2);

        int filled = backfiller.backfill();

        assertThat(filled).isEqualTo(2);
        assertThat(groups.findByWorkspaceIdAndLevel2CategoryIsNull(WORKSPACE)).isEmpty(); // null count == 0
        assertThat(groups.findByIdAndWorkspaceId(maven.getId(), WORKSPACE).orElseThrow().getLevel2Category())
                .isEqualTo("Maven");
        assertThat(groups.findByIdAndWorkspaceId(npm.getId(), WORKSPACE).orElseThrow().getLevel2Category())
                .isEqualTo("npm");
        assertThat(groups.findByIdAndWorkspaceId(maven.getId(), WORKSPACE).orElseThrow().getOwaspCategory())
                .isEqualTo("Software Supply Chain Failures");

        // Idempotent: a second run fills nothing.
        assertThat(backfiller.backfill()).isZero();
    }
}
