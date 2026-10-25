package com.company.secscan.repository.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A partial update of a repository's settings (OD-15 / D-070, D-076). Every field is optional; an absent field is
 * left unchanged. {@code sensitivity} is validated in the service (low/normal/high → else 400).
 *
 * <p>{@code scanBranch} is a {@link JsonNode} so the service can tell three cases apart that a plain {@code String}
 * cannot: the key absent (Java {@code null} → leave unchanged), the key present as JSON {@code null}
 * ({@link JsonNode#isNull()} → reset to the GitLab default branch), or a string value (set that branch, after
 * validating its name and existence in GitLab).
 */
public record RepositorySettingsRequest(String sensitivity, Boolean includeDevDeps, Boolean active,
                                        JsonNode scanBranch) {
}
