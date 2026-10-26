package com.jgo.engagement.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary-value validation coverage for the domain records' compact constructors. */
class DomainValidationTest {

    private static final EngagementRef REF = new EngagementRef("firm-1", "eng-1");

    @Test
    void engagementRefRejectsBlankIds() {
        assertThrows(IllegalArgumentException.class, () -> new EngagementRef(" ", "eng-1"));
        assertThrows(IllegalArgumentException.class, () -> new EngagementRef("firm-1", " "));
    }

    @Test
    void headChangeRejectsBlankAndNegativeFields() {
        assertThrows(IllegalArgumentException.class, () -> new HeadChange(" ", "T", "v5", 5));
        assertThrows(IllegalArgumentException.class, () -> new HeadChange("chg-1", " ", "v5", 5));
        assertThrows(IllegalArgumentException.class, () -> new HeadChange("chg-1", "T", " ", 5));
        assertThrows(IllegalArgumentException.class, () -> new HeadChange("chg-1", "T", "v5", -1));
    }

    @Test
    void pendingMarkerRejectsBlankAndNegativeFields() {
        Instant now = Instant.now();
        assertThrows(IllegalArgumentException.class, () ->
                new PendingMarker(REF, " ", "v3", "v5", "s-1", 5, now));
        assertThrows(IllegalArgumentException.class, () ->
                new PendingMarker(REF, "T", " ", "v5", "s-1", 5, now));
        assertThrows(IllegalArgumentException.class, () ->
                new PendingMarker(REF, "T", "v3", " ", "s-1", 5, now));
        assertThrows(IllegalArgumentException.class, () ->
                new PendingMarker(REF, "T", "v3", "v5", " ", 5, now));
        assertThrows(IllegalArgumentException.class, () ->
                new PendingMarker(REF, "T", "v3", "v5", "s-1", -1, now));
    }
}
