package com.company.secscan.gitlab.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for rotating an existing connection's token. The {@code path} must resolve to the same target the
 * connection already has (its type is fixed); to connect a different group/project, create a new connection.
 */
public record UpdateConnectionRequest(
        @NotBlank String baseUrl,
        @NotBlank String token,
        @NotBlank String path) {
}
