# Template-Publish Fan-Out Worker

A focused Java 21 / Spring Boot implementation of the template-publish fan-out worker described in the Engagement Project take-home exercise.

The worker processes a state-carrying template-head change and updates pending-update markers for engagements found in a regional index. It demonstrates a hexagonal architecture, a fast path for already-verified index entries, a capacity-limited verification path for unverified entries, retry scheduling, work leases, and conditional writes.

> **Scope and implementation status:** This repository is a targeted implementation, not a production-ready service. The `local` and `dev` profiles use in-memory or simulated adapters. This README documents the code that exists today; capabilities described in the broader design but not implemented by this worker are called out under [Known limitations](#known-limitations).

## Contents

- [Scope](#scope)
- [Architecture](#architecture)
- [Processing flow](#processing-flow)
- [Implementation trade-offs](#implementation-trade-offs)
- [Known limitations](#known-limitations)
- [Technology and prerequisites](#technology-and-prerequisites)
- [Run locally](#run-locally)
- [Configuration](#configuration)
- [Tests and coverage](#tests-and-coverage)
- [Project layout](#project-layout)
- [Design references](#design-references)
- [AI Usage](#ai-usage)

## Scope

The entry point is the `ProcessHeadChangeUseCase` contract. Its implementation, `ProcessHeadChangeService`, accepts a `HeadChange` containing:

- `changeId`: identifier used to associate the work and lease with this change.
- `templateId`: template whose head changed.
- `headVersion`: the current head version; `null` represents no currently published head, such as after a withdrawal.
- `catalogSeq`: monotonic ordering/fencing sequence supplied by the caller for the template.

For a given change, the service reads engagement rows from `EngagementIndexPort` and updates the materialized pending marker through `PendingMarkerPort`. It does not apply template updates to engagement content.

## Architecture

The code follows a ports-and-adapters (hexagonal) structure:

- **Domain** (`domain/model`): immutable records and validation for `EngagementRef`, `HeadChange`, `PendingMarker`, and `TemplateState`.
- **Inbound application port** (`application/port/in`): `ProcessHeadChangeUseCase`, the use-case contract.
- **Application service** (`application/service`): `ProcessHeadChangeService`, `PendingEvaluator`, `Settings`, and `FanOutPorts`. The service coordinates the work and depends on outbound interfaces rather than concrete storage implementations.
- **Outbound ports** (`application/port/out`): contracts for the engagement index, pending markers, leases, state probes, release catalog, dead-letter handling, and metrics.
- **Adapters** (`adapter`): local in-memory/simulated adapters, a local smoke runner, and an SLF4J-backed metrics adapter.
- **Configuration** (`config`): Spring bean wiring and profile-specific `fanout.*` properties.

### Ports and local adapters

| Port / responsibility | Current adapter | Behavior and limitation |
|---|---|---|
| `EngagementIndexPort` | `InMemoryEngagementIndex` | In-memory engagement rows and paged reads; no durable index. |
| `PendingMarkerPort` | `InMemoryPendingMarkerStore` | In-memory marker storage with sequence fences; data is lost on process restart. |
| `WorkLeasePort` | `InMemoryWorkLease` | In-memory lease expirations and completed-work tracking; not shared between processes. |
| `EngagementStateProbePort` | `SimulatedProbe` | Simulates latency and configured transient, permanent, or throttled failures; it does not load a real engagement. |
| `ReleaseCatalogPort` | `LocalReleaseCatalog` | Local catalog/version relationships and summary lookup for the exercise; not a connection to a production catalog. |
| `DeadLetterPort` | `LoggingDeadLetterAdapter` | Logs failures and retains a bounded in-memory list (up to 1,000 entries); it is not a durable or replayable DLQ. |
| `FanOutMetricsPort` | `Slf4jFanOutMetricsAdapter` | Emits one structured log event per metric increment; it is not a metrics backend or distributed tracing integration. |

The local adapters are registered only for the `local` and `dev` Spring profiles. The current repository does not provide production database, queue, catalog, or downstream-service adapters.

## Processing flow

`ProcessHeadChangeService.process(HeadChange)` runs two phases.

### Phase 1 — Fast path for verified rows

1. Find firms with engagements for the changed template.
2. Read each firm's matching engagements in pages using `EngagementIndexPort`.
3. For rows marked `VERIFIED`, evaluate pending status locally through `PendingEvaluator` and the release-catalog contract.
4. Upsert or delete the pending marker using a sequence-fenced `PendingMarkerPort` operation.
5. Collect `UNVERIFIED` rows for the slow path.

**Important:** the current fast path writes markers one row at a time. It does not perform batch writes and does not memoize catalog lookups within a run.

### Phase 2 — Capacity-limited path for unverified rows

1. Queue unverified rows by firm.
2. Dispatch work through a fixed-size thread pool bounded by `fanout.max-concurrency`.
3. Use per-work leases keyed by `changeId` and engagement ID to avoid duplicate work while a lease is active and to skip work already marked completed.
4. Schedule retryable failures outside the worker threads using delayed retry tasks and randomized exponential backoff.
5. Serve per-firm queues in round-robin order to reduce the risk that a large firm's queue starves smaller firms.
6. On successful verification, update the index state, evaluate the pending marker, and mark the work complete.
7. Send permanent failures and exhausted retries to the configured dead-letter port.

The current implementation uses a **fixed concurrency limit per process**. It does not enforce a global downstream budget. Probe calls do not have a configured timeout. It does not implement adaptive concurrency, honor `Retry-After`, or skip work solely because a newer template head has superseded the change.

### Simplified workflow

```text
HeadChange
    |
    v
Page index by firm and template
    |
    +--> VERIFIED --> Evaluate pending state --> Sequence-fenced marker write
    |
    +--> UNVERIFIED --> Per-firm queues --> Fixed-size worker pool
                                          |
                                          v
                                     Acquire lease
                                          |
                                          v
                                     Probe adapter
                                      /    |     \
                               success   retry   permanent/exhausted
                                  |        |              |
                                  v        v              v
                            Update index  Delayed      Dead-letter
                            and marker    retry         adapter
                                  |
                                  v
                            Mark completed
```

This workflow describes the current worker, not the full end-to-end architecture proposed in `DESIGN.md`.

## Implementation trade-offs

1. **Use verified index data before probing.** Rows marked `VERIFIED` can be evaluated without calling the slow verification port. This reduces downstream work, but correctness depends on the trustworthiness and freshness of the index.
2. **Bound concurrency rather than using an unbounded pool.** The slow path uses a fixed-size executor. This makes the local concurrency cap explicit, but the cap is per process and does not enforce a global downstream budget.
3. **Keep retries out of worker threads.** Retry tasks are delayed and re-enqueued after backoff, so a sleeping retry does not occupy a worker thread. Backoff is randomized and capped by `max-backoff`.
4. **Use leases and sequence-fenced marker writes.** Leases coordinate duplicate attempts within the lease adapter's scope. Marker writes reject updates whose `catalogSeq` is not newer than the stored fence. In this implementation, lease state is in memory and the fence is based on catalog sequence only.
5. **Schedule firms round-robin.** The slow path rotates among firm queues rather than exhausting one firm's queue before moving to the next. This is a per-process scheduling policy, not a global fairness guarantee.
6. **Keep external dependencies behind ports.** The application service can be tested with fakes and can be wired to real adapters later. Real persistence, queue integration, distributed coordination, and operational recovery are outside the current implementation.

## Known limitations

The following are limitations of the current code and must not be interpreted as implemented capabilities:

- **No probe timeout:** a blocked probe can occupy a worker thread indefinitely. Lease expiry can permit another attempt while the original call is still running.
- **Fixed concurrency and basic throttling retries:** concurrency is not adaptive, `Retry-After` is not honored, and throttling does not trigger a shared/global pause.
- **No supersession check:** the worker does not skip work when a newer head has superseded the change; sequence-fenced writes protect against older sequence values, but do not eliminate the wasted probe work.
- **No explicit processing result:** `process()` returns `void`. If interrupted or stalled, callers do not receive a structured summary of completed, skipped, dead-lettered, and incomplete work.
- **No durable DLQ or sweeper:** the local dead-letter adapter logs and retains a bounded in-memory list only. It does not persist or replay failed work, and the recovery sweeper described in the design is not implemented here.
- **No batch writes or per-run catalog cache:** the fast path writes one marker at a time and may perform repeated catalog lookups.
- **Summary ID fallback:** if the catalog does not resolve a summary ID, the service currently synthesizes an ID from the version pair. That ID is not proof that a persisted summary exists and does not meet the audit guarantee described in the design.
- **Catalog sequence is the only marker fence:** `TemplateState.revision` is present, but it is not part of the pending-marker write condition. Re-evaluating the same catalog sequence after an engagement decision can therefore be rejected as not newer.
- **Local state is ephemeral:** all data held by the supplied local adapters is lost on restart and is not shared across application instances.
- **No production integrations:** the repository does not include real AWS/DynamoDB, SQS/EventBridge, template-catalog, or engagement-management adapters.
- **No production telemetry backend:** the metrics port currently emits SLF4J log events; there is no metrics exporter or tracing pipeline wired in.
- **No handling of `Error` thrown by a probe task:** the dispatcher waits for a result from every submitted task. If a probe throws a `java.lang.Error` (as opposed to a `RuntimeException`), no result is published and `process()` does not return.
- **Interruption/stall behavior is limited:** the service may return early on interruption, and a stalled dispatch is logged rather than returned as an explicit incomplete result.

These limitations are intentionally listed so that the implementation can be reviewed against the design without overstating what the code currently guarantees.

## Technology and prerequisites

- Java 21
- Maven Wrapper (`mvnw` / `mvnw.cmd`)
- Spring Boot 3.5.16
- JUnit and Spring Boot Test (test scope)
- JaCoCo 0.8.13 for line coverage reporting and enforcement

No external database, queue, or cloud account is required to run the supplied `local` or `dev` profiles.

## Run locally

Run commands from the repository root (`EngagementProject/`). The wrapper may not have its executable bit set in every checkout, so the POSIX commands below use `sh ./mvnw`.

### Run tests

```bash
sh ./mvnw test
```

### Verify tests and the JaCoCo threshold

```bash
sh ./mvnw verify
```

The Maven `verify` lifecycle generates the JaCoCo report under `target/site/jacoco/` and enforces a **minimum 90% line coverage ratio at bundle level**. A build that falls below the configured threshold fails the JaCoCo check.

### Start with the `local` profile

```bash
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

The `local` profile uses in-memory adapters, seeds a small synthetic engagement set, and runs a one-shot smoke change on startup by default.

### Start with the `dev` profile

```bash
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

The `dev` profile uses the same in-memory/simulated adapter implementations as `local`, with a larger seed and different tuning values. It is not a deployment profile with real infrastructure.

### Windows

Use the Maven Wrapper batch file:

```bat
mvnw.cmd test
mvnw.cmd verify
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=dev
```

## Configuration

The common properties are bound under `fanout` through `FanOutProperties` and converted to `Settings`.

| Property | Value in `application-local.yaml` | Value in `application-dev.yaml` | Meaning |
|---|---:|---:|---|
| `fanout.max-concurrency` | `4` | `8` | Maximum concurrent slow-path probe tasks in this process. |
| `fanout.max-attempts` | `4` | `4` | Maximum transient-failure attempt budget used by the worker. |
| `fanout.max-throttle-attempts` | `10` | `10` | Maximum throttling retry budget. |
| `fanout.base-backoff` | `25ms` | `100ms` | Base for randomized exponential retry delay. |
| `fanout.max-backoff` | `250ms` | `1s` | Upper bound for randomized retry delay. |
| `fanout.lease-duration` | `2m` | `5m` | Duration for which a claimed work lease remains active. |
| `fanout.page-size` | `50` | `100` | Number of index rows requested per page. |

Local simulation settings are under `fanout.local`:

- `fanout.local.seed.enabled`: whether to seed synthetic index rows.
- `fanout.local.seed.firms`: firm IDs to seed.
- `fanout.local.seed.engagements-per-firm`: number of synthetic engagements per firm.
- `fanout.local.seed.template-id`: template ID assigned to seeded rows.
- `fanout.local.seed.applied-version`: applied version assigned to seeded rows.
- `fanout.local.probe.latency`: simulated latency per probe.
- `fanout.local.probe.failure-mode`: `none`, `transient`, `permanent`, or `throttled`.
- `fanout.local.probe.failure-budget`: number of initial probes that fail using the configured failure mode.
- `fanout.local.smoke.enabled`: whether the startup smoke run is enabled.
- `fanout.local.smoke.change-id`: synthetic change ID.
- `fanout.local.smoke.head-version`: synthetic target head version.
- `fanout.local.smoke.catalog-seq`: sequence used by the synthetic change.

The checked-in `application.yaml` activates `local` by default. Use the explicit profile commands above to select either `local` or `dev`.

## Tests and coverage

The test suite covers domain validation, pending evaluation, configuration binding, profile wiring, local adapters, retry/failure handling, leases, marker fencing, and service behavior, including concurrency-related cases.

The checked-in build configuration uses JaCoCo 0.8.13. Its `verify`-phase check enforces **90% minimum line coverage for the bundle**.

## Project layout

```text
EngagementProject/
├── src/
│   ├── main/
│   │   ├── java/com/jgo/engagement/
│   │   │   ├── adapter/
│   │   │   │   ├── inbound/local/
│   │   │   │   ├── out/metrics/
│   │   │   │   └── outbound/local/
│   │   │   ├── application/
│   │   │   │   ├── port/in/
│   │   │   │   ├── port/out/
│   │   │   │   └── service/
│   │   │   ├── config/
│   │   │   └── domain/model/
│   │   └── resources/
│   │       ├── application.yaml
│   │       ├── application-local.yaml
│   │       ├── application-dev.yaml
│   │       └── docs/
│   │           ├── DESIGN.md
│   │           └── architecture.mmd
│   └── test/java/com/jgo/engagement/
├── README.md
├── pom.xml
├── mvnw
└── mvnw.cmd
```

The design document and architecture source are kept under `src/main/resources/docs/` in this repository.

## Design references

- [`DESIGN.md`](src/main/resources/docs/DESIGN.md) — broader architecture design, data ownership, correctness, scale/cost assumptions, summary generation, SLOs, residency, and trade-offs.
- [`architecture.mmd`](src/main/resources/docs/architecture.mmd) — Mermaid source for the proposed end-to-end architecture.
- [`architecture.png`](src/main/resources/docs/architecture.png) — exported image of that architecture.

The broader design describes components and operational mechanisms that are not all implemented in this Part 2 worker. In particular, the architecture diagram is a design-level view, not a diagram of the Spring beans or local adapters alone.

## AI Usage

### Where AI Helped

AI tools used on this project include Claude (initial design document and a first prototype of the worker) and the OpenHands SDK with a DeepSeek model. AI supported several stages of the Engagement Project, including requirements analysis, implementation planning, code development, testing, and documentation. It helped structure the work into manageable steps, identify areas requiring additional test coverage, investigate implementation issues, and improve the clarity of technical documentation.

A particularly useful application was the controlled development workflow using the OpenHands SDK with a DeepSeek model. This workflow separated project inspection and planning from patch generation and application. AI could analyze the available project context and propose changes without having unrestricted access to the execution environment or independently modifying the project during the planning and implementation stages.

AI also helped evaluate testing and quality requirements, including the JaCoCo line-coverage threshold of 90% at the bundle level. This support was useful for identifying potential gaps and organizing verification activities, but the resulting coverage reports and build outcomes remained the evidence needed to assess whether the requirements were actually met.

### Where Human Judgment Was Essential

Throughout the project, AI-generated suggestions and documentation were treated as proposals requiring review rather than authoritative decisions.

An important example was the distinction between requirements explicitly stated in the project specification and additional details appearing in the README or design documentation. The development process required checking whether statements were actual requirements, design intentions, or descriptions of functionality already implemented. This distinction helped keep the documentation aligned with the project's real scope rather than presenting every architectural idea as a completed capability. For example, an earlier README draft described adaptive concurrency, `Retry-After` handling, supersession checks, and batched writes that the implementation did not provide. When checked against the source code, those statements were corrected, and the items now appear under [Known limitations](#known-limitations).

The same principle applied to implementation and testing. The workflow used constrained patch generation, explicit approval before applying a reviewed patch, SHA-256 verification of the approved patch, and Git's patch applicability checks. These controls reduced the risk of unintended changes and preserved a review step before modifications were applied.

The project history also demonstrates a preference for incremental, evidence-based changes over broad, unverified modifications. These controls do not imply that every AI suggestion was accepted or that every suggestion was flawed. Their purpose was to make review, validation, and accountability explicit.

### Guidance for Engineers Using AI on This System

Engineers working on this system should use AI as a development assistant within a clearly defined engineering process:

1. **Provide authoritative context.** Supply the project specification, relevant source files, architecture documentation, current tests, and applicable quality requirements. Explicitly distinguish mandatory requirements from design proposals and optional improvements.
2. **Constrain the scope of each task.** Break work into reviewable steps and specify the files, interfaces, behavior, and acceptance criteria involved. Avoid asking AI to redesign unrelated components when addressing a localized issue.
3. **Separate analysis from implementation.** Review the proposed plan before requesting a patch. Inspect the resulting diff before applying it, and ensure that the change addresses the intended problem without introducing unrelated modifications.
4. **Verify changes independently.** Use code review, automated tests, Maven build and verification goals, JaCoCo reports, and Git checks as appropriate. Evaluate the actual results rather than relying on an AI-generated summary of those results.
5. **Keep documentation synchronized with the implementation.** Confirm that descriptions of adapters, profiles, persistence, integrations, security, and runtime behavior match the current code. Label planned or simulated functionality accurately.
6. **Retain human accountability.** The engineer remains responsible for accepting changes, understanding their consequences, evaluating trade-offs, and ensuring that the delivered system satisfies the requirements.

These practices make AI useful without allowing it to replace established engineering controls.

### Where AI Should Not Be Trusted Without Verification

AI output requires particular scrutiny in areas where plausible explanations can conceal incorrect assumptions or unsupported conclusions.

- **Requirements interpretation:** AI may confuse an example, design recommendation, or aspirational capability with a mandatory requirement. The project specification and explicit acceptance criteria must remain authoritative.
- **Architecture and domain behavior:** AI can propose reasonable-looking abstractions that do not fit the existing design, domain constraints, concurrency model, or component responsibilities. Architectural changes require engineering evaluation.
- **Code correctness and concurrency:** Generated code may compile while still containing logical errors, race conditions, incorrect state transitions, or inadequate failure handling. Review and appropriate tests are necessary.
- **Test coverage and quality claims:** AI must not be treated as proof that a target has been achieved. Coverage percentages, test outcomes, and build status must come from actual tool results. In particular, meeting a line-coverage threshold does not establish complete behavioral or branch coverage.
- **Security and configuration:** Authentication, authorization, secret management, profile activation, and environment-specific configuration require direct inspection and verification. Credentials must be handled securely, including through environment variables where appropriate, and must not be exposed in logs or documentation.
- **Implemented functionality and documentation:** AI may describe intended behavior as if it already exists. Claims about production integrations, persistence, observability, or other capabilities must be checked against the actual implementation and runtime configuration.
- **Tool execution and reported outcomes:** An AI-generated statement that a patch was applied, a test passed, or a build succeeded is not sufficient evidence by itself. The corresponding operation and its result must be independently observable.

### Responsibility and Lessons Learned

The principal lesson from this project is that AI contributes the most value when it operates within explicit constraints and a verifiable development workflow. Its strengths include accelerating analysis, organizing implementation work, proposing code changes, and helping identify potential gaps. Its limitations require engineers to validate assumptions, inspect changes, and distinguish generated explanations from demonstrated results.

The approach adopted for this project reflects an engineering principle: **AI can assist with producing and evaluating changes, but acceptance depends on requirements, code review, and reproducible verification.**

**The engineer remains accountable for the final implementation, the accuracy of the documentation, and the evidence supporting claims about software quality and behavior.**
