package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.WorkLeasePort;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Local-profile in-memory {@link WorkLeasePort} keyed on {@code changeId|engagementId}.
 * Leases carry a real expiry: an expired lease is treated as free and can be taken
 * over by another worker, matching the production lease-ledger semantics.
 */
public final class InMemoryWorkLease implements WorkLeasePort {
    private final Map<String, Instant> leaseExpirations = new ConcurrentHashMap<>();
    private final Set<String> completed = ConcurrentHashMap.newKeySet();

    @Override
    public synchronized LeaseResult tryAcquire(String changeId, String engagementId, Duration leaseDuration) {
        String key = key(changeId, engagementId);
        if (completed.contains(key)) {
            return LeaseResult.ALREADY_COMPLETED;
        }

        Instant expiresAt = leaseExpirations.get(key);
        if (expiresAt != null && expiresAt.isAfter(Instant.now())) {
            return LeaseResult.HELD_BY_OTHER_WORKER;
        }
        leaseExpirations.put(key, Instant.now().plus(leaseDuration));
        return LeaseResult.ACQUIRED;
    }

    @Override
    public synchronized void markCompleted(String changeId, String engagementId) {
        String key = key(changeId, engagementId);
        completed.add(key);
        leaseExpirations.remove(key);
    }

    @Override
    public synchronized void releaseForRetry(String changeId, String engagementId) {
        leaseExpirations.remove(key(changeId, engagementId));
    }

    public synchronized boolean isCompleted(String changeId, String engagementId) {
        return completed.contains(key(changeId, engagementId));
    }

    public synchronized int activeLeaseCount() {
        Instant now = Instant.now();
        int count = 0;
        for (Instant expiresAt : leaseExpirations.values()) {
            if (expiresAt.isAfter(now)) {
                count++;
            }
        }
        return count;
    }

    private static String key(String changeId, String engagementId) {
        return changeId + "|" + engagementId;
    }
}
