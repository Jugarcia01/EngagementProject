package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.PendingMarkerPort.WriteResult;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.PendingMarker;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryPendingMarkerStoreTest {
    private static final String TEMPLATE = "T";

    private final InMemoryPendingMarkerStore store = new InMemoryPendingMarkerStore();

    @Test
    void upsertIfNewerAppliesOnlyStrictlyNewerSequences() {
        EngagementRef engagement = new EngagementRef("f1", "e1");

        assertEquals(WriteResult.APPLIED, store.upsertIfNewer(marker(engagement, 5)));
        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNewer(marker(engagement, 5)));
        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNewer(marker(engagement, 4)));
        assertEquals(WriteResult.APPLIED, store.upsertIfNewer(marker(engagement, 6)));

        assertEquals(6, store.get("f1", "e1", TEMPLATE).evaluatedAtSeq());
    }

    @Test
    void deleteIfNewerAppliesOnlyStrictlyNewerSequences() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        store.preload(marker(engagement, 5));

        assertEquals(WriteResult.STALE_IGNORED, store.deleteIfNewer(engagement, TEMPLATE, 5));
        assertNotNull(store.get("f1", "e1", TEMPLATE));

        assertEquals(WriteResult.STALE_IGNORED, store.deleteIfNewer(engagement, TEMPLATE, 4));
        assertNotNull(store.get("f1", "e1", TEMPLATE));

        assertEquals(WriteResult.APPLIED, store.deleteIfNewer(engagement, TEMPLATE, 6));
        assertNull(store.get("f1", "e1", TEMPLATE));
    }

    @Test
    void deleteIfNewerClearsAStalePendingRow() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        store.preload(marker(engagement, 5));

        assertEquals(WriteResult.APPLIED, store.deleteIfNewer(engagement, TEMPLATE, 6));

        assertNull(store.get("f1", "e1", TEMPLATE));
        assertEquals(6, store.fence("f1", "e1", TEMPLATE));
    }

    @Test
    void lateOlderUpsertAfterDeleteDoesNotResurrectTheRow() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        store.preload(marker(engagement, 5));
        assertEquals(WriteResult.APPLIED, store.deleteIfNewer(engagement, TEMPLATE, 6));

        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNewer(marker(engagement, 6)));
        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNewer(marker(engagement, 5)));

        assertNull(store.get("f1", "e1", TEMPLATE));
        assertEquals(6, store.fence("f1", "e1", TEMPLATE));

        assertEquals(WriteResult.APPLIED, store.upsertIfNewer(marker(engagement, 7)));
        assertNotNull(store.get("f1", "e1", TEMPLATE));
        assertEquals(7, store.get("f1", "e1", TEMPLATE).evaluatedAtSeq());
    }

    @Test
    void deleteWithNoRowAndNoFenceRecordsFence() {
        EngagementRef engagement = new EngagementRef("f1", "e1");

        assertEquals(WriteResult.APPLIED, store.deleteIfNewer(engagement, TEMPLATE, 5));

        assertNull(store.get("f1", "e1", TEMPLATE));
        assertEquals(5, store.fence("f1", "e1", TEMPLATE));
        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNewer(marker(engagement, 5)));
    }

    @Test
    void upsertIfNotOlderAllowsEqualSequences() {
        EngagementRef engagement = new EngagementRef("f1", "e1");

        assertEquals(WriteResult.APPLIED, store.upsertIfNotOlder(marker(engagement, 5)));
        assertEquals(WriteResult.APPLIED, store.upsertIfNotOlder(marker(engagement, 5)));
        assertEquals(WriteResult.STALE_IGNORED, store.upsertIfNotOlder(marker(engagement, 4)));
    }

    @Test
    void clearIfNotOlderAllowsEqualSequences() {
        EngagementRef engagement = new EngagementRef("f1", "e1");
        store.preload(marker(engagement, 5));

        assertEquals(WriteResult.STALE_IGNORED, store.clearIfNotOlder(engagement, TEMPLATE, 4));
        assertEquals(WriteResult.APPLIED, store.clearIfNotOlder(engagement, TEMPLATE, 5));
        assertNull(store.get("f1", "e1", TEMPLATE));
        assertEquals(5, store.fence("f1", "e1", TEMPLATE));
    }

    @Test
    void allReturnsSnapshotOfRows() {
        store.preload(marker(new EngagementRef("f1", "e1"), 1));
        store.preload(marker(new EngagementRef("f1", "e2"), 1));

        assertEquals(2, store.all().size());
        assertEquals(2, store.size());
    }

    private static PendingMarker marker(EngagementRef engagement, long seq) {
        return new PendingMarker(
                engagement,
                TEMPLATE,
                "v3",
                "v5",
                "summary-v3-v5",
                seq,
                Instant.EPOCH);
    }
}
