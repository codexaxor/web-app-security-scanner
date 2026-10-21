package com.company.secscan.issue;

import com.company.secscan.scan.parse.SecscanConfig;
import java.util.Map;

/**
 * The scan-time inputs the triage step applies beyond the stored findings (M7): the parsed {@code .secscan.yml}
 * ({@link #config}), the declared dev/test scope of dependencies read by our own inventory
 * ({@link #declaredScopes}: {@code coordinate -> "test"|"dev"}, D-059), and the effective include-dev-deps for
 * this scan ({@link #effectiveIncludeDev} = {@code repository.include_dev_deps OR .secscan.yml
 * include_dev_dependencies} — the file may only INCREASE visibility, review change #3).
 *
 * <p>None of these are fingerprint inputs; they are triage attributes recomputed every scan.
 */
public record TriageContext(SecscanConfig config, Map<String, String> declaredScopes, boolean effectiveIncludeDev) {

    private static final TriageContext EMPTY = new TriageContext(SecscanConfig.absent(), Map.of(), false);

    public TriageContext {
        config = config == null ? SecscanConfig.absent() : config;
        declaredScopes = declaredScopes == null ? Map.of() : Map.copyOf(declaredScopes);
    }

    /** No config, no declared scopes, dev deps not included (existing callers/tests). */
    public static TriageContext empty() {
        return EMPTY;
    }

    /** The declared scope of a dependency coordinate ({@code "test"}/{@code "dev"}), or null when unknown. */
    public String scopeOf(String coordinate) {
        return coordinate == null ? null : declaredScopes.get(coordinate);
    }
}
