package com.company.secscan.config;

import com.company.secscan.crypto.SecretFingerprinter;

/**
 * Holds the {@link SecretFingerprinter} when {@code SECSCAN_FINGERPRINT_KEY} is set, or signals that it is
 * absent. Callers use {@link #require()} so that a missing key surfaces as a clear error at the point where a
 * secret finding must be stored rather than a null pointer — and never as a bare-SHA-256 fallback.
 */
public final class SecretFingerprinterHolder {

    private final SecretFingerprinter fingerprinter; // null when not configured

    private SecretFingerprinterHolder(SecretFingerprinter fingerprinter) {
        this.fingerprinter = fingerprinter;
    }

    public static SecretFingerprinterHolder of(SecretFingerprinter fingerprinter) {
        return new SecretFingerprinterHolder(fingerprinter);
    }

    public static SecretFingerprinterHolder unconfigured() {
        return new SecretFingerprinterHolder(null);
    }

    public boolean isConfigured() {
        return fingerprinter != null;
    }

    public SecretFingerprinter require() {
        if (fingerprinter == null) {
            throw new FingerprintKeyNotConfiguredException();
        }
        return fingerprinter;
    }
}
