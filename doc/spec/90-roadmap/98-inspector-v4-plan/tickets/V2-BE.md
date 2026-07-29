# V2-BE — the activity feed: lifecycle becomes an event stream, not a 1 Hz sample

**Status**: Implemented — merged
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 4 · **Branches:** `ticket/v2-be` · **Depends:** `V2-KERNEL` merged to
`main` (fork its session; branch from `main` after its merge)

## Context

You are working on `:inspect`, the Inspector backend: a read-only HTTP/SSE view
of a live ComputeNet host process
(`inspect/src/main/kotlin/civictech/inspect/`, ~4.2 kLOC, 22 test files). Read
`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full first — it
is the decided design for this run and its §"Binding constraints" governs this
ticket.

This is the **V2 activity vertical**: the design's second priority after live
data, defined as "passivated/activated, which host"
(`10-design-notes.md:20-23`, `:44-50`).

How the server is put together today:

- `InspectorServer.kt` — routes registered in `init` (`:239-286`), one daemon
  scheduler running six `Tick`s (`:485-538`), a synchronous `tickAll()` test
  seam that runs every tick's action once (`:560-571`), path constants in the
  companion (`:585-609`), and `encodeRef`/`decodeRef` for `CellRef` ⇄ string.
- Registry hooks are held as `AutoCloseable`s and all released on `close()`
  (`:201-226`, `:542-555`).
- `InspectorModel.kt` — one monitor guards nodes/edges/`seq`; **every** SSE
  emission goes through it (`emitEvent`), so all feeds share one monotonic
  `seq` and one gap detector.
- `Errors.kt` — the closest structural precedent for what you are building: a
  bounded `RingBuffer` (cap 200, `:220-231`), rows built from extracted
  primitives at capture time (`:127-139`), a `GET` endpoint served from a
  snapshot (`:89-103`), and per-row SSE callbacks handed in at construction
  (`:44-52`, wired at `InspectorServer.kt:188-195`).
- `Cold.kt` — `Heat` (`:68-119`) is the vocabulary for "not running":
  `SUSPENDED` / `DRAINED` / `HELD` / `UNHOSTED`, computed from registry+host
  metadata only. `Waker` (`:147-173`) is the inspector's single causal act:
  `POST /api/inspect/graph/{id}/wake` resumes drained hosts then suspended
  cells and reports the blast radius.

`V2-KERNEL` (merged before you; fork its session) added two seams to
`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`: a **lifecycle
listener** with a deregistration handle, firing per cell on
suspend / resume / drain-completed / host-resumed from the existing transition
points, and an **attention-band read accessor** returning the cell's current
`civictech.cell.control.AttentionBand` or null. Read that ticket's completion
report for the exact signatures before you start.

`V2-FE` runs in parallel on `inspect/ui/**` and codes against the contract
shape fixed by this ticket plus fixtures it authors. You never edit
`inspect/ui/src/**`; see §"Cross-ticket coupling" for the one file pair that
touches you.

## Problem

**1. A 1 Hz poll stands in for a notification that now exists.**
`Tick("lifecycleChanged", GRAPHS_POLL_MS)` (`InspectorServer.kt:522-527`) drives
`InspectorModel.publishLifecycleChanges` (`InspectorModel.kt:254-284`), which
walks **every known node** once a second and recomputes
`Heat.of(registry, ref)` (`Cold.kt:109-117`) to detect transitions that are
individually rare. Its KDoc states the reason plainly (`InspectorModel.kt:261-265`):
"`HostManagementApi.suspend`/`resume` and `drainHost`/`resumeHost` are ordinary
management calls with no notification hook, so there is nothing to subscribe
to." That sentence is now false.

**2. There is no activity feed at all.** A transition is announced as a
`lifecycle` event carrying the cell's *current* state
(`InspectorModel.kt:271-284`, `:414-439`) and then forgotten. The client can
render "this cell is suspended"; it cannot render "this cell was passivated at
14:02:11, woken at 14:02:40, restarted twice since". The design's activity
vertical is exactly that history.

**3. `CellDetail.attention` is a hard-coded null.** `InspectorModel.kt:331-333`
puts `JsonNull` with the comment "the band lives on the cell object, out of
reach without new kernel surface the ticket forbids"; the DTO says "**Always
null in M1**" (`Dto.kt:135-142`); the UI renders a permanent em dash
(`inspect/ui/src/components/DetailPanel.tsx:139-140`). `V2-KERNEL` removed the
obstacle.

**4. Stale claims to check, not inherit.** `10-design-notes.md:44-50` and
`90-progress-log.md:1142-1149` also list "deregistration handles for
publish hooks" and "`remoteRefs()`" as missing. **Both landed** (kernel commit
`b5c4b43`, T21) and the inspector already consumes them — real handles at
`InspectorServer.kt:201-226`, `remoteRefs()` at `InspectorModel.kt:138-147`.
There is no disarmed-listener workaround left to remove. Verify and report;
do not invent work here.

## Solution direction

Decided; the mechanism and code shape are yours.

### 1. Retire the lifecycle poll

Replace the `"lifecycleChanged"` tick with the kernel lifecycle listener,
registered per inspected host, held as an `AutoCloseable` beside the registry
hooks (`InspectorServer.kt:201-226`) and released in `close()` (`:542-555`) —
a stopped inspector leaves no listener on a live host.

Keeping the tick as a *fallback* is permitted only if you find a lifecycle
change the listener genuinely cannot report, and only for that case. If you
keep it, the report must name the uncoverable transition concretely. Two things
to check before deciding:

- `lifecycleOf` (`InspectorModel.kt:312-313`) is `Heat.of(...).isCold ?
  SUSPENDED : HOT`, and `isCold` is `SUSPENDED || DRAINED` only
  (`Cold.kt:85-90`) — `HELD` and `UNHOSTED` both render as `HOT`. So the
  contract's two-valued `Node.lifecycle` moves on exactly the transitions the
  listener reports, plus publish/unpublish (already hooks).
- `published` (`InspectorModel.kt:414-439`) already updates
  `announcedLifecycle` on a re-publish so `resumeHost` is not double-announced
  (`:422-426`). Whatever you do must preserve "exactly one `lifecycle` event per
  real transition" — no duplicates now that two sources can report the same
  resume.

The `lifecycle` SSE event's shape and semantics are unchanged. This is a
*mechanism* change (push, not sample), not a contract change.

### 2. The activity feed

**Ring.** A bounded `RingBuffer` of activity entries, capacity 200 — reuse
`Errors.kt:220-231` verbatim (it is already `internal` in the same package and
tested by `RingBufferTest.kt`; do not fork a second implementation).

**Entry shape** (decided — `V2-FE` codes against exactly this, and its fixture
must strict-decode against your DTO):

```jsonc
{
  "ref": "<encoded CellRef>",     // InspectorServer.encodeRef, as every other row
  "kind": "activated",            // activated | passivated | drained | woken | restarted
  "atMs": 1730000000000,          // wall clock at capture, as DeadLetterRow.atMs
  "generation": 3                 // optional; present on "restarted", absent otherwise
}
```

**Kind semantics** (decided):

| kind | Source |
|---|---|
| `passivated` | kernel listener: a cell suspended — explicit `suspend` or supervision `SUSPEND` |
| `activated` | kernel listener: a cell resumed, or reactivated by `resumeHost` |
| `drained` | kernel listener: the cell's host finished draining (one entry per cell it holds) |
| `woken` | the inspector's own `Waker.wake` (`Cold.kt:147-173`) acted on this cell — the user's causal act, recorded as such and distinct from the kernel-observed `activated` that follows it |
| `restarted` | supervision restart, detected as it is today by `Errors.pollRestarts` (`Errors.kt:192-205`); carries the new `generation` |

`woken` and `activated` may both appear for one wake. That is intended: one is
"a user asked", the other is "the kernel did". Do not suppress either.

**Endpoint.** `GET /api/inspect/activity` → `200` with
`{"entries": [ …ActivityEntry ]}`, oldest first, at most 200. Register it
beside the other routes in `init` (`InspectorServer.kt:239-286`) with a
`BASE_PATH`-derived constant in the companion (`:585-609`). Mind the routing
note at `:595-608`: the JDK server matches by longest path prefix, so check
your new path prefixes none of the existing ones (`/activity` does not — say so
in the report).

**SSE.** A new event kind `activity`, payload = one `ActivityEntry`, emitted
through `InspectorModel`'s `emitEvent` so it rides the same monotonic `seq` as
every other feed. Add the constant beside the others (`Dto.kt:214-236`). Every
entry that enters the ring is also broadcast — GET is catch-up, SSE is live,
same pattern as errors.

**Not in scope for the feed:** despawn/spawn (topology deltas already report
them), migration, `HELD`, per-message anything.

### 3. `CellDetail.attention`

Populate it from the kernel accessor at `InspectorModel.kt:326-336`, replacing
the `JsonNull` at `:331-333`, and update the DTO's now-wrong "Always null in
M1" KDoc (`Dto.kt:135-142`).

**Decided encoding:** the lowercased band name — `"none"` | `"low"` |
`"normal"` | `"high"` — or `null` when the kernel accessor answers null (not
locally hosted, or a host with no `AttentionPolicy`). The four bands are
`AttentionBand` (`kernel/src/main/kotlin/civictech/cell/control/Attention.kt:83-94`).

This **widens** the contract, which currently documents
`"attention": "focus" | "idle" | null`
(`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:84`). The widening is
safe — the field has never carried a non-null value in any release, so no
client can regress — but it is still a contract change: **flag it, never edit
the contract file** (design notes constraint 8). If your judgment differs
(e.g. you would rather map `HIGH`→`focus`, everything else→`idle` to stay
inside the documented enum), implement what you judge right, keep `V2-FE`'s
render-the-string-as-given approach valid, and argue it in the report.

Reading the band must not raise attention (P6) — that property belongs to the
kernel accessor; do not add a refresh, a recompute, or a fallback that touches
the cell.

### Invariants that bound the design

- **P2** — no per-message hook. Everything here is a rare-path notification or
  an existing metadata poll. You are removing a poll, not adding a tap.
- **P6** — activity capture subscribes to nothing and raises no attention. A
  cell must never be observed, linked, woken or touched because the activity
  feed exists. `GET /activity` must be leak-free in the sense
  `InspectorDataSearchTest`/`InspectorColdTest` already assert for browsing.
- **Viz never blocks** — the notification path runs on a *host scheduler
  thread*. Record and hand off; never block, never do I/O, never take a lock
  the HTTP side holds. The SSE broadcaster's bounded drop-oldest queues
  (`SseBroadcaster.kt`) remain the backpressure answer. A listener that throws
  must not break the host: contain exceptions at your boundary regardless of
  what the kernel already contains.
- **Bounded** — the ring is capped at 200 for the whole process; nothing grows
  per cell without bound. If you keep any per-cell bookkeeping (e.g. to
  de-duplicate), it must shrink when refs go away, as
  `Errors.pollRestarts` does with `lastGeneration.keys.retainAll(known)`
  (`Errors.kt:204`).
- **Ownership** — unchanged; nothing here reads a payload.

### Cross-ticket coupling (read this before you finish)

`FixtureContractTest` (`inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt`)
strict-decodes every file in `inspect/ui/fixtures/` and asserts that its
hand-written `decoders` map's key set **equals the directory's actual contents**
(`:64`, `:89-94`). `V2-FE` adds exactly two fixtures, at exactly these names:

- `inspect/ui/fixtures/activity.json` — the `GET /activity` body
- `inspect/ui/fixtures/activity-event.json` — one `activity` SSE envelope

You must add the two matching `decoders` entries. Because the assertion is an
equality, **`:inspect:test` is green only when both branches are merged**: a
decoder entry without its file fails, and a file without its entry fails. So:
if those files are absent in your worktree, create them yourself, at those
exact paths and names, from your server's real output. Whichever branch merges
second resolves a trivial content conflict on two small JSON files. Note this
explicitly in your report so the C4 evaluator runs the repo gate *after* both
have landed rather than judging one in isolation.

## Files expected to touch

- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt` — the new
  route + constant; the lifecycle-listener registration beside the registry
  hooks; removal (or justified retention) of the `"lifecycleChanged"` tick;
  `close()` releasing the new handles.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt` — `attention`
  in `detailJson`; the `activity` emission point; whatever
  `publishLifecycleChanges` becomes.
- `inspect/src/main/kotlin/civictech/inspect/Dto.kt` — `ActivityEntry`,
  the `GET /activity` body type, the `Event.ACTIVITY` constant, the corrected
  `CellDetail.attention` KDoc.
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt` — `Waker` reporting the
  cells it woke, so `woken` entries can be recorded.
- A new `inspect/src/main/kotlin/civictech/inspect/Activity.kt` is expected —
  the feed deserves its own collaborator, the way `Errors.kt` and `Flow.kt` do.
- `inspect/src/test/kotlin/civictech/inspect/**` — new focused tests;
  `FixtureContractTest.kt`'s `decoders` map; existing tests that assert the
  lifecycle poll's cadence (search for `tickAll`, `lifecycleChanged`,
  `publishLifecycleChanges` — `InspectorColdTest`, `InspectorGraphsTest`,
  `InspectorNetTest` are the likely ones).
- `inspect/ui/fixtures/activity.json`, `inspect/ui/fixtures/activity-event.json`
  — **only** under the condition in §"Cross-ticket coupling". Nothing else
  under `inspect/ui/` is yours.

Touching files outside this list: note it in the completion report rather than
expanding silently. In particular: **no `kernel/**` edits.** If a kernel seam
is wrong or insufficient, stop and report it — do not patch the kernel from
this ticket (design notes §"Standing file split": `V2-KERNEL` is the only
kernel-touching ticket in this plan).

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — whole file;
  §"Binding constraints" 1, 2, 6, 8 and §"Verticals → V2".
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V2-KERNEL.md` **and its
  completion report** — the exact seam signatures and threading contract.
- `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` — §CellDetail
  (`:80-86`) and the SSE section; binding for everything you serve, and
  orchestrator-owned (do not edit).
- `inspect/src/main/kotlin/civictech/inspect/Errors.kt` (whole file) — the
  pattern: ring, primitives-at-capture, snapshot endpoint, per-row callbacks,
  poll hygiene (`:192-205`).
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:180-286` (
  collaborator construction, hooks, routes), `:485-571` (ticks, start, close,
  `tickAll`), `:585-609` (path constants and the prefix-ordering warning).
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:254-336` (
  lifecycle poll, `stamped`, `lifecycleOf`, `detailJson`) and `:399-460` (
  publish/unpublish handling).
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt` (whole file) — `Heat`'s
  vocabulary and `Waker`'s blast-radius reporting.
- `inspect/src/test/kotlin/civictech/inspect/InspectorColdTest.kt` — the
  suspend/drain/wake harness and the P6 leak-check idiom; the closest existing
  test to what you must write.
- `inspect/src/test/kotlin/civictech/inspect/InspectorEventsTest.kt` and
  `InspectorErrorsTest.kt` — SSE assertions filtered by event kind, and
  `tickAll()`-driven determinism.
- `inspect/src/test/kotlin/civictech/inspect/FixtureContractTest.kt:19-105` —
  the `decoders` contract described above.
- `AGENTS.md` §"Verification".

Do not modify: `kernel/**`, `inspect/ui/src/**` and `inspect/ui/test/**`
(`V2-FE`), `concord/**`, `wire/**`,
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`, any plan document
other than this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] A suspend → resume → drain → host-resume → supervision-restart sequence
      on a real in-process graph produces, in the ring and on the SSE stream,
      exactly the expected `passivated` / `activated` / `drained` /
      `activated` / `restarted` entries, in order, each naming the right ref,
      with a plausible `atMs` and a `generation` on the restart.
- [ ] `POST /api/inspect/graph/{id}/wake` on a cold component records `woken`
      for the cells it woke, and none for a hot component (a no-op wake).
- [ ] `GET /api/inspect/activity` returns the ring's contents; an entry
      produced after a client connected also arrives as an `activity` SSE
      frame, and the two agree.
- [ ] The ring is bounded: more than 200 entries evicts oldest-first and the
      endpoint never returns more than 200.
- [ ] The `"lifecycleChanged"` tick is **gone** from the `Tick` list — or
      retained with a concrete, named transition it alone can report, stated in
      both the code's KDoc and the report.
- [ ] Every real lifecycle transition still produces exactly one `lifecycle`
      SSE event — no duplicate now that publish and the listener can both see a
      `resumeHost`, and no transition lost relative to the poll.
- [ ] Every kernel listener registration is released on `InspectorServer.close()`;
      a closed inspector receives nothing and leaves no listener attached.
- [ ] `GET /cell/{ref}` reports a non-null `attention` for a cell on a host
      with an `AttentionPolicy`, and null on a host without one. Neither call
      raises attention, subscribes, or wakes anything (P6 — assert the leak
      check, as `InspectorColdTest` does).
- [ ] Nothing in the activity path runs per message (P2); nothing blocks a host
      scheduler thread; a slow/absent SSE client cannot stall a transition.
- [ ] Tests are deterministic — `tickAll()`, injected clocks (`Errors.kt:51`'s
      `clock` parameter is the pattern) and `awaitUntil`; no wall-clock sleeps,
      no assertions on scheduler timing.
- [ ] `FixtureContractTest` passes with the two new fixtures mapped.
- [ ] `20-api-contract.md` is unmodified in the diff; every contract addition
      is flagged in the report instead.
- [ ] No `kernel/**` in the diff. No unrelated files, no generated/build output.

## Verify

```bash
./gradlew :inspect:test
# narrow loop while iterating, e.g.
./gradlew :inspect:test --tests 'civictech.inspect.InspectorColdTest'
```

Then the consumers of `:inspect` and the repo gate:

```bash
./gradlew :demo:skillmatch:test :demo:shopping:test
./gradlew test
```

## Report on completion

- Checks run and their results; which existing tests changed and why the
  cadence they asserted is now wrong rather than merely different.
- **Flag to the orchestrator** (contract additions for
  `20-api-contract.md` — do not edit it yourself), with proposed wording:
  1. `GET /api/inspect/activity` → `{"entries": [ActivityEntry]}`, bounded at
     200, oldest first; behavior for an empty ring.
  2. `ActivityEntry`: `ref`, `kind` (the five values, each defined), `atMs`,
     optional `generation`.
  3. SSE kind `activity`, payload = one `ActivityEntry`, on the shared `seq`.
  4. `CellDetail.attention` widening from `"focus" | "idle" | null` to the four
     band names, with the argument for it and the note that the field has never
     been non-null in any release.
  5. Whether the `lifecycle` event's documented cadence needs re-wording now
     that it is push-driven rather than sampled at 1 Hz.
- The listener's threading behavior as you observed it (which thread, whether
  it can re-enter the model's monitor, what you did about it).
- Whether the lifecycle poll was removed or retained, and the evidence.
- The state of the two T21 claims (handles, `remoteRefs`) — confirmed present,
  or genuinely still missing with line numbers.
- The `inspect/ui/fixtures/` coupling: which of the two files you created vs.
  found, so the evaluator sequences the repo gate correctly.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
