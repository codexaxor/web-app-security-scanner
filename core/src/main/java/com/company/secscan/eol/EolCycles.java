package com.company.secscan.eol;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Normalizes a detected release cycle to the cycle KEY that endoflife.date actually publishes for that product
 * (D-055; M6 review BUG 2). Different products key cycles differently — .NET as bare majors ("8"), Spring Boot /
 * Alpine as major.minor ("2.1", "3.21") — so a detected "8.0" must resolve to "8" while "2.1" stays "2.1". We do
 * this GENERICALLY (no per-product rules): match the detected version against the product's PUBLISHED cycles and
 * pick the most specific (longest) cycle whose numeric components are a prefix of the detected version's. No
 * published cycle matches &rarr; {@code null} (the caller treats it as an uncovered coverage gap).
 *
 * <p>Matching is component-wise (not string prefix), so "2.1" never spuriously matches "2.10". Pure and
 * deterministic — the normalized cycle feeds the FROZEN EOL fingerprint ({@code product | cycle | file_path}),
 * so a product whose detected cycle already equals its published key (Spring Boot, Alpine) is unchanged.
 */
public final class EolCycles {

    private EolCycles() {
    }

    /** The published cycle key for {@code detected}, or null when none of {@code publishedCycles} matches. */
    public static String match(String detected, Collection<String> publishedCycles) {
        List<Integer> d = components(detected);
        if (d.isEmpty() || publishedCycles == null) {
            return null;
        }
        String best = null;
        int bestLen = 0;
        for (String cycle : publishedCycles) {
            List<Integer> c = components(cycle);
            if (!c.isEmpty() && isPrefix(c, d) && c.size() > bestLen) {
                best = cycle;
                bestLen = c.size();
            }
        }
        return best;
    }

    /** Leading numeric dotted components of a version/cycle: "net8.0"→[8,0], "2.1.0.RELEASE"→[2,1,0], "3.21.2"→[3,21,2]. */
    static List<Integer> components(String v) {
        List<Integer> out = new ArrayList<>();
        if (v == null) {
            return out;
        }
        int start = 0;
        while (start < v.length() && !Character.isDigit(v.charAt(start))) {
            start++; // skip a leading non-numeric prefix like "net" / "v"
        }
        for (String token : v.substring(start).split("\\.")) {
            int j = 0;
            while (j < token.length() && Character.isDigit(token.charAt(j))) {
                j++;
            }
            if (j == 0) {
                break; // first non-numeric token (e.g. "RELEASE") ends the version
            }
            // Guard against an overflowing/absurd component (e.g. a 20-digit version): treat the whole version
            // as unparseable rather than let Integer.parseInt throw and turn the scan into a permanent error
            // that hides SCA/secret/SAST/IaC (B2). An unparseable version → empty list → no match →
            // EOL_COVERAGE_GAP (safe). Real cycle components (8, 21, 3.4, …) are ≤ 9 digits and unaffected.
            if (j > 9) {
                return new ArrayList<>();
            }
            out.add(Integer.parseInt(token.substring(0, j)));
        }
        return out;
    }

    private static boolean isPrefix(List<Integer> cycle, List<Integer> detected) {
        if (cycle.size() > detected.size()) {
            return false;
        }
        for (int i = 0; i < cycle.size(); i++) {
            if (!cycle.get(i).equals(detected.get(i))) {
                return false;
            }
        }
        return true;
    }
}
