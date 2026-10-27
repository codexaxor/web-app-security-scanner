package com.company.secscan.source.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** A GitHub repository as the REST API reports it (the fields SecScan needs). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubRepo(
        long id,
        @JsonProperty("full_name") String fullName,
        @JsonProperty("default_branch") String defaultBranch,
        @JsonProperty("private") boolean isPrivate,
        String visibility,
        boolean archived,
        boolean disabled,
        @JsonProperty("size") long size) {

    /** GitHub reports an empty repo as size 0 with no default branch. */
    public boolean isEmpty() {
        return defaultBranch == null || defaultBranch.isBlank();
    }

    public String notSelectableReason() {
        if (archived || disabled) {
            return "The repository is archived or disabled.";
        }
        if (isEmpty()) {
            return "Empty repository (no commits yet).";
        }
        return null;
    }

    public String reportedVisibility() {
        if (visibility != null && !visibility.isBlank()) {
            return visibility;
        }
        return isPrivate ? "private" : "public";
    }
}
