package com.company.secscan.reconcile;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Stable identities for the issue model (CLAUDE.md domain rule 2, SPEC "Issue lifecycle"). Pure Java, no
 * Spring/JPA, so every rule is unit-testable without a container.
 *
 * <p>A {@code fingerprint} is the lowercase-hex SHA-256 of the type's fields joined with {@code "|"}. It is a
 * finding's identity across scans: status changes only by comparing fingerprints, never by line number or
 * tool-specific id. The fields deliberately exclude the line number (a moved finding on the same snippet keeps
 * its identity; SPEC).
 *
 * <p>A {@code group_key} identifies the {@code issue_group} a finding rolls up into. It is a readable
 * {@code type|discriminator} string (the repository is a separate column, so it is not repeated here); the
 * unique index is {@code (workspace_id, repository_id, group_key)}.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    /**
     * Normalizes a repository-relative path before it enters a fingerprint: backslashes become forward slashes
     * and any leading {@code ./} segments are stripped. Case is preserved (paths are case-sensitive on the
     * systems we scan). A {@code null} path stays {@code null}.
     */
    public static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        String p = path.replace('\\', '/');
        while (p.startsWith("./")) {
            p = p.substring(2);
        }
        return p;
    }

    /** SCA: {@code package | vulnId | manifestPath} (vuln id prefers CVE, else GHSA/OSV). */
    public static String sca(String packageName, String vulnId, String manifestPath) {
        return sha256(join(packageName, vulnId, normalizePath(manifestPath)));
    }

    /** SAST: {@code ruleId | filePath | snippetHash} — never the line number (domain rule 2). */
    public static String sast(String ruleId, String filePath, String snippetHash) {
        return sha256(join(ruleId, normalizePath(filePath), snippetHash));
    }

    /**
     * Secret: {@code ruleId | filePath | secretHash}. {@code secretHash} is the stored keyed HMAC-SHA256
     * (D-027), never the raw value and never the line number.
     */
    public static String secret(String ruleId, String filePath, String secretHash) {
        return sha256(join(ruleId, normalizePath(filePath), secretHash));
    }

    /** IaC: {@code ruleId | filePath | resourceName}. */
    public static String iac(String ruleId, String filePath, String resourceName) {
        return sha256(join(ruleId, normalizePath(filePath), resourceName));
    }

    /** EOL: {@code product | cycle | filePath}. */
    public static String eol(String product, String cycle, String filePath) {
        return sha256(join(product, cycle, normalizePath(filePath)));
    }

    /**
     * The {@code issue_group} key: {@code type|discriminator}. The discriminator is the grouping dimension per
     * type (SPEC): SCA {@code ecosystem/package}, SAST/IaC/Secret the rule id, EOL the product. Including the
     * type guards against two rules of different types sharing a discriminator in one repository.
     */
    public static String groupKey(String type, String discriminator) {
        return type + "|" + discriminator;
    }

    private static String join(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append(fields[i] == null ? "" : fields[i]);
        }
        return sb.toString();
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
