package com.company.secscan.config;

import com.company.secscan.crypto.SecretFingerprinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires the secret fingerprint key from {@code SECSCAN_FINGERPRINT_KEY} (D-027).
 *
 * <ul>
 *   <li>Key present and valid (base64 → 32 bytes): the fingerprinter is available.</li>
 *   <li>Key present but invalid: <b>startup fails</b> with a clear message (fromBase64Key throws).</li>
 *   <li>Key absent: the app still starts; storing a secret finding fails with
 *       {@link FingerprintKeyNotConfiguredException}.</li>
 * </ul>
 */
@Configuration
public class FingerprintConfig {

    private static final Logger log = LoggerFactory.getLogger(FingerprintConfig.class);

    @Bean
    SecretFingerprinterHolder secretFingerprinterHolder(
            @Value("${SECSCAN_FINGERPRINT_KEY:}") String keyBase64) {
        if (!StringUtils.hasText(keyBase64)) {
            log.warn("SECSCAN_FINGERPRINT_KEY is not set; secret findings cannot be stored until it is. "
                    + "Generate one with: openssl rand -base64 32");
            return SecretFingerprinterHolder.unconfigured();
        }
        // Validate eagerly: an invalid key fails startup rather than surfacing later.
        SecretFingerprinter fingerprinter = SecretFingerprinter.fromBase64Key(keyBase64);
        log.info("Secret fingerprint key configured (HMAC-SHA256).");
        return SecretFingerprinterHolder.of(fingerprinter);
    }
}
