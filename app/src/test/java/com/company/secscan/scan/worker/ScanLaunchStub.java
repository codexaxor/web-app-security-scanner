package com.company.secscan.scan.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Test helper: writes the scanner container's sanitized {@code /out} files, as the stubbed launcher would. */
public final class ScanLaunchStub {

    private ScanLaunchStub() {
    }

    /** Writes a result.json (one successful secret scanner) and a secrets.json with a single secret finding. */
    public static void writeSecretFinding(Path outDir, String commit, String secretHash, String preview) {
        try {
            Files.writeString(outDir.resolve("secrets.json"), "[{"
                    + "\"ruleId\":\"aws-access-token\",\"file\":\"config/application.properties\","
                    + "\"startLine\":5,\"secretHash\":\"" + secretHash + "\",\"preview\":\"" + preview + "\"}]");
            Files.writeString(outDir.resolve("result.json"), "{"
                    + "\"schemaVersion\":1,\"cloneFailed\":false,\"commit\":\"" + commit + "\","
                    + "\"toolVersions\":{\"git\":\"2.47.3\",\"gitleaks\":\"8.21.2\"},"
                    + "\"scanners\":[{\"name\":\"secret\",\"status\":\"ok\",\"exitCode\":2,\"durationMs\":5,"
                    + "\"findingsFile\":\"secrets.json\"}]}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes a result.json for a run where the clone succeeded but the secret scanner FAILED (as run-scan.sh
     * emits when gitleaks exits with an unexpected code or writes an invalid report): status "failed", no
     * findings file. The worker must record the task as failed, the scan as partial, and store no findings.
     */
    public static void writeScannerFailure(Path outDir, String commit) {
        try {
            Files.writeString(outDir.resolve("result.json"), "{"
                    + "\"schemaVersion\":1,\"cloneFailed\":false,\"commit\":\"" + commit + "\","
                    + "\"toolVersions\":{\"git\":\"2.47.3\",\"gitleaks\":\"8.21.2\"},"
                    + "\"scanners\":[{\"name\":\"secret\",\"status\":\"failed\",\"exitCode\":1,\"durationMs\":5,"
                    + "\"findingsFile\":\"secrets.json\"}]}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
