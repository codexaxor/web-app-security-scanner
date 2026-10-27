package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Strict public-repository URL parsing (M8.7 item 5). */
class PublicRepoUrlTest {

    @Test
    void parsesAGitHubRepoUrl() {
        PublicRepoUrl p = PublicRepoUrl.parse("https://github.com/octocat/Hello-World");
        assertThat(p.provider()).isEqualTo("github");
        assertThat(p.host()).isEqualTo("github.com");
        assertThat(p.baseUrl()).isEqualTo("https://github.com");
        assertThat(p.owner()).isEqualTo("octocat");
        assertThat(p.name()).isEqualTo("Hello-World");
        assertThat(p.fullName()).isEqualTo("octocat/Hello-World");
    }

    @Test
    void stripsDotGitAndTrailingSlash() {
        assertThat(PublicRepoUrl.parse("https://github.com/octocat/Hello-World.git/").fullName())
                .isEqualTo("octocat/Hello-World");
    }

    @Test
    void parsesANestedGitLabGroupPath() {
        PublicRepoUrl p = PublicRepoUrl.parse("https://gitlab.com/group/subgroup/project");
        assertThat(p.provider()).isEqualTo("gitlab");
        assertThat(p.fullName()).isEqualTo("group/subgroup/project");
        assertThat(p.owner()).isEqualTo("group");
        assertThat(p.name()).isEqualTo("project");
    }

    @Test
    void keepsAnExplicitNonDefaultPort() {
        PublicRepoUrl p = PublicRepoUrl.parse("https://gitlab.example.com:8443/team/app");
        assertThat(p.host()).isEqualTo("gitlab.example.com:8443");
        assertThat(p.baseUrl()).isEqualTo("https://gitlab.example.com:8443");
    }

    @Test
    void rejectsNonHttps() {
        assertThatThrownBy(() -> PublicRepoUrl.parse("http://github.com/o/r"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicRepoUrl.parse("git@github.com:o/r.git"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAGitHubUrlWithoutTwoSegments() {
        assertThatThrownBy(() -> PublicRepoUrl.parse("https://github.com/owner-only"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsPathTraversalAndBadCharacters() {
        assertThatThrownBy(() -> PublicRepoUrl.parse("https://github.com/o/../etc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublicRepoUrl.parse("https://github.com/o/a b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingPath() {
        assertThatThrownBy(() -> PublicRepoUrl.parse("https://github.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
