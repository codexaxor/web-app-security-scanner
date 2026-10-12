package com.company.secscan.gitlab.api;

import jakarta.validation.constraints.NotBlank;

/**
 * Body for the connection test and create. {@code targetType} is {@code "group"} or {@code "project"} and
 * {@code path} is the group or project path (a plain path or a pasted GitLab URL). The token is never logged or
 * echoed back.
 */
public record ConnectionRequest(
        @NotBlank String baseUrl,
        @NotBlank String token,
        @NotBlank String targetType,
        @NotBlank String path) {
}
