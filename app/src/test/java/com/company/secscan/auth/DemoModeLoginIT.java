package com.company.secscan.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.secscan.support.AbstractGitLabIT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * D-091 with demo mode ON: the demo user is bootstrapped at startup and signs in with its NON-email username,
 * and {@code /auth/session} reports {@code demoMode:true}. (The OFF case — the same username refused — is
 * {@code DemoModeLockoutIT}, which runs in a separate context with demo mode off.)
 */
@AutoConfigureMockMvc
class DemoModeLoginIT extends AbstractGitLabIT {

    @DynamicPropertySource
    static void demoOn(DynamicPropertyRegistry registry) {
        registry.add("SECSCAN_DEMO_MODE", () -> "true");
        registry.add("SECSCAN_DEMO_USERNAME", () -> "demoadmin");
        registry.add("SECSCAN_DEMO_PASSWORD", () -> "demo-pass");
    }

    @Autowired
    MockMvc mvc;

    @Test
    void theDemoUserSignsInWithANonEmailUsernameAndSessionReportsDemoMode() throws Exception {
        MvcResult login = mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demoadmin\",\"password\":\"demo-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("demoadmin"))
                .andExpect(jsonPath("$.demoMode").value(true))
                .andReturn();

        mvc.perform(get("/api/v1/auth/session").session(
                        (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demoMode").value(true));
    }

    @Test
    void aWrongDemoPasswordIsStillRejected() throws Exception {
        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"demoadmin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }
}
