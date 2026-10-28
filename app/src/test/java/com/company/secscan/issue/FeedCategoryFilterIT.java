package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.api.IssueGroupView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.Scan;
import com.company.secscan.scan.ScanFinding;
import com.company.secscan.scan.ScanFindingRepository;
import com.company.secscan.scan.ScanRepository;
import com.company.secscan.scan.ScanTask;
import com.company.secscan.scan.ScanTaskRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D-083: the feed can be filtered server-side by level-2 category. Reconcile sets each group's category; the
 * feed endpoint returns only groups of the requested category (workspace-scoped, paginated).
 */
class FeedCategoryFilterIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired ReconcileService reconcile;
    @Autowired IssueService issues;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository findings;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;

    @Test
    void feedFiltersByLevel2Category() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc", 8100L, "ct", "read_api,read_repository", null));
        UUID repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 8101L, "svc/app", "main",
                List.of("Java"))).getId();

        Scan scan = scans.save(Scan.manual(WORKSPACE, repo, "main"));
        tasks.save(new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_SCA, ScanTask.STATUS_OK, 0, 5L, null));
        tasks.save(new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_IAC, ScanTask.STATUS_OK, 0, 5L, null));
        findings.save(ScanFinding.sca(WORKSPACE, scan.getId(), repo, "CVE-1", "org.foo:bar", "Maven", "1.0", "2.0",
                "pom.xml", "high"));                                     // → Maven group
        findings.save(ScanFinding.iac(WORKSPACE, scan.getId(), repo, "CKV_AWS_24", "main.tf",
                "aws_security_group.open", 4, null));                    // → Terraform group
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);

        assertThat(issues.listGroups(null, null, null, "all", null, false, 0, 50).totalElements()).isEqualTo(2);

        var maven = issues.listGroups(null, null, "Maven", "all", null, false, 0, 50);
        assertThat(maven.content()).extracting(IssueGroupView::type).containsExactly("sca");
        assertThat(maven.content()).extracting(IssueGroupView::level2Category).containsExactly("Maven");

        var terraform = issues.listGroups(null, null, "Terraform", "all", null, false, 0, 50);
        assertThat(terraform.content()).extracting(IssueGroupView::type).containsExactly("iac");
        assertThat(terraform.content()).extracting(IssueGroupView::level2Category).containsExactly("Terraform");

        assertThat(issues.listGroups(null, null, "Nonexistent", "all", null, false, 0, 50).totalElements())
                .isZero();
    }
}
