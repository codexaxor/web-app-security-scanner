package com.company.secscan.config;

import com.company.secscan.crypto.TokenCipher;

/**
 * Holds the {@link TokenCipher} when {@code SECSCAN_ENCRYPTION_KEY} is set, or signals that it is absent.
 * Callers use {@link #require()} so that a missing key surfaces as a clear error at the point of use rather
 * than a null pointer — and never as a silent plaintext fallback.
 */
public final class TokenCipherHolder {

    private final TokenCipher cipher; // null when not configured

    private TokenCipherHolder(TokenCipher cipher) {
        this.cipher = cipher;
    }

    public static TokenCipherHolder of(TokenCipher cipher) {
        return new TokenCipherHolder(cipher);
    }

    public static TokenCipherHolder unconfigured() {
        return new TokenCipherHolder(null);
    }

    public boolean isConfigured() {
        return cipher != null;
    }

    public TokenCipher require() {
        if (cipher == null) {
            throw new EncryptionNotConfiguredException();
        }
        return cipher;
    }
}
