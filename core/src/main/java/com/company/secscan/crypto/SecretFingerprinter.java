package com.company.secscan.crypto;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Keys a secret's SHA-256 digest before it is stored, so the stored value is HMAC-SHA256(key, sha256) rather
 * than the bare digest. Pure Java, no Spring, so it is unit-testable without a container.
 *
 * <p>The scanner container computes a plain SHA-256 of the secret value (the raw secret never leaves the
 * container). Storing that bare digest would let anyone holding the database confirm a guessed secret by
 * hashing it. HMAC-ing the digest with a server-held key (never in the database) removes that offline-guessing
 * capability while still giving a stable, comparable fingerprint for reconcile (M3). The key is exactly 32
 * bytes, matching the AES key size.
 */
public final class SecretFingerprinter {

    private static final int KEY_BYTES = 32;
    private static final String HMAC = "HmacSHA256";

    private final byte[] key;

    /**
     * @param key exactly 32 bytes
     * @throws IllegalArgumentException if the key is not 32 bytes
     */
    public SecretFingerprinter(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Fingerprint key must be exactly " + KEY_BYTES + " bytes");
        }
        this.key = key.clone();
    }

    /**
     * Builds a fingerprinter from a base64-encoded 32-byte key (e.g. {@code openssl rand -base64 32}).
     *
     * @throws IllegalArgumentException if the value is not valid base64 or does not decode to 32 bytes
     */
    public static SecretFingerprinter fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalArgumentException("Fingerprint key is blank");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Fingerprint key is not valid base64", e);
        }
        if (decoded.length != KEY_BYTES) {
            throw new IllegalArgumentException(
                    "Fingerprint key must decode to " + KEY_BYTES + " bytes but was " + decoded.length);
        }
        return new SecretFingerprinter(decoded);
    }

    /**
     * Returns the lowercase-hex HMAC-SHA256 of {@code sha256Hex} (the container-computed digest) under the key.
     *
     * @throws IllegalArgumentException if {@code sha256Hex} is null or blank
     */
    public String hash(String sha256Hex) {
        if (sha256Hex == null || sha256Hex.isBlank()) {
            throw new IllegalArgumentException("sha256Hex must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            byte[] out = mac.doFinal(sha256Hex.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", e);
        }
    }
}
