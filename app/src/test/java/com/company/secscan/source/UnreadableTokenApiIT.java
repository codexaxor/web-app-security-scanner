package com.company.secscan.source;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.crypto.TokenCipher;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.gitlab.GitlabConnectionRepository;
import com.company.secscan.repository.Repository;
import com.company.secscan.repository.RepositoryRepository;
import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * M8.10 S12: a stored token that cannot be decrypted — encrypted under a different (rotated) key, or a damaged
 * value — answers the typed 409 {@code CONNECTION_TOKEN_UNREADABLE} ("re-enter the token for this source") on
 * EVERY path that decrypts a token, instead of a 500: GitLab project listing and selection, provider-neutral
 * listing and selection, the branch picker and the scan-branch check. The body never echoes the ciphertext or the
 * cipher's cause. (A manual scan start only refreshes the default branch best-effort, so it still queues the
 * scan — tolerated exactly like a missing token — and the worker fails it: {@code UnreadableTokenScanIT}.)
 */
@AutoConfigureMockMvc
class UnreadableTokenApiIT extends AbstractGitLabIT {

    private static final UUID WS = Workspace.DEFAULT_ID;

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    GitlabConnectionRepository connections;
    @Autowired
    RepositoryRepository repositories;

    private String auth;

    @BeforeEach
    void seedAdmin() {
        appUsers.deleteAll();
        appUsers.save(new AppUser(WS, "admin@test.local", encoder.encode("pw"), "admin"));
        auth = "Basic " + Base64.getEncoder().encodeToString("admin@test.local:pw".getBytes(StandardCharsets.UTF_8));
    }

    /** A well-formed AES-GCM value made under a DIFFERENT key — what a rotated SECSCAN_ENCRYPTION_KEY looks like. */
    private static String encryptedUnderAnotherKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return TokenCipher.fromBase64Key(Base64.getEncoder().encodeToString(key)).encrypt("glpat-EXAMPLE-rotated");
    }

    private static final String DAMAGED = "!!not-base64-and-not-a-ciphertext!!";

    private ResultActions expectUnreadable(ResultActions r, String ciphertext) throws Exception {
        return r.andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(UnreadableCredentialException.CODE))
                .andExpect(jsonPath("$.message", Matchers.containsString("Re-enter the token for this source")))
                .andExpect(jsonPath("$.message", Matchers.not(Matchers.containsString(ciphertext))))
                .andExpect(jsonPath("$.message", Matchers.not(Matchers.containsString("Decryption"))))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void gitLabPathsAnswer409ForARotatedKey() throws Exception {
        String ct = encryptedUnderAnotherKey();
        GitlabConnection conn = connections.save(GitlabConnection.forGroup(
                WS, "http://localhost", "svc-rotated", 7801L, ct, "read_api,read_repository", null));
        Repository repo = repositories.save(
                new Repository(WS, conn.getId(), 7802L, "svc-rotated/app", "main", List.of("Java")));

        expectUnreadable(mvc.perform(get("/api/v1/gitlab/connections/{id}/projects", conn.getId())
                .header("Authorization", auth)), ct);
        expectUnreadable(mvc.perform(post("/api/v1/repositories").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"connectionId\":\"" + conn.getId() + "\",\"gitlabProjectIds\":[7802]}")), ct);
        expectUnreadable(mvc.perform(get("/api/v1/repositories/{id}/branches", repo.getId())
                .header("Authorization", auth)), ct);
        expectUnreadable(mvc.perform(patch("/api/v1/repositories/{id}", repo.getId()).header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON).content("{\"scanBranch\":\"develop\"}")), ct);

    }

    @Test
    void providerNeutralPathsAnswer409ForADamagedValue() throws Exception {
        GitlabConnection conn = connections.save(GitlabConnection.forGitHubOwner(
                WS, "https://api.github.com", "acme-damaged", DAMAGED, null, null));

        expectUnreadable(mvc.perform(get("/api/v1/sources/connections/{id}/repositories", conn.getId())
                .header("Authorization", auth)), DAMAGED);
        expectUnreadable(mvc.perform(post("/api/v1/sources/connections/{id}/select", conn.getId())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"providerRepoIds\":[\"123\"]}")), DAMAGED);
    }
}
