package com.company.secscan.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.company.secscan.user.AppUser;
import com.company.secscan.user.AppUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** D-091: the startup bootstrap creates/updates the demo user and WARNs when on; is a no-op when off. */
class DemoBootstrapTest {

    private final AppUserRepository users = org.mockito.Mockito.mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private ListAppender<ILoggingEvent> appender;
    private Logger bootstrapLogger;

    @BeforeEach
    void attachAppender() {
        bootstrapLogger = (Logger) LoggerFactory.getLogger(DemoBootstrap.class);
        appender = new ListAppender<>();
        appender.start();
        bootstrapLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        bootstrapLogger.detachAppender(appender);
    }

    private static DemoMode demo(boolean enabled, String username, String password) {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("SECSCAN_DEMO_MODE", Boolean.toString(enabled));
        env.setProperty("SECSCAN_DEMO_USERNAME", username);
        if (password != null) {
            env.setProperty("SECSCAN_DEMO_PASSWORD", password);
        }
        return new DemoMode(env);
    }

    private boolean warnedDemoMode() {
        return appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("DEMO MODE is ON"));
    }

    @Test
    void whenOnItCreatesTheDemoUserWithABcryptHashAndWarns() {
        when(users.findByEmail("demoadmin")).thenReturn(Optional.empty());

        new DemoBootstrap(users, encoder, demo(true, "demoadmin", "demo-pass")).run(null);

        ArgumentCaptor<AppUser> saved = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getEmail()).isEqualTo("demoadmin");
        assertThat(saved.getValue().getPasswordHash()).isNotEqualTo("demo-pass");       // hashed, not plaintext
        assertThat(encoder.matches("demo-pass", saved.getValue().getPasswordHash())).isTrue();
        assertThat(warnedDemoMode()).isTrue();
    }

    @Test
    void whenOnWithAnExistingRowItUpdatesTheCredentialsIdempotently() {
        AppUser existing = new AppUser(com.company.secscan.workspace.Workspace.DEFAULT_ID, "demoadmin",
                "old-hash", "admin");
        when(users.findByEmail("demoadmin")).thenReturn(Optional.of(existing));

        new DemoBootstrap(users, encoder, demo(true, "demoadmin", "new-demo-pass")).run(null);

        verify(users).save(existing);
        assertThat(encoder.matches("new-demo-pass", existing.getPasswordHash())).isTrue();
    }

    @Test
    void whenOnButThePasswordIsTooShortItWarnsAndCreatesNothing() {
        new DemoBootstrap(users, encoder, demo(true, "demoadmin", "abc")).run(null);   // 3 chars
        verify(users, never()).save(any());
        assertThat(appender.list).anyMatch(e -> e.getLevel() == Level.WARN
                && e.getFormattedMessage().contains("NOT created"));
    }

    @Test
    void whenOffItDoesNothing() {
        new DemoBootstrap(users, encoder, demo(false, "admin", "demo-pass")).run(null);
        verify(users, never()).save(any());
        verify(users, never()).findByEmail(any());
        assertThat(warnedDemoMode()).isFalse();
    }
}
