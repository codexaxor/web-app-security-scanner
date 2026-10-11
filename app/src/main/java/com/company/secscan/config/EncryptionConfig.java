package com.company.secscan.config;

import com.company.secscan.crypto.TokenCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires the token cipher from {@code SECSCAN_ENCRYPTION_KEY}.
 *
 * <ul>
 *   <li>Key present and valid (base64 → 32 bytes): cipher is available.</li>
 *   <li>Key present but invalid: <b>startup fails</b> with a clear message (fromBase64Key throws).</li>
 *   <li>Key absent: the app still starts; connection save/use fails with {@link EncryptionNotConfiguredException}.</li>
 * </ul>
 */
@Configuration
public class EncryptionConfig {

    private static final Logger log = LoggerFactory.getLogger(EncryptionConfig.class);

    @Bean
    TokenCipherHolder tokenCipherHolder(@Value("${SECSCAN_ENCRYPTION_KEY:}") String keyBase64) {
        if (!StringUtils.hasText(keyBase64)) {
            log.warn("SECSCAN_ENCRYPTION_KEY is not set; GitLab connections cannot be saved until it is. "
                    + "Generate one with: openssl rand -base64 32");
            return TokenCipherHolder.unconfigured();
        }
        // Validate eagerly: an invalid key fails startup rather than surfacing later.
        TokenCipher cipher = TokenCipher.fromBase64Key(keyBase64);
        log.info("Token encryption configured (AES-256-GCM).");
        return TokenCipherHolder.of(cipher);
    }
}
