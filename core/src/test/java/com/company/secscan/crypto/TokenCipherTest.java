package com.company.secscan.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class TokenCipherTest {

    // A fixed, obviously-fake 32-byte key for tests.
    private static final String KEY_B64 =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes());

    private final TokenCipher cipher = TokenCipher.fromBase64Key(KEY_B64);

    @Test
    void roundTrips() {
        String secret = "glpat-EXAMPLE-not-a-real-token";
        String encrypted = cipher.encrypt(secret);
        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
    }

    @Test
    void producesDifferentCiphertextEachTime() {
        String secret = "same-input";
        assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
    }

    @Test
    void decryptFailsOnTamperedCiphertext() {
        String encrypted = cipher.encrypt("sensitive");
        byte[] blob = Base64.getDecoder().decode(encrypted);
        blob[blob.length - 1] ^= 0x01; // flip a bit in the tag/ciphertext
        String tampered = Base64.getEncoder().encodeToString(blob);

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decryptFailsUnderDifferentKey() {
        String encrypted = cipher.encrypt("sensitive");
        TokenCipher other = TokenCipher.fromBase64Key(
                Base64.getEncoder().encodeToString("ffffffffffffffffffffffffffffffff".getBytes()));

        assertThatThrownBy(() -> other.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsKeyOfWrongLength() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes());
        assertThatThrownBy(() -> TokenCipher.fromBase64Key(shortKey))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void rejectsNonBase64Key() {
        assertThatThrownBy(() -> TokenCipher.fromBase64Key("not valid base64 !!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankKey() {
        assertThatThrownBy(() -> TokenCipher.fromBase64Key("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
