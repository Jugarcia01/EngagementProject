package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Local-profile {@link DeadLetterPort}: logs each dead-lettered engagement and keeps
 * a small in-memory record so manual runs and tests can inspect what was dropped.
 */
public final class LoggingDeadLetterAdapter implements DeadLetterPort {
    private static final Logger LOG = LoggerFactory.getLogger(LoggingDeadLetterAdapter.class);
    private static final int MAX_RETAINED = 1_000;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void publish(HeadChange change, EngagementRef engagement, String failureCode, String safeMessage) {
        LOG.warn(
                "fanout.dlq changeId={} firmId={} engagementId={} failureCode={} safeMessage={}",
                change.changeId(),
                engagement.firmId(),
                engagement.engagementId(),
                failureCode,
                safeMessage);
        if (entries.size() < MAX_RETAINED) {
            entries.add(new Entry(change.changeId(), engagement, failureCode, safeMessage));
        }
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public record Entry(String changeId, EngagementRef engagement, String failureCode, String safeMessage) {
    }
}
