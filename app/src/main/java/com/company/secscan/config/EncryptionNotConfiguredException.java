package com.company.secscan.config;

/**
 * Thrown when a GitLab token must be encrypted or decrypted but {@code SECSCAN_ENCRYPTION_KEY} is not set.
 * We never fall back to storing a token unencrypted.
 */
public class EncryptionNotConfiguredException extends RuntimeException {

    public EncryptionNotConfiguredException() {
        super("SECSCAN_ENCRYPTION_KEY is not configured; cannot encrypt or decrypt GitLab tokens. "
                + "Generate one with: openssl rand -base64 32");
    }
}
