package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
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
 * D-083: an IaC group's title is the Checkov {@code check_name} (human), its subtitle the {@code check_id}; a
 * group created before this behaviour has its title refreshed on the next successful scan (title is descriptive).
 */
class IaCTitleIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;
    private static final String CHECK_ID = "CKV_AWS_24";
    private static final String CHECK_NAME = "Ensure no security groups allow ingress from 0.0.0.0:0 to port 22";

    @Autowired ReconcileService reconcile;
    @Autowired ScanRepository scans;
    @Autowired ScanTaskRepository tasks;
    @Autowired ScanFindingRepository findings;
    @Autowired IssueGroupRepository groups;
    @Autowired RepositoryRepository repositories;
    @Autowired GitlabConnectionRepository connections;

    private UUID seedRepo(long id) {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "file:///local", "svc", id, "ct", "read_api,read_repository", null));
        return repositories.save(new Repository(WORKSPACE, conn.getId(), id + 1, "svc/app", "main",
                List.of("HCL"))).getId();
    }

    private void runIac(UUID repo, String checkName) {
        Scan scan = scans.save(Scan.manual(WORKSPACE, repo, "main"));
        tasks.save(new ScanTask(WORKSPACE, scan.getId(), ScanTask.SCANNER_IAC, ScanTask.STATUS_OK, 0, 5L, null));
        ScanFinding iac = ScanFinding.iac(WORKSPACE, scan.getId(), repo, CHECK_ID, "main.tf",
                "aws_security_group.open", 22, null);
        iac.setAdvisorySummary(checkName); // the Checkov check_name (null = pre-S4 behaviour)
        findings.save(iac);
        scan.finish(Scan.STATUS_COMPLETED);
        scans.save(scan);
        reconcile.reconcile(scan.getId(), WORKSPACE);
    }

    private IssueGroup iacGroup(UUID repo) {
        return groups.findByWorkspaceIdAndRepositoryId(WORKSPACE, repo).stream()
                .filter(g -> "iac".equals(g.getType())).findFirst().orElseThrow();
    }

    @Test
    void iacTitleIsCheckNameAndSubtitleIsCheckId() {
        UUID repo = seedRepo(8200L);
        runIac(repo, CHECK_NAME);
        IssueGroup g = iacGroup(repo);
        assertThat(g.getTitle()).isEqualTo(CHECK_NAME);
        assertThat(g.getSubtitle()).isEqualTo(CHECK_ID);
        assertThat(g.getTitle()).isNotEqualTo(g.getSubtitle());
    }

    @Test
    void existingGroupTitleRefreshedOnNextScan() {
        UUID repo = seedRepo(8210L);
        runIac(repo, null);                                  // pre-S4: no check_name → title falls back to check_id
        assertThat(iacGroup(repo).getTitle()).isEqualTo(CHECK_ID);

        runIac(repo, CHECK_NAME);                            // next scan carries the check_name → title refreshed
        IssueGroup g = iacGroup(repo);
        assertThat(g.getTitle()).isEqualTo(CHECK_NAME);
        assertThat(g.getSubtitle()).isEqualTo(CHECK_ID);
    }
}
