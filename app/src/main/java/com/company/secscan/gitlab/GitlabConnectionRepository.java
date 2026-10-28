package com.company.secscan.gitlab;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitlabConnectionRepository extends JpaRepository<GitlabConnection, UUID> {

    List<GitlabConnection> findByWorkspaceId(UUID workspaceId);

    Optional<GitlabConnection> findByIdAndWorkspaceId(UUID id, UUID workspaceId);

    Optional<GitlabConnection> findByWorkspaceIdAndGitlabGroupId(UUID workspaceId, long gitlabGroupId);

    Optional<GitlabConnection> findByWorkspaceIdAndGitlabProjectId(UUID workspaceId, long gitlabProjectId);

    // Host-scoped dedup (V22): the same numeric group/project id on two hosts is two distinct connections.
    Optional<GitlabConnection> findByWorkspaceIdAndProviderAndHostAndGitlabGroupId(
            UUID workspaceId, String provider, String host, long gitlabGroupId);

    Optional<GitlabConnection> findByWorkspaceIdAndProviderAndHostAndGitlabProjectId(
            UUID workspaceId, String provider, String host, long gitlabProjectId);

    Optional<GitlabConnection> findByWorkspaceIdAndProviderAndHostAndOwnerLogin(
            UUID workspaceId, String provider, String host, String ownerLogin);

    Optional<GitlabConnection> findByWorkspaceIdAndProviderAndHostAndProviderRepoId(
            UUID workspaceId, String provider, String host, String providerRepoId);
}
