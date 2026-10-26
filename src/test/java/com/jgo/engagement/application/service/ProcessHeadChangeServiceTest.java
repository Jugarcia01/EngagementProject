package com.jgo.engagement.application.service;

import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.FanOutMetricsPort;
import com.jgo.engagement.application.port.out.ProbeException;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;
import com.jgo.engagement.domain.model.PendingMarker;
import com.jgo.engagement.domain.model.TemplateState;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessHeadChangeServiceTest {
    private static final String TEMPLATE = "T";
    private static final HeadChange V5 = new HeadChange("chg-5", TEMPLATE, "v5", 5);

    @Test
    void concurrencyCapAndCompleteness() {
        FakeEngagementIndex index = new FakeEngagementIndex()
                .add("f1", 20, "a", false)
                .add("f2", 10, "b", false)
                .add("f-empty", 0, "e", false);
        FakeProbe probe = new FakeProbe(60);
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        LinearReleaseCatalog catalog = new LinearReleaseCatalog().nullSummaryFor("v3", "v5");

        service(index, pending, new FakeWorkLeasePort(), probe, new FakeDeadLetterPort(), metrics, settings(4), catalog)
                .process(V5);

        assertTrue(probe.peak() <= 4, "peak downstream concurrency " + probe.peak() + " exceeded cap 4");
        assertTrue(probe.peak() >= 3, "limiter should use the capacity, peak=" + probe.peak());
        assertEquals(30, metrics.completed());
        assertEquals(30, pending.size());
        assertTrue(pending.all().stream().allMatch(marker ->
                TEMPLATE.equals(marker.templateId())
                        && "v3".equals(marker.appliedVersion())
                        && "v5".equals(marker.targetVersion())
                        && "summary-v3-v5".equals(marker.summaryId())));
        assertEquals(30, index.verifiedWrites());
    }

    @Test
    void idempotentRerun() {
        FakeEngagementIndex index = new FakeEngagementIndex()
                .add("f1", 15, "a", false)
                .setApplyVerification(false);
        FakeProbe probe = new FakeProbe(5);
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        FakeWorkLeasePort leases = new FakeWorkLeasePort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        ProcessHeadChangeService service = service(index, pending, leases, probe,
                new FakeDeadLetterPort(), metrics, settings(4));

        service.process(V5);
        assertEquals(15, probe.totalCalls());
        assertEquals(15, metrics.completed());

        service.process(V5);
        assertEquals(15, probe.totalCalls(), "re-run must not hit downstream again");
        assertEquals(15, metrics.skippedClaimed(), "re-run should skip via ledger");
    }

    @Test
    void transientFailureIsRetried() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 5, "a", false);
        FakeProbe probe = new FakeProbe(5);
        probe.transientLeft.put("a-2", new AtomicInteger(2));
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics,
                settings(2, Duration.ofMillis(30), Duration.ofMillis(60)))
                .process(V5);

        assertEquals(5, metrics.completed());
        assertEquals(0, metrics.deadLettered());
        assertEquals(3, probe.callsFor("a-2"));
        assertEquals(2, metrics.retried());
        assertTrue(dlq.entries().isEmpty());
    }

    @Test
    void permanentFailureIsDeadLetteredWithoutBlockingOthers() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 6, "a", false);
        FakeProbe probe = new FakeProbe(5);
        probe.permanent.add("a-1");
        probe.appliedOverride.put("a-0", "v5");
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(3))
                .process(V5);

        assertEquals(1, dlq.size());
        FakeDeadLetterPort.Entry entry = dlq.entries().get(0);
        assertEquals("a-1", entry.engagement().engagementId());
        assertEquals("PERMANENT", entry.failureCode());
        assertEquals(5, metrics.completed());
        assertEquals(1, probe.callsFor("a-1"), "permanent errors must not be retried");
        assertEquals(0, metrics.retried());
    }

    @Test
    void throttledProbesAreRetriedAndAllWorkCompletes() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 30, "a", false);
        FakeProbe probe = new FakeProbe(10);
        probe.throttleBudget.set(4);
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics,
                settings(8, Duration.ofMillis(20), Duration.ofMillis(60)))
                .process(V5);

        assertEquals(30, metrics.completed());
        assertEquals(0, metrics.deadLettered());
        assertTrue(metrics.retried() >= 4, "throttled probes must be retried, retried=" + metrics.retried());
        assertTrue(dlq.entries().isEmpty(), "throttled work must not be lost");
    }

    @Test
    void perFirmFairness() {
        FakeEngagementIndex index = new FakeEngagementIndex()
                .add("big", 40, "big", false)
                .add("s1", 3, "s1", false)
                .add("s2", 3, "s2", false);
        FakeProbe probe = new FakeProbe(2);
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe,
                new FakeDeadLetterPort(), metrics, settings(1)).process(V5);

        List<String> order = probe.order();
        int lastSmall = -1;
        for (int i = 0; i < order.size(); i++) {
            if (!order.get(i).startsWith("big")) {
                lastSmall = i;
            }
        }
        assertTrue(lastSmall >= 0, "small firms must be probed");
        assertTrue(lastSmall < 9, "small firms must finish within the first 3 round-robin rounds, lastSmall=" + lastSmall);
        assertEquals(46, metrics.completed());
    }

    @Test
    @Disabled("supersession deferred: cost optimization; correctness via catalogSeq fencing")
    void supersededChangeSkipsDownstream() {
        fail("supersession is deferred by D11; no ChangeRegistry port exists in the MVP");
    }

    @Test
    void duplicateWorkersNeverDoubleProbe() throws Exception {
        FakeEngagementIndex index = new FakeEngagementIndex()
                .add("f1", 25, "a", false)
                .add("f2", 25, "b", false)
                .setApplyVerification(false);
        FakeProbe probe = new FakeProbe(15);
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        FakeWorkLeasePort leases = new FakeWorkLeasePort();
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        ProcessHeadChangeService w1 = service(index, pending, leases, probe, dlq, metrics, settings(4));
        ProcessHeadChangeService w2 = service(index, pending, leases, probe, dlq, metrics, settings(4));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> w1.process(V5));
            Future<?> second = executor.submit(() -> w2.process(V5));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, probe.maxCallsPerEngagement(), "an engagement was probed more than once");
        assertEquals(50, metrics.completed(), "every engagement completed exactly once");
        assertEquals(50, pending.size());
    }

    @Test
    void verifiedRowsSkipDownstreamAndStaleWritesLose() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 12, "a", true);
        FakeProbe probe = new FakeProbe(5);
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        pending.preload(new PendingMarker(
                new EngagementRef("f1", "a-0"),
                TEMPLATE,
                "v6",
                "v7",
                "summary-v6-v7",
                10,
                Instant.parse("2024-01-01T00:00:00Z")));
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, pending, new FakeWorkLeasePort(), probe, new FakeDeadLetterPort(), metrics, settings(2))
                .process(V5);

        assertEquals(0, probe.totalCalls(), "verified rows must not touch downstream");
        assertEquals(11, metrics.fastApplied());
        assertEquals(1, metrics.fastStale());
        PendingMarker untouched = pending.get("f1", "a-0", TEMPLATE);
        assertNotNull(untouched);
        assertEquals(10, untouched.evaluatedAtSeq(), "stale writer must not overwrite newer state");
        assertEquals("v6", untouched.appliedVersion());
        assertEquals("v7", untouched.targetVersion());
        assertEquals(0, index.verifiedWrites());
    }

    @Test
    void transientProbeExhaustionIsDeadLettered() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 5, "a", false);
        FakeProbe probe = new FakeProbe(1);
        probe.transientLeft.put("a-0", new AtomicInteger(10));
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(2))
                .process(V5);

        assertEquals(4, metrics.completed());
        assertEquals(1, metrics.deadLettered());
        assertEquals(4, probe.callsFor("a-0"));
        assertEquals(3, metrics.retried());
        assertEquals(1, dlq.size());
        assertEquals("TRANSIENT_EXHAUSTED", dlq.entries().get(0).failureCode());
        assertEquals("a-0", dlq.entries().get(0).engagement().engagementId());
    }

    @Test
    void throttledProbeExhaustionIsDeadLettered() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 1, "a", false);
        FakeProbe probe = new FakeProbe(1);
        probe.throttleBudget.set(10);
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(2))
                .process(V5);

        assertEquals(0, metrics.completed());
        assertEquals(1, metrics.deadLettered());
        assertEquals(10, probe.callsFor("a-0"));
        assertEquals(9, metrics.retried());
        assertEquals(1, dlq.size());
        assertEquals("THROTTLED_EXHAUSTED", dlq.entries().get(0).failureCode());
    }

    @Test
    void runtimeFailuresAreRetriedThenDeadLettered() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 4, "a", false);
        FakeProbe probe = new FakeProbe(1);
        probe.runtimeLeft.put("a-2", new AtomicInteger(10));
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(2))
                .process(V5);

        assertEquals(3, metrics.completed());
        assertEquals(1, metrics.deadLettered());
        assertEquals(4, probe.callsFor("a-2"));
        assertEquals(3, metrics.retried());
        assertEquals(1, dlq.size());
        assertEquals("TRANSIENT_EXHAUSTED", dlq.entries().get(0).failureCode());
        assertEquals("a-2", dlq.entries().get(0).engagement().engagementId());
    }

    @Test
    void metricsFailureDuringRetryYieldsUnexpectedDeadLetter() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 3, "a", false);
        FakeProbe probe = new FakeProbe(1);
        probe.transientLeft.put("a-1", new AtomicInteger(1));
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();
        metrics.failOnRetried.set(true);

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(2))
                .process(V5);

        assertEquals(2, metrics.completed());
        assertEquals(1, metrics.deadLettered());
        assertEquals(1, dlq.size());
        assertEquals("UNEXPECTED", dlq.entries().get(0).failureCode());
        assertEquals("a-1", dlq.entries().get(0).engagement().engagementId());
    }

    @Test
    void deadLetterPublishFailureIsSwallowed() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 3, "a", false);
        FakeProbe probe = new FakeProbe(1);
        probe.permanent.add("a-1");
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        dlq.failOnPublish = true;
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), new FakeWorkLeasePort(), probe, dlq, metrics, settings(2))
                .process(V5);

        assertEquals(2, metrics.completed());
        assertEquals(1, metrics.deadLettered());
        assertEquals(0, dlq.size());
    }

    @Test
    void blankPageTokenStopsPaging() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 5, "a", true);
        index.overrideToken = " ";
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, pending, new FakeWorkLeasePort(), new FakeProbe(1), new FakeDeadLetterPort(), metrics, settings(4))
                .process(V5);

        assertEquals(5, pending.size());
        assertEquals(5, metrics.fastApplied());
        assertEquals(0, metrics.completed());
    }

    @Test
    void duplicatePageTokenStopsPaging() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 5, "a", true);
        index.overrideToken = "0";
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, pending, new FakeWorkLeasePort(), new FakeProbe(1), new FakeDeadLetterPort(), metrics, settings(4))
                .process(V5);

        assertEquals(5, pending.size());
        assertEquals(10, metrics.fastApplied() + metrics.fastStale());
        assertEquals(0, metrics.completed());
    }

    @Test
    void interruptBeforeProcessingReturnsEarly() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 3, "a", false);
        InMemoryPendingMarkerStore pending = new InMemoryPendingMarkerStore();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        Thread.currentThread().interrupt();
        service(index, pending, new FakeWorkLeasePort(), new FakeProbe(1), new FakeDeadLetterPort(), metrics, settings(2))
                .process(V5);

        assertTrue(Thread.interrupted(), "interrupt flag must remain set through process()");
        assertEquals(0, pending.size());
        assertEquals(0, metrics.probeStarted());
        assertEquals(0, metrics.completed());
    }

    @Test
    void interruptDuringSlowPhaseReleasesInFlightClaims() {
        FakeEngagementIndex index = new FakeEngagementIndex().add("f1", 2, "a", false);
        FakeProbe probe = new FakeProbe(250);
        probe.interruptTarget = Thread.currentThread();
        FakeWorkLeasePort leases = new FakeWorkLeasePort();
        leases.failRelease = true;
        FakeDeadLetterPort dlq = new FakeDeadLetterPort();
        RecordingFanOutMetrics metrics = new RecordingFanOutMetrics();

        service(index, new InMemoryPendingMarkerStore(), leases, probe, dlq, metrics, settings(2))
                .process(V5);

        assertTrue(metrics.probeStarted() >= 1);
        assertEquals(0, metrics.completed());
        assertEquals(0, dlq.size());
        Thread.interrupted();
    }

    private static ProcessHeadChangeService service(
            FakeEngagementIndex index,
            InMemoryPendingMarkerStore pending,
            FakeWorkLeasePort leases,
            FakeProbe probe,
            FakeDeadLetterPort deadLetters,
            RecordingFanOutMetrics metrics,
            Settings settings) {
        return service(index, pending, leases, probe, deadLetters, metrics, settings, new LinearReleaseCatalog());
    }

    private static ProcessHeadChangeService service(
            FakeEngagementIndex index,
            InMemoryPendingMarkerStore pending,
            FakeWorkLeasePort leases,
            FakeProbe probe,
            FakeDeadLetterPort deadLetters,
            RecordingFanOutMetrics metrics,
            Settings settings,
            LinearReleaseCatalog catalog) {
        return new ProcessHeadChangeService(
                index, pending, leases, probe, deadLetters,
                catalog, metrics, settings);
    }

    private static Settings settings(int maxConcurrency) {
        return settings(maxConcurrency, Duration.ZERO, Duration.ZERO);
    }

    private static Settings settings(int maxConcurrency, Duration baseBackoff, Duration maxBackoff) {
        return new Settings(
                maxConcurrency,
                4,
                10,
                baseBackoff,
                maxBackoff,
                Duration.ofMinutes(5),
                500);
    }

    static final class FakeEngagementIndex implements EngagementIndexPort {
        private final Map<String, List<IdxEntry>> byFirm = new LinkedHashMap<>();
        private final AtomicInteger verifiedWrites = new AtomicInteger();
        private volatile boolean applyVerification = true;
        volatile String overrideToken;

        FakeEngagementIndex add(String firm, int count, String prefix, boolean verified) {
            List<IdxEntry> entries = byFirm.computeIfAbsent(firm, ignored -> new ArrayList<>());
            for (int i = 0; i < count; i++) {
                entries.add(new IdxEntry(new EngagementRef(firm, prefix + "-" + i), state(verified)));
            }
            return this;
        }

        FakeEngagementIndex setApplyVerification(boolean apply) {
            this.applyVerification = apply;
            return this;
        }

        @Override
        public synchronized List<String> firmsWithEngagements(String templateId) {
            return new ArrayList<>(byFirm.keySet());
        }

        @Override
        public synchronized List<IndexedEngagement> findByTemplate(
                String firmId, String templateId, String pageToken, int pageSize) {
            List<IdxEntry> all = byFirm.get(firmId);
            if (all == null) {
                return List.of();
            }
            int from = pageToken == null || pageToken.isBlank() ? 0 : Integer.parseInt(pageToken);
            if (from >= all.size()) {
                return List.of();
            }
            int to = Math.min(all.size(), from + pageSize);
            String nextToken = overrideToken != null
                    ? overrideToken
                    : (to < all.size() ? String.valueOf(to) : null);
            List<IndexedEngagement> page = new ArrayList<>();
            for (int i = from; i < to; i++) {
                IdxEntry entry = all.get(i);
                page.add(new IndexedEngagement(entry.ref(), entry.state(), nextToken));
            }
            return page;
        }

        @Override
        public void recordVerified(EngagementRef engagement, TemplateState state) {
            verifiedWrites.incrementAndGet();
            if (!applyVerification) {
                return;
            }
            synchronized (this) {
                List<IdxEntry> entries = byFirm.get(engagement.firmId());
                if (entries == null) {
                    return;
                }
                for (int i = 0; i < entries.size(); i++) {
                    if (entries.get(i).ref().equals(engagement)) {
                        entries.set(i, new IdxEntry(engagement, state));
                        return;
                    }
                }
            }
        }

        int verifiedWrites() {
            return verifiedWrites.get();
        }

        private static TemplateState state(boolean verified) {
            return new TemplateState(
                    TEMPLATE,
                    "v3",
                    Set.of(),
                    verified
                            ? TemplateState.VerificationStatus.VERIFIED
                            : TemplateState.VerificationStatus.UNVERIFIED,
                    1);
        }

        private record IdxEntry(EngagementRef ref, TemplateState state) {}
    }

    static final class FakeProbe implements EngagementStateProbePort {
        private final long latencyMillis;
        private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        private final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Map<String, AtomicInteger> transientLeft = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> runtimeLeft = new ConcurrentHashMap<>();
        final Set<String> permanent = ConcurrentHashMap.newKeySet();
        final AtomicInteger throttleBudget = new AtomicInteger();
        final Map<String, String> appliedOverride = new ConcurrentHashMap<>();
        volatile Thread interruptTarget;
        private final AtomicBoolean interruptOnce = new AtomicBoolean(true);
        private int inFlight;
        private int peak;

        FakeProbe(long latencyMillis) {
            this.latencyMillis = latencyMillis;
        }

        @Override
        public TemplateState verify(EngagementRef engagement, TemplateState indexedState, String targetVersion)
                throws ProbeException {
            String id = engagement.engagementId();
            order.add(id);
            calls.computeIfAbsent(id, ignored -> new AtomicInteger()).incrementAndGet();
            synchronized (this) {
                peak = Math.max(peak, ++inFlight);
            }
            try {
                Thread target = interruptTarget;
                if (target != null && interruptOnce.compareAndSet(true, false)) {
                    target.interrupt();
                }
                if (latencyMillis > 0) {
                    try {
                        Thread.sleep(latencyMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ProbeException(ProbeException.Kind.TRANSIENT, "interrupted", e);
                    }
                }
                if (permanent.contains(id)) {
                    throw new ProbeException(ProbeException.Kind.PERMANENT, null);
                }
                int throttles = throttleBudget.getAndUpdate(value -> Math.max(0, value - 1));
                if (throttles > 0) {
                    throw new ProbeException(ProbeException.Kind.THROTTLED, "429 " + id);
                }
                AtomicInteger runtimeFailures = runtimeLeft.get(id);
                if (runtimeFailures != null && runtimeFailures.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
                    throw new RuntimeException("runtime boom " + id);
                }
                AtomicInteger left = transientLeft.get(id);
                if (left != null && left.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
                    throw new ProbeException(ProbeException.Kind.TRANSIENT, "transient " + id);
                }
                return new TemplateState(
                        TEMPLATE,
                        appliedOverride.getOrDefault(id, "v3"),
                        Set.of(),
                        TemplateState.VerificationStatus.VERIFIED,
                        2);
            } finally {
                synchronized (this) {
                    inFlight--;
                }
            }
        }

        synchronized int peak() {
            return peak;
        }

        int totalCalls() {
            return calls.values().stream().mapToInt(AtomicInteger::get).sum();
        }

        int callsFor(String id) {
            AtomicInteger value = calls.get(id);
            return value == null ? 0 : value.get();
        }

        int maxCallsPerEngagement() {
            return calls.values().stream().mapToInt(AtomicInteger::get).max().orElse(0);
        }

        List<String> order() {
            synchronized (order) {
                return new ArrayList<>(order);
            }
        }
    }

    static final class FakeWorkLeasePort implements WorkLeasePort {
        private final Set<String> completed = ConcurrentHashMap.newKeySet();
        private final Set<String> leases = ConcurrentHashMap.newKeySet();
        volatile boolean failRelease;

        @Override
        public synchronized LeaseResult tryAcquire(String changeId, String engagementId, Duration leaseDuration) {
            String key = key(changeId, engagementId);
            if (completed.contains(key)) {
                return LeaseResult.ALREADY_COMPLETED;
            }
            if (!leases.add(key)) {
                return LeaseResult.HELD_BY_OTHER_WORKER;
            }
            return LeaseResult.ACQUIRED;
        }

        @Override
        public synchronized void markCompleted(String changeId, String engagementId) {
            String key = key(changeId, engagementId);
            completed.add(key);
            leases.remove(key);
        }

        @Override
        public synchronized void releaseForRetry(String changeId, String engagementId) {
            if (failRelease) {
                throw new RuntimeException("lease release failed");
            }
            leases.remove(key(changeId, engagementId));
        }

        private static String key(String changeId, String engagementId) {
            return changeId + "|" + engagementId;
        }
    }

    static final class FakeDeadLetterPort implements DeadLetterPort {
        record Entry(HeadChange change, EngagementRef engagement, String failureCode, String safeMessage) {}

        private final List<Entry> entries = Collections.synchronizedList(new ArrayList<>());
        volatile boolean failOnPublish;

        @Override
        public void publish(HeadChange change, EngagementRef engagement, String failureCode, String safeMessage) {
            if (failOnPublish) {
                throw new RuntimeException("dead letter sink down");
            }
            entries.add(new Entry(change, engagement, failureCode, safeMessage));
        }

        int size() {
            return entries.size();
        }

        List<Entry> entries() {
            synchronized (entries) {
                return new ArrayList<>(entries);
            }
        }
    }

    static final class RecordingFanOutMetrics implements FanOutMetricsPort {
        private final AtomicInteger fastApplied = new AtomicInteger();
        private final AtomicInteger fastStale = new AtomicInteger();
        private final AtomicInteger probeStarted = new AtomicInteger();
        private final AtomicInteger probeSucceeded = new AtomicInteger();
        private final AtomicInteger completed = new AtomicInteger();
        private final AtomicInteger retried = new AtomicInteger();
        private final AtomicInteger deadLettered = new AtomicInteger();
        private final AtomicInteger skippedClaimed = new AtomicInteger();
        final AtomicBoolean failOnRetried = new AtomicBoolean();

        @Override
        public void recordFastApplied() {
            fastApplied.incrementAndGet();
        }

        @Override
        public void recordFastStale() {
            fastStale.incrementAndGet();
        }

        @Override
        public void recordProbeStarted() {
            probeStarted.incrementAndGet();
        }

        @Override
        public void recordProbeSucceeded() {
            probeSucceeded.incrementAndGet();
        }

        @Override
        public void recordCompleted() {
            completed.incrementAndGet();
        }

        @Override
        public void recordRetried() {
            if (failOnRetried.get()) {
                throw new RuntimeException("metrics sink down");
            }
            retried.incrementAndGet();
        }

        @Override
        public void recordDeadLettered() {
            deadLettered.incrementAndGet();
        }

        @Override
        public void recordSkippedClaimed() {
            skippedClaimed.incrementAndGet();
        }

        int fastApplied() {
            return fastApplied.get();
        }

        int fastStale() {
            return fastStale.get();
        }

        int probeStarted() {
            return probeStarted.get();
        }

        int probeSucceeded() {
            return probeSucceeded.get();
        }

        int completed() {
            return completed.get();
        }

        int retried() {
            return retried.get();
        }

        int deadLettered() {
            return deadLettered.get();
        }

        int skippedClaimed() {
            return skippedClaimed.get();
        }
    }

    static final class LinearReleaseCatalog implements ReleaseCatalogPort {
        private final Map<String, List<String>> parents = new HashMap<>();
        private final Set<String> nullSummaryPairs = ConcurrentHashMap.newKeySet();

        LinearReleaseCatalog() {
            for (int i = 2; i <= 7; i++) {
                parents.put("v" + i, List.of("v" + (i - 1)));
            }
            parents.put("v4x", List.of("v3"));
        }

        @Override
        public Optional<ReleaseHead> findHead(String templateId) {
            return Optional.empty();
        }

        @Override
        public boolean isAncestor(String templateId, String ancestorVersion, String descendantVersion) {
            if (ancestorVersion == null || descendantVersion == null
                    || ancestorVersion.equals(descendantVersion)) {
                return false;
            }
            Deque<String> queue = new ArrayDeque<>(List.of(descendantVersion));
            Set<String> seen = new HashSet<>();
            while (!queue.isEmpty()) {
                String version = queue.poll();
                if (version.equals(ancestorVersion)) {
                    return true;
                }
                if (seen.add(version)) {
                    queue.addAll(parents.getOrDefault(version, List.of()));
                }
            }
            return false;
        }

        @Override
        public Optional<String> findSummaryId(String templateId, String fromVersion, String toVersion) {
            return Optional.of("");
        }

        @Override
        public String resolveSummaryId(String templateId, String fromVersion, String toVersion) {
            if (nullSummaryPairs.contains(fromVersion + "->" + toVersion)) {
                return null;
            }
            return ReleaseCatalogPort.super.resolveSummaryId(templateId, fromVersion, toVersion);
        }

        LinearReleaseCatalog nullSummaryFor(String fromVersion, String toVersion) {
            nullSummaryPairs.add(fromVersion + "->" + toVersion);
            return this;
        }
    }
}
