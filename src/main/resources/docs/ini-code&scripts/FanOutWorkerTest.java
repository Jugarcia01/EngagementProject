package com.acme.fanout;

import com.acme.fanout.Ports.ProbeException;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Dependency-free test runner (javac + java), so it runs anywhere. Exit code != 0 on failure. */
public class FanOutWorkerTest {

    // ------------------------------------------------------------ fakes

    static final class Idx implements Ports.EngagementIndex {
        final Map<String, List<EngagementRef>> byFirm = new LinkedHashMap<>();
        final Map<String, TemplateState> verified = new ConcurrentHashMap<>();

        Idx add(String firm, int n, String prefix, boolean isVerified) {
            for (int i = 0; i < n; i++) {
                TemplateState s = isVerified ? truth() : null;
                byFirm.computeIfAbsent(firm, f -> new ArrayList<>()).add(new EngagementRef(prefix + "-" + i, firm, s));
            }
            return this;
        }
        public List<String> firmsWithEngagements(String t) { return new ArrayList<>(byFirm.keySet()); }
        public Page page(String t, String firm, String cursor, int limit) {
            List<EngagementRef> all = byFirm.get(firm);
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(all.size(), from + limit);
            return new Page(new ArrayList<>(all.subList(from, to)), to < all.size() ? String.valueOf(to) : null);
        }
        public void recordVerified(String id, TemplateState s) { verified.put(id, s); }
    }

    static TemplateState truth() { return new TemplateState("T", "v3", Set.of()); }

    static final class Probe implements Ports.EngagementStateProbe {
        final long latencyMs;
        final ConcurrentHashMap<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> order = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Integer> transientLeft = new ConcurrentHashMap<>();
        final Set<String> permanent = ConcurrentHashMap.newKeySet();
        final AtomicInteger throttleBudget = new AtomicInteger();
        private int inFlight, peak;
        volatile Runnable afterThirdCall = () -> {};

        Probe(long latencyMs) { this.latencyMs = latencyMs; }

        public TemplateState probe(String id, Duration timeout) throws ProbeException {
            order.add(id);
            calls.computeIfAbsent(id, k -> new AtomicInteger()).incrementAndGet();
            if (order.size() == 3) afterThirdCall.run();
            synchronized (this) { peak = Math.max(peak, ++inFlight); }
            try {
                Thread.sleep(latencyMs);
                if (permanent.contains(id)) throw new ProbeException(ProbeException.Kind.PERMANENT, "corrupt " + id, null);
                if (throttleBudget.getAndUpdate(v -> Math.max(0, v - 1)) > 0)
                    throw new ProbeException(ProbeException.Kind.THROTTLED, "429", Duration.ofMillis(10));
                Integer left = transientLeft.get(id);
                if (left != null && left > 0) {
                    transientLeft.put(id, left - 1);
                    throw new ProbeException(ProbeException.Kind.TRANSIENT, "boom", null);
                }
                return truth();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProbeException(ProbeException.Kind.TRANSIENT, "interrupted", null);
            } finally {
                synchronized (this) { inFlight--; }
            }
        }
        synchronized int peak() { return peak; }
        int totalCalls() { return calls.values().stream().mapToInt(AtomicInteger::get).sum(); }
        int maxCallsPerEngagement() { return calls.values().stream().mapToInt(AtomicInteger::get).max().orElse(0); }
    }

    static final class Store implements Ports.PendingUpdateStore {
        final ConcurrentHashMap<String, PendingMarker> rows = new ConcurrentHashMap<>();
        public int upsertAll(List<PendingMarker> ms) {
            int applied = 0;
            for (PendingMarker m : ms) {
                boolean[] ok = {false};
                rows.compute(m.engagementId(), (k, cur) -> {
                    if (cur != null && cur.catalogSeq() > m.catalogSeq()) return cur;
                    ok[0] = true;
                    return m;
                });
                if (ok[0]) applied++;
            }
            return applied;
        }
    }

    static final class Ledger implements Ports.WorkLedger {
        record Lease(String owner, long expiresNanos) {}
        final ConcurrentHashMap<String, Lease> leases = new ConcurrentHashMap<>();
        final Set<String> done = ConcurrentHashMap.newKeySet();
        public boolean tryClaim(String key, String owner, Duration lease) {
            if (done.contains(key)) return false;
            boolean[] won = {false};
            leases.compute(key, (k, cur) -> {
                if (cur == null || System.nanoTime() - cur.expiresNanos() > 0) {
                    won[0] = true;
                    return new Lease(owner, System.nanoTime() + lease.toNanos());
                }
                return cur;
            });
            return won[0];
        }
        public void markDone(String key) { done.add(key); }
        public void release(String key, String owner) { leases.computeIfPresent(key, (k, l) -> l.owner().equals(owner) ? null : l); }
    }

    /** v1<v2<...<v7 linear, plus branch v4x forked from v3. */
    static final class Graph implements Ports.VersionGraph {
        final Map<String, List<String>> parents = new HashMap<>();
        Graph() {
            for (int i = 2; i <= 7; i++) parents.put("v" + i, List.of("v" + (i - 1)));
            parents.put("v4x", List.of("v3"));
        }
        public boolean isAncestorOrSame(String t, String anc, String desc) {
            Deque<String> q = new ArrayDeque<>(List.of(desc));
            Set<String> seen = new HashSet<>();
            while (!q.isEmpty()) {
                String v = q.poll();
                if (v.equals(anc)) return true;
                if (seen.add(v)) q.addAll(parents.getOrDefault(v, List.of()));
            }
            return false;
        }
    }

    static final class Registry implements Ports.ChangeRegistry {
        volatile long latest;
        public boolean isSuperseded(String t, long seq) { return latest > seq; }
    }

    static final class Dlq implements Ports.DeadLetterSink {
        final List<String> ids = Collections.synchronizedList(new ArrayList<>());
        public void send(HeadChange c, EngagementRef r, String reason, int attempts) { ids.add(r.engagementId()); }
    }

    // ------------------------------------------------------------ harness

    static FanOutConfig cfg(int initial, int min, int max) {
        return new FanOutConfig(initial, min, max, Duration.ofSeconds(5), 10, 4, 20,
                Duration.ofMillis(5), Duration.ofMillis(40), Duration.ofSeconds(30), Duration.ZERO);
    }

    static final HeadChange V5 = new HeadChange("chg-5", "T", "v5", 5);

    static FanOutWorker worker(String id, Idx idx, Probe p, Store s, Ledger l, Registry r, Dlq d, FanOutConfig c) {
        return new FanOutWorker(id, idx, p, s, l, new Graph(), r, d, c);
    }

    static void check(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    // ------------------------------------------------------------ tests

    static void concurrencyCapAndCompleteness() throws Exception {
        Idx idx = new Idx().add("f1", 20, "a", false).add("f2", 10, "b", false);
        Probe p = new Probe(30);
        Store s = new Store();
        FanOutWorker w = worker("w1", idx, p, s, new Ledger(), new Registry(), new Dlq(), cfg(4, 1, 4));
        RunReport r = w.run(V5);
        check(p.peak() <= 4, "peak downstream concurrency " + p.peak() + " exceeded cap 4");
        check(p.peak() >= 3, "limiter should actually use the capacity, peak=" + p.peak());
        check(r.completed() == 30, "completed " + r.completed());
        check(s.rows.size() == 30 && s.rows.values().stream().allMatch(PendingMarker::pending), "all 30 should be pending v5");
        check(idx.verified.size() == 30, "probe results must be written back to the index (free backfill)");
    }

    static void idempotentRerun() throws Exception {
        Idx idx = new Idx().add("f1", 15, "a", false);
        Probe p = new Probe(5);
        Ledger l = new Ledger();
        FanOutWorker w = worker("w1", idx, p, new Store(), l, new Registry(), new Dlq(), cfg(4, 1, 4));
        w.run(V5);
        int first = p.totalCalls();
        RunReport again = w.run(V5);
        check(first == 15, "first run probes " + first);
        check(p.totalCalls() == 15, "re-run must not hit downstream again, calls=" + p.totalCalls());
        check(again.skippedClaimed() == 15, "re-run should skip via ledger, skipped=" + again.skippedClaimed());
    }

    static void transientFailureIsRetried() throws Exception {
        Idx idx = new Idx().add("f1", 5, "a", false);
        Probe p = new Probe(5);
        p.transientLeft.put("a-2", 2);
        Store s = new Store();
        RunReport r = worker("w1", idx, p, s, new Ledger(), new Registry(), new Dlq(), cfg(2, 1, 2)).run(V5);
        check(r.completed() == 5 && r.deadLettered() == 0, "all should eventually complete: " + r);
        check(p.calls.get("a-2").get() == 3, "a-2 should be probed 3 times");
        check(r.retried() == 2, "retried=" + r.retried());
    }

    static void permanentFailureIsDeadLetteredWithoutBlockingOthers() throws Exception {
        Idx idx = new Idx().add("f1", 6, "a", false);
        Probe p = new Probe(5);
        p.permanent.add("a-1");
        Dlq d = new Dlq();
        RunReport r = worker("w1", idx, p, new Store(), new Ledger(), new Registry(), d, cfg(3, 1, 3)).run(V5);
        check(r.deadLettered() == 1 && d.ids.equals(List.of("a-1")), "dlq=" + d.ids);
        check(r.completed() == 5, "others must complete: " + r.completed());
        check(p.calls.get("a-1").get() == 1, "permanent errors must not be retried");
    }

    static void throttlingShrinksConcurrencyAndStillCompletes() throws Exception {
        Idx idx = new Idx().add("f1", 30, "a", false);
        Probe p = new Probe(10);
        p.throttleBudget.set(4);
        FanOutWorker w = worker("w1", idx, p, new Store(), new Ledger(), new Registry(), new Dlq(), cfg(8, 1, 8));
        RunReport r = w.run(V5);
        check(r.lowestProbeLimit() < 8, "AIMD should have backed off, lowest=" + r.lowestProbeLimit());
        check(r.completed() == 30 && r.deadLettered() == 0, "throttled work must be retried, not lost: " + r);
    }

    static void perFirmFairness() throws Exception {
        Idx idx = new Idx().add("big", 40, "big", false).add("s1", 3, "s1", false).add("s2", 3, "s2", false);
        Probe p = new Probe(2);
        worker("w1", idx, p, new Store(), new Ledger(), new Registry(), new Dlq(), cfg(1, 1, 1)).run(V5);
        int lastSmall = -1;
        for (int i = 0; i < p.order.size(); i++) if (!p.order.get(i).startsWith("big")) lastSmall = i;
        check(lastSmall < 9, "small firms must finish within the first 3 round-robin rounds, lastSmall=" + lastSmall);
    }

    static void supersededChangeSkipsDownstream() throws Exception {
        // (a) already superseded before the run: nothing is probed or written for the slow rows
        Idx idx = new Idx().add("f1", 10, "a", false);
        Probe p = new Probe(5);
        Registry reg = new Registry();
        reg.latest = 6;
        RunReport r = worker("w1", idx, p, new Store(), new Ledger(), reg, new Dlq(), cfg(2, 1, 2)).run(V5);
        check(p.totalCalls() == 0 && r.completed() == 0, "no downstream calls for a superseded change");

        // (b) superseded mid-run: remaining items are dropped without spending downstream capacity
        Idx idx2 = new Idx().add("f1", 20, "b", false);
        Probe p2 = new Probe(5);
        Registry reg2 = new Registry();
        p2.afterThirdCall = () -> reg2.latest = 6;
        RunReport r2 = worker("w1", idx2, p2, new Store(), new Ledger(), reg2, new Dlq(), cfg(1, 1, 1)).run(V5);
        check(p2.totalCalls() <= 5, "should stop probing soon after supersession, calls=" + p2.totalCalls());
        check(r2.skippedSuperseded() >= 15, "skipped=" + r2.skippedSuperseded());
    }

    static void duplicateWorkersNeverDoubleProbe() throws Exception {
        Idx idx = new Idx().add("f1", 25, "a", false).add("f2", 25, "b", false);
        Probe p = new Probe(15);
        Store s = new Store();
        Ledger l = new Ledger();
        Registry reg = new Registry();
        Dlq d = new Dlq();
        FanOutWorker w1 = worker("w1", idx, p, s, l, reg, d, cfg(4, 1, 4));
        FanOutWorker w2 = worker("w2", idx, p, s, l, reg, d, cfg(4, 1, 4));
        ExecutorService ex = Executors.newFixedThreadPool(2);
        Future<RunReport> f1 = ex.submit(() -> w1.run(V5));
        Future<RunReport> f2 = ex.submit(() -> w2.run(V5)); // same event delivered twice
        RunReport r1 = f1.get(), r2 = f2.get();
        ex.shutdown();
        check(p.maxCallsPerEngagement() == 1, "an engagement was probed " + p.maxCallsPerEngagement() + " times");
        check(r1.completed() + r2.completed() == 50, "every engagement completed exactly once");
    }

    static void verifiedRowsSkipDownstreamAndStaleWritesLose() throws Exception {
        Idx idx = new Idx().add("f1", 12, "a", true);
        Probe p = new Probe(5);
        Store s = new Store();
        s.rows.put("a-0", new PendingMarker("a-0", "T", "v6", true, 10)); // newer fact already present
        RunReport r = worker("w1", idx, p, s, new Ledger(), new Registry(), new Dlq(), cfg(2, 1, 2)).run(V5);
        check(p.totalCalls() == 0, "verified rows must not touch downstream");
        check(r.fastApplied() == 11 && r.fastStale() == 1, "applied=" + r.fastApplied() + " stale=" + r.fastStale());
        check(s.rows.get("a-0").catalogSeq() == 10, "stale writer must not overwrite newer state");
    }

    static void declineSemantics() {
        Graph g = new Graph();
        TemplateState declinedV5 = new TemplateState("T", "v3", Set.of("v5"));
        check(!PendingEvaluator.isPending(declinedV5, "v5", g), "declined v5 is not re-offered");
        check(PendingEvaluator.isPending(declinedV5, "v7", g), "v7 (contains v5) IS offered; decline pinned v5 only");
        check(!PendingEvaluator.isPending(declinedV5, null, g), "nothing offerable -> not pending");
        TemplateState applied6 = new TemplateState("T", "v6", Set.of());
        check(!PendingEvaluator.isPending(applied6, "v5", g), "head already contained in applied -> not pending");
        TemplateState onMain = new TemplateState("T", "v3", Set.of());
        check(!PendingEvaluator.isPending(new TemplateState("T", "v4x", Set.of()), "v5", g), "branch divergence is not auto-offered");
        check(PendingEvaluator.isPending(onMain, "v4", g), "plain successor is pending");
        // withdrawal: head falls back from v7 to v5 which the user already declined -> nothing pending
        check(!PendingEvaluator.isPending(declinedV5, "v5", g), "after v7 withdrawn, head=v5 declined -> quiet");
    }

    // ------------------------------------------------------------ main

    public static void main(String[] args) {
        Map<String, ThrowingRunnable> tests = new LinkedHashMap<>();
        tests.put("concurrencyCapAndCompleteness", FanOutWorkerTest::concurrencyCapAndCompleteness);
        tests.put("idempotentRerun", FanOutWorkerTest::idempotentRerun);
        tests.put("transientFailureIsRetried", FanOutWorkerTest::transientFailureIsRetried);
        tests.put("permanentFailureIsDeadLettered", FanOutWorkerTest::permanentFailureIsDeadLetteredWithoutBlockingOthers);
        tests.put("throttlingShrinksConcurrency", FanOutWorkerTest::throttlingShrinksConcurrencyAndStillCompletes);
        tests.put("perFirmFairness", FanOutWorkerTest::perFirmFairness);
        tests.put("supersededChangeSkipsDownstream", FanOutWorkerTest::supersededChangeSkipsDownstream);
        tests.put("duplicateWorkersNeverDoubleProbe", FanOutWorkerTest::duplicateWorkersNeverDoubleProbe);
        tests.put("verifiedRowsSkipDownstreamAndStaleWritesLose", FanOutWorkerTest::verifiedRowsSkipDownstreamAndStaleWritesLose);
        tests.put("declineSemantics", FanOutWorkerTest::declineSemantics);
        int failed = 0;
        for (var e : tests.entrySet()) {
            try {
                e.getValue().run();
                System.out.println("PASS  " + e.getKey());
            } catch (Throwable t) {
                failed++;
                System.out.println("FAIL  " + e.getKey() + " -> " + t);
            }
        }
        System.out.println(failed == 0 ? "ALL PASSED" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    interface ThrowingRunnable { void run() throws Exception; }
}
