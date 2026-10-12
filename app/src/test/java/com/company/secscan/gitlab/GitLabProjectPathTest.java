package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Normalizing the project-path field: plain paths, pasted URLs, dash-suffix pages, and host/validity rejection. */
class GitLabProjectPathTest {

    private final GitLabHostPolicy policy = new GitLabHostPolicy(Set.of("gitlab.celloscope.net"));

    @Test
    void plainPathPassesThrough() {
        assertThat(GitLabProjectPath.normalize("celloscope/platform/core-keycloak", policy))
                .isEqualTo("celloscope/platform/core-keycloak");
    }

    @Test
    void trimsSurroundingSlashesAndWhitespace() {
        assertThat(GitLabProjectPath.normalize("  /celloscope/platform/core-keycloak/  ", policy))
                .isEqualTo("celloscope/platform/core-keycloak");
    }

    @Test
    void stripsHostFromProjectUrl() {
        assertThat(GitLabProjectPath.normalize(
                "https://gitlab.celloscope.net/celloscope/platform/core-keycloak", policy))
                .isEqualTo("celloscope/platform/core-keycloak");
    }

    @Test
    void stripsDashSuffixFromProjectPageUrl() {
        assertThat(GitLabProjectPath.normalize(
                "https://gitlab.celloscope.net/celloscope/platform/core-keycloak/-/tree/main", policy))
                .isEqualTo("celloscope/platform/core-keycloak");
    }

    @Test
    void rejectsUrlForADifferentHost() {
        assertThat(catchThrowableOfType(
                () -> GitLabProjectPath.normalize("https://github.com/acme/widgets", policy),
                GitLabException.class).getError()).isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void rejectsBlankInput() {
        assertThat(catchThrowableOfType(() -> GitLabProjectPath.normalize("  ", policy), GitLabException.class)
                .getError()).isEqualTo(GitLabError.INVALID_GROUP_PATH);
    }

    @Test
    void rejectsSingleSegmentAsNotAProjectPath() {
        // A project needs at least a namespace and a project segment.
        assertThat(catchThrowableOfType(
                () -> GitLabProjectPath.normalize("justagroup", policy), GitLabException.class).getError())
                .isEqualTo(GitLabError.INVALID_GROUP_PATH);
    }
}
