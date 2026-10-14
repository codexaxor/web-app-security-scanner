package com.company.secscan.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class SecretFingerprinterTest {

    // All-zero 32-byte key, matching the integration-test key, so the expected HMAC is a shared constant.
    private static final String ZERO_KEY_B64 = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String CONTAINER_SHA =
            "91bca0a22047b5d052ba8324cbe895460270fb64cb26ee2d6d47d85d9a6cb78d";

    @Test
    void hashesDeterministicallyAndMatchesTheKnownHmac() {
        SecretFingerprinter fp = SecretFingerprinter.fromBase64Key(ZERO_KEY_B64);
        String hashed = fp.hash(CONTAINER_SHA);
        assertThat(hashed)
                .isEqualTo("ba0975bc436fa197ac88b7f761f60b5faba43d99110db898fb06b547a7a289e4")
                .isNotEqualTo(CONTAINER_SHA);
        // Deterministic for the same input + key.
        assertThat(fp.hash(CONTAINER_SHA)).isEqualTo(hashed);
    }

    @Test
    void differentKeysProduceDifferentHashes() {
        byte[] other = new byte[32];
        other[0] = 1;
        String a = SecretFingerprinter.fromBase64Key(ZERO_KEY_B64).hash(CONTAINER_SHA);
        String b = new SecretFingerprinter(other).hash(CONTAINER_SHA);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void rejectsKeysThatAreNotThirtyTwoBytes() {
        assertThatThrownBy(() -> new SecretFingerprinter(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SecretFingerprinter.fromBase64Key(Base64.getEncoder()
                .encodeToString(new byte[16])))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("32 bytes");
        assertThatThrownBy(() -> SecretFingerprinter.fromBase64Key("not valid base64 !!!"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBlankInput() {
        SecretFingerprinter fp = SecretFingerprinter.fromBase64Key(ZERO_KEY_B64);
        assertThatThrownBy(() -> fp.hash(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keyIdIsShortStableAndKeySpecific() {
        SecretFingerprinter fp = SecretFingerprinter.fromBase64Key(ZERO_KEY_B64);
        String id = fp.keyId();
        assertThat(id).hasSize(8).matches("[0-9a-f]{8}");
        // Stable for the same key, and it is a prefix of HMAC(key, "secscan-key-id"), not of a secret hash.
        assertThat(fp.keyId()).isEqualTo(id);

        byte[] other = new byte[32];
        other[0] = 1;
        assertThat(new SecretFingerprinter(other).keyId()).isNotEqualTo(id);
    }
}
