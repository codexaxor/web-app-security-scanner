package com.company.secscan.source.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.secscan.source.SourcePage;
import com.company.secscan.source.github.dto.GitHubBranch;
import com.company.secscan.source.github.dto.GitHubRepo;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * M8.8: the GitHub branch/metadata cache is a bounded, access-ordered LRU — it never grows without limit.
 * M8.10 item 7: it is keyed per workspace, so one tenant is never served another tenant's cached entry.
 */
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

    @Test
    void sameConnectionAndRepoInTwoWorkspacesNeverShareACacheEntry() {
        GitHubClient client = mock(GitHubClient.class);
        when(client.repoById(anyString(), isNull(), anyLong())).thenReturn(
                new GitHubRepo(42L, "acme/app", "main", false, "public", false, false, 10L));
        when(client.listBranches(anyString(), isNull(), anyString(), anyInt(), anyInt()))
                .thenReturn(new SourcePage<>(List.of(new GitHubBranch("main", false)), 1, 1));
        GitHubProvider provider = new GitHubProvider(client);
        UUID conn = UUID.randomUUID();
        String scopeA = UUID.randomUUID() + "|" + conn;   // RepositoryService: workspaceId|connectionId
        String scopeB = UUID.randomUUID() + "|" + conn;

        provider.listBranches("https://api.github.com", null, "42", null, 0, 20, scopeA);
        provider.listBranches("https://api.github.com", null, "42", null, 0, 20, scopeA);   // A: cache hit
        verify(client, times(1)).repoById(anyString(), isNull(), anyLong());

        provider.listBranches("https://api.github.com", null, "42", null, 0, 20, scopeB);   // B: own fetch
        verify(client, times(2)).repoById(anyString(), isNull(), anyLong());
    }
}
