package com.company.secscan.repository.api;

import java.util.List;

/**
 * The branch-picker response (D-076 / M8.8 S5b): a page of branches plus {@code searchTruncated} — true when a
 * provider search could only scan a bounded prefix of the branches (GitHub has no server-side branch search, so
 * a search beyond ~1,000 branches is truncated). The UI shows a "first N branches" note when it is true.
 */
public record BranchPage(List<BranchView> content, int page, int size, long totalElements, int totalPages,
                         boolean searchTruncated) {

    public static BranchPage empty(int page, int size) {
        return new BranchPage(List.of(), page, size, 0, 0, false);
    }
}
