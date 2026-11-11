package com.company.secscan.source;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.gitlab.GitlabConnection;
import org.springframework.stereotype.Component;

/**
 * The single, provider-neutral resolver of a connection's clone/API credential (M8.8 item 1). Every path that
 * needs a token — the branch picker, the scan-branch validation, the default-branch refresh at enqueue, the
 * GitLab project listing/selection and the worker clone — goes through here instead of decrypting inline, so a
 * public connection never crashes on a blank ciphertext.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code credential_kind='none'} (a public repository) → {@code null}. The provider adapters document
 *       {@code token == null} as anonymous, so {@code null} IS "anonymous"; no literal token string is used.</li>
 *   <li>{@code credential_kind='token'} with a present ciphertext → the decrypted token.</li>
 *   <li>{@code credential_kind='token'} with a blank/missing ciphertext → {@link MissingCredentialException}
 *       (a broken connection needing a new token), never anonymous (change 2).</li>
 *   <li>{@code credential_kind='token'} with a ciphertext that cannot be decrypted (rotated key, damaged value) →
 *       {@link UnreadableCredentialException} (M8.10 S12): the token must be re-entered.</li>
 * </ul>
 *
 * Available to both the api and the worker (a plain {@code @Component} over {@link TokenCipherHolder}).
 */
@Component
public class SourceCredentials {

    private final TokenCipherHolder cipher;

    public SourceCredentials(TokenCipherHolder cipher) {
        this.cipher = cipher;
    }

    /**
     * The decrypted token for {@code conn}, or {@code null} when the connection is anonymous
     * ({@code credential_kind='none'}). Throws {@link MissingCredentialException} for a token-credentialed
     * connection whose ciphertext is blank. Never logs or echoes the token or the ciphertext.
     */
    public String tokenFor(GitlabConnection conn) {
        if (GitlabConnection.CREDENTIAL_NONE.equals(conn.getCredentialKind())) {
            return null; // anonymous: a public repository clones/queries without a token
        }
        String ciphertext = conn.getTokenCiphertext();
        if (ciphertext == null || ciphertext.isBlank()) {
            throw new MissingCredentialException();
        }
        // A missing SECSCAN_ENCRYPTION_KEY stays a configuration error (require() throws before any decrypt).
        com.company.secscan.crypto.TokenCipher c = cipher.require();
        try {
            return c.decrypt(ciphertext);
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Wrong key (rotated) or a damaged value: the connection needs its token re-entered (M8.10 S12). The
            // cause is dropped on purpose — it can only describe ciphertext/key, which must never be echoed.
            throw new UnreadableCredentialException();
        }
    }
}
