package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.TemplateState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local-profile in-memory {@link EngagementIndexPort}. Page tokens are string offsets
 * into the filtered row list; a null/blank token starts the scan, a null
 * {@code nextPageToken} on the last row ends it.
 */
public final class InMemoryEngagementIndex implements EngagementIndexPort {
    private final Map<String, List<Entry>> rowsByFirm = new LinkedHashMap<>();

    /** Adds or replaces one indexed engagement row (insertion order preserved). */
    public synchronized void add(EngagementRef engagement, TemplateState state) {
        List<Entry> rows = rowsByFirm.computeIfAbsent(engagement.firmId(), ignored -> new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).engagement().equals(engagement)) {
                rows.set(i, new Entry(engagement, state));
                return;
            }
        }
        rows.add(new Entry(engagement, state));
    }

    @Override
    public synchronized List<String> firmsWithEngagements(String templateId) {
        List<String> firms = new ArrayList<>();
        for (Map.Entry<String, List<Entry>> entry : rowsByFirm.entrySet()) {
            boolean hasRow = entry.getValue().stream()
                    .anyMatch(row -> row.state().templateId().equals(templateId));
            if (hasRow) {
                firms.add(entry.getKey());
            }
        }
        return firms;
    }

    @Override
    public List<IndexedEngagement> findByTemplate(
            String firmId, String templateId, String pageToken, int pageSize) {
        List<Entry> matching = matchingRows(firmId, templateId);
        int from = offsetOf(pageToken);
        if (from >= matching.size()) {
            return List.of();
        }

        int to = Math.min(matching.size(), from + Math.max(1, pageSize));
        String nextToken = to < matching.size() ? String.valueOf(to) : null;
        List<IndexedEngagement> page = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            Entry row = matching.get(i);
            page.add(new IndexedEngagement(row.engagement(), row.state(), nextToken));
        }
        return page;
    }

    @Override
    public synchronized void recordVerified(EngagementRef engagement, TemplateState state) {
        List<Entry> rows = rowsByFirm.get(engagement.firmId());
        if (rows == null) {
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).engagement().equals(engagement)) {
                rows.set(i, new Entry(engagement, state));
                return;
            }
        }
    }

    public synchronized int size() {
        return rowsByFirm.values().stream().mapToInt(List::size).sum();
    }

    private synchronized List<Entry> matchingRows(String firmId, String templateId) {
        List<Entry> rows = rowsByFirm.getOrDefault(firmId, List.of());
        List<Entry> matching = new ArrayList<>(rows.size());
        for (Entry row : rows) {
            if (row.state().templateId().equals(templateId)) {
                matching.add(row);
            }
        }
        return matching;
    }

    private static int offsetOf(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(pageToken.trim()));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private record Entry(EngagementRef engagement, TemplateState state) {
    }
}
