package com.company.secscan.issue;

import com.company.secscan.scan.parse.DeclaredScope;
import com.company.secscan.scan.parse.SecscanConfig;
import com.company.secscan.triage.PathExclusion;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The scan-time inputs the triage step applies beyond the stored findings (M7): the parsed {@code .secscan.yml}
 * ({@link #config}), the declared dev/test scope of dependencies read by our own inventory
 * ({@link #declaredScopes}, D-059; review S4), and the effective include-dev-deps for this scan
 * ({@link #effectiveIncludeDev} = {@code repository.include_dev_deps OR .secscan.yml include_dev_dependencies} —
 * the file may only INCREASE visibility, review change #3).
 *
 * <p>The declared scopes are keyed by (manifest path, coordinate), NOT a flat coordinate map (review S4): a
 * dependency is auto-ignored dev/test only when ITS OWN manifest declares it so. A coordinate that is production
 * in any manifest of the repo was already dropped from the inventory in-container.
 *
 * <p>None of these are fingerprint inputs; they are triage attributes recomputed every scan.
 */
public record TriageContext(SecscanConfig config, List<DeclaredScope> declaredScopes, boolean effectiveIncludeDev,
                            boolean epssAutoIgnoreEnabled, boolean scopeInventoryFailed,
                            List<String> unreadableScopeManifests) {

    private static final String SEP = "\u0000";
    private static final TriageContext EMPTY =
            new TriageContext(SecscanConfig.absent(), List.of(), false, false, false, List.of());

    public TriageContext {
        config = config == null ? SecscanConfig.absent() : config;
        declaredScopes = declaredScopes == null ? List.of() : List.copyOf(declaredScopes);
        unreadableScopeManifests = unreadableScopeManifests == null ? List.of()
                : List.copyOf(unreadableScopeManifests);
    }

    /** 5-arg (no per-manifest unreadable set) for callers predating SF1/SF3. */
    public TriageContext(SecscanConfig config, List<DeclaredScope> declaredScopes, boolean effectiveIncludeDev,
                         boolean epssAutoIgnoreEnabled, boolean scopeInventoryFailed) {
        this(config, declaredScopes, effectiveIncludeDev, epssAutoIgnoreEnabled, scopeInventoryFailed, List.of());
    }

    /** 4-arg (scope inventory OK) for the common case. */
    public TriageContext(SecscanConfig config, List<DeclaredScope> declaredScopes, boolean effectiveIncludeDev,
                         boolean epssAutoIgnoreEnabled) {
        this(config, declaredScopes, effectiveIncludeDev, epssAutoIgnoreEnabled, false, List.of());
    }

    /** Back-compatible 3-arg (EPSS toggle off) for callers predating the LOW_EPSS wiring. */
    public TriageContext(SecscanConfig config, List<DeclaredScope> declaredScopes, boolean effectiveIncludeDev) {
        this(config, declaredScopes, effectiveIncludeDev, false, false, List.of());
    }

    /** The repo-relative manifest paths whose scope could not be read this scan (SF3), normalized. */
    public Set<String> unreadableManifestsNormalized() {
        Set<String> out = new HashSet<>();
        for (String m : unreadableScopeManifests) {
            if (m != null) {
                out.add(PathExclusion.normalize(m));
            }
        }
        return out;
    }

    /** No config, no declared scopes, dev deps not included (existing callers/tests). */
    public static TriageContext empty() {
        return EMPTY;
    }

    /**
     * The declared scope ({@code "test"}/{@code "dev"}) of a dependency coordinate IN THE GIVEN MANIFEST, or null
     * when that manifest does not declare it dev/test (review S4 — keyed per manifest, never a flat coordinate
     * map). The manifest path is normalized the same way the SCA finding's file path is.
     */
    public String scopeOf(String manifestPath, String coordinate) {
        if (manifestPath == null || coordinate == null) {
            return null;
        }
        return lookup().get(key(manifestPath, coordinate));
    }

    private Map<String, String> lookup() {
        Map<String, String> out = new HashMap<>();
        for (DeclaredScope s : declaredScopes) {
            if (s.manifestPath() != null && s.coordinate() != null && s.scope() != null) {
                out.put(key(s.manifestPath(), s.coordinate()), s.scope());
            }
        }
        return out;
    }

    private static String key(String manifestPath, String coordinate) {
        return PathExclusion.normalize(manifestPath) + SEP + coordinate;
    }
}
