# Template Update Awareness: Design Document

**Scope:** show firms, in near real time, which engagement files have a pending template update and a human-readable summary of what changes. Applying the update is out of scope.

## 0. Decisions in brief
1. **Never read engagements to answer "what is pending".** The ~1 min rehydration is a hard constraint. Build a small, region-local **Engagement Template Index** (engagement → templateId, applied version, declined versions, trust) fed by events, and evaluate pending-ness against a global **Release Catalog**. The slow load is used only for backfill and drift audits.
2. **"Pending" is a derived, recomputable function**, not accumulated state, so lost or duplicated events heal.
3. **Summaries are per (fromVersion → toVersion) pair, not per engagement.** Template content is not firm-specific, so one summary serves every firm. This is what makes LLM cost negligible.
4. **Two planes:** a global plane that only ever sees template data, and regional cells (US / EU / CA) that hold everything engagement-derived.

## 1. Architecture and data ownership
See `diagrams/architecture.mmd`.

| Data | Owner / system of record | Where | Notes |
|---|---|---|---|
| Template versions | Content platform (existing) | Global | Adds a transactional **outbox**: `version.changed` events (published / withdrawn, parents, market, `seq` monotonic per templateId) |
| Release Catalog (version DAG, status, offerable **head** per templateId) | New feature | Global, replicated read-only to cells | Thousands of rows; non-confidential |
| Summaries + diffs | New feature | Global (S3 Object Lock + DynamoDB) | Immutable, see §4 |
| Engagement → template state, decisions | **Engagement system stays the source of truth** (it processes accept/decline) | Regional | Emits state-carrying events via outbox: `Created`, `Applied`, `Declined`, `Opened` |
| Engagement Template Index + Pending view | New feature, **derived cache** | Regional (DynamoDB) | Rebuildable; never authoritative |

**Version semantics (what "declined" pins).**
* The **baseline is always the applied version**. A decline never moves it.
* A decline records `(engagement, templateId, targetVersion, summaryId, user, ts)` and suppresses re-offering of **exactly that version**, not "everything up to it".
* `pending(e) = head ≠ null ∧ head ≠ applied ∧ head ∉ declined(e) ∧ head ∉ ancestors(applied) ∧ applied ∈ ancestors(head)`.
* Accumulation: several updates (v5, v6, v7) are never a queue of decisions. The user sees **one** cumulative diff `applied → head`.
* Declined v5, later offered v7: v7 is pending; the summary notes "includes N changes you previously declined in v5" (intersection of the two diffs' paths).
* Withdrawal: head recomputed. If the new head is v5 and it was declined, nothing is pending. If the head is withdrawn after a user *applied* it, an advisory is raised (handling is out of scope).
* Branches: a version is offered only if the applied version is its ancestor in the DAG. Cross-branch moves are not auto-offered and are flagged to content teams.
* Ordering: `Declined` for target v5 racing with a new publish is safe, because the decision call carries `(targetVersion, summaryId)` and the engagement system rejects it if the head moved (optimistic precondition). The user re-decides on the newer summary.

**Read path never lies.** Each Pending row stores `evaluatedAtSeq`. If it is behind the catalog's `seq` for that template, the API evaluates on the fly from the index row. Materialization is for fast listing/sorting; correctness does not depend on the fan-out having finished.

**Security.** Existing IdP; JWT `firmId` claim scopes every query (partition key = firm). KMS CMKs per region, TLS/mTLS everywhere, least-privilege roles per service, secrets in Secrets Manager. Events and the index carry IDs and versions only, never workpaper content. Decisions are audit-logged.

## 2. Correctness and production evolution
**Unreliable delivery.** Everything is at-least-once and unordered by assumption.
* Template events: catalog applies only if `seq > stored`. A **reconciler** pulls the full catalog snapshot (tiny) every 5 min and diffs it, so a missed event costs at most 5 min.
* Engagement events are **state-carrying** (full `TemplateState` + per-engagement revision), applied only if newer. Duplicates and reorderings are harmless. *Assumption: the engagement system can supply a monotonic revision.*
* The `Opened` hook gives free re-verification every time an engagement is loaded anyway.
* **Drift auditor:** probes a random 0.1%/day sample (800 loads ≈ 13 pod-hours/day) plus firms with recent anomalies, and compares with the index. Drift > 0.01% pages someone.
* Fan-out (Part 2) is idempotent: lease ledger + writes fenced by `catalogSeq`; DLQ items are retried by a sweeper.

**Migration without a maintenance window (everything is additive; rollback = flag off):**
1. Ship outbox + event hooks dark in both existing systems (additive tables only).
2. Consumers build the index for new/changed/opened engagements (`VERIFIED`).
3. **Backfill** (below), prioritized by recent activity.
4. **Shadow mode:** compute pending, show to nobody, compare with sampled probes.
5. Canary behind a flag: internal firm → small firms → whale firm → everyone, per region.

**Backfill arithmetic.** 800,000 × 60 s = 48 M s = **13,333 pod-hours**. At 50 concurrent loads: 267 h ≈ **11 days**; at 200: 67 h ≈ **2.8 days**. Priority: (a) engagements opened in the last 90 days fill themselves via `Opened`, (b) active-season firms, (c) the dormant tail. Unverified rows show "checking" instead of a wrong answer. One-time downstream cost ≈ 13.3 k pod-h × $0.05–0.20 = **$0.7–2.7 k**, which the owning team must budget.
**Highest-leverage spike:** ask the engagement team whether template ID/version/decision history can be read from the stored manifest or a decision log *without* rehydration. If yes, backfill drops from days to minutes.

**Why not just probe on every publish?** 20 k engagements/product × 60 s = 333 pod-hours **per publish**, 13.3 k pod-hours **per week**: the entire backfill, every week, with minutes-long staleness. Rejected.

**Failure recovery.** The index is derived but expensive to rebuild (days), so DynamoDB **PITR** + daily export. DLQ replay, outbox replay, and idempotent consumers cover the rest. No cross-region failover (residency forbids it); Multi-AZ inside the region.

## 3. Scale, cost, operations (arithmetic)
Assumptions: 20 users/firm, 20 listing views/user/day, 5 engagement events/engagement/month.

| Item | Arithmetic | $/month |
|---|---|---|
| Fan-out writes | 40 pubs/wk × avg 20 k rows = 800 k/wk ≈ 3.5 M/mo × $1.25/M, ×3 for withdrawals/branches | ~13 |
| Engagement events | 800 k × 5 = 4 M/mo (SQS + consumers) | <10 |
| Index storage (+PITR) | 800 k × ~0.5 KB = 400 MB | <1 |
| Reads | 4,000 × 20 × 20 = 1.6 M views/day = 48 M/mo × ~3.5 RRU (eventually consistent) | ~20–50 |
| Compute | 3 cells × 4 Fargate tasks (0.5 vCPU / 1 GB ≈ $18) | ~220 |
| Observability | logs, metrics, traces | ~100 |
| LLM summaries | 173 updates/mo × ~5 distinct in-use `from` versions ≈ 870 summaries × ~$0.08 (16k in + 1.2k out + 4k validator in) | ~70 (worst case ×10 = 700) |
| **Total** | | **≈ $0.5 k typical, ≈ $1.1 k with 10× LLM stress** |

Per-engagement summarization would be ~20,000× more calls; it is rejected because content is not firm-specific.

**Skew.** The 40,000-row firm is 5% of the fleet. A single DynamoDB partition key tops out at ~1,000 WCU, so 40 k writes would take ≥40 s. Fix: firm key sharded by `hash(engagementId) mod N` with N sized per firm (1 for ≤1,000 rows, up to 16 for the whale), scatter-gather for listing. 40 k writes ≈ 2.5 s. Fan-out dispatch is per-firm round-robin so the whale cannot starve small firms.

**SLOs / SLIs.**
* Publish → indicator visible: **p95 ≤ 5 s, p99 ≤ 30 s** for VERIFIED engagements.
* Deterministic change list available at indicator time: 100%. LLM prose: p95 ≤ 2 min.
* Index coverage: ≥99% of engagements active in the last 90 days `VERIFIED` within 14 days of launch.
* Correctness: audit drift < 0.1% (alert at 0.01%).
* Alerts: outbox lag, consumer age, DLQ depth, downstream throttle rate, reconciler diffs, summary validation-failure rate. One trace ID from publish → fan-out → API.

**Data residency.** The global plane holds template data only (catalog, summaries, LLM calls on template diffs via Bedrock, never any engagement content). Template events are broadcast to cells; **no engagement-derived data (not even firm or engagement IDs) leaves its region**. Global dashboards get region-level aggregates only. Decision logs, index and pending view live in the region's cell.

## 4. Human-readable summaries and regulatory defensibility
**Pipeline.** JSON diff → deterministic **normalizer** (maps paths to template display names, classifies add/remove/modify and severity) → **structured change list** (always available; this is the legal-grade artifact) → LLM writes plain language **only from that list**, citing change IDs → **validator**: every citation exists, every number/identifier in the prose appears in the change list, counts match, nothing uncited. On failure: one retry at temperature 0, then fall back to a deterministic rendering ("12 changes: 3 procedures added…"). Oversized diffs are chunked by template section (map-reduce). Template text is treated as untrusted data: delimited, no tools, schema-constrained output.

**Defensibility (March → November).** LLM output is not reproducible, so we **store, never regenerate**:
* Immutable record: `summaryId, templateId, from, to, sha256(canonical diff), pinned model snapshot ID (not an alias), prompt hash, params, output text, validator report, generatedAt`.
* Diff and summary in S3 Object Lock (compliance mode; retention per regulator, assume 7 y). A correction is a new record that supersedes; the old one stays.
* Every decision stores `summaryId` + content hash: "what exactly did this user see" is answerable from the record alone.
* UI always links the full structured change list beside the prose, labelled AI-generated.

**Evaluation.**
* Golden set of ~200 historical diffs with must-mention and must-not-claim labels. Metrics: material-change recall, faithfulness (validator pass rate), readability. CI gate on any prompt/model change, plus shadow comparison on the last 50 real diffs.
* Content-team review of all high-severity changes (calculations, procedures) and a 10% sample. User thumbs-down feeds back into the golden set.

## 5. Tradeoffs, assumptions, and the requirement I would challenge
**Challenge: "real time within seconds of publication."** For the indicator it is cheap here, because it is derived from one catalog write. For the LLM summary and for unverified engagements it is neither achievable nor valuable: updates come weekly and a human decides over days. I would define it as **indicator p99 ≤ 30 s, summary ≤ 2 min, unverified rows labelled**, and spend the saved complexity on correctness. I would also push for the actual **$X**; the design lands near $0.5 k and compute is the lever if X is lower.

**Key assumptions (validate first):** engagement system can emit events with a monotonic revision; the downstream owner can commit a concurrency budget (assumed 50–200 loads); template IDs map to product×market lineages; one language per market.

**Riskiest to get wrong: trust in the index.** The system of record can only be read by 1-minute loads, so the index is an inference. Silent drift means a firm misses an update, a compliance problem rather than a UI glitch. Mitigations: state-carrying events, `Opened` re-verification, audit probes, "checking" state, shadow mode. Second riskiest: version-DAG semantics ("contains v5's changes"); they need explicit contract tests with content teams.

**Deliberately left out:** applying updates; UI and notifications; firm-level auto-apply policies; engagement deletion/archival tombstones (hook exists, handling deferred); multi-language summaries; cross-region DR; fine-grained per-user authorization beyond firm scoping.
