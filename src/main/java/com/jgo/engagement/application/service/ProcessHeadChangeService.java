package com.jgo.engagement.application.service;

import com.jgo.engagement.application.port.in.ProcessHeadChangeUseCase;
import com.jgo.engagement.application.port.out.DeadLetterPort;
import com.jgo.engagement.application.port.out.EngagementIndexPort;
import com.jgo.engagement.application.port.out.EngagementStateProbePort;
import com.jgo.engagement.application.port.out.FanOutMetricsPort;
import com.jgo.engagement.application.port.out.PendingMarkerPort;
import com.jgo.engagement.application.port.out.ProbeException;
import com.jgo.engagement.application.port.out.ReleaseCatalogPort;
import com.jgo.engagement.application.port.out.WorkLeasePort;
import com.jgo.engagement.domain.model.EngagementRef;
import com.jgo.engagement.domain.model.HeadChange;
import com.jgo.engagement.domain.model.PendingMarker;
import com.jgo.engagement.domain.model.TemplateState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Synchronous fan-out for one state-carrying template-head change.
 *
 * <p>Phase 1 evaluates index rows the regional index can vouch for and writes pending markers
 * locally. Phase 2 probes only unverified rows under a fixed concurrency cap, with leases,
 * per-firm round-robin scheduling, and off-thread delayed retries.</p>
 */
public final class ProcessHeadChangeService implements ProcessHeadChangeUseCase {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessHeadChangeService.class);

    private final EngagementIndexPort index;
    private final PendingMarkerPort pendingMarkers;
    private final WorkLeasePort leases;
    private final EngagementStateProbePort probes;
    private final DeadLetterPort deadLetters;
    private final ReleaseCatalogPort releaseCatalog;
    private final FanOutMetricsPort metrics;
    private final Settings settings;
    private final PendingEvaluator evaluator;

    public ProcessHeadChangeService(
            EngagementIndexPort index,
            PendingMarkerPort pendingMarkers,
            WorkLeasePort leases,
            EngagementStateProbePort probes,
            DeadLetterPort deadLetters,
            ReleaseCatalogPort releaseCatalog,
            FanOutMetricsPort metrics,
            Settings settings) {
        this.index = Objects.requireNonNull(index, "index");
        this.pendingMarkers = Objects.requireNonNull(pendingMarkers, "pendingMarkers");
        this.leases = Objects.requireNonNull(leases, "leases");
        this.probes = Objects.requireNonNull(probes, "probes");
        this.deadLetters = Objects.requireNonNull(deadLetters, "deadLetters");
        this.releaseCatalog = Objects.requireNonNull(releaseCatalog, "releaseCatalog");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.evaluator = new PendingEvaluator(releaseCatalog);
    }

    @Override
    public void process(HeadChange change) {
        Objects.requireNonNull(change, "change");

        Map<String, Deque<Attempt>> slowQueues = fastPhase(change);
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        slowPhase(change, slowQueues);
    }

    // ---------------------------------------------------------------- phase 1

    private Map<String, Deque<Attempt>> fastPhase(HeadChange change) {
        Map<String, Deque<Attempt>> slow = new LinkedHashMap<>();

        for (String firm : index.firmsWithEngagements(change.templateId())) {
            String cursor = null;
            Set<String> seenTokens = new HashSet<>();

            while (!Thread.currentThread().isInterrupted()) {
                List<EngagementIndexPort.IndexedEngagement> page =
                        index.findByTemplate(firm, change.templateId(), cursor, settings.pageSize());
                if (page == null || page.isEmpty()) {
                    break;
                }

                for (EngagementIndexPort.IndexedEngagement entry : page) {
                    if (entry.state().verificationStatus() == TemplateState.VerificationStatus.VERIFIED) {
                        PendingMarkerPort.WriteResult result =
                                evaluateAndWrite(change, entry.engagement(), entry.state());
                        if (result == PendingMarkerPort.WriteResult.APPLIED) {
                            metrics.recordFastApplied();
                        } else {
                            metrics.recordFastStale();
                        }
                    } else {
                        String entryFirm = entry.engagement().firmId();
                        slow.computeIfAbsent(entryFirm, ignored -> new ArrayDeque<>())
                                .addLast(new Attempt(entry.engagement(), entry.state(), 0, 0));
                    }
                }

                cursor = page.get(page.size() - 1).nextPageToken();
                if (cursor == null || cursor.isBlank()) {
                    break;
                }
                if (!seenTokens.add(cursor)) {
                    break;
                }
            }
        }

        return slow;
    }

    private PendingMarkerPort.WriteResult evaluateAndWrite(
            HeadChange change, EngagementRef engagement, TemplateState state) {
        PendingEvaluator.Evaluation evaluation = evaluator.evaluate(state, change.headVersion());

        if (evaluation.pending()) {
            String summaryId = releaseCatalog.resolveSummaryId(
                    change.templateId(), evaluation.fromVersion(), evaluation.toVersion());
            if (summaryId == null || summaryId.isBlank()) {
                summaryId = "summary-" + evaluation.fromVersion() + "-" + evaluation.toVersion();
            }

            PendingMarker marker = new PendingMarker(
                    engagement,
                    change.templateId(),
                    evaluation.fromVersion(),
                    evaluation.toVersion(),
                    summaryId,
                    change.catalogSeq(),
                    Instant.now());
            return pendingMarkers.upsertIfNewer(marker);
        }

        return pendingMarkers.deleteIfNewer(engagement, change.templateId(), change.catalogSeq());
    }

    // ---------------------------------------------------------------- phase 2

    private void slowPhase(HeadChange change, Map<String, Deque<Attempt>> slowQueues) {
        int remaining = slowQueues.values().stream().mapToInt(Deque::size).sum();
        if (remaining == 0) {
            return;
        }

        Map<String, Deque<Attempt>> queues = new LinkedHashMap<>();
        for (Map.Entry<String, Deque<Attempt>> entry : slowQueues.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                queues.put(entry.getKey(), new ArrayDeque<>(entry.getValue()));
            }
        }

        ArrayDeque<String> ring = new ArrayDeque<>();
        Set<String> inRing = new HashSet<>();
        for (String firm : queues.keySet()) {
            ring.addLast(firm);
            inRing.add(firm);
        }

        DelayQueue<RetryTask> retries = new DelayQueue<>();
        BlockingQueue<AttemptResult> completions = new LinkedBlockingQueue<>();
        Set<String> claimed = ConcurrentHashMap.newKeySet();
        ExecutorService executor = Executors.newFixedThreadPool(settings.maxConcurrency());

        int inFlight = 0;
        try {
            while (remaining > 0) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }

                drainDueRetries(retries, queues, ring, inRing);

                while (inFlight < settings.maxConcurrency()) {
                    Attempt next = pollNext(ring, queues, inRing);
                    if (next == null) {
                        break;
                    }
                    inFlight++;
                    submitAttempt(change, next, completions, claimed, executor);
                }

                if (inFlight == 0) {
                    if (retries.isEmpty()) {
                        LOG.error("fanout.stalled changeId={} remaining={}", change.changeId(), remaining);
                        break;
                    }
                    RetryTask due = retries.poll(50, TimeUnit.MILLISECONDS);
                    if (due != null) {
                        enqueueRetry(due, queues, ring, inRing);
                    }
                    continue;
                }

                AttemptResult result = completions.poll(50, TimeUnit.MILLISECONDS);
                if (result != null) {
                    inFlight--;
                    if (result.nextAttempt() == null) {
                        remaining--;
                    } else {
                        retries.add(new RetryTask(result.nextAttempt(), result.readyAtNanos()));
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
            releaseAllClaimed(change.changeId(), claimed);
        }
    }

    private void submitAttempt(
            HeadChange change,
            Attempt attempt,
            BlockingQueue<AttemptResult> completions,
            Set<String> claimed,
            ExecutorService executor) {
        executor.submit(() -> {
            AttemptResult result;
            try {
                result = executeAttempt(change, attempt, claimed);
            } catch (RuntimeException e) {
                releaseClaim(change.changeId(), attempt.engagement().engagementId(), claimed);
                publishDeadLetter(change, attempt.engagement(), "UNEXPECTED", safeMessage(e));
                result = AttemptResult.terminal();
            }
            completions.add(result);
        });
    }

    private AttemptResult executeAttempt(HeadChange change, Attempt attempt, Set<String> claimed) {
        EngagementRef engagement = attempt.engagement();
        String engagementId = engagement.engagementId();
        boolean acquired = false;

        try {
            WorkLeasePort.LeaseResult lease = leases.tryAcquire(
                    change.changeId(), engagementId, settings.leaseDuration());
            if (lease != WorkLeasePort.LeaseResult.ACQUIRED) {
                metrics.recordSkippedClaimed();
                return AttemptResult.terminal();
            }

            acquired = true;
            claimed.add(engagementId);
            metrics.recordProbeStarted();

            TemplateState verified = probes.verify(engagement, attempt.indexedState(), change.headVersion());
            metrics.recordProbeSucceeded();

            index.recordVerified(engagement, verified);
            evaluateAndWrite(change, engagement, verified);

            leases.markCompleted(change.changeId(), engagementId);
            claimed.remove(engagementId);
            metrics.recordCompleted();
            return AttemptResult.terminal();
        } catch (ProbeException e) {
            if (acquired) {
                releaseClaim(change.changeId(), engagementId, claimed);
            }
            return handleProbeFailure(change, attempt, e);
        } catch (RuntimeException e) {
            if (acquired) {
                releaseClaim(change.changeId(), engagementId, claimed);
            }
            return handleRuntimeFailure(change, attempt, e);
        }
    }

    private AttemptResult handleProbeFailure(HeadChange change, Attempt attempt, ProbeException failure) {
        EngagementRef engagement = attempt.engagement();

        if (failure.kind() == ProbeException.Kind.PERMANENT) {
            publishDeadLetter(change, engagement, "PERMANENT", safeMessage(failure));
            return AttemptResult.terminal();
        }

        if (failure.kind() == ProbeException.Kind.TRANSIENT) {
            int failures = attempt.failures() + 1;
            if (failures >= settings.maxAttempts()) {
                publishDeadLetter(
                        change,
                        engagement,
                        "TRANSIENT_EXHAUSTED",
                        safeMessage(failure) + " after " + failures + " attempts");
                return AttemptResult.terminal();
            }
            return scheduleRetry(change, attempt, failures, attempt.throttles(), "TRANSIENT", failures, failure);
        }

        int throttles = attempt.throttles() + 1;
        if (throttles >= settings.maxThrottleAttempts()) {
            publishDeadLetter(
                    change,
                    engagement,
                    "THROTTLED_EXHAUSTED",
                    safeMessage(failure) + " after " + throttles + " throttles");
            return AttemptResult.terminal();
        }
        return scheduleRetry(change, attempt, attempt.failures(), throttles, "THROTTLED", throttles, failure);
    }

    private AttemptResult handleRuntimeFailure(HeadChange change, Attempt attempt, RuntimeException failure) {
        int failures = attempt.failures() + 1;
        if (failures >= settings.maxAttempts()) {
            publishDeadLetter(
                    change,
                    attempt.engagement(),
                    "TRANSIENT_EXHAUSTED",
                    "runtime: " + safeMessage(failure) + " after " + failures + " attempts");
            return AttemptResult.terminal();
        }
        return scheduleRetry(change, attempt, failures, attempt.throttles(), "TRANSIENT", failures, failure);
    }

    private AttemptResult scheduleRetry(
            HeadChange change,
            Attempt attempt,
            int failures,
            int throttles,
            String kind,
            int attemptNumber,
            Throwable failure) {
        Duration delay = backoff(attemptNumber);
        long readyAtNanos = System.nanoTime() + delay.toNanos();
        metrics.recordRetried();
        LOG.info(
                "fanout.retry changeId={} firmId={} engagementId={} kind={} attempt={} delayMs={} reason={}",
                change.changeId(),
                attempt.engagement().firmId(),
                attempt.engagement().engagementId(),
                kind,
                attemptNumber,
                delay.toMillis(),
                safeMessage(failure));

        Attempt next = new Attempt(attempt.engagement(), attempt.indexedState(), failures, throttles);
        return new AttemptResult(next, readyAtNanos);
    }

    private Duration backoff(int attemptNumber) {
        long base = settings.baseBackoff().toMillis();
        long max = settings.maxBackoff().toMillis();
        long cap = Math.min(max, base << Math.min(attemptNumber, 20));
        if (cap <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(cap + 1));
    }

    private void publishDeadLetter(
            HeadChange change, EngagementRef engagement, String failureCode, String safeMessage) {
        metrics.recordDeadLettered();
        LOG.warn(
                "fanout.dead_letter changeId={} firmId={} engagementId={} failureCode={} safeMessage={}",
                change.changeId(),
                engagement.firmId(),
                engagement.engagementId(),
                failureCode,
                safeMessage);
        try {
            deadLetters.publish(change, engagement, failureCode, safeMessage);
        } catch (RuntimeException e) {
            LOG.error(
                    "fanout.dead_letter.publish_failed changeId={} firmId={} engagementId={} error={}",
                    change.changeId(),
                    engagement.firmId(),
                    engagement.engagementId(),
                    e.toString());
        }
    }

    // ---------------------------------------------------------------- scheduling helpers

    private void drainDueRetries(
            DelayQueue<RetryTask> retries,
            Map<String, Deque<Attempt>> queues,
            ArrayDeque<String> ring,
            Set<String> inRing) {
        RetryTask task;
        while ((task = retries.poll()) != null) {
            enqueueRetry(task, queues, ring, inRing);
        }
    }

    private void enqueueRetry(
            RetryTask task,
            Map<String, Deque<Attempt>> queues,
            ArrayDeque<String> ring,
            Set<String> inRing) {
        Attempt attempt = task.attempt();
        String firm = attempt.engagement().firmId();
        Deque<Attempt> queue = queues.computeIfAbsent(firm, ignored -> new ArrayDeque<>());
        queue.addLast(attempt);
        if (inRing.add(firm)) {
            ring.addLast(firm);
        }
    }

    private Attempt pollNext(
            ArrayDeque<String> ring,
            Map<String, Deque<Attempt>> queues,
            Set<String> inRing) {
        while (!ring.isEmpty()) {
            String firm = ring.pollFirst();
            inRing.remove(firm);

            Deque<Attempt> queue = queues.get(firm);
            if (queue == null || queue.isEmpty()) {
                queues.remove(firm);
                continue;
            }

            Attempt next = queue.pollFirst();
            if (queue.isEmpty()) {
                queues.remove(firm);
            } else {
                ring.addLast(firm);
                inRing.add(firm);
            }
            return next;
        }
        return null;
    }

    // ---------------------------------------------------------------- lease helpers

    private void releaseAllClaimed(String changeId, Set<String> claimed) {
        for (String engagementId : new ArrayList<>(claimed)) {
            releaseClaim(changeId, engagementId, claimed);
        }
    }

    private void releaseClaim(String changeId, String engagementId, Set<String> claimed) {
        if (!claimed.remove(engagementId)) {
            return;
        }
        try {
            leases.releaseForRetry(changeId, engagementId);
        } catch (RuntimeException e) {
            LOG.warn(
                    "fanout.lease.release_failed changeId={} engagementId={} error={}",
                    changeId,
                    engagementId,
                    e.toString());
        }
    }

    private static String safeMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private record Attempt(
            EngagementRef engagement,
            TemplateState indexedState,
            int failures,
            int throttles) {
    }

    private record AttemptResult(Attempt nextAttempt, long readyAtNanos) {
        static AttemptResult terminal() {
            return new AttemptResult(null, 0);
        }
    }

    private record RetryTask(Attempt attempt, long readyAtNanos) implements Delayed {
        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(readyAtNanos - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(readyAtNanos, ((RetryTask) other).readyAtNanos);
        }
    }
}
