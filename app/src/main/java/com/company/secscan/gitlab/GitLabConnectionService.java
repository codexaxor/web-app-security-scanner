package com.company.secscan.gitlab;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.api.ConnectionRemovalResult;
import com.company.secscan.gitlab.api.ConnectionTestResult;
import com.company.secscan.gitlab.dto.GitLabGroup;
import com.company.secscan.gitlab.dto.GitLabProject;
import com.company.secscan.gitlab.dto.GitLabTokenInfo;
import com.company.secscan.gitlab.dto.GitLabVersion;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryEventRecorder;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.workspace.Workspace;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tests and stores GitLab connections. A workspace may hold several (M1b): a whole-group connection and/or any
 * number of single-project connections. Token decryption happens in-memory here (api process); the scanner
 * container never sees the token except via a one-time file in M2 (D-014). The token is never logged.
 */
@Service
public class GitLabConnectionService {

    private static final Logger log = LoggerFactory.getLogger(GitLabConnectionService.class);
    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    static final List<String> REQUIRED_SCOPES = List.of("read_api", "read_repository");
    static final int EXPIRY_WARN_DAYS = 14;
    static final String NO_ACCESSIBLE_CONNECTION = "NO_ACCESSIBLE_CONNECTION";

    private final GitLabClient client;
    private final GitlabConnectionRepository connections;
    private final RepositoryRepository repositories;
    private final TokenCipherHolder cipher;
    private final GitLabHostPolicy hostPolicy;
    private final ConnectionTokenWriter tokenWriter;
    private final RepositoryEventRecorder repositoryEvents;

    public GitLabConnectionService(GitLabClient client, GitlabConnectionRepository connections,
                                   RepositoryRepository repositories, TokenCipherHolder cipher,
                                   GitLabHostPolicy hostPolicy, ConnectionTokenWriter tokenWriter,
                                   RepositoryEventRecorder repositoryEvents) {
        this.client = client;
        this.connections = connections;
        this.repositories = repositories;
        this.cipher = cipher;
        this.hostPolicy = hostPolicy;
        this.tokenWriter = tokenWriter;
        this.repositoryEvents = repositoryEvents;
    }

    /** Tests a connection without persisting anything. */
    public ConnectionTestResult test(String baseUrl, String token, String targetType, String path) {
        try {
            return toResult(resolve(baseUrl, token, targetType, path));
        } catch (GitLabException e) {
            return ConnectionTestResult.failure(e.getError().name(), e.getMessage());
        }
    }

    /**
     * Tests then creates a new connection (encrypting the token). Rejects a duplicate of a group/project this
     * workspace is already connected to. Requires the encryption key to be configured. Not wrapped in a single
     * transaction: {@code saveAndFlush} must surface a unique-index violation here (a concurrent duplicate
     * create) so it maps to {@code CONNECTION_EXISTS} rather than failing at an outer commit.
     */
    public ConnectionTestResult create(String baseUrl, String token, String targetType, String path) {
        Resolved r;
        try {
            r = resolve(baseUrl, token, targetType, path);
        } catch (GitLabException e) {
            return ConnectionTestResult.failure(e.getError().name(), e.getMessage());
        }
        // Dedup within this host (V22): the same numeric id on gitlab.com and a self-managed host are two
        // different connections, so scope the "already connected" check by provider + host.
        String host = com.company.secscan.source.SourceHosts.authorityOf(baseUrl);
        Optional<GitlabConnection> duplicate = r.isGroup()
                ? connections.findByWorkspaceIdAndProviderAndHostAndGitlabGroupId(
                        WORKSPACE, GitlabConnection.PROVIDER_GITLAB, host, r.id())
                : connections.findByWorkspaceIdAndProviderAndHostAndGitlabProjectId(
                        WORKSPACE, GitlabConnection.PROVIDER_GITLAB, host, r.id());
        if (duplicate.isPresent()) {
            return alreadyConnected(r);
        }
        String ciphertext = cipher.require().encrypt(token);
        String scopes = String.join(",", r.tokenInfo().scopes());
        GitlabConnection connection = r.isGroup()
                ? GitlabConnection.forGroup(WORKSPACE, baseUrl, r.path(), r.id(), ciphertext, scopes,
                        r.tokenInfo().expiresAt())
                : GitlabConnection.forProject(WORKSPACE, baseUrl, r.path(), r.id(), ciphertext, scopes,
                        r.tokenInfo().expiresAt());
        try {
            connections.saveAndFlush(connection);
        } catch (DataIntegrityViolationException e) {
            // A concurrent create inserted the same target first; the partial unique index rejected this one.
            return alreadyConnected(r);
        }
        return toResult(r);
    }

    private static ConnectionTestResult alreadyConnected(Resolved r) {
        return ConnectionTestResult.failure(GitLabError.CONNECTION_EXISTS.name(),
                "This workspace is already connected to " + r.targetType() + " '" + r.path()
                        + "'. Edit that connection to rotate its token.");
    }

    /**
     * Rotates the token (and base URL) of an existing connection. The supplied path must resolve to the same
     * target; to connect a different group/project, create a new connection instead. Selected repositories are
     * kept untouched.
     *
     * <p>Deliberately NOT {@code @Transactional}: the GitLab I/O (token/target verification) must not run inside
     * a transaction holding a DB connection. It reads the target in its own short tx, verifies over the network
     * with no tx held, then commits the rotation via {@link ConnectionTokenWriter} in a second short write tx.
     */
    public ConnectionTestResult update(UUID connectionId, String baseUrl, String token, String path) {
        // Read tx #1 (Spring Data default): capture the connection's current target.
        GitlabConnection existing = connections.findByIdAndWorkspaceId(connectionId, WORKSPACE)
                .orElseThrow(() -> new GitLabException(GitLabError.CONNECTION_NOT_FOUND,
                        "No such connection in this workspace."));
        // M8.8 item 7: a public connection has no stored token — rotating one is meaningless and is refused.
        if (GitlabConnection.CREDENTIAL_NONE.equals(existing.getCredentialKind())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "A public repository has no token to rotate.");
        }
        Long existingTargetId = existing.targetId();
        String existingTargetType = existing.getTargetType();

        // Network I/O: no transaction is held here.
        Resolved r;
        try {
            r = resolve(baseUrl, token, existingTargetType, path);
        } catch (GitLabException e) {
            return ConnectionTestResult.failure(e.getError().name(), e.getMessage());
        }
        if (!Long.valueOf(r.id()).equals(existingTargetId)) {
            return ConnectionTestResult.failure(GitLabError.CONNECTION_TARGET_MISMATCH.name(),
                    "That path resolves to a different " + r.targetType() + " than this connection. To connect a "
                            + "different target, add a new connection.");
        }

        // Write tx #2 (short): persist the rotated token.
        String ciphertext = cipher.require().encrypt(token);
        tokenWriter.rotate(connectionId, WORKSPACE, baseUrl, ciphertext,
                String.join(",", r.tokenInfo().scopes()), r.tokenInfo().expiresAt());
        return toResult(r);
    }

    @Transactional(readOnly = true)
    public List<GitlabConnection> list() {
        return connections.findByWorkspaceId(WORKSPACE);
    }

    @Transactional(readOnly = true)
    public GitlabConnection require(UUID connectionId) {
        return connections.findByIdAndWorkspaceId(connectionId, WORKSPACE)
                .orElseThrow(() -> new GitLabException(GitLabError.CONNECTION_NOT_FOUND,
                        "No such connection in this workspace."));
    }

    /**
     * Removes a connection. Its repositories are never deleted: each is provisionally reassigned to another
     * connection that still exposes it, or deactivated with {@code NO_ACCESSIBLE_CONNECTION} if none does
     * (D-020). The connection row (and its token ciphertext) is then deleted.
     */
    @Transactional
    public ConnectionRemovalResult delete(UUID connectionId) {
        GitlabConnection conn = connections.findByIdAndWorkspaceId(connectionId, WORKSPACE)
                .orElseThrow(() -> new GitLabException(GitLabError.CONNECTION_NOT_FOUND,
                        "No such connection in this workspace."));
        List<GitlabConnection> others = new ArrayList<>(connections.findByWorkspaceId(WORKSPACE));
        others.removeIf(c -> c.getId().equals(conn.getId()));

        int reassigned = 0;
        int deactivated = 0;
        for (Repository repo : repositories.findByWorkspaceIdAndConnectionId(WORKSPACE, conn.getId())) {
            Optional<GitlabConnection> target = pickReassignTarget(repo, others);
            if (target.isPresent()) {
                repo.reassignTo(target.get().getId());
                log.info("Reassigned repository {} from connection {} to {} on removal",
                        repo.getId(), conn.getId(), target.get().getId());
                repositoryEvents.reassigned(repo, conn.getId(), target.get().getId());
                reassigned++;
            } else {
                repo.detachAndDeactivate(NO_ACCESSIBLE_CONNECTION);
                log.info("Deactivated repository {} after removing connection {}: {}",
                        repo.getId(), conn.getId(), NO_ACCESSIBLE_CONNECTION);
                repositoryEvents.detached(repo, conn.getId(), NO_ACCESSIBLE_CONNECTION);
                deactivated++;
            }
            repositories.save(repo);
        }
        connections.delete(conn);
        return new ConnectionRemovalResult(reassigned, deactivated);
    }

    // --- reassignment target selection (D-020) ---

    private Optional<GitlabConnection> pickReassignTarget(Repository repo, List<GitlabConnection> allOthers) {
        // A connection can only reach a repository on its OWN host (V22 identity includes host): a connection on
        // another host, even with the same numeric id, is a different repository. Restrict candidates to the
        // repo's host before applying the D-020 preference order.
        List<GitlabConnection> others = allOthers.stream()
                .filter(c -> repo.getHost() != null && repo.getHost().equals(c.getHost()))
                .toList();
        // 1. A single-repo connection targeting the same repository id (most recently verified wins a tie).
        //    Match by provider_repo_id (the identity component) so it works for GitLab and GitHub alike.
        Optional<GitlabConnection> sameProject = others.stream()
                .filter(GitlabConnection::isProject)
                .filter(c -> c.getProviderRepoId() != null
                        && c.getProviderRepoId().equals(repo.getProviderRepoId()))
                .max(byVerified());
        if (sameProject.isPresent()) {
            return sameProject;
        }
        // 2. A group-mode connection whose group is a namespace ancestor of the repo; longest (most specific)
        //    path first, most recently verified breaking a tie.
        return others.stream()
                .filter(GitlabConnection::isGroup)
                .filter(c -> namespaceContains(c.getGroupFullPath(), repo.getPathWithNamespace()))
                .max(Comparator.comparingInt((GitlabConnection c) -> c.getGroupFullPath().length())
                        .thenComparing(byVerified()));
    }

    /** Orders connections by when they were last verified; a null (never recorded) is treated as oldest. */
    private static Comparator<GitlabConnection> byVerified() {
        return Comparator.comparing(c -> c.getLastVerifiedAt() == null ? Instant.MIN : c.getLastVerifiedAt());
    }

    /** True if {@code pathWithNamespace} sits under {@code groupFullPath} (the group, or one of its subgroups). */
    private static boolean namespaceContains(String groupFullPath, String pathWithNamespace) {
        if (groupFullPath == null || pathWithNamespace == null) {
            return false;
        }
        return pathWithNamespace.toLowerCase(Locale.ROOT)
                .startsWith(groupFullPath.toLowerCase(Locale.ROOT) + "/");
    }

    // --- resolve + token checks ---

    private Resolved resolve(String baseUrl, String token, String targetType, String rawPath) {
        if (!GitlabConnection.TARGET_GROUP.equals(targetType)
                && !GitlabConnection.TARGET_PROJECT.equals(targetType)) {
            throw new GitLabException(GitLabError.INVALID_GROUP_PATH,
                    "Choose whether to connect a group or a single project.");
        }
        GitLabTokenInfo tokenInfo = requireUsableToken(baseUrl, token);
        GitLabVersion version = client.version(baseUrl, token);
        if (GitlabConnection.TARGET_PROJECT.equals(targetType)) {
            String path = GitLabProjectPath.normalize(rawPath, hostPolicy);
            GitLabProject p = client.resolveProject(baseUrl, token, path);
            return new Resolved(version.version(), tokenInfo, GitlabConnection.TARGET_PROJECT,
                    p.id(), p.pathWithNamespace());
        }
        String path = GitLabGroupPath.normalize(rawPath, hostPolicy);
        GitLabGroup g = client.resolveGroup(baseUrl, token, path);
        return new Resolved(version.version(), tokenInfo, GitlabConnection.TARGET_GROUP, g.id(), g.fullPath());
    }

    private ConnectionTestResult toResult(Resolved r) {
        boolean soon = expiringSoon(r.tokenInfo().expiresAt());
        return r.isGroup()
                ? ConnectionTestResult.successGroup(r.version(), r.id(), r.path(), r.tokenInfo().scopes(),
                        r.tokenInfo().expiresAt(), soon)
                : ConnectionTestResult.successProject(r.version(), r.id(), r.path(), r.tokenInfo().scopes(),
                        r.tokenInfo().expiresAt(), soon);
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

    /** A resolved + verified target: GitLab version, token info, and the group/project id + canonical path. */
    private record Resolved(String version, GitLabTokenInfo tokenInfo, String targetType, long id, String path) {
        boolean isGroup() {
            return GitlabConnection.TARGET_GROUP.equals(targetType);
        }
    }
}
