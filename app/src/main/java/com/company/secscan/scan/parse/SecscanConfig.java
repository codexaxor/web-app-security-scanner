package com.company.secscan.scan.parse;

import java.util.List;
import java.util.Map;

/**
 * The parsed, bounded {@code .secscan.yml} the scanner container read as data and emitted in its manifest
 * (M7, D-057). The worker applies it at triage step 9: {@link #excludePaths()} → EXCLUDED_PATH,
 * {@link #ignoreCves()} (CVE → repo-authored reason) → IGNORE_RULE (materialized system rules),
 * {@link #includeDevDependencies()} feeds the D-059 dev-deps precedence.
 *
 * <p>{@link #present()} is false when the repo has no config (or the reader refused it). The scanner has already
 * whitelisted and size-capped every field; this record is just the typed carrier.
 */
public record SecscanConfig(boolean present, List<String> excludePaths, Map<String, String> ignoreCves,
                            Boolean includeDevDependencies) {

    public SecscanConfig {
        excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
        ignoreCves = ignoreCves == null ? Map.of() : Map.copyOf(ignoreCves);
    }

    /** No in-repo config (the default for every existing caller/test). */
    public static SecscanConfig absent() {
        return new SecscanConfig(false, List.of(), Map.of(), null);
    }
}
