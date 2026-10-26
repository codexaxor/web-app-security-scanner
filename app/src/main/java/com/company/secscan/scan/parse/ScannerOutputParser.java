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
    /** Warning code the scanner raises per .csproj that has no sibling packages.lock.json (run-scan.sh). */
    static final String WARN_MISSING_NUGET_LOCKFILE = "MISSING_NUGET_LOCKFILE";
    /** Parser-side warning: an OSV source path that is not safely repo-relative (absolute or contains ".."). */
    static final String WARN_UNRESOLVED_PATH = "UNRESOLVED_PATH";

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

        // Warnings the scanner surfaced in its manifest (coverage gaps), built first so the SCA parse can append
        // its own (UNRESOLVED_PATH for an OSV source path that escaped the checkout). The .csproj paths flagged
        // MISSING_NUGET_LOCKFILE (no sibling packages.lock.json on disk) keep their .csproj-sourced SCA findings;
        // see parseOsv (D-035).
        List<ScanWarning> warnings = new ArrayList<>();
        java.util.Set<String> csprojMissingLockfile = new java.util.HashSet<>();
        if (m.warnings != null) {
            for (ManifestWarning w : m.warnings) {
                warnings.add(new ScanWarning(w.code, w.message, w.filePath));
                if (WARN_MISSING_NUGET_LOCKFILE.equals(w.code) && w.filePath != null) {
                    csprojMissingLockfile.add(w.filePath);
                }
            }
        }

        // D-038: the scanner emits a synthetic Maven pom (outside the clone) per lockfile-less Gradle build file
        // so OSV can resolve it transitively. OSV reports the synthetic pom's /work path; this map rewrites it
        // back to the originating build.gradle (repo-relative) before fingerprinting, so a Gradle SCA finding
        // anchors to the build file. An OSV source path that is NOT in this map and not under the checkout still
        // hits the UNRESOLVED_PATH guard below and is dropped.
        Map<String, String> syntheticPoms = new java.util.HashMap<>();
        if (m.syntheticPoms != null) {
            for (ManifestSyntheticPom sp : m.syntheticPoms) {
                if (sp.syntheticPath != null && sp.buildFile != null) {
                    // Normalize the key the same way the OSV source path is normalized at lookup, so a cosmetic
                    // difference (a doubled slash, a trailing slash) can never cause a multi-module synthetic pom
                    // to miss the map and be dropped as UNRESOLVED_PATH.
                    syntheticPoms.put(normalizeSyntheticKey(sp.syntheticPath), sp.buildFile);
                }
            }
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
                findings.addAll(parseOsv(outDir.resolve(s.findingsFile), m.checkoutPath, csprojMissingLockfile,
                        warnings, syntheticPoms));
            } else if (RawFinding.SCANNER_SAST.equals(s.name)) {
                findings.addAll(parseOpengrep(outDir.resolve(s.findingsFile), m.checkoutPath, warnings));
            } else if (RawFinding.SCANNER_IAC.equals(s.name)) {
                findings.addAll(parseCheckov(outDir.resolve(s.findingsFile), warnings));
            } else if (RawFinding.SCANNER_EOL.equals(s.name)) {
                findings.addAll(parseEol(outDir.resolve(s.findingsFile)));
            }
        }

        Map<String, String> versions = m.toolVersions == null ? Map.of() : m.toolVersions;
        String scopeStatus = m.scopeInventoryStatus == null ? ParsedScan.SCOPE_STATUS_OK : m.scopeInventoryStatus;
        List<String> unreadable = m.unreadableScopeManifests == null ? List.of()
                : List.copyOf(m.unreadableScopeManifests);
        return new ParsedScan(false, m.commit, versions, tasks, findings, warnings, toSecscanConfig(m.config),
                toDeclaredScopes(m.declaredScopes), scopeStatus, unreadable);
    }

    /**
     * Carries the manifest's declared-scope entries (D-059) through as typed {@link DeclaredScope}s keyed by
     * (manifest path, coordinate) — NOT a flat {@code coordinate -> scope} map (review S4), so a coordinate that
     * is test-scoped in one module but production in another is kept distinct per manifest. The in-container
     * inventory already suppresses any coordinate declared production anywhere in the repo.
     */
    private List<DeclaredScope> toDeclaredScopes(List<ManifestScope> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<DeclaredScope> out = new ArrayList<>();
        for (ManifestScope s : entries) {
            if (s.coordinate != null && s.scope != null && s.filePath != null) {
                out.add(new DeclaredScope(s.filePath, s.coordinate, s.scope));
            }
        }
        return List.copyOf(out);
    }

    /** Maps the manifest's bounded {@code .secscan.yml} block (D-057) to the typed {@link SecscanConfig}. */
    private SecscanConfig toSecscanConfig(ManifestConfig c) {
        if (c == null) {
            return SecscanConfig.absent();
        }
        String status = c.status == null ? SecscanConfig.STATUS_OK : c.status;
        // Genuinely absent (no config, read cleanly) → absent/ok, so the revoke lifecycle runs normally. But a
        // status != ok (reader refused/crashed, even with present=false) must carry through so the worker FREEZES
        // prior config-derived triage rather than treating it as "config removed" (review S3).
        if (!c.present && SecscanConfig.STATUS_OK.equals(status)) {
            return SecscanConfig.absent();
        }
        List<String> paths = c.excludePaths == null ? List.of() : c.excludePaths;
        Map<String, String> cves = c.ignoreCves == null ? Map.of() : c.ignoreCves;
        return new SecscanConfig(c.present, paths, cves, c.includeDevDependencies, status);
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
        return parseOsv(osvJson, checkoutPath, java.util.Set.of(), new ArrayList<>(), Map.of());
    }

    List<RawFinding> parseOsv(Path osvJson, String checkoutPath, java.util.Set<String> csprojMissingLockfile) {
        return parseOsv(osvJson, checkoutPath, csprojMissingLockfile, new ArrayList<>(), Map.of());
    }

    List<RawFinding> parseOsv(Path osvJson, String checkoutPath, java.util.Set<String> csprojMissingLockfile,
                             List<ScanWarning> warningsOut) {
        return parseOsv(osvJson, checkoutPath, csprojMissingLockfile, warningsOut, Map.of());
    }

    /**
     * As {@link #parseOsv(Path, String)}, plus the NuGet .csproj/lockfile dedup (D-035). OSV reports the same
     * package from both a {@code .csproj} and its sibling {@code packages.lock.json}, so a {@code .csproj}-sourced
     * finding is kept ONLY when that {@code .csproj} (repo-relative path) is in {@code csprojMissingLockfile} —
     * i.e. the scan raised {@code MISSING_NUGET_LOCKFILE} for it (no lockfile on disk). Otherwise a lockfile
     * exists and is authoritative (resolved versions, transitives), so the {@code .csproj} copy is dropped. The
     * rule keys on the filesystem inventory (the warning), not on OSV's results: a clean lockfile may be absent
     * from OSV's JSON, and a {@code .csproj} beside it must still be dropped or its declared-but-not-resolved
     * version would be a false positive.
     *
     * <p>Any OSV source path that does not relativize to a safely repo-relative path (still absolute, or
     * containing a {@code ".."} segment) has its findings dropped and raises an {@code UNRESOLVED_PATH} warning
     * into {@code warningsOut}: such a path must never enter a fingerprint. Normal OSV output never triggers this
     * (every source path is under the checkout); it is defense in depth.
     */
    List<RawFinding> parseOsv(Path osvJson, String checkoutPath, java.util.Set<String> csprojMissingLockfile,
                             List<ScanWarning> warningsOut, Map<String, String> syntheticPoms) {
        if (!Files.exists(osvJson)) {
            return List.of();
        }
        OsvReport report = read(osvJson, OsvReport.class);
        if (report == null || report.results == null) {
            return List.of();
        }
        List<RawFinding> out = new ArrayList<>();
        for (OsvResult result : report.results) {
            String rawPath = result.source == null ? null : result.source.path;
            // D-038: a synthetic Gradle pom's /work path maps back to its originating build.gradle
            // (repo-relative) BEFORE the path guard and fingerprinting; everything else is relativized to the
            // checkout as usual. OSV emits SEPARATE result entries for the same source path (a direct block and
            // a transitive block), so one synthetic pom appears in several results here — the transitive block
            // carries the resolved CVEs. The lookup is a plain map read, applied independently to EVERY result
            // and NEVER consuming the entry, so all blocks on a mapped path are rewritten and kept;
            // UNRESOLVED_PATH fires only for a path genuinely absent from the map. (Duplicate package+vuln across
            // the two blocks share a fingerprint and are deduped downstream by the reconciler, not dropped.)
            String mappedBuildFile = rawPath == null ? null : syntheticPoms.get(normalizeSyntheticKey(rawPath));
            String manifestPath = mappedBuildFile != null ? mappedBuildFile : relativize(rawPath, checkoutPath);
            if (result.packages == null) {
                continue;
            }
            if (isPathOutsideCheckout(manifestPath)) {
                String src = result.source == null ? null : result.source.path;
                log.warn("Dropping OSV result with a source path outside the checkout (UNRESOLVED_PATH): {}", src);
                warningsOut.add(new ScanWarning(WARN_UNRESOLVED_PATH,
                        "OSV reported a dependency manifest at a path outside the repository checkout; its "
                                + "findings were dropped (source path: " + src + ").", null));
                continue;
            }
            if (manifestPath != null && manifestPath.endsWith(".csproj")
                    && !csprojMissingLockfile.contains(manifestPath)) {
                log.debug("Dropping .csproj-sourced SCA findings for {} — an authoritative packages.lock.json "
                        + "sits beside it (no MISSING_NUGET_LOCKFILE warning) (D-035)", manifestPath);
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
                // Match each alias group to its vulnerability object to pull the CVSS vector, advisory label,
                // CWE ids and summary (M5 enrichment, D-044/D-048). None of this feeds the fingerprint.
                Map<String, OsvVuln> vulnsById = new java.util.HashMap<>();
                if (p.vulnerabilities != null) {
                    for (OsvVuln v : p.vulnerabilities) {
                        if (v.id != null) {
                            vulnsById.put(v.id, v);
                        }
                    }
                }
                for (OsvGroup g : p.groups) {
                    String vulnId = pickVulnId(g.ids, g.aliases);
                    if (vulnId == null) {
                        continue;
                    }
                    OsvVuln v = matchVuln(g, vulnsById);
                    String cvss = chooseVector(v);
                    String label = v != null && v.databaseSpecific != null ? v.databaseSpecific.severity : null;
                    List<String> cwe = v != null && v.databaseSpecific != null
                            && v.databaseSpecific.cweIds != null
                            ? List.copyOf(v.databaseSpecific.cweIds) : List.of();
                    String summary = v != null ? v.summary : null;
                    out.add(RawFinding.sca(vulnId, pkg.name, pkg.ecosystem, pkg.version, fixed, manifestPath,
                            g.maxSeverity, cvss, label, cwe, cveAliasesOf(g), summary));
                }
            }
        }
        return out;
    }

    /**
     * Parses Opengrep JSON ({@code opengrep.json}) into one {@link RawFinding} per result (M6 SAST, D-053).
     * {@code check_id} is the rule id, the matched snippet ({@code extra.lines}) is normalized and hashed for
     * the fingerprint (never the line number), and the absolute container path is made repo-relative. A result
     * whose path escapes the checkout is dropped with an {@code UNRESOLVED_PATH} warning (defense in depth — it
     * must never enter a fingerprint). Opengrep's {@code errors[]} (rule-load issues) are ignored here; the scan
     * status already gated success on a valid report.
     */
    List<RawFinding> parseOpengrep(Path json, String checkoutPath, List<ScanWarning> warningsOut) {
        if (!Files.exists(json)) {
            return List.of();
        }
        OpengrepReport report = read(json, OpengrepReport.class);
        if (report == null || report.results == null) {
            return List.of();
        }
        List<RawFinding> out = new ArrayList<>();
        for (OpengrepResult r : report.results) {
            if (r.checkId == null) {
                continue;
            }
            String filePath = relativize(r.path, checkoutPath);
            if (isPathOutsideCheckout(filePath)) {
                log.warn("Dropping Opengrep result with a path outside the checkout (UNRESOLVED_PATH): {}",
                        r.path);
                warningsOut.add(new ScanWarning(WARN_UNRESOLVED_PATH,
                        "Opengrep reported a finding at a path outside the repository checkout; it was dropped "
                                + "(source path: " + r.path + ").", null));
                continue;
            }
            Integer line = r.start == null ? null : r.start.line;
            // The snippet hash is computed and sanitized INSIDE the scanner container (run-scan.sh, D-023/S1):
            // Opengrep's raw matched lines never reach /out, so we read only the precomputed hash here.
            String snippetHash = r.extra == null ? null : r.extra.snippetSha256;
            String rawLevel = r.extra == null ? null : r.extra.severity;
            String label = sastSeverityLabel(r.extra);
            List<String> cwe = r.extra == null ? List.of() : cweOf(r.extra.metadata);
            out.add(RawFinding.sast(r.checkId, filePath, line, snippetHash, label, rawLevel, cwe));
        }
        return out;
    }

    /**
     * Parses Checkov JSON ({@code checkov.json}) into one {@link RawFinding} per IaC misconfiguration (M6,
     * D-054). The output is a JSON ARRAY of per-{@code check_type} reports when several frameworks match, a
     * single report object for one framework, or a summary-only object (no {@code results}) when nothing matched
     * — all handled. Both {@code failed_checks} and {@code skipped_checks} become findings: an inline
     * {@code #checkov:skip} cannot be disabled by a flag, so we RECOVER the skipped checks and promote them back
     * (D-037) — a scanned repo cannot hide its own IaC findings. The IaC fingerprint is
     * {@code rule_id | file_path | resource_name} (the resource goes in the {@code package} column), never the
     * line number; Checkov's {@code file_path} has a leading {@code /} (relative to the scan root) which is
     * stripped to a repo-relative path.
     */
    List<RawFinding> parseCheckov(Path json, List<ScanWarning> warningsOut) {
        if (!Files.exists(json)) {
            return List.of();
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = mapper.readTree(json.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse scanner output " + json.getFileName(), e);
        }
        if (root == null || root.isNull()) {
            return List.of();
        }
        List<RawFinding> out = new ArrayList<>();
        List<com.fasterxml.jackson.databind.JsonNode> reports = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(reports::add);
        } else {
            reports.add(root);
        }
        for (com.fasterxml.jackson.databind.JsonNode report : reports) {
            com.fasterxml.jackson.databind.JsonNode results = report.get("results");
            if (results == null || results.isNull()) {
                continue; // summary-only object (nothing matched this framework)
            }
            addCheckovFindings(results.get("failed_checks"), out, warningsOut);
            addCheckovFindings(results.get("skipped_checks"), out, warningsOut); // D-037 recovery (promote suppressed)
        }
        return out;
    }

    /**
     * Parses the static EOL detector's JSON ({@code eol.json}) into one {@link RawFinding} per detection (M6,
     * D-055): {@code product}/{@code cycle}/{@code filePath} — the EOL fingerprint ({@code product | cycle |
     * file_path}), never a line number. These are CANDIDATES; the worker's EOL enricher decides which are
     * actual issues (past-EOL / ending-soon) against the eol_cycle cache and whether the EOL scanner is solvable
     * this scan. A detection missing a product or cycle is skipped.
     */
    List<RawFinding> parseEol(Path json) {
        if (!Files.exists(json)) {
            return List.of();
        }
        com.fasterxml.jackson.databind.JsonNode root;
        try {
            root = mapper.readTree(json.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not parse scanner output " + json.getFileName(), e);
        }
        com.fasterxml.jackson.databind.JsonNode dets = root == null ? null : root.get("detections");
        if (dets == null || !dets.isArray()) {
            return List.of();
        }
        List<RawFinding> out = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode d : dets) {
            String product = text(d, "product");
            String cycle = text(d, "cycle");
            String filePath = text(d, "filePath");
            if (product == null || product.isBlank() || cycle == null || cycle.isBlank()) {
                continue;
            }
            out.add(RawFinding.eol(product, cycle, filePath));
        }
        return out;
    }

    /** Emits a SAST-style IaC {@link RawFinding} for each check in a Checkov {@code failed_checks}/{@code
     * skipped_checks} array ({@code null}/non-array is ignored). A check whose file path escapes the checkout
     * (absolute, or containing {@code ..}, after stripping Checkov's leading slash) is dropped with an
     * {@code UNRESOLVED_PATH} warning — such a path must never enter a fingerprint (matching the SCA/Opengrep
     * guard). */
    private static void addCheckovFindings(com.fasterxml.jackson.databind.JsonNode checks, List<RawFinding> out,
                                           List<ScanWarning> warningsOut) {
        if (checks == null || !checks.isArray()) {
            return;
        }
        for (com.fasterxml.jackson.databind.JsonNode c : checks) {
            String checkId = text(c, "check_id");
            if (checkId == null) {
                continue;
            }
            String resource = normalizeIacResource(text(c, "resource"));
            String filePath = stripLeadingSlash(text(c, "file_path"));
            if (isPathOutsideCheckout(filePath)) {
                warningsOut.add(new ScanWarning(WARN_UNRESOLVED_PATH,
                        "Checkov reported an IaC finding at a path outside the repository checkout; it was "
                                + "dropped (file_path: " + text(c, "file_path") + ").", null));
                continue;
            }
            Integer line = null;
            com.fasterxml.jackson.databind.JsonNode range = c.get("file_line_range");
            if (range != null && range.isArray() && !range.isEmpty() && range.get(0).isInt()) {
                line = range.get(0).asInt();
            }
            String severityLabel = text(c, "severity"); // usually null for OSS checks → base MEDIUM (D-054)
            // D-083: Checkov's human description becomes the IaC title (carried via advisory_summary, reused);
            // the check_id remains the subtitle and the fingerprint input.
            String checkName = text(c, "check_name");
            out.add(RawFinding.iac(checkId, filePath, resource, line, severityLabel, checkName));
        }
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Checkov reports {@code file_path} relative to the scan root with a leading {@code /}; strip it. */
    static String stripLeadingSlash(String path) {
        if (path == null) {
            return null;
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    /**
     * Normalizes a Checkov {@code resource} to a STABLE, repo-relative IaC fingerprint input (review bug;
     * D-054). Checkov reports file-based frameworks (Dockerfile) as {@code "<leading-slash path>.<stage>"} — a
     * stageless Dockerfile yields e.g. {@code "/iac/docker/Dockerfile."} (leading {@code /}, trailing {@code .}
     * for the empty stage), unlike the already-clean Terraform resources ({@code aws_s3_bucket.foo}). We collapse
     * {@code //}, strip a leading {@code /}, and strip a single trailing {@code .}, so a stageless Dockerfile's
     * resource becomes its repo-relative path (the chosen stable synthetic resource — a named stage keeps its
     * {@code .<stage>}), while Terraform/Kubernetes/CloudFormation resources (no leading slash, no trailing dot)
     * are unchanged. Deterministic — the IaC fingerprint {@code rule_id | file_path | resource_name} is frozen.
     */
    static String normalizeIacResource(String resource) {
        if (resource == null) {
            return null;
        }
        String r = resource.replace('\\', '/');
        while (r.contains("//")) {
            r = r.replace("//", "/");
        }
        if (r.startsWith("/")) {
            r = r.substring(1);
        }
        if (r.endsWith(".")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    /**
     * The SAST severity label (CRITICAL/HIGH/MEDIUM/LOW) that drives the base score (D-053): prefer the rule's
     * {@code metadata.severity} when it is one of those, else map Opengrep's level — ERROR→HIGH, WARNING→MEDIUM,
     * INFO→LOW — defaulting to MEDIUM.
     */
    static String sastSeverityLabel(OpengrepExtra extra) {
        if (extra != null && extra.metadata != null) {
            Object s = extra.metadata.get("severity");
            if (s instanceof String str) {
                switch (str.trim().toUpperCase(java.util.Locale.ROOT)) {
                    case "CRITICAL" -> { return "CRITICAL"; }
                    case "HIGH" -> { return "HIGH"; }
                    case "MEDIUM" -> { return "MEDIUM"; }
                    case "LOW" -> { return "LOW"; }
                    default -> { }
                }
            }
        }
        String level = extra == null ? null : extra.severity;
        if (level == null) {
            return "MEDIUM";
        }
        return switch (level.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "ERROR" -> "HIGH";
            case "WARNING" -> "MEDIUM";
            case "INFO" -> "LOW";
            default -> "MEDIUM";
        };
    }

    /** CWE ids from an Opengrep rule's {@code metadata.cwe}, which may be a single string or an array. */
    @SuppressWarnings("unchecked")
    static List<String> cweOf(Map<String, Object> metadata) {
        if (metadata == null) {
            return List.of();
        }
        Object cwe = metadata.get("cwe");
        if (cwe instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        if (cwe instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return List.copyOf(out);
        }
        return List.of();
    }

    /**
     * Canonical key for matching an OSV {@code source.path} against the synthetic-pom map (D-038): backslashes
     * to forward slashes, repeated slashes collapsed, a trailing slash dropped. The scanner keys the map on each
     * synthetic pom's real path and OSV reports that same path, so this is normally a no-op; it is defense in
     * depth so a cosmetic path difference can never orphan a (multi-module) synthetic pom into UNRESOLVED_PATH.
     */
    static String normalizeSyntheticKey(String path) {
        if (path == null) {
            return null;
        }
        String p = path.replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
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
     * True when a relativized manifest path is not safely repo-relative — it is still absolute or contains a
     * {@code ".."} segment. Normally every OSV source path is under the checkout and {@link #relativize} strips
     * it to a clean relative path; this is defense in depth so a stray absolute/escaping path can never enter a
     * fingerprint. A {@code null} path (no source) is not treated as unsafe.
     */
    static boolean isPathOutsideCheckout(String path) {
        if (path == null) {
            return false;
        }
        String p = path.replace('\\', '/');
        if (p.startsWith("/")) {
            return true;
        }
        for (String segment : p.split("/")) {
            if (segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The group's preferred vulnerability id (plan change #3): the numerically lowest CVE across the group's ids
     * and aliases, else the lowest-sorted GHSA, else the first id. Returns null only for an empty group. The
     * chosen id feeds the SCA fingerprint, so this rule is frozen from M4 on (D-035).
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
        String lowestCve = null;
        for (String id : all) {
            if (id.startsWith("CVE-") && (lowestCve == null || CVE_ORDER.compare(id, lowestCve) < 0)) {
                lowestCve = id;
            }
        }
        if (lowestCve != null) {
            return lowestCve; // numerically lowest CVE by (year, sequence)
        }
        for (String id : all) {
            if (id.startsWith("GHSA-")) {
                return id;
            }
        }
        return all.first();
    }

    /** The vulnerability object for an alias group: the first of the group's ids, then aliases, present by id. */
    private static OsvVuln matchVuln(OsvGroup g, Map<String, OsvVuln> vulnsById) {
        if (g.ids != null) {
            for (String id : g.ids) {
                OsvVuln v = vulnsById.get(id);
                if (v != null) {
                    return v;
                }
            }
        }
        if (g.aliases != null) {
            for (String a : g.aliases) {
                OsvVuln v = vulnsById.get(a);
                if (v != null) {
                    return v;
                }
            }
        }
        return null;
    }

    /**
     * The CVSS vector string to persist as {@code cvss} (D-044): the CVSS v4 vector if present (OSV folds v4 into
     * max_severity and we prefer it for display, SPEC "v4, else v3.1"), else a CVSS v3 vector, else null. The
     * numeric base used for scoring comes from {@code max_severity}, not from this vector.
     */
    static String chooseVector(OsvVuln v) {
        if (v == null || v.severity == null) {
            return null;
        }
        String v3 = null;
        for (OsvSeverity s : v.severity) {
            if (s == null || s.score == null || s.score.isBlank()) {
                continue;
            }
            if ("CVSS_V4".equalsIgnoreCase(s.type)) {
                return s.score.trim();
            }
            if (v3 == null && s.type != null && s.type.toUpperCase(java.util.Locale.ROOT).startsWith("CVSS_V3")) {
                v3 = s.score.trim();
            }
        }
        return v3;
    }

    /** The group's CVE aliases (ids + aliases filtered to {@code CVE-…}), deduped and ordered, for KEV matching. */
    static List<String> cveAliasesOf(OsvGroup g) {
        TreeSet<String> cves = new TreeSet<>();
        if (g.ids != null) {
            for (String id : g.ids) {
                if (id != null && id.startsWith("CVE-")) {
                    cves.add(id);
                }
            }
        }
        if (g.aliases != null) {
            for (String a : g.aliases) {
                if (a != null && a.startsWith("CVE-")) {
                    cves.add(a);
                }
            }
        }
        return List.copyOf(cves);
    }

    /**
     * Orders CVE ids numerically by (year, sequence) rather than as text, so {@code CVE-2024-9999} precedes
     * {@code CVE-2024-10000}. A malformed id sorts last; ties break lexicographically so the choice is stable.
     */
    private static final java.util.Comparator<String> CVE_ORDER = (a, b) -> {
        long[] ka = cveKey(a);
        long[] kb = cveKey(b);
        int c = Long.compare(ka[0], kb[0]);
        if (c == 0) {
            c = Long.compare(ka[1], kb[1]);
        }
        return c != 0 ? c : a.compareTo(b);
    };

    /** (year, sequence) for a {@code CVE-<year>-<seq>} id, or (MAX, MAX) when it does not parse as one. */
    private static long[] cveKey(String id) {
        String[] parts = id.split("-");
        if (parts.length == 3) {
            try {
                return new long[] {Long.parseLong(parts[1]), Long.parseLong(parts[2])};
            } catch (NumberFormatException ignored) {
                // fall through to the max sentinel so a malformed id never wins and ordering stays total
            }
        }
        return new long[] {Long.MAX_VALUE, Long.MAX_VALUE};
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
        public List<ManifestSyntheticPom> syntheticPoms;
        public ManifestConfig config;
        public List<ManifestScope> declaredScopes;
        /** The scope inventory's own status ("ok"/"error"); "error" freezes dev/test triage (review S3). */
        public String scopeInventoryStatus;
        /** Manifests the inventory could not parse; the worker freezes each one's scope state (review SF3). */
        public List<String> unreadableScopeManifests;
    }

    /** A declared dev/test dependency scope read by our own inventory (M7, D-059). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestScope {
        public String coordinate;
        public String scope;
        public String ecosystem;
        public String filePath;
    }

    /** The bounded, whitelisted {@code .secscan.yml} the scanner read as data (M7, D-057). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestConfig {
        public boolean present;
        public List<String> excludePaths;
        public Map<String, String> ignoreCves;
        public Boolean includeDevDependencies;
        /** "ok" for an absent/valid config; any other value freezes prior config-derived triage (review S3). */
        public String status;
    }

    /** Maps a synthetic Gradle pom's container path to its originating {@code build.gradle} (repo-relative). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class ManifestSyntheticPom {
        public String syntheticPath;
        public String buildFile;
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
        public List<OsvSeverity> severity;
        @com.fasterxml.jackson.annotation.JsonProperty("database_specific")
        public OsvDbSpecific databaseSpecific;
        public String summary;
    }

    /** One CVSS vector entry under a vulnerability: {@code type} ∈ CVSS_V3/CVSS_V4, {@code score} = the vector. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvSeverity {
        public String type;
        public String score;
    }

    /** OSV {@code vulnerabilities[].database_specific}: the advisory severity label and CWE ids (D-044/D-048). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OsvDbSpecific {
        public String severity;
        @com.fasterxml.jackson.annotation.JsonProperty("cwe_ids")
        public List<String> cweIds;
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

    // --- Opengrep JSON bindings (Semgrep-compatible; https://semgrep.dev/docs output schema) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OpengrepReport {
        public List<OpengrepResult> results;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OpengrepResult {
        @com.fasterxml.jackson.annotation.JsonProperty("check_id")
        public String checkId;
        public String path;
        public OpengrepPos start;
        public OpengrepExtra extra;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class OpengrepPos {
        public Integer line;
    }

    /** {@code extra}: the rule level ({@code severity} = ERROR/WARNING/INFO), free-form {@code metadata}
     * (severity/cwe/owasp/…), and {@code snippet_sha256} — the normalized-snippet hash computed in the container
     * (the raw {@code lines}/{@code metavars} are stripped there, D-023/S1, so they are absent here). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class OpengrepExtra {
        public String severity;
        @com.fasterxml.jackson.annotation.JsonProperty("snippet_sha256")
        public String snippetSha256;
        public Map<String, Object> metadata;
    }
}
