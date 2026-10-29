package com.company.secscan.source.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** M8.8: the GitHub branch/metadata cache is a bounded, access-ordered LRU — it never grows without limit. */
class GitHubProviderCacheTest {

    @Test
    void evictsTheLeastRecentlyUsedEntryPastTheBound() {
        Map<String, Integer> cache = GitHubProvider.boundedLru(3);
        cache.put("a", 1);
        cache.put("b", 2);
        cache.put("c", 3);
        cache.get("a"); // touch "a" so "b" becomes the least-recently-used
        cache.put("d", 4); // over the bound → evict the LRU ("b")

        assertThat(cache).hasSize(3);
        assertThat(cache).containsKeys("a", "c", "d");
        assertThat(cache).doesNotContainKey("b");
    }

    @Test
    void neverExceedsTheBoundUnderManyInserts() {
        Map<String, Integer> cache = GitHubProvider.boundedLru(500);
        for (int i = 0; i < 5_000; i++) {
            cache.put("k" + i, i);
        }
        assertThat(cache).hasSize(500);
        assertThat(cache).containsKey("k4999"); // the most recent survive
        assertThat(cache).doesNotContainKey("k0"); // the oldest are gone
    }
}
