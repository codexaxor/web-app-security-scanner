package com.company.secscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.support.AbstractGitLabIT;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * D-091 with demo mode OFF (the default): a leftover non-email demo row ({@code admin}) is refused at login —
 * the no-{@code @} lockout backstop — while a real email user signs in normally.
 */
@AutoConfigureMockMvc
class DemoModeLockoutIT extends AbstractGitLabIT {

    @Autowired
    MockMvc mvc;
    @Autowired
    AppUserRepository appUsers;
    @Autowired
    PasswordEncoder encoder;

    @BeforeEach
    void seed() {
        appUsers.deleteAll();
        // A leftover demo row from a previous demo-on run, plus a real email user.
        appUsers.save(new AppUser(Workspace.DEFAULT_ID, "admin", encoder.encode("demo-pass"), "admin"));
        appUsers.save(new AppUser(Workspace.DEFAULT_ID, "real@company.net", encoder.encode("a-strong-password"),
                "admin"));
    }

    @Test
    void theLeftoverDemoUsernameIsRefusedWhenDemoModeIsOff() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"demo-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aRealEmailUserStillSignsIn() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"real@company.net\",\"password\":\"a-strong-password\"}"))
                .andExpect(status().isOk());
    }
}
