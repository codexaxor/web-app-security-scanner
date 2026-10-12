package com.company.secscan.scan.worker;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything needed to launch one scanner container. {@code outDir} and {@code tokenFile} are host paths (the
 * worker shares {@link ScannerProperties#getWorkRoot()} with the daemon at the same absolute path). The token
 * never appears here as a value — only as a file path, mounted read-only into the container (D-014). A null
 * {@code tokenFile} means a tokenless clone (e.g. a local {@code file://} fixture).
 */
public record ScannerLaunchSpec(
        String scanId,
        String image,
        String cloneUrl,
        String branch,
        int cloneDepth,
        Path outDir,
        Path tokenFile,
        String tmpfsSize,
        long memoryBytes,
        double cpus,
        long pidsLimit,
        boolean networkDisabled,
        String runAsUser,
        Duration timeout) {
}
