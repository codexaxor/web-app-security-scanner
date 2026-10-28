package com.company.secscan.scan.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.repository.Repository;
import com.company.secscan.source.ProviderError;
import com.company.secscan.source.ProviderException;
import com.company.secscan.source.ProviderRegistry;
import com.company.secscan.source.SourceProvider;
import com.company.secscan.source.SourceRepo;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * SF-4: the pre-clone branch check disambiguates a missing branch (with a reachable repo) vs. an unreachable
 * repo vs. a transient error. Pure unit test over {@link ScanJobProcessor#branchVerdict}, now provider-neutral:
 * it routes through the {@link SourceProvider} adapter (M8.7 V7).
 */
class BranchVerdictTest {

    private final SourceProvider adapter = mock(SourceProvider.class);
    private final ProviderRegistry providers = mock(ProviderRegistry.class);
    private final ScanJobProcessor processor = new ScanJobProcessor(
            null, null, null, null, null, null, null, null, null, null, null, null, providers);

    private static final Repository REPO = new Repository(
            Workspace.DEFAULT_ID, UUID.randomUUID(), 101L, "svc/app", "main", List.of());
    private static final GitlabConnection CONN = GitlabConnection.forGroup(
            Workspace.DEFAULT_ID, "https://gitlab.example", "svc", 42L, "ct", "read_api", null);

    BranchVerdictTest() {
        when(providers.require("gitlab")).thenReturn(adapter);
    }

    private ScanJobProcessor.BranchVerdict verdict() {
        return processor.branchVerdict(REPO, CONN, "token", "dev");
    }

    @Test
    void okWhenTheBranchExists() {
        when(adapter.branchExists(anyString(), anyString(), eq("101"), eq("dev"))).thenReturn(true);
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.OK);
    }

    @Test
    void missingWhenBranchAbsentButRepoReachable() {
        when(adapter.branchExists(anyString(), anyString(), eq("101"), eq("dev"))).thenReturn(false);
        when(adapter.getRepository(anyString(), anyString(), eq("101")))
                .thenReturn(new SourceRepo("101", "svc/app", "main", null, false, null));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.MISSING);
    }

    @Test
    void projectUnreachableWhenBranchAbsentAndRepoNotFound() {
        when(adapter.branchExists(anyString(), anyString(), eq("101"), eq("dev"))).thenReturn(false);
        when(adapter.getRepository(anyString(), anyString(), eq("101")))
                .thenThrow(new ProviderException(ProviderError.NOT_FOUND, "no repo"));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.PROJECT_UNREACHABLE);
    }

    @Test
    void transientOnA5xxOrTimeout() {
        when(adapter.branchExists(anyString(), anyString(), eq("101"), eq("dev")))
                .thenThrow(new ProviderException(ProviderError.UNREACHABLE, "timeout"));
        assertThat(verdict()).isEqualTo(ScanJobProcessor.BranchVerdict.TRANSIENT);
    }
}
