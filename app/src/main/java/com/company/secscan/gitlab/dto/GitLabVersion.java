package com.company.secscan.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Response of {@code GET /api/v4/version}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabVersion(String version, String revision) {
}
