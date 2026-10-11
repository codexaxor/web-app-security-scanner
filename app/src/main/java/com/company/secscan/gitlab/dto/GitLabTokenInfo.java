package com.company.secscan.gitlab.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

/** Response of {@code GET /api/v4/personal_access_tokens/self}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitLabTokenInfo(
        List<String> scopes,
        boolean active,
        boolean revoked,
        @JsonProperty("expires_at") LocalDate expiresAt) {

    public List<String> scopes() {
        return scopes == null ? List.of() : scopes;
    }
}
