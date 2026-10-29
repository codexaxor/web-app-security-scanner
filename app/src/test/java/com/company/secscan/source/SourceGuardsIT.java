package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.gitlab.GitLabConnectionService;
import com.company.secscan.gitlab.GitLabError;
import com.company.secscan.gitlab.GitLabException;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.workspace.Workspace;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * M8.8 item 7 server-side guards for the Sources page. The UI URL parser is a convenience only; the server keeps
 * the authoritative checks: a public (credential_kind='none') connection cannot have its token rotated, and a
 * denied host is rejected regardless of what the client would parse/send.
 */
class SourceGuardsIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    GitLabConnectionService gitlabConnections;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    SourceConnectionService sourceConnections;

    @Test
    void rotatingAPublicConnectionIsRejected() {
        GitlabConnection pub = connections.save(GitlabConnection.forSingleRepo(
                WS, "https://github.com", GitlabConnection.PROVIDER_GITHUB, GitlabConnection.CREDENTIAL_NONE,
                "123", "octo/pub", null, null, null));

        assertThatThrownBy(() -> gitlabConnections.update(pub.getId(), "https://github.com", "tok", "octo/pub"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> {
                    ResponseStatusException rse = (ResponseStatusException) e;
                    assertThat(rse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(rse.getReason()).contains("no token to rotate");
                });
    }

    @Test
    void addingAPublicRepoOnADeniedHostIsRejectedEvenIfTheUrlParses() {
        // The allowlist is "localhost" (AbstractGitLabIT); a well-formed https URL on another host is still denied.
        assertThatThrownBy(() -> sourceConnections.createPublicRepo("https://evil.example.com/owner/repo"))
                .isInstanceOf(GitLabException.class)
                .satisfies(e -> assertThat(((GitLabException) e).getError()).isEqualTo(GitLabError.HOST_NOT_ALLOWED));
    }
}
