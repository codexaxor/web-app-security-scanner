package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.dto.GitLabBranch;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.repository.Repository;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SF-4: the pre-clone branch check disambiguates GitLab's 404 — a missing branch (with a reachable project) vs.
 * an unreachable project vs. a transient error. Pure unit test over {@link ScanJobProcessor#branchVerdict}; only
 * the GitLab client matters, the other collaborators are unused.
 */
class BranchVerdictTest {

    private final GitLabClient client = mock(GitLabClient.class);
    private final ScanJobProcessor processor = new ScanJobProcessor(
            null, null, null, null, null, client, null, null, null, null, null, null);

    private static final Repository REPO = new Repository(
            Workspace.DEFAULT_ID, UUID.randomUUID(), 101L, "svc/app", "main", List.of());
    private static final GitlabConnection CONN = GitlabConnection.forGroup(
            Workspace.DEFAULT_ID, "https://gitlab.example", "svc", 42L, "ct", "read_api", null);

    private ScanJobProcessor.BranchVerdict verdict() {
        return processor.branchVerdict(REPO, CONN, "token", "dev");
    }

    @Test
    void okWhenTheBranchExists() {
        when(client.getBranch(anyString(), anyString(), anyLong(), eq("dev")))
                .thenReturn(new GitLabBranch("dev", false, false, null));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.OK);
    }

    @Test
    void missingWhenBranch404ButProjectReachable() {
        when(client.getBranch(anyString(), anyString(), anyLong(), eq("dev")))
                .thenThrow(new GitLabException(GitLabError.BRANCH_NOT_FOUND, "no branch"));
        when(client.getProject(anyString(), anyString(), anyLong()))
                .thenReturn(new GitLabProject(101L, "svc/app", "main", false, false, null));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.MISSING);
    }

    @Test
    void projectUnreachableWhenBranch404AndProjectAlso404() {
        when(client.getBranch(anyString(), anyString(), anyLong(), eq("dev")))
                .thenThrow(new GitLabException(GitLabError.BRANCH_NOT_FOUND, "no branch"));
        when(client.getProject(anyString(), anyString(), anyLong()))
                .thenThrow(new GitLabException(GitLabError.PROJECT_NOT_FOUND, "no project"));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.PROJECT_UNREACHABLE);
    }

    @Test
    void transientOnA5xxOrTimeout() {
        when(client.getBranch(anyString(), anyString(), anyLong(), eq("dev")))
                .thenThrow(new GitLabException(GitLabError.UNREACHABLE, "timeout"));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.TRANSIENT);
    }
}
