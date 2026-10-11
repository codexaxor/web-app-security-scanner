package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Normalizing the group-path field: plain paths, pasted URLs, and a URL for a different host. */
class GitLabGroupPathTest {

    private final GitLabHostPolicy policy = new GitLabHostPolicy(Set.of("gitlab.celloscope.net"));

    @Test
    void plainPathPassesThrough() {
        assertThat(GitLabGroupPath.normalize("celloscope/platform", policy)).isEqualTo("celloscope/platform");
    }

    @Test
    void trimsSurroundingSlashesAndWhitespace() {
        assertThat(GitLabGroupPath.normalize("  /celloscope/platform/  ", policy))
                .isEqualTo("celloscope/platform");
    }

    @Test
    void stripsHostAndGroupsPrefixFromGroupUrl() {
        assertThat(GitLabGroupPath.normalize(
                "https://gitlab.celloscope.net/groups/celloscope/nextgen-archtitecture-solution", policy))
                .isEqualTo("celloscope/nextgen-archtitecture-solution");
    }

    @Test
    void stripsDashSuffixFromAProjectOrPageUrl() {
        assertThat(GitLabGroupPath.normalize(
                "https://gitlab.celloscope.net/celloscope/nextgen/-/tree/main", policy))
                .isEqualTo("celloscope/nextgen");
    }

    @Test
    void rejectsUrlForADifferentHost() {
        GitLabException ex = catchThrowableOfType(
                () -> GitLabGroupPath.normalize("https://github.com/acme/widgets", policy),
                GitLabException.class);
        assertThat(ex.getError()).isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void rejectsBlankInputAsInvalidGroupPath() {
        assertThat(catchThrowableOfType(() -> GitLabGroupPath.normalize("   ", policy), GitLabException.class)
                .getError()).isEqualTo(GitLabError.INVALID_GROUP_PATH);
    }
}
