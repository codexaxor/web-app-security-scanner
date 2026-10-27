package com.company.secscan.source;

import java.time.LocalDate;
import java.util.List;

/**
 * The result of verifying a connection's credential: whether it is usable, the scopes the provider reports, and
 * the expiry date when the provider exposes one (GitLab PAT expiry; a GitHub fine-grained PAT may not expose a
 * date). For a public ({@code credential_kind=none}) connection, {@code valid} is true with empty scopes and no
 * expiry. Never carries the token itself.
 */
public record CredentialStatus(boolean valid, List<String> scopes, LocalDate expiresAt, String message) {

    public CredentialStatus {
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
    }

    public static CredentialStatus anonymous() {
        return new CredentialStatus(true, List.of(), null, "Public access (no credential).");
    }
}
