package com.company.secscan.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Fast unit tests for the bootstrap decision logic (D-011 / B4), no database:
 * creates exactly one admin for valid input, and refuses when env vars are missing, the password is too short,
 * or a user already exists.
 */
class AdminBootstrapTest {

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private AdminBootstrap bootstrap(AppUserRepository users, String email, String password) {
        MockEnvironment env = new MockEnvironment();
        if (email != null) {
            env.setProperty("SECSCAN_BOOTSTRAP_ADMIN_EMAIL", email);
        }
        if (password != null) {
            env.setProperty("SECSCAN_BOOTSTRAP_ADMIN_PASSWORD", password);
        }
        return new AdminBootstrap(users, encoder, env);
    }

    @Test
    void createsOneAdminWithLowercasedEmailForValidInput() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.count()).thenReturn(0L);

        bootstrap(users, "Admin@Example.com", "sufficiently-long-pw").run(null);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo("admin");
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
        assertThat(encoder.matches("sufficiently-long-pw", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void doesNothingWhenEnvVarsMissing() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.count()).thenReturn(0L);

        bootstrap(users, null, null).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void refusesPasswordShorterThanTwelveCharacters() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.count()).thenReturn(0L);

        bootstrap(users, "admin@example.com", "short-11chr").run(null); // 11 chars

        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWhenAUserAlreadyExists() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.count()).thenReturn(1L);

        bootstrap(users, "admin@example.com", "sufficiently-long-pw").run(null);

        verify(users, never()).save(any());
    }
}
