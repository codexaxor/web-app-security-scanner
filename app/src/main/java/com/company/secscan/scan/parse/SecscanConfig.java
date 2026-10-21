package com.company.secscan.scan.parse;

import java.util.List;
import java.util.Map;

/**
 * The parsed, bounded {@code .secscan.yml} the scanner container read as data and emitted in its manifest
 * (M7, D-057). The worker applies it at triage step 9: {@link #excludePaths()} → EXCLUDED_PATH,
 * {@link #ignoreCves()} (CVE → repo-authored reason) → IGNORE_RULE (materialized system rules),
 * {@link #includeDevDependencies()} feeds the D-059 dev-deps precedence.
 *
 * <p>{@link #present()} is false when the repo has no config. {@link #status()} is {@code "ok"} for an absent,
 * empty or valid config; any other value means a config was PRESENT but could not be safely read/parsed, so the
 * worker FREEZES the prior config-derived triage state rather than treating it as empty ({@link #frozen()},
 * review S3) — a transient refusal or typo must never silently un-suppress every {@code .secscan.yml} ignore.
 * The scanner has already whitelisted and size-capped every field; this record is just the typed carrier.
 */
public record SecscanConfig(boolean present, List<String> excludePaths, Map<String, String> ignoreCves,
                            Boolean includeDevDependencies, String status) {

    public static final String STATUS_OK = "ok";

    public SecscanConfig {
        excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
        ignoreCves = ignoreCves == null ? Map.of() : Map.copyOf(ignoreCves);
        status = status == null ? STATUS_OK : status;
    }

    /** Back-compatible: a valid config (status {@code "ok"}) for existing callers/tests. */
    public SecscanConfig(boolean present, List<String> excludePaths, Map<String, String> ignoreCves,
                         Boolean includeDevDependencies) {
        this(present, excludePaths, ignoreCves, includeDevDependencies, STATUS_OK);
    }

    /** No in-repo config (the default for every existing caller/test). */
    public static SecscanConfig absent() {
        return new SecscanConfig(false, List.of(), Map.of(), null, STATUS_OK);
    }

    /** True when a config was present but unusable → the worker freezes prior config-derived triage (S3). */
    public boolean frozen() {
        return !STATUS_OK.equals(status);
    }
}
