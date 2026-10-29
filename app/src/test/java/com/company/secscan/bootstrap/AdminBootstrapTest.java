package com.company.secscan.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
 * Fast unit tests for the bootstrap decision logic (D-011 / B4 / SF-4), no database: the real admin is created
 * when no EMAIL user exists (an empty DB, or a demo-only DB whose user has no '@'), and refused when the env
 * vars are missing, the password is too short, or an email user already exists.
 */
class AdminBootstrapTest {

    private final PasswordEncoder realEncoder = new BCryptPasswordEncoder();

    private AdminBootstrap bootstrap(AppUserRepository users, PasswordEncoder encoder, String email,
                                     String password) {
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
    void createsOneAdminWithLowercasedEmailOnAnEmptyDb() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.existsByEmailContaining("@")).thenReturn(false); // no email user yet

        bootstrap(users, realEncoder, "Admin@Example.com", "sufficiently-long-pw").run(null);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("admin@example.com");
        assertThat(saved.getValue().getRole()).isEqualTo("admin");
        assertThat(saved.getValue().getWorkspaceId()).isEqualTo(Workspace.DEFAULT_ID);
        assertThat(realEncoder.matches("sufficiently-long-pw", saved.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void createsTheRealAdminWhenOnlyANonEmailDemoUserExists() { // SF-4: gate on an EMAIL user, not any user
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.existsByEmailContaining("@")).thenReturn(false); // a demo-only DB has no '@' user

        bootstrap(users, realEncoder, "admin@example.com", "sufficiently-long-pw").run(null);

        verify(users).save(any()); // the real admin IS created, so turning demo off leaves a working login
    }

    @Test
    void doesNothingWhenEnvVarsMissing() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.existsByEmailContaining("@")).thenReturn(false);

        bootstrap(users, realEncoder, null, null).run(null);

        verify(users, never()).save(any());
    }

    @Test
    void refusesPasswordShorterThanTwelveCharacters() {
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        when(users.existsByEmailContaining("@")).thenReturn(false);

        bootstrap(users, realEncoder, "admin@example.com", "short-11chr").run(null); // 11 chars

        verify(users, never()).save(any());
    }

    @Test
    void doesNothingWhenAnEmailUserAlreadyExists() { // SF-4: an existing real (email) admin → leave it alone
        AppUserRepository users = Mockito.mock(AppUserRepository.class);
        PasswordEncoder encoder = Mockito.mock(PasswordEncoder.class);
        when(users.existsByEmailContaining("@")).thenReturn(true);

        bootstrap(users, encoder, "admin@example.com", "sufficiently-long-pw").run(null);

        verify(users, never()).save(any());
        verify(encoder, never()).encode(anyString()); // no password is encoded either
    }
}
