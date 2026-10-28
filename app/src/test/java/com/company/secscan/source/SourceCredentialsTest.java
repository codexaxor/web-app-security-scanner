package com.company.secscan.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.company.secscan.config.TokenCipherHolder;
import com.company.secscan.crypto.TokenCipher;
import com.company.secscan.gitlab.GitlabConnection;
import com.company.secscan.workspace.Workspace;
import org.junit.jupiter.api.Test;

/**
 * The single provider-neutral credential resolver (M8.8 item 1): {@code credential_kind='none'} → null
 * (anonymous, the cipher is never touched); a token-credentialed connection decrypts; a token-credentialed
 * connection with a blank ciphertext is a broken connection ({@link MissingCredentialException}), never
 * silently anonymous (change 2).
 */
class SourceCredentialsTest {

    private final TokenCipher cipher = mock(TokenCipher.class);
    private final TokenCipherHolder holder = TokenCipherHolder.of(cipher);
    private final SourceCredentials credentials = new SourceCredentials(holder);

    private static GitlabConnection publicRepo() {
        return GitlabConnection.forSingleRepo(Workspace.DEFAULT_ID, "https://github.com",
                GitlabConnection.PROVIDER_GITHUB, GitlabConnection.CREDENTIAL_NONE, "123", "octo/app",
                null, null, null);
    }

    private static GitlabConnection tokenRepo(String ciphertext) {
        return GitlabConnection.forGroup(Workspace.DEFAULT_ID, "https://gitlab.example", "svc", 7L,
                ciphertext, "read_api,read_repository", null);
    }

    @Test
    void anonymousForCredentialKindNoneWithoutTouchingTheCipher() {
        assertThat(credentials.tokenFor(publicRepo())).isNull();
        verifyNoInteractions(cipher);
    }

    @Test
    void decryptsAPresentCiphertext() {
        when(cipher.decrypt("ct")).thenReturn("glpat-secret");
        assertThat(credentials.tokenFor(tokenRepo("ct"))).isEqualTo("glpat-secret");
        verify(cipher).decrypt("ct");
    }

    @Test
    void blankCiphertextOnATokenConnectionThrowsAndNeverDecrypts() {
        assertThatThrownBy(() -> credentials.tokenFor(tokenRepo("")))
                .isInstanceOf(MissingCredentialException.class)
                .hasMessage("Connection needs a new token.");
        verify(cipher, never()).decrypt(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void nullCiphertextOnATokenConnectionThrows() {
        assertThatThrownBy(() -> credentials.tokenFor(tokenRepo(null)))
                .isInstanceOf(MissingCredentialException.class);
    }
}
