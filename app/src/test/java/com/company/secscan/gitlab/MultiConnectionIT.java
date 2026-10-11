package com.company.secscan.gitlab;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.support.GitLabWireMock;
import com.company.secscan.workspace.Workspace;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** A workspace may hold several connections at once: a whole group plus single projects. */
class MultiConnectionIT extends AbstractGitLabIT {

    private static final String TOKEN = "glpat-EXAMPLE-not-real";

    @Autowired
    GitLabConnectionService service;
    @Autowired
    GitlabConnectionRepository connections;

    private GitLabWireMock gitlab;

    @BeforeEach
    void setUp() {
        gitlab = new GitLabWireMock();
        gitlab.stubVersion().stubTokenSelf("token-self.json")
                .stubGroup("services")
                .stubProject("services%2Fib-keycloak");
    }

    @AfterEach
    void tearDown() {
        gitlab.stop();
    }

    @Test
    void aGroupAndAProjectConnectionCoexist() {
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "group", "services").ok()).isTrue();
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak").ok()).isTrue();

        List<GitlabConnection> all = connections.findByWorkspaceId(Workspace.DEFAULT_ID);
        assertThat(all).hasSize(2);
        assertThat(all).anyMatch(GitlabConnection::isGroup);
        assertThat(all).anyMatch(GitlabConnection::isProject);
        assertThat(service.list()).hasSize(2);
    }

    @Test
    void creatingTheSameProjectTwiceIsRejected() {
        assertThat(service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak").ok()).isTrue();

        var second = service.create(gitlab.baseUrl(), TOKEN, "project", "services/ib-keycloak");

        assertThat(second.ok()).isFalse();
        assertThat(second.errorCode()).isEqualTo(GitLabError.CONNECTION_EXISTS.name());
        assertThat(connections.findByWorkspaceId(Workspace.DEFAULT_ID)).hasSize(1);
    }
}
