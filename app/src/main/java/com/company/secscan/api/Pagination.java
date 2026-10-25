package com.company.secscan.api;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * One place that turns a request's {@code page}/{@code size} into a {@link PageRequest} for the M8 list
 * endpoints. {@code page} is clamped to {@code >= 0} and {@code size} to {@code [1, MAX_SIZE]} so a client
 * cannot ask the server to materialize an unbounded page (N-5). The cap is documented as {@link #MAX_SIZE}.
 */
public final class Pagination {

    /** The largest page size any list endpoint will honour; a larger request is clamped down to this. */
    public static final int MAX_SIZE = 200;

    private Pagination() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(clampPage(page), clampSize(size));
    }

    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(clampPage(page), clampSize(size), sort);
    }

    private static int clampPage(int page) {
        return Math.max(0, page);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(1, size), MAX_SIZE);
    }
}
