package com.company.secscan.crypto;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption for secrets at rest (GitLab tokens). Pure Java, no Spring, so it is unit-testable
 * without a container.
 *
 * <p>The key is exactly 32 bytes (AES-256). Each {@link #encrypt} uses a fresh random 12-byte IV and a 128-bit
 * auth tag; the output is base64 of {@code IV || ciphertext+tag}. Decryption verifies the tag, so any tampering
 * (or a wrong key) fails loudly rather than returning garbage.
 */
public final class TokenCipher {

    private static final int KEY_BYTES = 32;      // AES-256
    private static final int IV_BYTES = 12;       // GCM standard nonce length
    private static final int TAG_BITS = 128;      // GCM auth tag
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param key exactly 32 bytes
     * @throws IllegalArgumentException if the key is not 32 bytes
     */
    public TokenCipher(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Encryption key must be exactly " + KEY_BYTES + " bytes (AES-256)");
        }
        this.key = key.clone();
    }

    /**
     * Builds a cipher from a base64-encoded 32-byte key (e.g. {@code openssl rand -base64 32}).
     *
     * @throws IllegalArgumentException if the value is not valid base64 or does not decode to 32 bytes
     */
    public static TokenCipher fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Encryption key is blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Encryption key is not valid base64", e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Encryption key must decode to " + KEY_BYTES + " bytes (AES-256) but was " + decoded.length);
        }
        return new TokenCipher(decoded);
    }

    /** Encrypts UTF-8 plaintext, returning base64 of {@code IV || ciphertext+tag}. */
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /**
     * Decrypts a value produced by {@link #encrypt}.
     *
     * @throws IllegalStateException if the input is malformed, tampered with, or encrypted under a different key
     */
    public String decrypt(String ciphertextBase64) {
        if (ciphertextBase64 == null || ciphertextBase64.isBlank()) {
            throw new IllegalArgumentException("ciphertext must not be blank");
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(ciphertextBase64.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Ciphertext is not valid base64", e);
        }
        if (blob.length <= IV_BYTES) {
            throw new IllegalStateException("Ciphertext is too short to contain an IV");
        }
        try {
            byte[] iv = Arrays.copyOfRange(blob, 0, IV_BYTES);
            byte[] ct = Arrays.copyOfRange(blob, IV_BYTES, blob.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // Never echo the ciphertext or key; just report failure.
            throw new IllegalStateException("Decryption failed (wrong key or tampered ciphertext)", e);
        }
    }
}
