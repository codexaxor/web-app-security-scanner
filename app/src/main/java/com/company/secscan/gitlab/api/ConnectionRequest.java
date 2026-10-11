package com.company.secscan.gitlab.api;

import jakarta.validation.constraints.NotBlank;

/** Body for the connect test and save. The token is never logged or echoed back. */
public record ConnectionRequest(
        @NotBlank String baseUrl,
        @NotBlank String token,
        @NotBlank String groupPath) {
}
