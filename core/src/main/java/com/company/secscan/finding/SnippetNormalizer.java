package com.company.secscan.finding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Normalizes a SAST matched snippet before it feeds the SAST fingerprint ({@code rule_id | file_path |
 * normalized_snippet_hash}, CLAUDE.md domain rule 2). The normalization strips leading/trailing whitespace and
 * collapses internal whitespace runs so that a cosmetic edit — reindentation, trailing spaces, a reflow — does
 * NOT change the fingerprint, while a real change to the matched code does. It is deliberately
 * language-agnostic (whitespace only; comment/token stripping is out of scope) and carries NO line number, so a
 * finding that moves within a file keeps its identity.
 *
 * <p>Pure Java (no Spring/JPA), unit-tested without a container. The hash is the lowercase-hex SHA-256 of the
 * normalized text; a null/blank snippet hashes the empty string, so a finding with no snippet still gets a
 * stable (empty-normalized) hash rather than a null fingerprint component.
 */
public final class SnippetNormalizer {

    private SnippetNormalizer() {
    }

    /**
     * Canonical form of a matched snippet: each line trimmed, internal whitespace runs collapsed to a single
     * space, blank lines dropped, the surviving lines joined with {@code "\n"}. Tabs/CR are whitespace.
     */
    public static String normalize(String snippet) {
        if (snippet == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        for (String raw : snippet.split("\n", -1)) {
            String collapsed = raw.replaceAll("\\s+", " ").trim();
            if (!collapsed.isEmpty()) {
                lines.add(collapsed);
            }
        }
        return String.join("\n", lines);
    }

    /** The lowercase-hex SHA-256 of {@link #normalize(String)} — the SAST fingerprint's snippet component. */
    public static String normalizedHash(String snippet) {
        return sha256(normalize(snippet));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
