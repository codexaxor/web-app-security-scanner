package com.company.secscan.source;

import java.util.List;

/**
 * One page of a provider list call, with the totals the provider reported (for server-side pagination).
 * {@code truncated} is true when the result is a bounded view of a larger set — e.g. a GitHub branch search that
 * scanned only the first N branches (M8.8 S5b); it is false for every provider call that returns a complete page.
 */
public record SourcePage<T>(List<T> items, long totalElements, int totalPages, boolean truncated) {

    public SourcePage {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** A complete (non-truncated) page — the common case. */
    public SourcePage(List<T> items, long totalElements, int totalPages) {
        this(items, totalElements, totalPages, false);
    }
}
