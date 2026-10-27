package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The outbound-host allowlist (SSRF guard, D-018). */
class GitLabHostPolicyTest {

    @Test
    void emptyListDeniesEverything() {
        GitLabHostPolicy deny = new GitLabHostPolicy(Set.of());

        assertThat(catchThrowableOfType(
                () -> deny.requireAllowed("https://gitlab.celloscope.net"), GitLabException.class).getError())
                .isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void allowsHttpsAllowlistedHost() {
        GitLabHostPolicy policy = new GitLabHostPolicy(Set.of("gitlab.celloscope.net"));

        assertThatCode(() -> policy.requireAllowed("https://gitlab.celloscope.net")).doesNotThrowAnyException();
    }

    @Test
    void rejectsHttpForNonLoopbackEvenIfAllowlisted() {
        GitLabHostPolicy policy = new GitLabHostPolicy(Set.of("gitlab.celloscope.net"));

        assertThat(catchThrowableOfType(
                () -> policy.requireAllowed("http://gitlab.celloscope.net"), GitLabException.class).getError())
                .isEqualTo(GitLabError.HOST_NOT_ALLOWED);
    }

    @Test
    void allowsHttpForAllowlistedLoopback() {
        GitLabHostPolicy policy = new GitLabHostPolicy(Set.of("localhost"));

        assertThatCode(() -> policy.requireAllowed("http://localhost:8080")).doesNotThrowAnyException();
    }

    @Test
    void parsesCommaSeparatedHostsCaseInsensitively() {
        GitLabHostPolicy policy = new GitLabHostPolicy(" GitLab.Celloscope.NET , other.example.com ");

        assertThat(policy.isAllowedHost("gitlab.celloscope.net")).isTrue();
        assertThat(policy.isAllowedHost("other.example.com")).isTrue();
        assertThat(policy.isAllowedHost("nope.example.com")).isFalse();
    }

    @Test
    void startupSummaryWarnsWhenEmpty() {
        assertThat(new GitLabHostPolicy(Set.of()).startupSummary())
                .contains("EMPTY")
                .contains("denied")
                .contains("SECSCAN_GITLAB_ALLOWED_HOSTS");
    }

    @Test
    void startupSummaryListsConfiguredHosts() {
        assertThat(new GitLabHostPolicy("gitlab.celloscope.net").startupSummary())
                .contains("1 host(s)")
                .contains("gitlab.celloscope.net");
    }

    @Test
    void builtinSaasHostsAreAllowedByDefault() {
        // The Spring constructor merges the built-in SaaS hosts unless they are switched off (M8.7 approval 17).
        GitLabHostPolicy policy = new GitLabHostPolicy("gitlab.celloscope.net", true);
        assertThat(policy.isAllowedHost("github.com")).isTrue();
        assertThat(policy.isAllowedHost("api.github.com")).isTrue();
        assertThat(policy.isAllowedHost("gitlab.com")).isTrue();
        assertThat(policy.isAllowedHost("gitlab.celloscope.net")).isTrue();
        assertThatCode(() -> policy.requireAllowed("https://github.com")).doesNotThrowAnyException();
    }

    @Test
    void builtinSaasCanBeSwitchedOffForEgressLockedDeployments() {
        GitLabHostPolicy policy = new GitLabHostPolicy("gitlab.celloscope.net", false);
        assertThat(policy.isAllowedHost("github.com")).isFalse();
        assertThat(policy.isAllowedHost("gitlab.com")).isFalse();
        assertThat(policy.isAllowedHost("gitlab.celloscope.net")).isTrue();
    }
}
