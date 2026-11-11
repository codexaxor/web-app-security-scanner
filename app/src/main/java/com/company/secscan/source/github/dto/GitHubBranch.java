package com.company.secscan.source.github.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A GitHub branch from {@code GET /repos/{owner}/{repo}/branches}. The list form carries no commit date and does
 * not mark the default branch, so {@code isDefault} is resolved by the adapter against the repo's default branch.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubBranch(String name, @JsonProperty("protected") boolean isProtected) {
}
