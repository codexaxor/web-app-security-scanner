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

    /** Empty repositories have no default branch and cannot be scanned. */
    public boolean isEmpty() {
        return emptyRepo || defaultBranch == null || defaultBranch.isBlank();
    }
}
