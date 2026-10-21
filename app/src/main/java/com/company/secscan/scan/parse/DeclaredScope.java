package com.company.secscan.scan.parse;

/**
 * One declared dev/test dependency scope read by our own in-container inventory (M7, D-059; review S4). Keyed by
 * BOTH the manifest it was declared in AND the coordinate — never a flat coordinate→scope map — so a coordinate
 * that is test-scoped in one module but a production dependency in another is suppressed in the inventory, and a
 * finding is only auto-ignored when ITS manifest declares the coordinate dev/test.
 *
 * @param manifestPath the repo-relative manifest the scope was declared in (matches the SCA finding's file path)
 * @param coordinate   the dependency coordinate ({@code groupId:artifactId} for Maven/Gradle, the package name
 *                     for npm)
 * @param scope        {@code "test"} (Maven/Gradle test configs) or {@code "dev"} (npm devDependencies)
 */
public record DeclaredScope(String manifestPath, String coordinate, String scope) {
}
