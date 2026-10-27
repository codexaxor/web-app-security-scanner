package com.company.secscan.source;

/**
 * One adapter per source provider (GitLab, GitHub). Read-only, stateless: the connection's base URL and (for a
 * token-credentialed connection) the decrypted token are passed per call; a public connection passes a null
 * token. Every method maps provider HTTP responses to one {@link ProviderException} taxonomy
 * ({@link ProviderError}); rate limits and 5xx/timeouts are {@link ProviderError#isTransient() transient} and
 * must never be read as "the repository is gone". TLS is always verified and redirects are never followed with a
 * credential (M8.7 item 2; D-080). Identity ({@code providerRepoId}) is the provider's stable numeric id as text.
 */
public interface SourceProvider {

    /** The provider id this adapter serves: {@code "gitlab"} or {@code "github"}. */
    String id();

    /**
     * Verifies a credential against the host, reporting scopes and expiry when the provider exposes them. For a
     * public connection ({@code token == null}) returns {@link CredentialStatus#anonymous()} after confirming the
     * host is reachable and allowed.
     */
    CredentialStatus verifyCredential(String baseUrl, String token);

    /**
     * One page of the repositories the connection can see (a GitLab group's projects, a GitHub owner's repos, or
     * the single repo of a project/repo connection). {@code search} is optional server-side filtering; {@code
     * page} is 0-based; {@code size} is capped by the adapter at the provider's maximum.
     */
    SourcePage<SourceRepo> listRepositories(SourceConnectionRef connection, String token, String search,
                                            int page, int size);

    /** A single repository by the provider's stable numeric id (as text); 404 → {@link ProviderError#NOT_FOUND}. */
    SourceRepo getRepository(String baseUrl, String token, String providerRepoId);

    /**
     * One page of a repository's branches (branch picker). {@code search} is optional; {@code page} is 0-based;
     * {@code size} is capped at the provider's maximum. Totals come from the provider's paging headers.
     */
    SourcePage<SourceBranch> listBranches(String baseUrl, String token, String providerRepoId, String search,
                                          int page, int size);

    /**
     * The authoritative existence check for a branch (exact match) — used when setting a scan branch (D-076) and
     * for the A5 pre-clone check. 404 → {@code false}; other errors propagate as {@link ProviderException}.
     */
    boolean branchExists(String baseUrl, String token, String providerRepoId, String branch);

    /**
     * The https clone URL for a repository. NEVER contains a credential (the token is delivered to the scanner
     * via the D-014 one-time file, never the URL). {@code repoFullName} is the display path; {@code providerRepoId}
     * is the stable id — the adapter uses whichever its provider clones by.
     */
    String cloneUrl(String baseUrl, String providerRepoId, String repoFullName);
}
