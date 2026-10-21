package com.company.secscan.triage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

/**
 * A curated map {@code {stock rule id -> winning custom rule id}} for duplicate-SAST-rule auto-triage (D-061).
 * When both the stock (loser) and its winner fire for the SAME {@code file_path} in the SAME scan, the loser is
 * auto-ignored as {@link ReasonCode#DUPLICATE_RULE} and the winner stays open. No cross-rule merge, no
 * fingerprint change.
 *
 * <p>Pure, no Spring. The default seed is loaded from {@code duplicate-sast-rules.properties} on the classpath;
 * every id there is captured from real Opengrep output, never guessed. A unit test can build an instance from an
 * explicit map instead.
 */
public final class DuplicateRuleMap {

    private static final String RESOURCE = "/duplicate-sast-rules.properties";
    private static final DuplicateRuleMap DEFAULT = new DuplicateRuleMap(loadDefaults());

    /** stock (loser) rule id -> winning rule id. */
    private final Map<String, String> stockToWinner;

    public DuplicateRuleMap(Map<String, String> stockToWinner) {
        this.stockToWinner = Map.copyOf(stockToWinner);
    }

    /** The default curated map, seeded from {@code duplicate-sast-rules.properties} (D-061). */
    public static DuplicateRuleMap defaults() {
        return DEFAULT;
    }

    /** The winning rule id for a stock loser rule id, if the loser is in the map. */
    public Optional<String> winnerFor(String stockRuleId) {
        return Optional.ofNullable(stockToWinner.get(stockRuleId));
    }

    /** True when {@code ruleId} is a configured stock loser (its winner may or may not be present). */
    public boolean isConfiguredLoser(String ruleId) {
        return stockToWinner.containsKey(ruleId);
    }

    /**
     * True when {@code ruleId} is a stock loser AND its winner is among {@code ruleIdsInSameFile} — i.e. this
     * finding should be auto-ignored as a duplicate. {@code ruleIdsInSameFile} is the set of SAST rule ids that
     * fired for the finding's {@code file_path} in this scan.
     */
    public boolean shouldIgnore(String ruleId, Collection<String> ruleIdsInSameFile) {
        String winner = stockToWinner.get(ruleId);
        return winner != null && ruleIdsInSameFile != null && ruleIdsInSameFile.contains(winner);
    }

    /** The configured pairs, sorted by stock id — for diagnostics/reporting. */
    public Map<String, String> entries() {
        return new TreeMap<>(stockToWinner);
    }

    private static Map<String, String> loadDefaults() {
        Properties props = new Properties();
        try (InputStream in = DuplicateRuleMap.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return Map.of();
            }
            props.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, e);
        }
        Map<String, String> map = new TreeMap<>();
        for (String stock : props.stringPropertyNames()) {
            String winner = props.getProperty(stock).trim();
            if (!stock.isBlank() && !winner.isBlank()) {
                map.put(stock.trim(), winner);
            }
        }
        return map;
    }
}
