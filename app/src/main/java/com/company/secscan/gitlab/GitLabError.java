package com.company.secscan.gitlab;

/**
 * Categories of GitLab connection failure, mapped to clear messages on the Connect screen.
 */
public enum GitLabError {

    /** Host unreachable: DNS failure, connection refused, or timeout (e.g. VPN off). */
    UNREACHABLE,
    /** 401: the token is invalid or revoked. */
    INVALID_TOKEN,
    /** 403, or the token is missing a required scope (read_api / read_repository). */
    MISSING_SCOPES,
    /** The token has expired. */
    TOKEN_EXPIRED,
    /** 429: rate limited; honor Retry-After and try again later. */
    RATE_LIMITED,
    /** The requested group was not found or the token cannot see it. */
    GROUP_NOT_FOUND,
    /** Any other unexpected GitLab/HTTP error. */
    UNEXPECTED
}
