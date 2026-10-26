package com.jgo.engagement.adapter.outbound.local;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LocalReleaseCatalogTest {
    private final LocalReleaseCatalog catalog = new LocalReleaseCatalog();

    @Test
    void noHeadsOrSummariesAreStored() {
        assertTrue(catalog.findHead("T").isEmpty());
        assertTrue(catalog.findSummaryId("T", "v3", "v5").isEmpty());
    }

    @Test
    void resolveSummaryIdFallsBackToDeterministicPairId() {
        assertEquals("summary-v3-v5", catalog.resolveSummaryId("T", "v3", "v5"));
    }

    @Test
    void ancestryFollowsLinearVersionNumbers() {
        assertTrue(catalog.isAncestor("T", "v3", "v5"));
        assertTrue(catalog.isAncestor("T", "v3", "v10"));
        assertFalse(catalog.isAncestor("T", "v5", "v3"));
        assertFalse(catalog.isAncestor("T", "v3", "v3"));
    }

    @Test
    void nonNumericVersionsHaveNoAncestry() {
        assertFalse(catalog.isAncestor("T", "v3", "release-5"));
        assertFalse(catalog.isAncestor("T", "abc", "v5"));
        assertFalse(catalog.isAncestor("T", null, "v5"));
        assertFalse(catalog.isAncestor("T", "v99999999999999999999", "v1"));
    }
}
