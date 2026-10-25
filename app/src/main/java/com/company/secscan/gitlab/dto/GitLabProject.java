package com.company.secscan.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A project from {@code GET /api/v4/groups/:id/projects}. {@code defaultBranch} is null for an empty repository;
 * {@code markedForDeletionOn} is set when the project is scheduled for deletion.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabProject(
        long id,
        @JsonProperty("path_with_namespace") String pathWithNamespace,
        @JsonProperty("default_branch") String defaultBranch,
        boolean archived,
        @JsonProperty("empty_repo") boolean emptyRepo,
        @JsonProperty("marked_for_deletion_on") String markedForDeletionOn) {

    public boolean markedForDeletion() {
        return markedForDeletionOn != null && !markedForDeletionOn.isBlank();
    }

    /** Not scannable: either a genuinely empty repo, or one whose code we cannot read (no default branch). */
    public boolean isEmpty() {
        return emptyRepo || defaultBranch == null || defaultBranch.isBlank();
    }

    /** GitLab's own "no commits yet" signal — distinct from a repo whose code we simply cannot read (B2). */
    public boolean genuinelyEmpty() {
        return emptyRepo;
    }

    /**
     * An honest reason the project is not selectable (B2), or null when it is selectable: a genuinely empty repo
     * vs. a repo whose code is unreadable (default branch null while {@code empty_repo} is false — typically a
     * token without the Reporter role on the code).
     */
    public String notSelectableReason() {
        if (!isEmpty()) {
            return null;
        }
        return genuinelyEmpty()
                ? "Empty repository (no commits yet)."
                : "No access to repository code — check the token role (Reporter or higher).";
    }
}
