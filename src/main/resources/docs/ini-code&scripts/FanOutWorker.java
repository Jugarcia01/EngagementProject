package com.acme.fanout;

import com.acme.fanout.AdaptiveLimiter.Signal;
import com.acme.fanout.Ports.ProbeException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Template head-change fan-out worker (one instance per region cell).
 *
 * Phase 1 (fast, seconds): page the regional index per firm. Rows whose state is already VERIFIED are
 *   evaluated locally and written in batches. No downstream call.
 * Phase 2 (slow, capacity-bound): rows the index cannot vouch for need the ~60s downstream probe.
 *   Dispatch is round-robin across firms (a 40k-engagement firm cannot starve a 40-engagement one),
 *   concurrency is bounded by an AIMD limiter, failures are retried off-thread via a delay queue
 *   (a retrying item never holds a downstream permit), and each item is guarded by a lease ledger.
 *
 * Guarantees: at-least-once execution, effectively-once outcome. Re-running {@link #run} after a crash is
 * safe: completed items are skipped via the ledger, writes are fenced by catalogSeq.
 */
public final class FanOutWorker {
    private final String workerId;
    private final Ports.EngagementIndex index;
    private final Ports.EngagementStateProbe probe;
    private final Ports.PendingUpdateStore store;
    private final Ports.WorkLedger ledger;
    private final Ports.VersionGraph graph;
    private final Ports.ChangeRegistry registry;
    private final Ports.DeadLetterSink dlq;
    private final FanOutConfig cfg;
    private final AdaptiveLimiter limiter;
    /** Global pause when downstream says "slow down" (Retry-After). nanoTime-based. */
    private final AtomicLong pauseUntilNanos = new AtomicLong(System.nanoTime());

    public FanOutWorker(String workerId, Ports.EngagementIndex index, Ports.EngagementStateProbe probe,
                        Ports.PendingUpdateStore store, Ports.WorkLedger ledger, Ports.VersionGraph graph,
                        Ports.ChangeRegistry registry, Ports.DeadLetterSink dlq, FanOutConfig cfg) {
        this.workerId = workerId;
        this.index = index;
        this.probe = probe;
        this.store = store;
        this.ledger = ledger;
        this.graph = graph;
        this.registry = registry;
        this.dlq = dlq;
        this.cfg = cfg;
        this.limiter = new AdaptiveLimiter(cfg.initialProbeConcurrency(), cfg.minProbeConcurrency(),
                cfg.maxProbeConcurrency(), cfg.decreaseCooldown());
    }

    public AdaptiveLimiter limiter() { return limiter; }

    public RunReport run(HeadChange change) throws InterruptedException {
        Stats st = new Stats();
        Map<String, ArrayDeque<EngagementRef>> slow = new LinkedHashMap<>();
        fastPhase(change, st, slow);
        slowPhase(change, st, slow);
        return st.report(limiter);
    }

    // ---------------------------------------------------------------- phase 1

    private void fastPhase(HeadChange change, Stats st, Map<String, ArrayDeque<EngagementRef>> slow) {
        for (String firm : index.firmsWithEngagements(change.templateId())) {
            String cursor = null;
            do {
                if (superseded(change)) return; // newer change will redo this; stale writes would lose anyway
                Ports.EngagementIndex.Page page = index.page(change.templateId(), firm, cursor, cfg.pageSize());
                List<PendingMarker> batch = new ArrayList<>();
                for (EngagementRef ref : page.items()) {
                    if (ref.needsProbe()) {
                        slow.computeIfAbsent(firm, f -> new ArrayDeque<>()).add(ref);
                    } else {
                        batch.add(marker(change, ref.knownState(), ref.engagementId()));
                    }
                }
                if (!batch.isEmpty()) {
                    int applied = store.upsertAll(batch); // idempotent + fenced: safe to repeat
                    st.fastApplied.addAndGet(applied);
                    st.fastStale.addAndGet(batch.size() - applied);
                }
                cursor = page.nextCursor();
            } while (cursor != null);
        }
    }

    // ---------------------------------------------------------------- phase 2

    private void slowPhase(HeadChange change, Stats st, Map<String, ArrayDeque<EngagementRef>> slow)
            throws InterruptedException {
        ArrayDeque<ArrayDeque<EngagementRef>> ring = new ArrayDeque<>(slow.values());
        AtomicInteger remaining = new AtomicInteger(slow.values().stream().mapToInt(ArrayDeque::size).sum());
        DelayQueue<RetryEntry> retries = new DelayQueue<>();

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) { // close() joins all tasks
            while (remaining.get() > 0) {
                Attempt next;
                RetryEntry due = retries.poll();
                if (due != null) {
                    next = due.attempt;
                } else if (!ring.isEmpty()) {
                    next = new Attempt(pollRoundRobin(ring), 0, 0);
                } else { // only in-flight work / not-yet-due retries left
                    due = retries.poll(50, TimeUnit.MILLISECONDS);
                    if (due == null) continue;
                    next = due.attempt;
                }
                if (superseded(change)) { // cheap cost control; correctness does not depend on it
                    st.skippedSuperseded.incrementAndGet();
                    remaining.decrementAndGet();
                    continue;
                }
                awaitPause();
                limiter.acquire();
                final Attempt a = next;
                exec.submit(() -> process(change, a, st, remaining, retries));
            }
        }
    }

    private void process(HeadChange change, Attempt a, Stats st, AtomicInteger remaining,
                         DelayQueue<RetryEntry> retries) {
        Signal signal = Signal.NEUTRAL;
        String key = change.changeId() + "|" + a.ref().engagementId();
        boolean claimed = false;
        try {
            if (!ledger.tryClaim(key, workerId, cfg.lease())) { // done already, or another worker owns it
                st.skippedClaimed.incrementAndGet();
                remaining.decrementAndGet();
                return;
            }
            claimed = true;
            st.probesStarted.incrementAndGet();
            TemplateState state = probe.probe(a.ref().engagementId(), cfg.probeTimeout());
            st.probesSucceeded.incrementAndGet();
            signal = Signal.SUCCESS;

            index.recordVerified(a.ref().engagementId(), state);
            store.upsert(marker(change, state, a.ref().engagementId()));
            ledger.markDone(key);
            st.completed.incrementAndGet();
            remaining.decrementAndGet();
        } catch (ProbeException e) {
            if (claimed) safeRelease(key);
            switch (e.kind()) {
                case PERMANENT -> {
                    deadLetter(change, a, e.getMessage(), a.failures() + 1, st, remaining);
                }
                case THROTTLED -> {
                    signal = Signal.THROTTLED;
                    pauseFor(e.retryAfter());
                    if (a.throttles() + 1 > cfg.maxThrottledRetries()) {
                        deadLetter(change, a, "throttled too long: " + e.getMessage(), a.failures() + a.throttles() + 1, st, remaining);
                    } else {
                        scheduleRetry(new Attempt(a.ref(), a.failures(), a.throttles() + 1), a.throttles(), e.retryAfter(), st, retries);
                    }
                }
                case TRANSIENT -> {
                    signal = Signal.FAILURE;
                    retryTransient(change, a, e.getMessage(), e.retryAfter(), st, remaining, retries);
                }
            }
        } catch (RuntimeException e) { // ledger/index/store hiccup: same treatment as a transient downstream error
            if (claimed) safeRelease(key);
            if (signal != Signal.SUCCESS) signal = Signal.FAILURE;
            retryTransient(change, a, String.valueOf(e), Duration.ZERO, st, remaining, retries);
        } finally {
            limiter.release(signal);
        }
    }

    private void retryTransient(HeadChange change, Attempt a, String reason, Duration retryAfter, Stats st,
                                AtomicInteger remaining, DelayQueue<RetryEntry> retries) {
        int failures = a.failures() + 1;
        if (failures >= cfg.maxAttempts()) {
            deadLetter(change, a, reason, failures, st, remaining);
        } else {
            scheduleRetry(new Attempt(a.ref(), failures, a.throttles()), failures, retryAfter, st, retries);
        }
    }

    private void scheduleRetry(Attempt next, int n, Duration retryAfter, Stats st, DelayQueue<RetryEntry> retries) {
        Duration delay = backoff(n);
        if (retryAfter.compareTo(delay) > 0) delay = retryAfter;
        st.retried.incrementAndGet();
        retries.add(new RetryEntry(next, System.nanoTime() + delay.toNanos()));
    }

    private void deadLetter(HeadChange change, Attempt a, String reason, int attempts, Stats st, AtomicInteger remaining) {
        dlq.send(change, a.ref(), reason, attempts); // parked for the reconciliation sweeper; never blocks the rest
        st.deadLettered.incrementAndGet();
        remaining.decrementAndGet();
    }

    // ---------------------------------------------------------------- helpers

    private PendingMarker marker(HeadChange c, TemplateState s, String engagementId) {
        boolean pending = s.templateId().equals(c.templateId()) && PendingEvaluator.isPending(s, c.headVersion(), graph);
        return new PendingMarker(engagementId, c.templateId(), c.headVersion(), pending, c.catalogSeq());
    }

    private boolean superseded(HeadChange c) {
        return registry.isSuperseded(c.templateId(), c.catalogSeq());
    }

    private void safeRelease(String key) {
        try { ledger.release(key, workerId); } catch (RuntimeException ignored) { /* lease expiry covers it */ }
    }

    /** Full-jitter exponential backoff. */
    private Duration backoff(int n) {
        long cap = Math.min(cfg.maxBackoff().toMillis(), cfg.baseBackoff().toMillis() << Math.min(n, 20));
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(Math.max(1, cap) + 1));
    }

    private void pauseFor(Duration d) {
        long target = System.nanoTime() + d.toNanos();
        pauseUntilNanos.accumulateAndGet(target, (cur, t) -> t - cur > 0 ? t : cur);
    }

    private void awaitPause() throws InterruptedException {
        long until = pauseUntilNanos.get();
        long now;
        while ((now = System.nanoTime()) - until < 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(until - now, TimeUnit.MILLISECONDS.toNanos(50)));
            until = pauseUntilNanos.get();
        }
    }

    private static EngagementRef pollRoundRobin(ArrayDeque<ArrayDeque<EngagementRef>> ring) {
        ArrayDeque<EngagementRef> firmQueue = ring.pollFirst();
        EngagementRef ref = firmQueue.pollFirst();
        if (!firmQueue.isEmpty()) ring.addLast(firmQueue);
        return ref;
    }

    private record Attempt(EngagementRef ref, int failures, int throttles) {}

    private static final class RetryEntry implements Delayed {
        final Attempt attempt;
        final long readyAtNanos;

        RetryEntry(Attempt attempt, long readyAtNanos) {
            this.attempt = attempt;
            this.readyAtNanos = readyAtNanos;
        }

        @Override public long getDelay(TimeUnit unit) {
            return unit.convert(readyAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override public int compareTo(Delayed o) {
            return Long.compare(readyAtNanos, ((RetryEntry) o).readyAtNanos);
        }
    }

    private static final class Stats {
        final AtomicLong fastApplied = new AtomicLong(), fastStale = new AtomicLong(),
                probesStarted = new AtomicLong(), probesSucceeded = new AtomicLong(), completed = new AtomicLong(),
                retried = new AtomicLong(), deadLettered = new AtomicLong(), skippedSuperseded = new AtomicLong(),
                skippedClaimed = new AtomicLong();

        RunReport report(AdaptiveLimiter l) {
            return new RunReport(fastApplied.get(), fastStale.get(), probesStarted.get(), probesSucceeded.get(),
                    completed.get(), retried.get(), deadLettered.get(), skippedSuperseded.get(),
                    skippedClaimed.get(), l.peakInFlight(), l.lowestLimit());
        }
    }
}
