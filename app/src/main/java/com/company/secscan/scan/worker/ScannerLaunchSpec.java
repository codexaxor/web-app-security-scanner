package com.company.secscan.scan.worker;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything needed to launch one scanner container. {@code outDir}, {@code tokenFile} and {@code localSource}
 * are host paths (the worker shares {@link ScannerProperties#getWorkRoot()} with the daemon at the same
 * absolute path). The token never appears here as a value — only as a file path, mounted read-only into the
 * container (D-014). A null {@code tokenFile} means a tokenless clone.
 *
 * <p>{@code localSource}, when set, is a host git repository mounted read-only at {@code /src} so the container
 * can clone it over {@code file:///src} with no network (used for offline/local repositories and the done-when
 * fixture test); it is null for normal {@code https} clones.
 */
public record ScannerLaunchSpec(
        String scanId,
        String image,
        String cloneUrl,
        String branch,
        int cloneDepth,
        Path outDir,
        Path tokenFile,
        Path localSource,
        String tmpfsSize,
        long memoryBytes,
        double cpus,
        long pidsLimit,
        boolean networkDisabled,
        String runAsUser,
        Duration timeout,
        // The git basic-auth username for the clone when a token is present: "oauth2" for GitLab,
        // "x-access-token" for GitHub (D-014: the token itself is the password, delivered via the one-time file).
        String cloneAuthUser) {
}
