# V1C-BENCH — measure what a whole-state snapshot actually costs, before anyone implements a bounded read

**Status**: Specified — measurement complete, awaiting C7 evaluation (recommendation: GO). See `../30-bounded-read-measurement.md`.
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 7 · **Branches:** `ticket/v1c-bench`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. `:inspect` is a read-only HTTP/SSE
view of a live host process. When the inspector wants a cell's state it calls
`ManagedHost.snapshotOf(ref)`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1244-1263`), which
runs the cell's own `Stateful.snapshot()`
(`kernel/src/main/kotlin/civictech/cell/Stateful.kt:11-14`) on that cell's
execution context. That is a **whole-state copy** — every one of the 24
`Stateful` implementations under `kernel/src/main` returns its entire state as
one `Serializable` (`SetCell.kt:200`, `MapCell.kt:49`, `ShardCell.kt:185`, …).

The inspector then throws most of it away: `ValueEncoder` truncates at
`MAX_ROWS = 200` (`inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt:53`)
and `MAX_BYTES = 50_000` (`:56`), enforced by `Budget` (`:341-348`) over an
already-materialized value. The cell's thread pays for 10⁵ rows so the
instrument can render 200.

`doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` (the
V1C design note, wave 6 of this run) proposes closing that with a **bounded
state read** — an opt-in `BoundedStateful : Stateful` interface plus a
`ManagedHost.readState` accessor that pages state under a cursor and a limit, so
a 10⁵-row read becomes ~500 short scheduler tasks instead of one task that owns
the cell's thread for the whole copy (§3.2 of that document).

## Problem

The design note's own §7 closes with this, verbatim, under "what could not be
determined from the code":

> **The real magnitude of the copy cost.** No benchmark exists. `MAX_CELLS = 50`
> and `BUDGET_MS = 2000` were chosen by ticket, not measured
> (`DataSearch.kt:344-348` cites "Ticket:" for both). The claim that paging is
> cheaper than copying is structurally sound but unquantified, and the replan
> should ask for one measurement on a 10⁵-row `SetCell` before sizing the work.

The C-replan checkpoint is asking for exactly that. Four downstream tickets
(`V1C-KERNEL`, `V1C-CELLS`, `V1C-OPS`, `V1C-BE`) add a new kernel interface,
implement it across ~15 cell classes and rewire three inspector consumers. That
is a lot of surface to add on a structural argument alone. **This ticket buys
the number that justifies — or kills — the rest.**

Note precisely what is and is not in doubt. That a whole-state copy is O(state)
is not in doubt. What is unmeasured is (a) how much wall-clock a cell's
execution context is actually held for at realistic sizes, (b) whether splitting
that into N short tasks materially improves the cell's availability to its own
traffic or merely relabels the same total work, and (c) whether the current
`DataSearch` bounds (`MAX_CELLS = 50`, `BUDGET_MS = 2_000`,
`DataSearch.kt:345`/`:348`) are conservative, about right, or already too
generous.

## Solution direction

Produce **one measurement document**, not production code. This ticket is
doc-producing, in the same register as `V1C-DESIGN` (wave 6): its deliverable is
a file under `doc/spec/90-roadmap/98-inspector-v4-plan/`, and its harness is
reproducible from the document rather than checked into a test source set.

### What to measure

Three experiments, on a real `civictech.cell.data.SetCell` hosted on a real
`ManagedHost` (not a simulated one — the question is about a real execution
context, real threads, real allocation). Sweep set sizes
**10³ / 10⁴ / 10⁵ elements**, and report every number with the JVM, heap, and
machine it was taken on.

**E1 — the copy, in isolation.** Time `ManagedHost.snapshotOf(ref)` end to end,
and separately time the `Stateful.snapshot()` call itself on the cell thread.
Report median and p95 over enough repetitions to be stable (state the count).
Report the allocated bytes if you can get them cheaply
(`com.sun.management.ThreadMXBean.getThreadAllocatedBytes` is available on the
Hotspot JVM this repo targets); if you cannot, say so rather than estimating.

**E2 — the occupancy cost, which is the actual question.** Drive the same
`SetCell` with continuous live traffic (a steady stream of `SetDelta` adds from
a source cell through a real link) and measure the **cell's own throughput**
with and without a concurrent `snapshotOf`. The number that matters is: *for how
long, and by how much, does an inspector read degrade the cell's service to its
graph?* Report the throughput dip and its duration. This is the P2-adjacent
number the design's "one page = one scheduler task" claim is trying to improve.

**E3 — the paging counterfactual, simulated without implementing the design.**
You do **not** need `BoundedStateful` to answer whether paging helps. Simulate
it: submit N tasks to the same host that each copy a 200-element slice of the
same underlying state (a `scheduler.submit(0)` per page, mirroring
`ManagedHost.kt:1249-1257`), and measure (a) total wall time across all pages,
(b) the maximum single-task occupancy, and (c) the same live-traffic throughput
dip as E2. The comparison that decides the design is **E2's dip versus E3's
dip**, not E1's total versus E3's total — paging is expected to cost *more*
total work and *less* contiguous occupancy, and the design is only worth
building if that trade is real and large.

**E4 (cheap, do it) — how much of the copy is discarded today.** For a 10⁵-row
`SetCell`, report what fraction of the copied state survives `ValueEncoder`'s
200-row / 50 KB budget on the `GET /api/inspect/cell/{ref}/state` path, and the
end-to-end latency of that request. One number, one sentence.

### Latitude

The harness shape is yours. A `main()` in a scratch source set, a temporary
JUnit test you delete before committing, or a Kotlin script are all fine —
whatever gets a trustworthy number. Warm the JIT before timing and say how.
If a measurement turns out to be dominated by something you did not expect
(GC, tag-map allocation in `SetCell`'s OR-set structure, `ConcurrentHashMap`
iteration), **report that instead of tuning it away** — an unexpected dominant
cost is a more valuable finding than a clean number.

### What NOT to do

- **Do not implement `BoundedStateful`, `StateRead`, `StatePage`, `Cursor`, or
  `ManagedHost.readState`.** `V1C-KERNEL` owns those and must not inherit a
  half-shape from here. E3 simulates paging with existing API only.
- **Do not check in a benchmark test.** This repository has no JMH, no
  benchmark source set, and no tag-gating convention for slow tests (verified:
  zero matches for `jmh`/`Benchmark` under `kernel`, `demo`, `testkit`,
  `inspect`). Adding that infrastructure for a one-shot measurement is not
  this ticket's job, and a slow test in `./gradlew test` is a permanent tax.
  Paste the harness into the document's appendix instead, complete enough to
  re-run.
- **Do not tune any existing constant.** `MAX_CELLS`, `BUDGET_MS`, `MAX_ROWS`,
  `MAX_BYTES` stay exactly as they are. Recommending a change is in scope;
  making one is not.
- **No kernel edits, no `inspect/src` edits, no `concord/` edits.**

## Files expected to touch

- **New**: `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md`
  — the deliverable. Must open with a `**Status**: Exploratory — …` header
  (`:concord:docLints` enforces the vocabulary; see
  `../10-design-notes.md` binding constraint 9).
- This ticket's `**Status**:` line.

Nothing else. Any scratch harness you write must be deleted from the tree
before you finish; the appendix in the document is its only surviving form.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` §1.4
  ("The read that *is* wave-neutral is unbounded, unstamped and local-only"),
  §1.5 ("The consequence, quantified"), §3.2 ("Execution context, threading,
  cancellation"), §4.3 ("Keep `snapshot()`, bound only at the encoder") and §7's
  closing "what could not be determined" list. §3.2's "one page = one scheduler
  task" is the claim you are testing.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1201-1263` —
  `snapshotOf`'s KDoc states the cost in prose (`:1217-1221`) and the threading
  contract (`:1223-1234`); the submit/cancellation block is `:1249-1262`.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — the subject.
  `snapshot()` at `:200` copies both OR-set tag maps.
- `inspect/src/main/kotlin/civictech/inspect/ValueEncoder.kt:53`, `:56`,
  `:341-348` — the budget that discards most of the copy.
- `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:343-359` — the two
  unmeasured constants, and `:300-339`, the closing notice that confesses the
  consequence to users.
- `kernel/src/test/kotlin/civictech/cell/host/SnapshotOfHardeningTest.kt` — an
  existing harness that drives `snapshotOf` against a real host; reuse its setup
  rather than inventing one.
- `kernel/src/test/kotlin/civictech/cell/data/` — for a real
  source→`SetCell` link with live traffic, copy an existing test's graph
  construction.
- `AGENTS.md` §"Verification".

Do not modify: `kernel/**`, `inspect/**`, `wire/**`, `concord/**`, `demo/**`,
any plan document other than the new measurement doc and this ticket's
`**Status**:` line.

## Acceptance criteria

- [ ] `30-bounded-read-measurement.md` exists, carries a valid `**Status**:`
      header, and `./gradlew :concord:docLints` is clean.
- [ ] E1, E2, E3 and E4 are each reported with concrete numbers at 10³, 10⁴ and
      10⁵ (E4 at 10⁵ only), with the JVM version, heap settings, machine and
      repetition count stated.
- [ ] The document states the **E2-versus-E3 comparison explicitly** — the
      occupancy dip with one whole copy versus with N paged copies — because
      that single comparison is what the design rests on.
- [ ] The document ends with a **one-paragraph recommendation in one of exactly
      three registers**, and says which: **GO** (the trade is real, build the
      bounded read as designed), **RESIZE** (the trade is real but the design's
      scope is wrong — say which parts are worth it, e.g. only the largest
      cell families), or **NO-GO** (paging does not materially improve
      occupancy; keep `snapshot()` and the encoder budget, i.e. the design
      note's own §4.3 branch). A hedge that picks none of the three fails this
      criterion.
- [ ] The recommendation includes a sentence on whether `MAX_CELLS = 50` and
      `BUDGET_MS = 2_000` are supported by the measurement — recommendation
      only, no code change.
- [ ] The harness is reproducible from the document's appendix.
- [ ] No scratch harness files remain in the tree. `git status` is clean apart
      from the two files in the claim.
- [ ] No kernel, `inspect/`, `concord/` or generated/build output in the diff.

## Verify

```bash
./gradlew :concord:docLints
git status --porcelain     # only the two claimed files
```

The measurement runs are yours to invoke; state the exact commands in the
document.

## Report on completion

- The three-register recommendation, up front, in one word.
- The E2-versus-E3 numbers, as a table.
- Anything that dominated a measurement unexpectedly.
- Whether you believe the four downstream tickets (`V1C-KERNEL`, `V1C-CELLS`,
  `V1C-OPS`, `V1C-BE`) should proceed as scheduled, be resized, or be dropped —
  and if resized, exactly which cell families carry the value. The C7 checkpoint
  acts on this answer.
- Anything specified here you could not do, and why.
