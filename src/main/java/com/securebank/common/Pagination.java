package com.securebank.common;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

public final class Pagination {

    public static final int MAX_PAGE_SIZE = 100;

    private Pagination() {
    }

    /** Builds a page request, clamping client-supplied values into a safe range. */
    public static PageRequest of(int page, int size, Sort sort) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE), sort);
    }

    public static PageRequest of(int page, int size) {
        return of(page, size, Sort.unsorted());
    }
}
