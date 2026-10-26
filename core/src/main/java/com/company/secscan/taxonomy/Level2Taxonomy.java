package com.company.secscan.taxonomy;

import com.company.secscan.finding.RawFinding;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The level-2 finding taxonomy (D-083, D-074): a short category per finding derived ONLY from stored data, plus
 * an optional OWASP Top 10:2025 category NAME (names only — CC BY-SA; no OWASP text). Pure and unit-testable
 * (no Spring), loaded once; the app stores the result on {@code issue_group} and exposes it on the feed/group/
 * sub-issue views.
 *
 * <p>Per type:
 * <ul>
 *   <li><b>SCA</b> → the ecosystem, from the manifest file name ({@code pom.xml}→Maven, {@code package.json}→npm,
 *       …). OWASP = Software Supply Chain Failures (A03:2025 expands "vulnerable/outdated components").</li>
 *   <li><b>Secret</b> → the secret kind, from the Betterleaks rule id (private key / cloud credential / API token
 *       / password). OWASP = none (a hardcoded secret spans several categories — not guessed).</li>
 *   <li><b>SAST</b> → the weakness class of the first CWE id ({@code cwe-weakness-class.properties}); OWASP from
 *       {@code cwe-owasp-2025.properties} (confident CWE→category mappings only, else null).</li>
 *   <li><b>IaC</b> → the platform, a COARSE heuristic (D-draft-1) over the Checkov {@code check_id} + file name
 *       (Dockerfile / Kubernetes / Terraform), fallback "Infrastructure". OWASP = Security Misconfiguration
 *       (A02:2025).</li>
 *   <li><b>EOL</b> → the runtime/product name. OWASP = Software Supply Chain Failures.</li>
 * </ul>
 * The level-2 category is NEVER null (each type has a fallback); the OWASP name is null when no confident
 * mapping exists. None of these inputs is a fingerprint input.
 */
public final class Level2Taxonomy {

    // OWASP Top 10:2025 category NAMES (verbatim from top10.owasp.org/2025, verified 2026-10-27; CC BY-SA).
    public static final String OWASP_BROKEN_ACCESS_CONTROL = "Broken Access Control";
    public static final String OWASP_SECURITY_MISCONFIGURATION = "Security Misconfiguration";
    public static final String OWASP_SUPPLY_CHAIN = "Software Supply Chain Failures";
    public static final String OWASP_CRYPTO_FAILURES = "Cryptographic Failures";
    public static final String OWASP_INJECTION = "Injection";
    public static final String OWASP_INSECURE_DESIGN = "Insecure Design";
    public static final String OWASP_AUTH_FAILURES = "Authentication Failures";
    public static final String OWASP_INTEGRITY_FAILURES = "Software or Data Integrity Failures";
    public static final String OWASP_LOGGING_FAILURES = "Security Logging and Alerting Failures";
    public static final String OWASP_EXCEPTIONAL_CONDITIONS = "Mishandling of Exceptional Conditions";

    private static final Level2Taxonomy INSTANCE =
            new Level2Taxonomy(load("/taxonomy/cwe-weakness-class.properties"),
                    load("/taxonomy/cwe-owasp-2025.properties"));

    private final Map<String, String> cweClass;
    private final Map<String, String> cweOwasp;

    Level2Taxonomy(Map<String, String> cweClass, Map<String, String> cweOwasp) {
        this.cweClass = Map.copyOf(cweClass);
        this.cweOwasp = Map.copyOf(cweOwasp);
    }

    public static Level2Taxonomy getInstance() {
        return INSTANCE;
    }

    /** A finding's level-2 category and optional OWASP Top 10:2025 category name (the name may be null). */
    public record Category(String level2, String owasp) {
    }

    /**
     * The level-2 category for a finding from its stored fields. {@code filePath} is the manifest (SCA) or source
     * (SAST/IaC) path; {@code ruleId} is the secret/SAST/IaC rule or Checkov check id; {@code cweIds} is the SAST
     * CWE list (first id used); {@code packageName} is the EOL product. The level-2 label is never null.
     */
    public Category of(String type, String filePath, String ruleId, List<String> cweIds, String packageName) {
        if (type == null) {
            return new Category("Finding", null);
        }
        return switch (type) {
            case RawFinding.TYPE_SCA -> new Category(ecosystem(filePath), OWASP_SUPPLY_CHAIN);
            case RawFinding.TYPE_SECRET -> new Category(secretKind(ruleId), null);
            case RawFinding.TYPE_SAST -> new Category(weaknessClass(cweIds), owaspForCwe(cweIds));
            case RawFinding.TYPE_IAC -> new Category(iacPlatform(ruleId, filePath), OWASP_SECURITY_MISCONFIGURATION);
            case RawFinding.TYPE_EOL -> new Category(eolRuntime(packageName), OWASP_SUPPLY_CHAIN);
            default -> new Category("Finding", null);
        };
    }

    // --- SCA: ecosystem from the manifest file name ---

    private static String ecosystem(String filePath) {
        String name = baseName(filePath);
        if (name.isEmpty()) {
            return "Dependency";
        }
        if (name.equals("pom.xml")) {
            return "Maven";
        }
        if (name.startsWith("build.gradle") || name.startsWith("settings.gradle") || name.equals("gradle.lockfile")) {
            return "Gradle";
        }
        if (name.equals("package.json") || name.equals("package-lock.json") || name.equals("yarn.lock")
                || name.equals("pnpm-lock.yaml") || name.equals("npm-shrinkwrap.json")) {
            return "npm";
        }
        if (name.endsWith(".csproj") || name.endsWith(".vbproj") || name.endsWith(".fsproj")
                || name.equals("packages.lock.json") || name.equals("packages.config")) {
            return "NuGet";
        }
        if (name.equals("requirements.txt") || name.equals("pipfile") || name.equals("pipfile.lock")
                || name.equals("poetry.lock") || name.equals("pyproject.toml")) {
            return "PyPI";
        }
        if (name.equals("go.mod") || name.equals("go.sum")) {
            return "Go";
        }
        if (name.equals("gemfile") || name.equals("gemfile.lock")) {
            return "RubyGems";
        }
        if (name.equals("composer.json") || name.equals("composer.lock")) {
            return "Composer";
        }
        return "Dependency";
    }

    // --- Secret: kind from the Betterleaks rule id ---

    private static String secretKind(String ruleId) {
        if (ruleId == null) {
            return "Secret";
        }
        String r = ruleId.toLowerCase(Locale.ROOT);
        if (r.contains("private-key") || r.contains("privatekey")) {
            return "Private key";
        }
        if (r.contains("aws") || r.contains("gcp") || r.contains("azure") || r.contains("cloud")) {
            return "Cloud credential";
        }
        if (r.contains("password") || r.contains("passwd")) {
            return "Password";
        }
        if (r.contains("token") || r.contains("pat") || r.contains("api-key") || r.contains("apikey")
                || r.contains("secret") || r.contains("key")) {
            return "API token";
        }
        return "Secret";
    }

    // --- SAST: weakness class + OWASP from the first CWE id ---

    private String weaknessClass(List<String> cweIds) {
        String id = firstCwe(cweIds);
        if (id != null) {
            String c = cweClass.get(id);
            if (c != null) {
                return c;
            }
        }
        return "Code weakness";
    }

    private String owaspForCwe(List<String> cweIds) {
        String id = firstCwe(cweIds);
        return id == null ? null : cweOwasp.get(id);
    }

    private static String firstCwe(List<String> cweIds) {
        if (cweIds == null || cweIds.isEmpty() || cweIds.get(0) == null) {
            return null;
        }
        return cweIds.get(0).trim().toUpperCase(Locale.ROOT);
    }

    // --- IaC: coarse platform heuristic (D-draft-1) ---

    private static String iacPlatform(String checkId, String filePath) {
        String name = baseName(filePath);
        String rule = checkId == null ? "" : checkId.toUpperCase(Locale.ROOT);
        if (name.equals("dockerfile") || name.startsWith("dockerfile") || name.endsWith(".dockerfile")) {
            return "Dockerfile";
        }
        if (name.endsWith(".tf") || name.endsWith(".tf.json") || name.endsWith(".hcl")) {
            return "Terraform";
        }
        if (rule.contains("_K8S") || rule.contains("KUBERNETES")) {
            return "Kubernetes";
        }
        return "Infrastructure";
    }

    // --- EOL: runtime/product display name ---

    private static String eolRuntime(String product) {
        if (product == null) {
            return "Runtime";
        }
        return switch (product.toLowerCase(Locale.ROOT)) {
            case "spring-boot" -> "Spring Boot";
            case "dotnet", ".net" -> ".NET";
            case "nodejs", "node" -> "Node.js";
            case "keycloak" -> "Keycloak";
            case "alpine-linux", "alpine" -> "Alpine Linux";
            case "eclipse-temurin", "java" -> "Java runtime";
            default -> "Runtime";
        };
    }

    private static String baseName(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        int slash = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return (slash >= 0 ? p.substring(slash + 1) : p).toLowerCase(Locale.ROOT);
    }

    /** Sizes of the two CWE maps (coverage test). */
    public int cweClassSize() {
        return cweClass.size();
    }

    public int cweOwaspSize() {
        return cweOwasp.size();
    }

    private static Map<String, String> load(String resource) {
        Properties props = new Properties();
        try (InputStream in = Level2Taxonomy.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing taxonomy resource: " + resource);
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + resource, e);
        }
        Map<String, String> map = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name.trim().toUpperCase(Locale.ROOT), props.getProperty(name).trim());
        }
        return map;
    }
}
