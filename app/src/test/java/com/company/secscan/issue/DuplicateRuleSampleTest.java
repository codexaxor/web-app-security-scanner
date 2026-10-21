package com.company.secscan.issue;

import static org.assertj.core.api.Assertions.assertThat;

import com.company.secscan.triage.DuplicateRuleMap;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the curated duplicate-SAST-rule seed (D-061) pairs the two CSRF ids that REALLY fire on the SAME file
 * in genuine Opengrep output. The sample {@code samples/opengrep/real-csrf-duplicate.json} is a sanitized real
 * Opengrep report (pinned secscan-scanner:8.21.2 image, full {@code opengrep-rules} config over
 * {@code fixtures/sast-custom/positive}): both the stock {@code opengrep-rules.java.spring.security.audit}
 * rule and our custom rule fired on {@code SecurityConfig.java}'s {@code http.csrf().disable()}.
 *
 * <p>Lives in the app module because that is where the sample resource lives; app depends on core, so it can
 * assert against {@link DuplicateRuleMap#defaults()} directly.
 */
class DuplicateRuleSampleTest {

    private static final String SAMPLE = "/samples/opengrep/real-csrf-duplicate.json";
    private static final String STOCK_CSRF = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String CUSTOM_CSRF = "opengrep-rules.custom.spring-csrf-disabled";

    @Test
    void defaultsPairTheRealCsrfIdsPresentInTheOpengrepSample() throws Exception {
        JsonNode root;
        try (InputStream in = DuplicateRuleSampleTest.class.getResourceAsStream(SAMPLE)) {
            assertThat(in).as("sample resource %s is on the classpath", SAMPLE).isNotNull();
            root = new ObjectMapper().readTree(in);
        }

        // The distinct check_ids in the real sample, and the distinct paths each csrf id fired on.
        Set<String> checkIds = new LinkedHashSet<>();
        Set<String> stockPaths = new LinkedHashSet<>();
        Set<String> customPaths = new LinkedHashSet<>();
        for (JsonNode result : root.get("results")) {
            String checkId = result.get("check_id").asText();
            checkIds.add(checkId);
            String path = result.get("path").asText();
            if (STOCK_CSRF.equals(checkId)) {
                stockPaths.add(path);
            } else if (CUSTOM_CSRF.equals(checkId)) {
                customPaths.add(path);
            }
        }

        // Both target ids are genuinely present in the real Opengrep output...
        assertThat(checkIds)
                .as("both the stock loser and the custom winner fired in the real sample")
                .contains(STOCK_CSRF, CUSTOM_CSRF);
        // ...and they fired on the SAME file (the precondition for the D-061 duplicate-rule auto-ignore).
        assertThat(stockPaths).isNotEmpty();
        assertThat(customPaths).containsAll(stockPaths);

        // The curated default map maps the stock loser to the custom winner for exactly those ids.
        DuplicateRuleMap map = DuplicateRuleMap.defaults();
        assertThat(map.isConfiguredLoser(STOCK_CSRF)).isTrue();
        assertThat(map.winnerFor(STOCK_CSRF)).contains(CUSTOM_CSRF);
        // When both fire on the same file, the stock loser is the one that should be auto-ignored.
        assertThat(map.shouldIgnore(STOCK_CSRF, checkIds)).isTrue();
        assertThat(map.shouldIgnore(CUSTOM_CSRF, checkIds)).as("the winner never loses").isFalse();
    }
}
