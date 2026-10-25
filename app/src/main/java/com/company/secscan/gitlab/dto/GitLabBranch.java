package com.company.secscan.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A branch from {@code GET /api/v4/projects/:id/repository/branches[/:branch]}. Only the fields the branch picker
 * needs are mapped; {@code commit.committed_date} gives the last-commit time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabBranch(
        String name,
        @JsonProperty("default") boolean isDefault,
        @JsonProperty("protected") boolean isProtected,
        Commit commit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Commit(@JsonProperty("committed_date") String committedDate) {
    }

    /** The last-commit timestamp as the raw ISO-8601 string GitLab returns, or null when absent. */
    public String lastCommitAt() {
        return commit == null ? null : commit.committedDate();
    }
}
