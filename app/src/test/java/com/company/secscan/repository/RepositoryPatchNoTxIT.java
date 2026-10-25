package com.company.secscan.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitLabClient;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.gitlab.dto.GitLabBranch;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * SF-2: the scan-branch PATCH does its GitLab existence check OUTSIDE any transaction. A mocked
 * {@link GitLabClient#getBranch} records whether a transaction is active when it runs; it must be false.
 */
class RepositoryPatchNoTxIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    @Autowired
    RepositoryService service;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    TokenCipherHolder cipher;
    @MockBean
    GitLabClient client;

    @Test
    void theGitLabExistenceCheckRunsWithNoActiveTransaction() {
        AtomicBoolean txActiveDuringCall = new AtomicBoolean(true);
        when(client.getBranch(anyString(), anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            txActiveDuringCall.set(TransactionSynchronizationManager.isActualTransactionActive());
            return new GitLabBranch("dev", false, false, null);
        });

        String ciphertext = cipher.require().encrypt("glpat-test-token");
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "https://gitlab.example", "svc", 6101L, ciphertext, "read_api,read_repository", null));
        Repository repo = repositories.save(new Repository(WORKSPACE, conn.getId(), 6101L, "svc/app", "main",
                List.of("Java")));

        var view = service.patch(repo.getId(), null, null, null, TextNode.valueOf("dev"));

        assertThat(view.scanBranch()).isEqualTo("dev");                 // the write still happened
        assertThat(txActiveDuringCall.get()).isFalse();                 // but GitLab ran outside any transaction
    }
}
