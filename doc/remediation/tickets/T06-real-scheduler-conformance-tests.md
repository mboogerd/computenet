# T06 — Real-scheduler & multi-threaded conformance tests

**Phase 1, step 3 of 3 · CONTINUE the T04/T05 session · Sonnet 5**
**Prereq**: T04 and T05 complete in this session.
**Write scope**: `kernel/src/test/kotlin` (new test files), and — expected —
follow-up fixes to code T04/T05 just touched when a test exposes a residual
defect. Fixes to *other* subsystems are out of scope: report them instead.

## Problem

Concurrency audit, finding 7: **the real schedulers are effectively
untested**, which is why every T04/T05 defect went unnoticed.

- 99 test/source files use `SimulationController`/`SimWorld` — single-threaded
  by design (`SimulationController.kt:27`).
- `VirtualThreadScheduler` appears in **zero** test files; it runs only
  implicitly via ~61 `ManagedHost(...)` constructions with no `scheduler`
  argument, none driven from more than one thread.
- `CoroutineScheduler` appears in exactly one 3-message smoke test
  (`kernel/src/test/.../host/CoroutineHostTest.kt:196`).
- No test drives a durable host from two threads; no test exercises the
  saturation announce under real concurrency; the wire smoke tests are the
  only wall-clock-concurrent tests in the repo.

The deterministic simulator is the project's genuine strength — this ticket
does not dilute it. It adds the *missing complement*: a small, targeted
real-thread conformance suite that would have caught T04's findings 1–6.

## Solution

New test files under `kernel/src/test/kotlin/civictech/cell/host/` (follow
existing naming/style; use `testkit`'s `awaitUntil` for bounded waits — never
bare sleeps; every assertion is a semantic outcome, not a timing).

### A. `VirtualThreadHostConformanceTest`

Real `VirtualThreadScheduler`, one host:

1. **Per-cell FIFO under contention**: N=8 sender threads × M=200 sequenced
   invocations each into one `ListCell`-style order-sensitive cell (tag each
   invocation with (thread, seq)); assert per-thread subsequences arrive in
   order.
2. **No lost messages / no lost announce**: saturate intake
   (`intakeBound` low) from multiple threads; assert every accepted
   invocation is eventually processed, every rejected one is accounted
   (dead letter or ack per T05), and the saturation announce reaches an
   upstream observer — no silent drop (T04 finding 3's race).
3. **Deadlock regression**: two hosts in one JVM, cross-linked both
   directions (mirror the exchange topology), concurrent traffic both ways
   with small intake bounds — assert quiescence within a bounded wait. Before
   T04 this configuration could ABBA-deadlock; the test must fail (timeout)
   on pre-T04 code — verify that by reverting the T04 announce fix locally
   once, then re-applying (do not commit the revert).

### B. `TwoWriterDurabilityTest`

Real scheduler, durable host (`FileJournal` in a temp dir):

1. Two sender threads interleave writes to one non-commutative cell; run to
   quiescence; snapshot live state; kill the host (no checkpoint); rebuild
   graph + `recoverFrom(journal)`; assert recovered state equals live state.
   This is the direct test of T04's "journal order = acceptance order" fix —
   it must be able to fail: temporarily reordering append outside the lock
   should break it (verify locally, don't commit).
2. Recovery-under-bound: journal longer than `intakeBound` high-water →
   recovery completes (T05 finding 4 regression).

### C. `CoroutineSchedulerContextTest`

1. A `SuspendingCell` whose handler suspends (`delay`/yield) mid-processing
   during (a) a journal replay and (b) a RESTART re-baseline; assert the
   post-suspension emission carries the replay scope / baseline stamp, not a
   live wave (T04 finding 7).
2. Self-await guard: a suspending handler that calls `enqueueAwaiting` into
   its own host must fail fast with `IllegalStateException`, not a 5s
   timeout — including after a suspension point (the `drainingThread`
   staleness case).

### D. Terminated-host loudness

Handler throws `StackOverflowError` (deep recursion) under each real
scheduler; assert: supervision/dead-letter accounting fires (T04 finding 5),
and if the drain loop cannot survive, subsequent `submit` fails loudly rather
than silently accepting.

### E. Wire-thread entry (one test, minimal)

Using `:wire` loopback (`WsTransport.listen(0)` + connect, port from the
bound socket — no fixed ports): a peer floods frames while the receiving host
is concurrently driven locally; assert convergence and that no invocation is
lost. This exercises the WS-read-thread → `enqueueHostedInvocation` path
under the T04 fixes. Keep deadlines generous (existing smoke-test style);
this is a conformance check, not a stress benchmark.

## Iteration expectation

These tests are designed to shake residual defects out of T04/T05. If one
fails: diagnose, fix **within the files T04/T05 already touched**, and note
it in the report. If the defect is in territory owned by another ticket
(replication, partition, data/op beyond T05's edits), mark the test
`@Disabled` with the finding + file a note in the report — do NOT
silently weaken the assertion or the seed/parameters (AGENTS.md rule).
`@Disabled` here is a tracked hand-off, not a suppression: the report must
name the owning ticket.

## Verification

```bash
./gradlew :kernel:test --tests '*ConformanceTest' --tests '*TwoWriterDurabilityTest' --tests '*CoroutineSchedulerContextTest'
./gradlew test
./gradlew :concord:test -Pconcord.profiles=core,dist,dur
./gradlew :demo:exchange:test
```

Run the new tests 5× locally (`--rerun`) to shake out flakiness; a flaky new
test is a bug in the test or the runtime — resolve which before completing.

## Report

Per test: what it pins, whether it exposed a defect (and the fix). List any
`@Disabled` hand-offs with owning ticket. Confirm the two
verify-it-can-fail checks (A3, B1) were performed. This ends the Phase 1
session.
