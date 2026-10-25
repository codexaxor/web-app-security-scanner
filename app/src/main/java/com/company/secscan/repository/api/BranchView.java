package com.company.secscan.repository.api;

import com.company.secscan.gitlab.dto.GitLabBranch;

/**
 * A branch offered in the "Branch to scan" picker (D-076). {@code lastCommitAt} is the raw ISO-8601 timestamp
 * GitLab returns (rendered as text by the UI, D-067). Branch names are external data and are never interpreted.
 */
public record BranchView(String name, boolean isDefault, boolean isProtected, String lastCommitAt) {

    public static BranchView of(GitLabBranch b) {
        return new BranchView(b.name(), b.isDefault(), b.isProtected(), b.lastCommitAt());
    }
}
