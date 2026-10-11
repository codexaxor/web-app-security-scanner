package com.company.secscan.gitlab.api;

import java.util.List;

/**
 * A group project as shown on the Select-repositories screen. {@code selectable} is false for empty
 * repositories (with {@code notSelectableReason} = "empty repository").
 */
public record ProjectView(
        long gitlabProjectId,
        String pathWithNamespace,
        String defaultBranch,
        List<String> languages,
        boolean selectable,
        String notSelectableReason,
        boolean alreadySelected,
        boolean active) {
}
