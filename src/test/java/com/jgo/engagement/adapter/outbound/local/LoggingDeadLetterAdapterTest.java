package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoggingDeadLetterAdapterTest {
    private final LoggingDeadLetterAdapter adapter = new LoggingDeadLetterAdapter();

    @Test
    void publishRetainsEntriesForInspection() {
        HeadChange change = new HeadChange("c1", "T", "v5", 100);
        EngagementRef engagement = new EngagementRef("f1", "e1");

        assertDoesNotThrow(() -> adapter.publish(change, engagement, "PERMANENT", "boom"));

        assertEquals(1, adapter.entries().size());
        LoggingDeadLetterAdapter.Entry entry = adapter.entries().getFirst();
        assertEquals("c1", entry.changeId());
        assertEquals(engagement, entry.engagement());
        assertEquals("PERMANENT", entry.failureCode());
        assertEquals("boom", entry.safeMessage());
    }
}
