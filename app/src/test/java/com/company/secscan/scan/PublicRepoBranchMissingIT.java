package com.company.secscan.scan;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.scan.api.ScanView;
import com.company.secscan.scan.worker.ScanClaimer;
import com.company.secscan.scan.worker.ScanJobProcessor;
import com.company.secscan.support.AbstractScanWorkerIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * M8.8 item 1 (the blocker), A5 at the worker: a public repository ({@code credential_kind='none'}, no token)
 * whose configured scan branch no longer exists fails cleanly BEFORE cloning (BRANCH_NOT_FOUND) — proving the
 * pre-clone branch gate works anonymously, through the provider adapter, with no credential header sent.
 */
class PublicRepoBranchMissingIT extends AbstractScanWorkerIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    ScanService scanService;
    @Autowired
    ScanClaimer claimer;
    @Autowired
    ScanJobProcessor processor;
    @Autowired
    ScanRepository scans;
    @Autowired
    ScanWarningRepository warnings;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private GitLabWireMock gitlab;

    @AfterEach
    void stop() {
        if (gitlab != null) {
            gitlab.stop();
        }
    }

    @Test
    void aMissingBranchFailsBeforeCloningForAPublicRepoWithoutAToken() {
        gitlab = new GitLabWireMock();
        GitlabConnection conn = connections.save(GitlabConnection.forSingleRepo(
                WS, gitlab.baseUrl(), GitlabConnection.PROVIDER_GITLAB, GitlabConnection.CREDENTIAL_NONE,
                "9401", "pub/app", null, null, null));
        Repository repo = repositories.save(new Repository(WS, conn.getId(), 9401L, "pub/app", "main",
                List.of("Java")));
        repo.setScanBranch("ghost");
        repositories.save(repo);

        gitlab.stubBranchStatus(9401L, "ghost", 404);     // branch gone
        gitlab.stubProjectById(9401L,
                "{\"id\":9401,\"path_with_namespace\":\"pub/app\",\"default_branch\":\"main\",\"empty_repo\":false}");

        ScanView scan = scanService.startManual(repo.getId());
        List<UUID> claimed = claimer.claimBatch(10);
        assertThat(claimed).hasSize(1);
        processor.process(claimed.get(0));

        Scan finished = scans.findById(scan.id()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(Scan.STATUS_FAILED);
        assertThat(finished.getFailReason()).isEqualTo("BRANCH_NOT_FOUND");
        assertThat(finished.getReconciledAt()).isNull();                        // no reconcile → closes nothing
        assertThat(repositories.findById(repo.getId()).orElseThrow().isBranchMissing()).isTrue();
        // The anonymous branch check carried no PRIVATE-TOKEN header.
        gitlab.server().verify(getRequestedFor(urlPathEqualTo("/api/v4/projects/9401/repository/branches/ghost"))
                .withHeader("PRIVATE-TOKEN", absent()));
    }
}
