package com.jgo.engagement.application.service;

import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.PendingMarker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryPendingMarkerStore implements PendingMarkerPort {
    private final Map<Key, PendingMarker> rows = new ConcurrentHashMap<>();
    private final Map<Key, Long> fences = new ConcurrentHashMap<>();

    @Override
    public synchronized WriteResult upsertIfNotOlder(PendingMarker marker) {
        Key key = Key.of(marker);
        long effective = effectiveSeq(key);
        if (marker.evaluatedAtSeq() < effective) {
            return WriteResult.STALE_IGNORED;
        }
        rows.put(key, marker);
        fences.put(key, marker.evaluatedAtSeq());
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized WriteResult clearIfNotOlder(EngagementRef engagement, String templateId, long catalogSeq) {
        Key key = Key.of(engagement, templateId);
        if (catalogSeq < effectiveSeq(key)) {
            return WriteResult.STALE_IGNORED;
        }
        rows.remove(key);
        fences.put(key, catalogSeq);
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized WriteResult upsertIfNewer(PendingMarker marker) {
        Key key = Key.of(marker);
        if (marker.evaluatedAtSeq() <= effectiveSeq(key)) {
            return WriteResult.STALE_IGNORED;
        }
        rows.put(key, marker);
        fences.put(key, marker.evaluatedAtSeq());
        return WriteResult.APPLIED;
    }

    @Override
    public synchronized WriteResult deleteIfNewer(EngagementRef engagement, String templateId, long catalogSeq) {
        Key key = Key.of(engagement, templateId);
        if (catalogSeq <= effectiveSeq(key)) {
            return WriteResult.STALE_IGNORED;
        }
        rows.remove(key);
        fences.put(key, catalogSeq);
        return WriteResult.APPLIED;
    }

    synchronized void preload(PendingMarker marker) {
        Key key = Key.of(marker);
        rows.put(key, marker);
        fences.merge(key, marker.evaluatedAtSeq(), Math::max);
    }

    synchronized PendingMarker get(String firmId, String engagementId, String templateId) {
        return rows.get(new Key(firmId, engagementId, templateId));
    }

    synchronized long fence(String firmId, String engagementId, String templateId) {
        return fences.getOrDefault(new Key(firmId, engagementId, templateId), -1L);
    }

    synchronized int size() {
        return rows.size();
    }

    synchronized List<PendingMarker> all() {
        return List.copyOf(rows.values());
    }

    private long effectiveSeq(Key key) {
        long fence = fences.getOrDefault(key, -1L);
        PendingMarker row = rows.get(key);
        return row == null ? fence : Math.max(fence, row.evaluatedAtSeq());
    }

    private record Key(String firmId, String engagementId, String templateId) {
        static Key of(PendingMarker marker) {
            return new Key(
                    marker.engagement().firmId(),
                    marker.engagement().engagementId(),
                    marker.templateId());
        }

        static Key of(EngagementRef engagement, String templateId) {
            return new Key(engagement.firmId(), engagement.engagementId(), templateId);
        }
    }
}
