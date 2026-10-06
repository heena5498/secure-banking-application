package com.securebank.common;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PageResponseTest {

    @Test
    void mapsSpringPageMetadataAndContent() {
        List<String> content = new ArrayList<>();
        content.add("one");
        content.add("two");
        PageImpl<String> page = new PageImpl<>(content, PageRequest.of(1, 2), 5);

        PageResponse<String> response = PageResponse.from(page);

        assertEquals(List.of("one", "two"), response.content());
        assertEquals(1, response.page());
        assertEquals(2, response.size());
        assertEquals(5, response.totalElements());
        assertEquals(3, response.totalPages());
    }
}