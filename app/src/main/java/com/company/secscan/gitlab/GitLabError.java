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
    /** The requested group was not found or the token cannot see it (group resolve only). */
    GROUP_NOT_FOUND,
    /** The requested project was not found or the token cannot see it (project resolve only). */
    PROJECT_NOT_FOUND,
    /** 404 on a non-group call: the resource was not found. */
    NOT_FOUND,
    /** A chosen scan branch is invalid or does not exist in GitLab for the project (D-076). */
    BRANCH_NOT_FOUND,
    /** The target host is not https or is not in the allowed-hosts list (SSRF guard, D-018). */
    HOST_NOT_ALLOWED,
    /** The group/project path/URL entered is blank or could not be parsed (user input validation). */
    INVALID_GROUP_PATH,
    /** A connection to the same group/project already exists in this workspace (create would duplicate it). */
    CONNECTION_EXISTS,
    /** An update (token rotation) resolved a different target than the connection it was applied to. */
    CONNECTION_TARGET_MISMATCH,
    /** The connection id was not found in this workspace. */
    CONNECTION_NOT_FOUND,
    /** GitLab answered with a 3xx redirect; we never follow redirects (a token must not leak to another host). */
    UNEXPECTED_REDIRECT,
    /** Any other unexpected GitLab/HTTP error. */
    UNEXPECTED
}
