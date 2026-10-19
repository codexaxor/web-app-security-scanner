package com.company.secscan.scoring;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Resolves a CWE id to a short plain-language phrase for the issue-group subtitle (SPEC "Grouping"; D-048). The
 * lookup is a maintained, seeded resource ({@code cwe-phrases.properties}) rather than a DB table, so it is pure
 * and unit-testable without Spring, and loads once.
 *
 * <p>For a finding carrying several {@code cwe_ids} we take the <strong>first</strong> id in OSV array order
 * (CWE "worst" is undefined — the feed's order is the only meaningful one). When the list is empty or the first
 * id has no seeded phrase, the caller's advisory-summary fallback is used (unknown CWEs → summary, D-048).
 */
public final class CweDictionary {

    private static final CweDictionary INSTANCE = load("/cwe-phrases.properties");

    private final Map<String, String> phrases;

    CweDictionary(Map<String, String> phrases) {
        this.phrases = Map.copyOf(phrases);
    }

    public static CweDictionary getInstance() {
        return INSTANCE;
    }

    /** The seeded phrase for a single CWE id (e.g. {@code CWE-94}), or null when not in the dictionary. */
    public String lookup(String cweId) {
        if (cweId == null) {
            return null;
        }
        return phrases.get(cweId.trim().toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * The subtitle phrase for a finding's {@code cweIds}: the phrase of the FIRST id (OSV array order). Falls
     * back to {@code summaryFallback} (which may be null) when the list is empty or the first id is unknown.
     */
    public String phrase(List<String> cweIds, String summaryFallback) {
        if (cweIds != null && !cweIds.isEmpty()) {
            String p = lookup(cweIds.get(0));
            if (p != null) {
                return p;
            }
        }
        return summaryFallback;
    }

    /** The number of seeded CWE phrases (for the coverage test). */
    public int size() {
        return phrases.size();
    }

    private static CweDictionary load(String resource) {
        Properties props = new Properties();
        try (InputStream in = CweDictionary.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing CWE phrase resource: " + resource);
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load " + resource, e);
        }
        Map<String, String> map = new TreeMap<>();
        for (String name : props.stringPropertyNames()) {
            map.put(name.trim().toUpperCase(java.util.Locale.ROOT), props.getProperty(name).trim());
        }
        return new CweDictionary(map);
    }
}
