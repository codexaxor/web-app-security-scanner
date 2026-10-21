package com.company.secscan.scan;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.issue.TriageService;
import com.company.secscan.issue.api.IgnoreRuleView;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** OD-14 item 8 (mapped to R1/R4, change 10): POST scan and POST ignore-rule return a non-null createdAt — the
 *  not-refreshed-after-insert bug is fixed by @Generated(INSERT) on the DB-default column. */
class ScanCreatedAtIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    ScanService scanService;
    @Autowired
    TriageService triageService;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    @Test
    void postScanReturnsANonNullCreatedAt() {
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc", 9301L, "ct", "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 9301L, "svc/scan", "main",
                List.of("Java")));

        ScanView view = scanService.startManual(repo.getId());
        assertThat(view.createdAt()).isNotNull(); // R1 — was null before the @Generated(INSERT) fix
        assertThat(view.status()).isEqualTo(Scan.STATUS_QUEUED);
    }

    @Test
    void postIgnoreRuleReturnsANonNullCreatedAt() {
        IgnoreRuleView rule = triageService.createRule("cve", null, "CVE-2022-42889", "offline build tool only");
        assertThat(rule.createdAt()).isNotNull(); // R4 — Ignored view's createdAt is never null/blank
    }
}
