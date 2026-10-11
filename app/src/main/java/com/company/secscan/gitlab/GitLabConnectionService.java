package com.company.secscan.gitlab;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.gitlab.dto.GitLabGroup;
import com.company.secscan.gitlab.dto.GitLabTokenInfo;
import com.company.secscan.gitlab.dto.GitLabVersion;
import com.company.secscan.workspace.Workspace;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests and stores the GitLab connection. Token decryption happens in-memory here (api process) for the
 * connection test and project listing (D-014); the scanner container never sees the token except via a
 * one-time file in M2. The token is never logged.
 */
@Service
public class GitLabConnectionService {

    static final List<String> REQUIRED_SCOPES = List.of("read_api", "read_repository");
    static final int EXPIRY_WARN_DAYS = 14;

    private final GitLabClient client;
    private final GitlabConnectionRepository connections;
    private final TokenCipherHolder cipher;
    private final GitLabHostPolicy hostPolicy;

    public GitLabConnectionService(GitLabClient client, GitlabConnectionRepository connections,
                                   TokenCipherHolder cipher, GitLabHostPolicy hostPolicy) {
        this.client = client;
        this.connections = connections;
        this.cipher = cipher;
        this.hostPolicy = hostPolicy;
    }

    /** Tests a connection without persisting anything. */
    public ConnectionTestResult test(String baseUrl, String token, String groupPath) {
        try {
            return runTest(baseUrl, token, GitLabGroupPath.normalize(groupPath, hostPolicy));
        } catch (GitLabException e) {
            return ConnectionTestResult.failure(e.getError().name(), e.getMessage());
        }
    }

    /**
     * Tests then persists the connection for the default workspace (encrypting the token). Requires the
     * encryption key to be configured. Returns the same result shape as {@link #test}.
     */
    @Transactional
    public ConnectionTestResult save(String baseUrl, String token, String groupPath) {
        ConnectionTestResult result;
        GitLabGroup group;
        GitLabTokenInfo tokenInfo;
        try {
            String path = GitLabGroupPath.normalize(groupPath, hostPolicy);
            tokenInfo = requireUsableToken(baseUrl, token);
            GitLabVersion version = client.version(baseUrl, token);
            group = client.resolveGroup(baseUrl, token, path);
            // Re-saving the same group (e.g. token rotation) updates the row and keeps selected repositories.
            // Pointing an existing connection at a different group is rejected for now (D-019).
            Optional<GitlabConnection> existing = connections.findByWorkspaceId(Workspace.DEFAULT_ID);
            if (existing.isPresent() && existing.get().getGitlabGroupId() != group.id()) {
                throw new GitLabException(GitLabError.CONNECTION_GROUP_MISMATCH,
                        "This workspace is already connected to group '" + existing.get().getGroupFullPath()
                                + "'. Changing the group is not supported yet — remove the connection first.");
            }
            result = ConnectionTestResult.success(version.version(), group.id(), group.fullPath(),
                    tokenInfo.scopes(), tokenInfo.expiresAt(), expiringSoon(tokenInfo.expiresAt()));
        } catch (GitLabException e) {
            return ConnectionTestResult.failure(e.getError().name(), e.getMessage());
        }

        // Only reached on success. Encrypt the token (clear error if the key is missing — never store plaintext).
        String ciphertext = cipher.require().encrypt(token);
        UUID workspaceId = Workspace.DEFAULT_ID;
        String scopes = String.join(",", tokenInfo.scopes());
        GitlabConnection connection = connections.findByWorkspaceId(workspaceId)
                .map(existing -> {
                    existing.updateGroup(baseUrl, group.fullPath(), group.id());
                    existing.updateToken(ciphertext, scopes, tokenInfo.expiresAt());
                    return existing;
                })
                .orElseGet(() -> new GitlabConnection(workspaceId, baseUrl, group.fullPath(), group.id(),
                        ciphertext, scopes, tokenInfo.expiresAt()));
        connections.save(connection);
        return result;
    }

    @Transactional(readOnly = true)
    public Optional<GitlabConnection> current() {
        return connections.findByWorkspaceId(Workspace.DEFAULT_ID);
    }

    // --- internals ---

    private ConnectionTestResult runTest(String baseUrl, String token, String groupPath) {
        GitLabTokenInfo tokenInfo = requireUsableToken(baseUrl, token);
        GitLabVersion version = client.version(baseUrl, token);
        GitLabGroup group = client.resolveGroup(baseUrl, token, groupPath);
        return ConnectionTestResult.success(version.version(), group.id(), group.fullPath(),
                tokenInfo.scopes(), tokenInfo.expiresAt(), expiringSoon(tokenInfo.expiresAt()));
    }

    /** Fetches token info and rejects revoked/expired tokens or missing scopes. */
    private GitLabTokenInfo requireUsableToken(String baseUrl, String token) {
        GitLabTokenInfo info = client.tokenInfo(baseUrl, token);
        if (!info.active() || info.revoked()) {
            throw new GitLabException(GitLabError.INVALID_TOKEN, "The token is inactive or revoked.");
        }
        if (info.expiresAt() != null && info.expiresAt().isBefore(LocalDate.now())) {
            throw new GitLabException(GitLabError.TOKEN_EXPIRED,
                    "The token expired on " + info.expiresAt() + ".");
        }
        List<String> missing = new ArrayList<>(REQUIRED_SCOPES);
        missing.removeAll(info.scopes());
        if (!missing.isEmpty()) {
            throw new GitLabException(GitLabError.MISSING_SCOPES,
                    "The token is missing required scope(s): " + String.join(", ", missing) + ".");
        }
        return info;
    }

    private boolean expiringSoon(LocalDate expiresAt) {
        return expiresAt != null && !expiresAt.isBefore(LocalDate.now())
                && expiresAt.isBefore(LocalDate.now().plusDays(EXPIRY_WARN_DAYS + 1L));
    }
}
