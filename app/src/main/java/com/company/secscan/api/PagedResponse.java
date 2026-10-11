package com.company.secscan.api;

import java.util.List;
import org.springframework.data.domain.Page;

/** Server-side pagination envelope for every list endpoint (CLAUDE.md: paginate every list endpoint). */
public record PagedResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PagedResponse<T> fromList(List<T> all, int page, int size) {
        int safeSize = Math.max(1, size);
        int from = Math.min(Math.max(0, page) * safeSize, all.size());
        int to = Math.min(from + safeSize, all.size());
        int totalPages = (int) Math.ceil((double) all.size() / safeSize);
        return new PagedResponse<>(List.copyOf(all.subList(from, to)), page, safeSize, all.size(), totalPages);
    }

    public static <T> PagedResponse<T> fromPage(Page<T> p) {
        return new PagedResponse<>(p.getContent(), p.getNumber(), p.getSize(), p.getTotalElements(),
                p.getTotalPages());
    }
}
