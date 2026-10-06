package com.securebank.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PaginationTest {

    @Test
    void clampsPageAndSizeToSafeBounds() {
        assertEquals(PageRequest.of(0, 1), Pagination.of(-1, 0));
        assertEquals(PageRequest.of(2, Pagination.MAX_PAGE_SIZE), Pagination.of(2, 500));
    }

    @Test
    void preservesRequestedSort() {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");

        assertEquals(sort, Pagination.of(1, 20, sort).getSort());
    }
}