package com.company.secscan.source;

/**
 * A provider-neutral view of a repository as the provider reports it. {@code providerRepoId} is the provider's
 * stable numeric id (GitLab project id, GitHub repository id) as text — the identity component (D-080).
 * {@code fullName} is the display path (owner/name or group/.../project). {@code visibility} is
 * {@code public}/{@code private}/{@code internal} as the provider reports it (null if unknown).
 * {@code notSelectableReason} is non-null when the repo exists but cannot be scanned yet (empty repo / no code
 * access), carrying a safe message for the selection UI.
 */
public record SourceRepo(
        String providerRepoId,
        String fullName,
        String defaultBranch,
        String visibility,
        boolean empty,
        String notSelectableReason) {

    public boolean selectable() {
        return notSelectableReason == null;
    }

    public boolean isPublic() {
        return "public".equalsIgnoreCase(visibility);
    }
}
