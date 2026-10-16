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
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Parses the scanner container's {@code /out} directory into a {@link ParsedScan}. Secret entries are already
 * sanitized by the container (D-023): only a SHA-256 hash and a 4-char preview, never the value. SCA findings
 * come from OSV-Scanner's JSON ({@code osv.json}): one finding per alias group (plan change #3), the manifest
 * path made repo-relative by stripping the container checkout prefix (never a {@code /work} path in a
 * fingerprint), and OSV's own junk versions (e.g. an unresolved MSBuild property) dropped (D-035).
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
            tasks.add(new ScannerTaskResult(s.name, s.status, s.exitCode, s.durationMs, s.findingsFile,
                    s.noPackages));
            if (!s.succeeded() || s.findingsFile == null) {
                continue;
            }
            if (RawFinding.SCANNER_SECRET.equals(s.name)) {
                findings.addAll(readSecretFindings(outDir.resolve(s.findingsFile)));
            } else if (RawFinding.SCANNER_SCA.equals(s.name)) {
                findings.addAll(parseOsv(outDir.resolve(s.findingsFile), m.checkoutPath));
            }
        }

        List<ScanWarning> warnings = new ArrayList<>();
        if (m.warnings != null) {
            for (ManifestWarning w : m.warnings) {
                warnings.add(new ScanWarning(w.code, w.message, w.filePath));
            }
        }
        Map<String, String> versions = m.toolVersions == null ? Map.of() : m.toolVersions;
        return new ParsedScan(false, m.commit, versions, tasks, findings, warnings);
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
     * Parses OSV-Scanner JSON into one {@link RawFinding} per alias group (plan change #3). {@code checkoutPath}
     * is the container's clone root; each result's source path is made repo-relative to it so no {@code /work}
     * path enters a fingerprint. A package whose version is not concrete (an unresolved MSBuild property, a
     * range or a wildcard) is dropped — OSV passes such versions through literally but they identify nothing
     * (D-035).
     */
    List<RawFinding> parseOsv(Path osvJson, String checkoutPath) {
        if (!Files.exists(osvJson)) {
            return List.of();
        }
        OsvReport report = read(osvJson, OsvReport.class);
        if (report == null || report.results == null) {
            return List.of();
        }
        // NuGet dedup (D-035): OSV reports the same package from both a .csproj and its sibling
        // packages.lock.json when both sit in one folder. The lockfile is authoritative (resolved versions,
        // includes transitives), so collect the directories that have one and drop the .csproj-sourced
        // findings there. A .csproj with no lockfile beside it is untouched.
        var nugetLockfileDirs = new java.util.HashSet<String>();
        for (OsvResult result : report.results) {
            String path = relativize(result.source == null ? null : result.source.path, checkoutPath);
            if (path != null && "packages.lock.json".equals(fileName(path))) {
                nugetLockfileDirs.add(dirOf(path));
            }
        }

        List<RawFinding> out = new ArrayList<>();
        for (OsvResult result : report.results) {
            String manifestPath = relativize(result.source == null ? null : result.source.path, checkoutPath);
            if (result.packages == null) {
                continue;
            }
            if (manifestPath != null && fileName(manifestPath).endsWith(".csproj")
                    && nugetLockfileDirs.contains(dirOf(manifestPath))) {
                log.debug("Dropping .csproj-sourced SCA findings for {} — authoritative packages.lock.json "
                        + "present in the same directory (D-035)", manifestPath);
                continue;
            }
            for (OsvPackage p : result.packages) {
                OsvPkgInfo pkg = p.pkg;
                if (pkg == null || p.groups == null || p.groups.isEmpty()) {
                    continue; // no vulnerability groups → nothing to record
                }
                if (!isConcreteVersion(pkg.version)) {
                    log.debug("Dropping SCA package {} with non-concrete version '{}' (D-035)",
                            pkg.name, pkg.version);
                    continue;
                }
                String fixed = fixedVersion(p);
                for (OsvGroup g : p.groups) {
                    String vulnId = pickVulnId(g.ids, g.aliases);
                    if (vulnId == null) {
                        continue;
                    }
                    out.add(RawFinding.sca(vulnId, pkg.name, pkg.ecosystem, pkg.version, fixed, manifestPath,
                            g.maxSeverity));
                }
            }
        }
        return out;
    }

    /** The final path segment (handles both repo-relative and bare paths); empty string for a null/blank path. */
    private static String fileName(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The directory of a repo-relative path ("" for a file at the repo root), used to match siblings. */
    private static String dirOf(String path) {
        if (path == null) {
            return "";
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Makes an OSV source path repo-relative by stripping the container checkout prefix. */
    private static String relativize(String path, String checkoutPath) {
        if (path == null) {
            return null;
        }
        if (checkoutPath != null && !checkoutPath.isBlank()) {
            String prefix = checkoutPath.endsWith("/") ? checkoutPath : checkoutPath + "/";
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    /**
     * The group's preferred vulnerability id (plan change #3): the lowest-sorted CVE across the group's ids and
     * aliases, else the lowest-sorted GHSA, else the first id. Returns null only for an empty group.
     */
    static String pickVulnId(List<String> ids, List<String> aliases) {
        TreeSet<String> all = new TreeSet<>();
        if (ids != null) {
            all.addAll(ids);
        }
        if (aliases != null) {
            all.addAll(aliases);
        }
        if (all.isEmpty()) {
            return null;
        }
        for (String id : all) {
            if (id.startsWith("CVE-")) {
                return id; // TreeSet is sorted → first CVE is the lowest-sorted one
            }
        }
        for (String id : all) {
            if (id.startsWith("GHSA-")) {
                return id;
            }
        }
        return all.first();
    }

    /** A concrete pinned version OSV can identify — not empty, an MSBuild property, a range or a wildcard. */
    static boolean isConcreteVersion(String version) {
        if (version == null || version.isBlank()) {
            return false;
        }
        for (int i = 0; i < version.length(); i++) {
            switch (version.charAt(i)) {
                case '$', '[', ']', '(', ')', ',', '*' -> {
                    return false;
                }
                default -> { }
            }
        }
        return true;
    }

    /** Best-effort "fixed in" version: the first fixed event across the package's vulnerabilities, else null. */
    private static String fixedVersion(OsvPackage p) {
        if (p.vulnerabilities == null) {
            return null;
        }
        for (OsvVuln v : p.vulnerabilities) {
            if (v.affected == null) {
                continue;
            }
            for (OsvAffected a : v.affected) {
                if (a.ranges == null) {
                    continue;
                }
                for (OsvRange r : a.ranges) {
                    if (r.events == null) {
                        continue;
                    }
                    for (OsvEvent e : r.events) {
                        if (e.fixed != null && !e.fixed.isBlank()) {
                            return e.fixed;
                        }
                    }
                }
            }
        }
        return null;
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
        public String checkoutPath;
        public Map<String, String> toolVersions;
        public List<ManifestScanner> scanners;
        public List<ManifestWarning> warnings;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestScanner {
        public String name;
        public String status;
        public Integer exitCode;
        public Long durationMs;
        public String findingsFile;
        public boolean noPackages;

        boolean succeeded() {
            return "ok".equals(status);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestWarning {
        public String code;
        public String message;
        public String filePath;
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

    // --- OSV-Scanner JSON bindings (https://google.github.io/osv-scanner output schema) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvReport {
        public List<OsvResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvResult {
        public OsvSource source;
        public List<OsvPackage> packages;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvSource {
        public String path;
        public String type;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvPackage {
        @com.fasterxml.jackson.annotation.JsonProperty("package")
        public OsvPkgInfo pkg;
        public List<OsvGroup> groups;
        public List<OsvVuln> vulnerabilities;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvPkgInfo {
        public String name;
        public String version;
        public String ecosystem;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvGroup {
        public List<String> ids;
        public List<String> aliases;
        @com.fasterxml.jackson.annotation.JsonProperty("max_severity")
        public String maxSeverity;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvVuln {
        public String id;
        public List<OsvAffected> affected;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvAffected {
        public List<OsvRange> ranges;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvRange {
        public List<OsvEvent> events;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvEvent {
        public String introduced;
        public String fixed;
    }
}
