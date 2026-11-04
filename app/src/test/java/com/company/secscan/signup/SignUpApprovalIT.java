package com.company.secscan.signup;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.support.AbstractApiServerIT;
import com.company.secscan.support.HttpSession;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import com.company.secscan.workspace.WorkspaceRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * D-093/D-094/D-098 + approval round 2 item 1: the operator approves a pending sign-up in ONE transaction —
 * workspace + active owner (password hash copied from the request) + request approved (hash cleared). The new
 * owner signs in with the sign-up password (JSON and Basic) into their own workspace. Reject clears the hash and
 * frees the email. Double decisions, taken emails and unknown ids are refused.
 */
class SignUpApprovalIT extends AbstractApiServerIT {

    static final String OPERATOR_PASSWORD = "operator-password-1";
    static final String SIGNUP_PASSWORD = "my-signup-password";

    @LocalServerPort
    int port;
    @Autowired
    TestRestTemplate rest;
    @Autowired
    AppUserRepository users;
    @Autowired
    SignupRequestRepository requests;
    @Autowired
    WorkspaceRepository workspaces;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    HttpSession operator;

    @BeforeEach
    void seed() {
        AppUser op = new AppUser(Workspace.DEFAULT_ID, "operator@test.local", encoder.encode(OPERATOR_PASSWORD),
                "admin");
        op.promoteToOperator();
        users.save(op);
        operator = HttpSession.browser(rest, port, "operator@test.local", OPERATOR_PASSWORD);
    }

    private UUID pending(String email, String workspaceName) {
        return requests.save(SignupRequest.pending(email, encoder.encode(SIGNUP_PASSWORD), workspaceName)).getId();
    }

    @Test
    void approveCreatesTheWorkspaceAndAnActiveOwnerWhoSignsInWithTheSignUpPassword() {
        UUID id = pending("owner@acme.test", "Acme");
        assertThat(operator.get("/api/v1/admin/sign-ups").getBody()).contains("owner@acme.test");

        var res = operator.post("/api/v1/admin/sign-ups/" + id + "/approve", "{}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HttpSession.field(res.getBody(), "status")).isEqualTo("approved");

        SignupRequest decided = requests.findById(id).orElseThrow();
        assertThat(jdbc.queryForObject("select password_hash from signup_request where id = ?", String.class, id))
                .as("hash cleared on the request row").isNull();
        AppUser owner = users.findByEmail("owner@acme.test").orElseThrow();
        assertThat(owner.getWorkspaceId()).isEqualTo(decided.getWorkspaceId()).isNotEqualTo(Workspace.DEFAULT_ID);
        assertThat(owner.isActive()).isTrue();
        assertThat(owner.isOperator()).isFalse();
        assertThat(workspaces.findById(owner.getWorkspaceId()).orElseThrow().getName()).isEqualTo("Acme");
        assertThat(decided.getDecidedBy()).isEqualTo(users.findByEmail("operator@test.local").orElseThrow().getId());

        var login = HttpSession.login(rest, port, "owner@acme.test", SIGNUP_PASSWORD);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(HttpSession.field(login.getBody(), "workspaceName")).isEqualTo("Acme");
        var basic = HttpSession.basic(rest, port, "owner@acme.test", SIGNUP_PASSWORD).get("/api/v1/auth/session");
        assertThat(basic.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(operator.get("/api/v1/admin/sign-ups").getBody()).doesNotContain("owner@acme.test");
    }

    @Test
    void aSecondDecisionIsA409() {
        UUID id = pending("twice@acme.test", "Acme");
        operator.post("/api/v1/admin/sign-ups/" + id + "/approve", "{}");

        var again = operator.post("/api/v1/admin/sign-ups/" + id + "/approve", "{}");
        var reject = operator.post("/api/v1/admin/sign-ups/" + id + "/reject", "{}");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(HttpSession.field(again.getBody(), "error")).isEqualTo("SIGNUP_ALREADY_DECIDED");
        assertThat(reject.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rejectClearsTheHashBlocksLoginAndFreesTheEmail() {
        UUID id = pending("nope@acme.test", "Nope");
        long workspacesBefore = workspaces.count();

        var res = operator.post("/api/v1/admin/sign-ups/" + id + "/reject", "{}");
        assertThat(HttpSession.field(res.getBody(), "status")).isEqualTo("rejected");
        assertThat(jdbc.queryForObject("select password_hash from signup_request where id = ?", String.class, id))
                .isNull();
        assertThat(workspaces.count()).isEqualTo(workspacesBefore);
        assertThat(HttpSession.login(rest, port, "nope@acme.test", SIGNUP_PASSWORD).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(pending("nope@acme.test", "Nope again")).isNotEqualTo(id);   // may sign up again
    }

    @Test
    void approvingAnEmailThatAlreadyHasAnAccountIsA409AndCreatesNothing() {
        users.save(new AppUser(Workspace.DEFAULT_ID, "taken@acme.test", encoder.encode("whatever-password"),
                "admin"));
        UUID id = pending("taken@acme.test", "Taken");
        long workspacesBefore = workspaces.count();

        var res = operator.post("/api/v1/admin/sign-ups/" + id + "/approve", "{}");
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(HttpSession.field(res.getBody(), "error")).isEqualTo("EMAIL_TAKEN");
        assertThat(workspaces.count()).isEqualTo(workspacesBefore);
        assertThat(requests.findById(id).orElseThrow().isPending()).isTrue();
    }

    @Test
    void anUnknownRequestIsA404AndABadStatusFilterIsA400() {
        assertThat(operator.post("/api/v1/admin/sign-ups/" + UUID.randomUUID() + "/approve", "{}").getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(operator.get("/api/v1/admin/sign-ups?status=bogus").getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
