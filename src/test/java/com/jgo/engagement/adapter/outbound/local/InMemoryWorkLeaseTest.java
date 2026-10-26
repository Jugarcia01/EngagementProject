package com.jgo.engagement.adapter.outbound.local;

import com.jgo.engagement.application.port.out.WorkLeasePort.LeaseResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryWorkLeaseTest {
    private final InMemoryWorkLease leases = new InMemoryWorkLease();

    @Test
    void secondAcquireOnLiveLeaseIsHeldByOther() {
        assertEquals(LeaseResult.ACQUIRED, leases.tryAcquire("c1", "e1", Duration.ofMinutes(5)));
        assertEquals(LeaseResult.HELD_BY_OTHER_WORKER, leases.tryAcquire("c1", "e1", Duration.ofMinutes(5)));
        assertEquals(1, leases.activeLeaseCount());
    }

    @Test
    void markCompletedBlocksFutureAcquires() {
        leases.tryAcquire("c1", "e1", Duration.ofMinutes(5));
        leases.markCompleted("c1", "e1");

        assertEquals(LeaseResult.ALREADY_COMPLETED, leases.tryAcquire("c1", "e1", Duration.ofMinutes(5)));
        assertTrue(leases.isCompleted("c1", "e1"));
        assertEquals(0, leases.activeLeaseCount());
    }

    @Test
    void releaseForRetryFreesTheLease() {
        leases.tryAcquire("c1", "e1", Duration.ofMinutes(5));
        leases.releaseForRetry("c1", "e1");

        assertEquals(LeaseResult.ACQUIRED, leases.tryAcquire("c1", "e1", Duration.ofMinutes(5)));
        assertFalse(leases.isCompleted("c1", "e1"));
    }

    @Test
    void expiredLeaseCanBeTakenOver() {
        assertEquals(LeaseResult.ACQUIRED, leases.tryAcquire("c1", "e1", Duration.ofNanos(1)));

        // Wait for the lease to lapse, then a competing worker must win it.
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        LeaseResult result;
        do {
            result = leases.tryAcquire("c1", "e1", Duration.ofMinutes(5));
        } while (result == LeaseResult.HELD_BY_OTHER_WORKER && System.nanoTime() < deadline);

        assertEquals(LeaseResult.ACQUIRED, result);
    }

    @Test
    void keysAreScopedPerChangeAndEngagement() {
        leases.tryAcquire("c1", "e1", Duration.ofMinutes(5));

        assertEquals(LeaseResult.ACQUIRED, leases.tryAcquire("c2", "e1", Duration.ofMinutes(5)));
        assertEquals(LeaseResult.ACQUIRED, leases.tryAcquire("c1", "e2", Duration.ofMinutes(5)));
    }
}
