package com.company.secscan.repository.api;

import com.company.secscan.gitlab.dto.GitLabBranch;
import com.company.secscan.source.SourceBranch;

/**
 * A branch offered in the "Branch to scan" picker (D-076). {@code lastCommitAt} is the raw ISO-8601 timestamp
 * the provider returns (rendered as text by the UI, D-067). Branch names are external data and are never
 * interpreted.
 */
public record BranchView(String name, boolean isDefault, boolean isProtected, String lastCommitAt) {

    public static BranchView of(GitLabBranch b) {
        return new BranchView(b.name(), b.isDefault(), b.isProtected(), b.lastCommitAt());
    }

    /** Provider-neutral mapping (M8.8): the picker now goes through the {@link SourceBranch} adapter type. */
    public static BranchView of(SourceBranch b) {
        return new BranchView(b.name(), b.isDefault(), b.isProtected(), b.lastCommitAt());
    }
}
