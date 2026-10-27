package com.company.secscan.source;

import java.util.List;

/** One page of a provider list call, with the totals the provider reported (for server-side pagination). */
public record SourcePage<T>(List<T> items, long totalElements, int totalPages) {

    public SourcePage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
