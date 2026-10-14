package com.company.secscan.scan.parse;

import com.company.secscan.finding.RawFinding;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses the scanner container's {@code /out} directory into a {@link ParsedScan}. The container emits an
 * already-sanitized format (D-023): secret entries carry only a SHA-256 hash and a 4-char preview, never the
 * value. The secret-entry binding deliberately has no field for a raw value, and unknown JSON properties are
 * ignored, so even a malformed {@code secrets.json} containing a raw secret cannot surface one here.
 *
 * <p>No Spring is required to use it (own {@link ObjectMapper}), so it is unit-testable against saved samples.
 */
@Component
public class ScannerOutputParser {

    private static final Logger log = LoggerFactory.getLogger(ScannerOutputParser.class);
    static final String MASKED = "****";

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Reads {@code result.json} and each scanner's findings file from {@code outDir}. */
    public ParsedScan parse(Path outDir) {
        Manifest m = read(outDir.resolve("result.json"), Manifest.class);
        if (m == null) {
            throw new IllegalStateException("Scanner produced no result.json in " + outDir);
        }
        if (m.cloneFailed) {
            return ParsedScan.cloneFailure();
        }

        List<ScannerTaskResult> tasks = new ArrayList<>();
        List<RawFinding> findings = new ArrayList<>();
        List<ManifestScanner> scanners = m.scanners == null ? List.of() : m.scanners;
        for (ManifestScanner s : scanners) {
            tasks.add(new ScannerTaskResult(s.name, s.status, s.exitCode, s.durationMs, s.findingsFile));
            if (s.succeeded() && RawFinding.SCANNER_SECRET.equals(s.name) && s.findingsFile != null) {
                findings.addAll(readSecretFindings(outDir.resolve(s.findingsFile)));
            }
        }
        Map<String, String> versions = m.toolVersions == null ? Map.of() : m.toolVersions;
        return new ParsedScan(false, m.commit, versions, tasks, findings);
    }

    private List<RawFinding> readSecretFindings(Path findingsFile) {
        if (!Files.exists(findingsFile)) {
            return List.of();
        }
        SecretEntry[] entries = read(findingsFile, SecretEntry[].class);
        if (entries == null) {
            return List.of();
        }
        List<RawFinding> out = new ArrayList<>(entries.length);
        for (SecretEntry e : entries) {
            out.add(RawFinding.secret(e.ruleId, e.file, e.startLine, e.secretHash, safePreview(e.preview), null));
        }
        return out;
    }

    /**
     * Defense in depth against a preview that leaks part of the secret: the container must emit at most 4
     * characters with no whitespace/newline (see {@code secscan-lib.sh}). If it ever doesn't, clamp to the
     * fully-masked {@code ****} and warn WITHOUT the offending value, so a bad preview can never reach the DB,
     * the API or the logs.
     */
    static String safePreview(String preview) {
        if (preview == null || preview.equals(MASKED)) {
            return preview;
        }
        boolean tooLong = preview.length() > 4;
        boolean hasWhitespace = preview.chars().anyMatch(Character::isWhitespace);
        if (tooLong || hasWhitespace) {
            log.warn("Scanner emitted an invalid secret preview (length={}, hasWhitespace={}); clamping to '{}'.",
                    preview.length(), hasWhitespace, MASKED);
            return MASKED;
        }
        return preview;
    }

    private <T> T read(Path file, Class<T> type) {
        try {
            return mapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse scanner output " + file.getFileName(), e);
        }
    }

    // --- JSON bindings (match scanner-image/run-scan.sh output; see scanner-image/README.md) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Manifest {
        public int schemaVersion;
        public boolean cloneFailed;
        public String commit;
        public Map<String, String> toolVersions;
        public List<ManifestScanner> scanners;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestScanner {
        public String name;
        public String status;
        public Integer exitCode;
        public Long durationMs;
        public String findingsFile;

        boolean succeeded() {
            return "ok".equals(status);
        }
    }

    /** A sanitized secret finding. Intentionally has NO field for the secret value, match or line text. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class SecretEntry {
        public String ruleId;
        public String file;
        public Integer startLine;
        public String secretHash;
        public String preview;
    }
}
