package com.company.secscan.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Response of {@code GET /api/v4/groups/:url-encoded-path}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabGroup(long id, @JsonProperty("full_path") String fullPath) {
}
