package com.company.secscan.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * M8.8 item 2: an unhandled exception becomes the generic {@code {"error":"INTERNAL","message":"Unexpected
 * error."}} envelope (no class name, no message, no stack trace), while the catch-all leaves the security 401,
 * unknown-route 404 and existing 400s untouched — and a {@link com.company.secscan.source.MissingCredentialException}
 * (a token connection with a blank ciphertext) still surfaces as its specific 409 over HTTP (change 2).
 */
@AutoConfigureMockMvc
@Import(ApiExceptionHandlerIT.BoomController.class)
class ApiExceptionHandlerIT extends AbstractGitLabIT {

    private static final UUID WORKSPACE = Workspace.DEFAULT_ID;

    /** A throwaway endpoint that throws an unexpected (untyped) exception, to exercise the catch-all. */
    @RestController
    static class BoomController {
        @GetMapping("/api/v1/__test__/boom")
        String boom() {
            throw new IllegalStateException("boom-secret-internal-detail-should-not-leak");
        }
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    RepositoryRepository repositories;
    @Autowired
    GitlabConnectionRepository connections;

    private String auth;

    @BeforeEach
    void seedAdmin() {
        appUsers.deleteAll();
        appUsers.save(new AppUser(WORKSPACE, "admin@test.local", encoder.encode("pw"), "admin"));
        auth = "Basic " + Base64.getEncoder()
                .encodeToString("admin@test.local:pw".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anUnhandledExceptionBecomesTheGenericInternalEnvelopeWithoutLeaking() throws Exception {
        mvc.perform(get("/api/v1/__test__/boom").header("Authorization", auth))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("INTERNAL"))
                .andExpect(jsonPath("$.message").value("Unexpected error."))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                // the real exception's class name / message must never reach the client
                .andExpect(jsonPath("$.message", Matchers.not(Matchers.containsString("IllegalState"))))
                .andExpect(jsonPath("$.message", Matchers.not(Matchers.containsString("boom-secret"))));
    }

    @Test
    void aTokenConnectionWithABlankCiphertextIsA409NeedingANewToken() throws Exception {
        // credential_kind defaults to 'token'; a blank ciphertext ("" — a broken connection, not anonymous).
        // The V22 check constraint forbids a NULL ciphertext on a token connection, but an empty string is
        // IS NOT NULL (so it persists) and tokenFor() treats it as a missing credential.
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WORKSPACE, "http://localhost", "svc-broken", 7701L, "", "read_api,read_repository", null));
        Repository repo = repositories.save(
                new Repository(WORKSPACE, conn.getId(), 7701L, "svc/broken", "main", List.of("Java")));

        mvc.perform(get("/api/v1/repositories/{id}/branches", repo.getId()).header("Authorization", auth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CONNECTION_TOKEN_MISSING"))
                .andExpect(jsonPath("$.message").value("Connection needs a new token."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void theCatchAllLeavesTheSecurity401Untouched() throws Exception {   // no credentials
        mvc.perform(get("/api/v1/__test__/boom"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("WWW-Authenticate"))
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"));
    }

    @Test
    void theCatchAllLeavesUnknownRoutesAs404() throws Exception {
        mvc.perform(get("/api/v1/definitely-not-a-route").header("Authorization", auth))
                .andExpect(status().isNotFound());
    }

    @Test
    void theCatchAllLeavesExistingBadParameter400sUntouched() throws Exception {
        mvc.perform(get("/api/v1/issue-groups/{id}", "not-a-uuid").header("Authorization", auth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_PARAMETER"))
                .andExpect(jsonPath("$.message", Matchers.containsString("groupId")));
    }
}
