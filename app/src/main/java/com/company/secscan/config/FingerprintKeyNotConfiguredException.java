package com.company.secscan.config;

/**
 * Thrown when a secret finding must be stored but {@code SECSCAN_FINGERPRINT_KEY} is not set. We never persist
 * the bare SHA-256 of a secret, so without the key a secret finding cannot be stored.
 */
public class FingerprintKeyNotConfiguredException extends RuntimeException {

    public FingerprintKeyNotConfiguredException() {
        super("SECSCAN_FINGERPRINT_KEY is not configured; cannot store secret findings (the SHA-256 is keyed "
                + "with HMAC before storage and the bare digest is never persisted). "
                + "Generate one with: openssl rand -base64 32");
    }
}
