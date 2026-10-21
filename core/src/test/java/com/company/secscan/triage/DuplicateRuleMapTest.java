package com.company.secscan.triage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DuplicateRuleMapTest {

    private static final String STOCK_CSRF = "opengrep-rules.java.spring.security.audit.spring-csrf-disabled";
    private static final String CUSTOM_CSRF = "opengrep-rules.custom.spring-csrf-disabled";

    @Test
    void defaultsContainTheRealCsrfPairFromOpengrepOutput() {
        DuplicateRuleMap map = DuplicateRuleMap.defaults();
        assertThat(map.isConfiguredLoser(STOCK_CSRF)).isTrue();
        assertThat(map.winnerFor(STOCK_CSRF)).contains(CUSTOM_CSRF);
        assertThat(map.winnerFor(CUSTOM_CSRF)).as("the winner is never itself a loser").isEmpty();
    }

    @Test
    void stockLoserIsIgnoredOnlyWhenItsWinnerIsPresentInTheSameFile() {
        DuplicateRuleMap map = DuplicateRuleMap.defaults();
        assertThat(map.shouldIgnore(STOCK_CSRF, List.of(STOCK_CSRF, CUSTOM_CSRF))).isTrue();
        assertThat(map.shouldIgnore(STOCK_CSRF, List.of(STOCK_CSRF))).as("winner absent").isFalse();
        assertThat(map.shouldIgnore(CUSTOM_CSRF, List.of(STOCK_CSRF, CUSTOM_CSRF))).as("winner never loses").isFalse();
        assertThat(map.shouldIgnore("some.other.rule", List.of("some.other.rule"))).isFalse();
    }

    @Test
    void canBeBuiltFromAnExplicitMapForTesting() {
        DuplicateRuleMap map = new DuplicateRuleMap(Map.of("stock.a", "custom.a"));
        assertThat(map.shouldIgnore("stock.a", List.of("custom.a"))).isTrue();
        assertThat(map.entries()).containsEntry("stock.a", "custom.a");
    }
}
