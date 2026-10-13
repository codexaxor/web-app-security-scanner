package com.company.secscan.gitlab;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The short write transaction for a token rotation. Kept in its own bean so {@link GitLabConnectionService}
 * can do the GitLab I/O (token/target verification) OUTSIDE any transaction and then commit the rotation in a
 * brief tx of its own — no network call ever holds a DB connection (M1b carry-over fix). The entity is reloaded
 * here so the write acts on current state.
 */
@Component
public class ConnectionTokenWriter {

    private final GitlabConnectionRepository connections;

    public ConnectionTokenWriter(GitlabConnectionRepository connections) {
        this.connections = connections;
    }

    @Transactional
    public void rotate(UUID connectionId, UUID workspaceId, String baseUrl, String ciphertext,
                       String scopes, LocalDate expiresAt) {
        GitlabConnection existing = connections.findByIdAndWorkspaceId(connectionId, workspaceId)
                .orElseThrow(() -> new GitLabException(GitLabError.CONNECTION_NOT_FOUND,
                        "No such connection in this workspace."));
        existing.updateToken(baseUrl, ciphertext, scopes, expiresAt);
        connections.save(existing);
    }
}
