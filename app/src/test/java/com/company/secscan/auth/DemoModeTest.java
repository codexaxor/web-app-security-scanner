package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** D-091 demo-mode flag + the no-{@code @} lockout rule and the relaxed demo password floor. */
class DemoModeTest {

    private static DemoMode demo(String enabled, String username, String password) {
        MockEnvironment env = new MockEnvironment();
        if (enabled != null) {
            env.setProperty("SECSCAN_DEMO_MODE", enabled);
        }
        if (username != null) {
            env.setProperty("SECSCAN_DEMO_USERNAME", username);
        }
        if (password != null) {
            env.setProperty("SECSCAN_DEMO_PASSWORD", password);
        }
        return new DemoMode(env);
    }

    @Test
    void defaultsToDisabledWithAdminUsername() {
        DemoMode d = demo(null, null, null);
        assertThat(d.enabled()).isFalse();
        assertThat(d.username()).isEqualTo("admin");
    }

    @Test
    void usernameIsNormalizedLikeALogin() {
        assertThat(demo("true", "  DemoAdmin  ", "demo-pass").username()).isEqualTo("demoadmin");
    }

    @Test
    void whenDisabledOnlyEmailIdentifiersAreAllowed() {
        DemoMode d = demo("false", "admin", null);
        assertThat(d.identifierAllowed("someone@company.net")).isTrue();   // real users unaffected
        assertThat(d.identifierAllowed("admin")).isFalse();                // leftover demo row locked out
    }

    @Test
    void whenEnabledANonEmailIdentifierIsAllowed() {
        DemoMode d = demo("true", "admin", "demo-pass");
        assertThat(d.identifierAllowed("admin")).isTrue();
        assertThat(d.identifierAllowed("someone@company.net")).isTrue();
    }

    @Test
    void usablePasswordRequiresAtLeastTheDemoFloor() {
        assertThat(demo("true", "admin", "demo").hasUsablePassword()).isTrue();    // 4 chars = floor
        assertThat(demo("true", "admin", "dem").hasUsablePassword()).isFalse();    // 3 chars
        assertThat(demo("true", "admin", null).hasUsablePassword()).isFalse();
    }
}
