package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import com.company.secscan.workspace.WorkspaceSettingsService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * M8.10 item 1: a request path takes its workspace from the authenticated principal — for the JSON session AND
 * HTTP Basic alike — never from {@code Workspace.DEFAULT_ID}. A user of workspace B changing the workspace
 * settings changes B's settings only; Default's stay as they were.
 */
class PrincipalWorkspaceResolutionIT extends AbstractApiServerIT {

    static final String PASSWORD = "correct-horse-battery";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    WorkspaceSettingsService settings;
    @Autowired
    PasswordEncoder encoder;

    UUID tenantB;

    @BeforeEach
    void seed() {
        tenantB = workspaces.save(new Workspace("Tenant B")).getId();
        users.save(new AppUser(tenantB, "b@test.local", encoder.encode(PASSWORD), "admin"));
    }

    @Test
    void theSessionResolvesTheUsersOwnWorkspace() {
        assertWritesOnlyTenantB(HttpSession.browser(rest, port, "b@test.local", PASSWORD));
    }

    @Test
    void basicResolvesTheUsersOwnWorkspace() {
        assertWritesOnlyTenantB(HttpSession.basic(rest, port, "b@test.local", PASSWORD));
    }

    @Test
    void theSessionNamesTheUsersWorkspace() {
        var res = HttpSession.basic(rest, port, "b@test.local", PASSWORD).get("/api/v1/auth/session");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HttpSession.field(res.getBody(), "workspaceName")).isEqualTo("Tenant B");
    }

    private void assertWritesOnlyTenantB(HttpSession caller) {
        boolean defaultBefore = settings.get(Workspace.DEFAULT_ID).isEpssAutoIgnoreEnabled();
        boolean target = !settings.get(tenantB).isEpssAutoIgnoreEnabled();

        var res = caller.send(HttpMethod.PUT, "/api/v1/workspace/settings",
                "{\"epssAutoIgnoreEnabled\":" + target + "}");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(settings.get(tenantB).isEpssAutoIgnoreEnabled()).isEqualTo(target);
        assertThat(settings.get(Workspace.DEFAULT_ID).isEpssAutoIgnoreEnabled()).isEqualTo(defaultBefore);
        assertThat(caller.get("/api/v1/workspace/settings").getBody())
                .contains("\"epssAutoIgnoreEnabled\":" + target);
    }
}
