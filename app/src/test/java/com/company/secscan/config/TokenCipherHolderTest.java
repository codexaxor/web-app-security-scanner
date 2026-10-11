package com.company.secscan.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.company.secscan.crypto.TokenCipher;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherHolderTest {

    @Test
    void requireThrowsWhenUnconfigured() {
        TokenCipherHolder holder = TokenCipherHolder.unconfigured();

        assertThat(holder.isConfigured()).isFalse();
        assertThatThrownBy(holder::require).isInstanceOf(EncryptionNotConfiguredException.class);
    }

    @Test
    void requireReturnsCipherWhenConfigured() {
        TokenCipher cipher = TokenCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]));
        TokenCipherHolder holder = TokenCipherHolder.of(cipher);

        assertThat(holder.isConfigured()).isTrue();
        assertThat(holder.require()).isSameAs(cipher);
    }
}
