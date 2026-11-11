package com.company.secscan.source;

/**
 * A provider-neutral descriptor of what a connection targets, built from the stored connection row and handed to
 * a {@link SourceProvider} so the adapter needs no persistence types. {@code baseUrl} is the host's https base
 * (e.g. {@code https://gitlab.celloscope.net}, {@code https://github.com}); {@code targetType} is one of
 * {@code group}/{@code project}/{@code owner}/{@code repo}. The id/path/login fields are populated per target
 * kind (a GitLab group/project carries {@code targetId}+{@code targetPath}; a GitHub owner carries
 * {@code ownerLogin}; a single repo carries {@code providerRepoId}+{@code repoFullName}).
 */
public record SourceConnectionRef(
        String provider,
        String baseUrl,
        String host,
        String targetType,
        Long targetId,
        String targetPath,
        String ownerLogin,
        String providerRepoId,
        String repoFullName) {

    public static final String TARGET_GROUP = "group";
    public static final String TARGET_PROJECT = "project";
    public static final String TARGET_OWNER = "owner";
    public static final String TARGET_REPO = "repo";

    public boolean isGroup() {
        return TARGET_GROUP.equals(targetType);
    }

    public boolean isOwner() {
        return TARGET_OWNER.equals(targetType);
    }

    public boolean isSingleRepo() {
        return TARGET_PROJECT.equals(targetType) || TARGET_REPO.equals(targetType);
    }
}
