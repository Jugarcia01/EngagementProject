# Template head-change fan-out worker (Part 2)

Java 21, zero dependencies. `bash run-tests.sh` compiles and runs 10 tests (concurrency, idempotency, retries, fairness, etc.).

## What it does
On "head of template T is now vN (seq S)" (publish **or** withdrawal), bring every engagement's pending-update marker up to date in one region.

* **Phase 1 (seconds):** page the regional index per firm. Rows the index can vouch for (`VERIFIED`) are evaluated locally (`PendingEvaluator`) and batch-written. No downstream call.
* **Phase 2 (capacity-bound):** only rows the index cannot vouch for need the ~60 s downstream probe. Those are dispatched round-robin across firms, under an AIMD concurrency limiter, with off-thread retries. The probe result is written back to the index, so this phase doubles as backfill.

Files: `FanOutWorker` (orchestration), `AdaptiveLimiter`, `PendingEvaluator` (decline/branch/withdrawal semantics, pure), `Ports` (all external contracts), `FanOutConfig`, `RunReport`.

## Key tradeoffs
1. **Probe only when necessary.** The downstream call is the scarcest resource (20k engagements x 60 s = 333 pod-hours per publish if probed naively). The index absorbs the steady state; the probe is for unverified rows. Cost: correctness of the fast path depends on index trust, handled by the design's drift audit.
2. **Concurrency, not rate, is the knob.** At 60 s latency, throughput = concurrency / 60 (Little's law). AIMD (+1 per window of successes, /2 on throttle, at most once per cooldown) finds the capacity the other team can actually give instead of us guessing a static number. Cost: slow ramp-up (+1 per ~limit calls, minutes at this latency); a static `initial` close to the agreed budget mitigates it. Budget is **per pod** (agreed total / pods); a central token service would be the next step if the other team wants a hard global cap.
3. **Fairness by construction.** Per-firm queues served round-robin, so a 40,000-engagement firm cannot starve a 40-engagement one (tested). Not weighted by firm tier; deliberate simplicity.
4. **Retries never hold a permit.** Failed items go to a `DelayQueue` (full-jitter backoff, honoring `Retry-After`), so a sleeping retry does not eat scarce downstream capacity. Throttles have their own, larger budget (not the engagement's fault) and trigger a global pause. Permanent errors go straight to the DLQ, never retried.
5. **Idempotency in two layers.** (a) Lease ledger keyed `changeId|engagementId` stops duplicate deliveries and concurrent workers from double-probing (tested: two workers, same event, each engagement probed exactly once). (b) Writes are fenced by `catalogSeq`, so a late or duplicate worker can never overwrite newer state. Result: at-least-once execution, effectively-once outcome. The probe is read-only by contract, so a re-probe after a crash is harmless, just wasteful.
6. **Supersession is a cost optimization, not a correctness mechanism.** Skipping work for a change already overtaken saves downstream capacity; if the check is wrong or late, fencing still protects the data.
7. **State-carrying event (`headVersion`), not a delta.** Publish, withdrawal and branch change are one code path; reordering/duplication cannot corrupt anything.

## Known limitations (deliberately left out)
* Phase 1 is single-threaded and a store failure there fails the run loudly; the orchestrator (SQS redelivery) re-invokes and idempotency makes that safe. Parallelizing per firm is trivial if 800 batch calls per publish ever matters.
* The slow-row queue is in memory (~100 B/row, fine for a cell); a crash loses it, but re-running rebuilds it and the ledger skips finished work.
* In-flight probes at crash time are repeated (lease must exceed `probeTimeout`).
* No real metrics/tracing wiring; `RunReport` marks where counters/spans go. No sweeper for DLQ items (described in the design doc).
* Fairness and limits are per pod, not global.

## AI usage for this project
This code and the design document were drafted with an AI assistants and then compiled and tested.
This will be used as a template for the project, so it will be modified to fit to the project changes.
* **Where AI helped:** _e.g. scaffolding ports/records, test fakes, enumerating failure modes._
* **What I corrected or rejected:** _e.g. one real defect found during the session: the first test for supersession asserted a counter that was never incremented, because the run aborts before the slow phase; the test, not the worker, was wrong. List others you find on your own review._
* **Where AI should not be trusted here:** concurrency semantics, lease/fencing invariants, `nanoTime` arithmetic, cost figures (all numbers in the design are stated assumptions, not measurements), and version-graph semantics ("contains v5's changes"), which must be confirmed with the content teams.
* **Guidance for other engineers:** require tests for every concurrency claim, treat AI-written cost numbers as hypotheses to measure, and never let generated text be the only record of a compliance-relevant decision.
