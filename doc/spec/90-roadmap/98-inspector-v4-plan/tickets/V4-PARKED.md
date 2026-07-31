# V4-PARKED — the error lane reports zero parked messages exactly when messages are parked

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** unscheduled — queued by checkpoint C-replan-2 for the next planning
session · **Branches:** ticket/v4-parked

## Context

The inspector's error lane reports three live gauges and three row lists;
`parked` is the one that answers *"is anything queued behind an unreachable
target right now"*. It is computed fresh on every `GET /api/inspect/errors` and
diffed on a 2 s tick into the `errors.parked` SSE row
(`inspect/src/main/kotlin/civictech/inspect/Errors.kt:126-146`, `:236-273`).

`V4-PILOT` (wave 10, merged `9dd03a8`) ran two JVMs with a real replicated cell
gossiping across a real socket, killed one peer, and wrote at the survivor. The
writes demonstrably parked — the returning peer received them on reconnect,
anti-entropy catch-up doing its job. The inspector reported
`{"counters":{…,"parked":0,…},"parked":[]}` at every capture point, in the
automated run and in the live run alike
(`doc/demo-shopping-replica-pilot.md:335-352`, defect **D2** at `:481-495`).

`V4-PILOT` was an evidence ticket and correctly did not diagnose it — its file
claim did not include `inspect/src/**`, and its report says so, hypothesising
that "the replica mesh's park queue lives in `LocationRegistry`'s routing, not
in a host's supervision accounting, so the likely cause is that these two are
simply different queues", and asking for a reader who owns `inspect/src/**` to
confirm before a fix is ticketed.

**Checkpoint C-replan-2 did that reading, and the hypothesis is refuted.** They
are the same queue. The inspector is already reading the right one. The bug is
the key set it enumerates, and it is one line.

## Problem

`Errors.parkedCounts()` (`Errors.kt:237-244`):

```kotlin
registry.localRefs().forEach { ref ->
    registry.parkedFor(ref).groupingBy { it.portName }.eachCount()
        .forEach { (port, count) -> counts[ref to port] = count }
}
```

Three facts make this blind exactly when it matters:

1. **`parkedFor` is the right accessor.** `LocationRegistry.parkedFor`
   (`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt:282-283`)
   reads `LocationRegistry.parked` (`:51`), the routing park queue — the very
   queue `deliver` parks into when `send` fails (`:291-304`, the park at
   `:297`). Replica gossip to a partitioned peer goes through
   `HostedCellProxy` → `InvocationSink(registry::deliver)`
   (`kernel/.../host/HostedCellProxy.kt:29-30`,
   `kernel/.../cell/replication/Replication.kt:431`) and therefore parks
   exactly there; `Replication.kt:352` and `cell/wire/Peering.kt:137-138` both
   say so. `counters.parked` is likewise **not** host accounting — `Errors.kt:137`
   sums the same registry-derived rows. The pilot's "different queues" guess is
   wrong.

2. **On peer loss the ref leaves both key sets.** The transport calls
   `LocationRegistry.unpublishRemotes(via = egress)` on send failure and on
   close (`wire/src/main/kotlin/civictech/wire/WsTransport.kt:146`, `:192`),
   which **removes the entry from `locations` entirely** (`LocationRegistry.kt:514-525`).
   The partitioned ref is then neither `Local` (`localRefs()`, `:266-267`) nor
   `Remote` (`remoteRefs()`, `:278-279`) — while `parked[thatRef]` is
   non-empty and growing.

3. **So the enumeration can never reach it.** This is structural, not a race:
   even a `localRefs() + remoteRefs()` union stays blind, because the ref is in
   neither map after `unpublishRemotes`. The gauge reads zero for the whole
   partition and the row list stays empty.

The consequence is the worst shape a read-only instrument can have: it does not
merely under-report, it reports a confident **zero** during precisely the
incident an operator opened it for, and returns to plausible numbers when the
peer comes back. Nothing on screen distinguishes "nothing is parked" from
"everything that is parked is invisible to me".

There is also **no accessor for the park map's key set**. `parked` is private;
`parkedFor` answers per-ref only; nothing in `LocationRegistry`'s public
surface, and nothing in `testkit/`, enumerates it. That is the seam this ticket
adds.

## Solution direction

The decided design. Two edits, one in each module, plus tests.

**Part 1 — one read-only kernel accessor.**

Add to `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` an
enumeration over the existing private `parked` map. Shape (final; the name is
yours if you prefer another, the semantics are not):

```kotlin
/** Refs with at least one parked invocation — the introspection counterpart of [parkedFor]. */
fun parkedRefs(): Set<CellRef>
```

Requirements, each load-bearing:

- **Filter empty queues.** `deliver` and `install` use
  `parked.computeIfAbsent(...)` (`:293`, `:463`) and never remove a drained
  queue, so the raw key set is a high-water mark, not a live gauge. Filter on
  non-empty using the existing `ParkQueue` surface
  (`kernel/.../cell/control/ParkQueue.kt:68`, `:71`), under the same per-queue
  `synchronized` discipline `parkedFor` already uses (`:283`) — a snapshot read,
  never a mutation.
- **Read-only, off the data path.** No hook, no listener, no per-message call
  site. This is the same shape and the same justification `remoteRefs()` took
  when `V2-KERNEL` added it (`:269-279`) — an introspection counterpart of an
  existing accessor for an observer built after the events it needs. The C4/C8
  kernel-invariant audit applies: P2 (nothing on the per-message path), P6 (no
  link, no tap, no attention raised), transport-neutral.
- **No change to `deliver`, `send`, `install`, `unpublishRemotes` or any park
  or drain behaviour.** If you find yourself wanting to make `unpublishRemotes`
  retain the ref, stop — that changes routing semantics to serve an instrument,
  which is backwards.

**Part 2 — the inspector enumerates the union.**

`Errors.parkedCounts()` iterates `registry.localRefs() + registry.parkedRefs()`
(deduplicated; a locally published ref with a parked queue must produce exactly
one row per `(ref, port)`, not two). Everything downstream —
`parkedRows`, `parkFirstSeenMs` ageing, the `pollParked` diff with its
`count: 0` clear, `counters.parked` — is already keyed on `(ref, port)` and
needs no change; verify that rather than assuming it.

One consequence to handle deliberately rather than discover: a `ParkedRow` may
now name a ref that is **absent from the current topology**, because the
mirrored node was retracted when the peer left. That is correct and is the
point — the row is the truth about the queue, not about the node list. Do not
suppress such a row, and do not invent a node for it.

**Contract.** `../97-inspector-plan/20-api-contract.md` is **orchestrator-owned;
do not edit it.** `ErrorSnapshot.parked[]`'s description needs one sentence
saying a row's `ref` may name a cell that no longer appears in
`GET /topology` — an unreachable peer's ref is unpublished while its queue
persists. **Propose that wording verbatim in your report.**

**Latitude**: the accessor's exact name and return type (`Set<CellRef>` or
`Map<CellRef, Int>` — the map form would let `Errors` skip a `parkedFor` call
per ref, but only if it does not weaken the per-port grouping the rows need);
how you deduplicate the two key sets; the internal factoring of
`parkedCounts()`.

**NOT in scope:**

- **No `inspect/ui/**` work.** How the UI renders a parked row whose ref
  resolves to no node is a real question and it is deferred with the rest of
  the FE residuals at C-replan-2. The backend must be honest first.
- **No new counter, no new field, no new endpoint.** The gauge and the rows
  already exist; they are wrong.
- **The other three `V4-PILOT` defects.** D1 is `V4-COMPONENT`; D3 is deferred
  to the graph-identity question; D4 was re-diagnosed at C-replan-2 as not the
  defect it was filed as.
- **`ManagedHost.suspendedCells`** (`kernel/.../host/ManagedHost.kt:231`) is a
  *genuinely different* park queue — per-cell SUSPEND/attention parking, whose
  only inspector-visible trace is the `drainedOnTeardown` teardown total
  (`Supervision.kt:26-30`, `Errors.kt:138`). It was never the source of the
  `parked` rows and it is **not** in scope here. Do not merge the two.
- No `demo/**`, no `concord/**`, no `wire/**`, no `gen/**`.

### Test requirement

Two levels, because the seam and the consumer fail differently.

**Kernel** — `kernel/src/test/kotlin/civictech/cell/host/` (extend the existing
`LocationRegistry` test if one covers `parkedFor`/`remoteRefs`; otherwise a new
file):

- A ref with no location parks an invocation → `parkedRefs()` contains it.
- Publishing that ref drains the queue → `parkedRefs()` no longer contains it,
  proving the empty-queue filter (this is the assertion a naive `parked.keys`
  fails).
- A ref published `Remote` then removed by `unpublishRemotes(via)`, with
  invocations parked before and after the removal → `parkedRefs()` contains it
  while `localRefs()` and `remoteRefs()` both do not. **This is the defect's
  exact shape at kernel level** and must be asserted as a three-way statement,
  not as "the accessor returns something".

**Inspector** — `inspect/src/test/kotlin/civictech/inspect/InspectorErrorsTest.kt`
(the file already drives `GET /api/inspect/errors` and the SSE row; keep its
register, `port = 0`, bounded `awaitUntil`):

- Reproduce the pilot from the registry side without two JVMs: publish a remote
  ref through a sink, park invocations on it, `unpublishRemotes` that sink,
  park more. Assert `GET /errors` reports a non-zero `counters.parked` **and** a
  `parked[]` row naming that ref with the right port and count — the assertion
  that fails today.
- Assert the `errors.parked` SSE row fires for it on the poll tick, and that
  the `count: 0` clear still fires when the queue finally drains (a `publish`
  of the ref). The clear path runs off `lastParkedCounts` and must not regress.
- Regression: a graph with no parked anything still reports
  `counters.parked == 0` and no rows — the union must not manufacture rows from
  drained queues.

Bounded waits and the existing simulation/probe controls only.

## Files expected to touch

- **Modified**: `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt`
  — one new read-only accessor plus its KDoc. Nothing else in the file.
- **Modified**: `inspect/src/main/kotlin/civictech/inspect/Errors.kt` —
  `parkedCounts()` and its KDoc (which today says "across known refs" and must
  say what "known" now means).
- **Modified or new**: a `LocationRegistry` test under
  `kernel/src/test/kotlin/civictech/cell/host/`.
- **Modified**: `inspect/src/test/kotlin/civictech/inspect/InspectorErrorsTest.kt`
- This ticket's `**Status**:` line.

Nothing else. No generated/build output in the diff.

## Read first

- `doc/demo-shopping-replica-pilot.md` — finding 4 (`:335-358`) and defect
  **D2** (`:481-495`). Note that the hypothesis recorded there is refuted by
  this ticket's Problem section; the *observation* stands, the *cause* does not.
- `inspect/src/main/kotlin/civictech/inspect/Errors.kt` — in full, but
  especially `snapshot()` (`:126-146`), `parkedCounts`/`parkedRows`/`pollParked`
  (`:236-273`) and `parkFirstSeenMs`' concurrency note (`:99-104`).
- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` — `parked`
  (`:51`), `localRefs`/`remoteRefs`/`parkedFor` (`:265-283`), `deliver`
  (`:286-310`), `install`'s `computeIfAbsent` (`:463`), `unpublishRemotes`
  (`:505-525`). `remoteRefs`' KDoc is the precedent for the accessor you add —
  read the reasoning in it.
- `kernel/src/main/kotlin/civictech/cell/control/ParkQueue.kt` — the snapshot
  and emptiness surface you filter on.
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:340-360`,
  `:425-445` — why replica gossip parks at the registry, so the fix is known to
  cover the pilot's case rather than assumed to.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — `ErrorSnapshot`
  and the `errors.parked` SSE row as the contract states them. You propose
  wording; you do not edit.
- `doc/spec/90-roadmap/98-inspector-v4-plan/00-orchestration.md` — checkpoint
  C4's and C8's kernel-invariant audit clauses (the bar your `LocationRegistry`
  edit is judged against), and the C-replan-2 section.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/**` other than the single `LocationRegistry` accessor,
`inspect/ui/**`, `demo/**`, `concord/**`, `wire/**`, `gen/**`,
`doc/spec/90-roadmap/97-inspector-plan/**`, any plan document other than this
ticket's `**Status**:` line.

## Acceptance criteria

- [ ] `LocationRegistry` exposes a read-only enumeration of refs with a
      non-empty park queue; drained-but-allocated queues are excluded.
- [ ] The accessor touches no per-message path, raises no attention, installs
      no hook, and changes no park, drain, publish or unpublish behaviour.
- [ ] A ref removed by `unpublishRemotes` while its queue is non-empty is
      reported by the new accessor and by neither `localRefs()` nor
      `remoteRefs()` — asserted as one three-way statement.
- [ ] `GET /api/inspect/errors` reports a non-zero `counters.parked` and a
      `parked[]` row for such a ref; the row's `count` and `portName` are
      correct.
- [ ] The `errors.parked` SSE row fires for it, and the `count: 0` clear still
      fires when the queue drains.
- [ ] A locally published ref with a parked queue yields exactly one row per
      `(ref, port)` — no duplication from the union.
- [ ] No rows appear for a graph with nothing parked.
- [ ] `./gradlew :kernel:test` and `./gradlew :inspect:test` green;
      `./gradlew test` green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.*Registry*'
./gradlew :inspect:test --tests 'civictech.inspect.InspectorErrorsTest'
./gradlew :kernel:test
./gradlew :inspect:test
./gradlew test
git status --porcelain     # only the claimed files
```

## Report on completion

- The accessor's final signature and the emptiness-filter mechanism, in two
  sentences.
- Confirmation, with the test name, that the three-way kernel assertion
  (`parkedRefs` yes / `localRefs` no / `remoteRefs` no) is what fails on the
  pre-change baseline.
- **Proposed contract sentence** for `ErrorSnapshot.parked[]` — verbatim,
  ready to paste. The contract file is orchestrator-owned.
- Whether anything downstream of `parkedCounts()` needed changing after all
  (`parkFirstSeenMs`, the `pollParked` diff, the counter) — you were told to
  verify rather than assume.
- Whether a two-JVM reproduction was attempted; if not, say so plainly — the
  registry-level reproduction is sufficient for this ticket and the pilot's own
  runbook remains the end-to-end check.
- Anything specified here you could not do, and why.
