package com.company.secscan.repository.api;

/**
 * A partial update of a repository's settings (OD-15 / D-070). Every field is optional; a null field is left
 * unchanged. {@code sensitivity} is validated in the service (low/normal/high → else 400).
 */
public record RepositorySettingsRequest(String sensitivity, Boolean includeDevDeps, Boolean active) {
}
