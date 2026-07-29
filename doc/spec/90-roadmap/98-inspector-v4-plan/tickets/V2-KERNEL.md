# V2-KERNEL — lifecycle transitions and the attention band become observable without polling

**Status**: Implemented — merged
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 4 · **Branches:** `ticket/v2-kernel`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports, explicit
links, hosted execution. `ManagedHost`
(`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`) runs cells;
`LocationRegistry` (same package) says where each `CellRef` lives and is the
process's announcement seam.

`:inspect` is a read-only HTTP/SSE view of a live host process
(`doc/spec/90-roadmap/97-inspector-plan/`, milestones M0–M5 all merged). It is
an *out-of-kernel observer*: it consumes registry hooks and read-only
accessors, and holds no privileged position. Where the kernel offers no
notification, the inspector polls on its own daemon scheduler — six periodic
`Tick`s today (`inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:500-528`).

The v3 delivery's closing report lists the entire cumulative kernel diff the
whole inspector required — five read-only accessors, nothing else
(`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088`):

| File | Addition | Why it was acceptable |
|---|---|---|
| `LocationRegistry.kt` | `describe(ref)` + weak `descriptions` map + defaulted `publish` param (`LocationRegistry.kt:227-241`, `:363-368`) | captured on the rare publish path, never reflected at read time |
| `ManagedHost.kt` | `outletAt(PortRef)` | tap-seam resolution, ~6 lines |
| `ManagedHost.kt` | `snapshotOf(ref)` | host-routed `Stateful.snapshot()`, caller-owned deadline |
| `ManagedHost.kt` | `isSuspended(ref)` (`ManagedHost.kt:220-231`) | tell a cone is parked *without touching it* |
| `ManagedHost.kt` | `isDrained` + `@Volatile state` (`ManagedHost.kt:181-204`) | distinguish DRAINED from DRAINING |

That table is the pattern this ticket must follow: small, read-only,
transport-neutral, threaded through structures that already exist, each with a
focused kernel test. This is the **only** ticket in the v4 plan permitted to
touch `kernel/**` (`../10-design-notes.md` §"Standing file split").

The V2 vertical it unblocks is *activity*: the inspector wants to show a
timestamped per-cell log of activated / passivated / drained / woken /
restarted, and to render a cell's attention band. `V2-BE` (wave 4, sequenced
after you, forked from your session) consumes what you build. It cannot do its
job with what exists today.

### What already landed, and must NOT be re-implemented

The v4 design notes (`../10-design-notes.md:44-50`) and the progress log
(`../../97-inspector-plan/90-progress-log.md:1142-1149`) both list **four**
kernel gaps. Two of them were closed after those paragraphs were written, by
the `audit-2026-07-28` remediation ticket T21 (commit `b5c4b43`). Verify this
yourself and then leave them alone:

1. **Deregistration handles for `onPublish`/`onUnpublish`** — present:
   `LocationRegistry.kt:119-122` and `:131-134` return `AutoCloseable`, exactly
   like their four `onLocal…` siblings (`:108-111`, `:125-128`, `:136-140`) and
   the any-scope `onTopology` pair (`:148-152`). The inspector's
   disarmed-listener workaround is already gone — it holds real handles
   (`InspectorServer.kt:201-226`) and closes every one (`:542-546`).
2. **`remoteRefs()` beside `localRefs()`** — present:
   `LocationRegistry.kt:252-261`, and already consumed by the inspector's
   catch-up sync (`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:138-147`).

Both are covered by `kernel/src/test/kotlin/civictech/cell/host/LocationRegistryHooksTest.kt`
(see its tests at `:64`, `:82`, `:103`, `:207`). Re-deriving either is wasted
work and diff noise; report that you checked.

## Problem

Two seams genuinely do not exist, and each one costs the inspector a lie or a
poll.

**1. No lifecycle notification.** Every suspend/resume/drain/host-resume
transition happens inside `ManagedHost` and tells nobody:

- `suspend(ref)` — `ManagedHost.kt:870-876` (writes `suspendedCells`)
- supervision `SUSPEND` — `ManagedHost.kt:710-713` (same map, failure path)
- `resume(ref)` — `ManagedHost.kt:862-868` (removes it, replays parked traffic)
- `beginDrain` phase 2 — `ManagedHost.kt:384-403`, `state = State.DRAINED` at
  `:400`
- `resumeHost()` — `ManagedHost.kt:885-892` (reactivates every cell, reopens
  intake, republishes)

The only externally visible consequences are the *readable* predicates
`isSuspended` (`:231`) and `isDrained` (`:204`). So the inspector samples them
at 1 Hz forever: `Tick("lifecycleChanged", …)`
(`InspectorServer.kt:522-527`) drives `InspectorModel.publishLifecycleChanges`
(`InspectorModel.kt:254-284`), which re-reads `Heat.of(registry, ref)`
(`inspect/src/main/kotlin/civictech/inspect/Cold.kt:109-117`) for **every known
node, every second, forever**, to detect transitions that are individually
rare. Its own KDoc says why (`InspectorModel.kt:261-265`): "there is nothing to
subscribe to". A poll also cannot report *when* a transition happened more
precisely than its period, and cannot report a transition that flips back
inside one period at all — which is exactly what a per-cell activity log needs.

**2. No attention-band read.** The band lives on the cell object, reachable
only through `ManagedHost`'s private `cells` map (`ManagedHost.kt:153`) via
`AttentionSupport.of(cell).band` (`kernel/src/main/kotlin/civictech/cell/control/Attention.kt:219-221`).
The host reads it internally for dispatch (`bandOf`, `ManagedHost.kt:559-562`)
but exposes nothing. Consequence: `CellDetail.attention` has been hard-null
since M1 — `InspectorModel.kt:331-333` literally puts `JsonNull` with the
comment "the band lives on the cell object, out of reach without new kernel
surface the ticket forbids", the DTO documents it as "**Always null in M1**"
(`inspect/src/main/kotlin/civictech/inspect/Dto.kt:135-142`), and the UI renders
a permanent em dash (`inspect/ui/src/components/DetailPanel.tsx:139-140`).
`Cold.kt:46-57` records the same wall from the other side.

## Solution direction

Add exactly **two** seams to `ManagedHost.kt`. Their shape is yours; the
properties below are decided.

### Seam A — a lifecycle listener

A registerable listener, returning a deregistration handle, that fires on
suspend / resume / drain-completed / host-resumed, from the existing
state-transition points listed under Problem 1. Suggested shape (adjust if you
find better, and justify):

```kotlin
enum class LifecycleTransition { SUSPENDED, RESUMED, DRAINED, HOST_RESUMED }

fun onLifecycle(listener: (CellRef, LifecycleTransition) -> Unit): AutoCloseable
```

Decided properties:

- **On `ManagedHost`, not `LocationRegistry`.** Every transition site is a host
  concern, a host may run registry-less (`ManagedHost(registry = null)` —
  `LocationRegistry.kt:229-241` documents that case), and the inspector already
  holds the host set (`InspectorServer.kt:189-195` passes `hosts` to `Errors`).
  A registry-level fan-in would have to invent a host→registry back-edge that
  does not exist. Do not add one.
- **Per cell.** A host-level drain notifies once per cell the host holds; the
  consumer wants per-cell rows. Rare path, bounded by cell count.
- **Fires from the existing transition points only.** Nothing new on the
  per-message data path (P2). Notification is synchronous on the mutating
  thread (the host's scheduler, management band) *after* the state change is
  visible — the same contract `LocationRegistry`'s hooks already have
  (`LocationRegistryHooksTest.kt:238`, "a publish hook runs on the publishing
  thread, after the park replay").
- **Listeners are notifications, not participants.** A throwing listener must
  not break a suspend, a drain, or the invocation that triggered supervision.
  Mirror `LocationRegistry.notify` (`LocationRegistry.kt:376-388`): catch,
  print to stderr, carry on. Registration list must tolerate
  register/deregister during iteration (`CopyOnWriteArrayList`, as at
  `LocationRegistry.kt:77-104`).
- **Idempotence matches the kernel's.** `suspend(ref)` on an already-suspended
  cell is a no-op today (`ManagedHost.kt:872`) — it must stay one, and must not
  notify. Decide and *test* what supervision `SUSPEND` (`:710-713`) does when
  the cell is already suspended.
- Deregistration must be real: after `close()`, the listener is off the list
  and never called again.

Explicitly **not** in this seam: despawn (`ManagedHost.kt:842-855` — already
covered by `LocationRegistry.onUnpublish`), migration, spawn, restart. Restart
is observable today through `generationOf` (`ManagedHost.kt:243`) and the
inspector already derives it (`inspect/src/main/kotlin/civictech/inspect/Errors.kt:192-205`);
do not duplicate it here.

### Seam B — an attention-band read

A read-only accessor for a locally hosted cell's current
`civictech.cell.control.AttentionBand` (`Attention.kt:83-94`). Suggested shape:

```kotlin
fun attentionOf(ref: CellRef): AttentionBand?
```

Decided properties:

- **Null, not a guess**, when the ref is not hosted here, and when this host
  runs without an `AttentionPolicy` (`ManagedHost.kt:53-54`) — with no policy,
  scheduling is plain FIFO and no band is in effect; reporting `NORMAL` would
  invent information. Note `bandOf` (`ManagedHost.kt:559-562`) already makes
  exactly this distinction internally.
- **The read must not raise attention (P6) and must not mutate.** Three traps,
  all real:
  1. `AttentionSupport.of(owner)` **lazily creates and `wire()`s** a support
     object when the cell has none (`Attention.kt:306-314`, `:272-297`) —
     installing protocol handlers and unlink listeners as a side effect of a
     *read*. A host with an `AttentionPolicy` already created one for every
     cell at spawn (`ManagedHost.kt:801-810`), which is why gating the
     accessor on `attention != null` makes `of()` a pure lookup. State that
     reasoning in the KDoc.
  2. Never call `refresh()` (`Attention.kt:237`) or `attend()` (`:227-231`):
     both run `recompute()`, which can change the band, fire listeners and
     `emitUpstream` (`:245-270`) — i.e. push attention up the cone. A read must
     never do that.
  3. Do not touch the cell, enqueue on the host, or take the scheduler's
     `dataLock`. `band` is `@Volatile` (`Attention.kt:219-221`); a racy read is
     fine and is what the kernel itself already does (`ManagedHost.kt:808-809`
     calls attention "advisory metadata").

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — both seams, and
  the notification calls at the five transition sites named above.
- `kernel/src/test/kotlin/civictech/cell/host/**` — new focused tests, one file
  per seam (suggested: `HostLifecycleListenerTest.kt`, `AttentionOfTest.kt`).
- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` — **expected
  to be unchanged**. Listed only so that if your analysis says a transition
  cannot be observed without touching it, that edit is inside your claim rather
  than a surprise. Justify any such edit in the report.

Nothing else. In particular **nothing under `inspect/`** — `V2-BE` consumes
these seams and owns every consumer-side change, including the poll removal.

Touching files outside this list: note it in the completion report rather than
expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — the whole
  file; §"Binding constraints" 1 (P2), 2 (P6), 5 (kernel transport-neutral,
  explicitly listed read-only accessors only), 7 (no `concord/` edits) govern
  you, and §"Standing file split" is why you are the only kernel-touching
  ticket.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088` — the
  cumulative kernel diff table: the bar every addition here is judged against.
  `:1142-1149` is the gap list, two entries of which are stale (see Context).
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — `:181-204`
  (drain state), `:209-231` (suspension), `:377-403` (`beginDrain`), `:551-562`
  (`bandOf`), `:683-716` (supervision RESTART/SUSPEND), `:798-816` (spawn:
  attention wiring, then publish), `:842-892` (despawn / resume / suspend /
  drainHost / resumeHost).
- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt:72-152` — the
  house pattern for a hook: doc'd purpose, `CopyOnWriteArrayList`,
  `AutoCloseable` handle. `:376-388` — hook-failure containment.
- `kernel/src/main/kotlin/civictech/cell/control/Attention.kt:83-94`,
  `:219-252`, `:261-314` — band, recompute/emit, and the lazily-creating
  `of()`.
- `kernel/src/test/kotlin/civictech/cell/host/LocationRegistryDescribeTest.kt`
  — the exemplar for a kernel-seam test: real host, real spawn, `awaitUntil`
  for the asynchronous management call, plus the negative case.
- `kernel/src/test/kotlin/civictech/cell/host/LocationRegistryHooksTest.kt` —
  the exemplar for hook tests (detachment, mutating-thread contract).
- `kernel/src/test/kotlin/civictech/cell/host/DrainAndMigrateTest.kt` and
  `SupervisionTest.kt` — existing drain / suspend / restart harnesses; reuse
  their setup rather than inventing one.
- `kernel/src/test/kotlin/civictech/cell/host/MagnitudeSchedulingTest.kt:47` —
  how a test constructs a host *with* an `AttentionPolicy`
  (`ManagedHost(scheduler = controller.scheduler(), attention = policy)`).
- `inspect/src/main/kotlin/civictech/inspect/Cold.kt:8-119` — how the
  downstream consumer defines "cold" from your predicates. Read it so your
  transition set is the one that actually retires the poll (see the note in
  Acceptance criteria).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: anything under `inspect/**` (`V2-BE`, `V2-FE`), `wire/**`,
`gen/**`, `nature/**`, `concord/**`, `testkit/**`, `demo/**`, any plan document
other than this ticket's `**Status**:` line, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned).

## Acceptance criteria

- [ ] Registering a lifecycle listener and driving `suspend(ref)`,
      `resume(ref)`, `drainHost()`, `resumeHost()` yields exactly the
      transitions those calls make, once each, per affected cell, with the
      correct `CellRef`.
- [ ] A drain on a host with several cells reports every cell it holds; a
      `resumeHost()` reports the same set.
- [ ] Supervision-driven suspension (`SupervisionPolicy.SUSPEND`,
      `ManagedHost.kt:710-713`) reaches the listener — a cell parked by a
      failure is not silently different from one parked by an explicit
      `suspend`.
- [ ] A repeated `suspend(ref)` on an already-suspended cell notifies nothing
      (no state changed).
- [ ] The deregistration handle works: after `close()` the listener receives
      nothing, while a second still-open listener keeps receiving.
- [ ] A listener that throws does not break the transition, the host, or the
      other listeners; the transition still completes and remains observable
      through `isSuspended`/`isDrained`.
- [ ] No notification is emitted from any per-message path. State this as a
      claim about the *call sites you added* and name them in the report (P2).
- [ ] `attentionOf` on a host with an `AttentionPolicy` returns the cell's
      current band, and follows it when the band changes.
- [ ] `attentionOf` returns null for a ref this host does not hold, and null on
      a host constructed without an `AttentionPolicy`.
- [ ] Reading `attentionOf` repeatedly on a cell that has never had attention
      raised leaves the band, its listeners and its upstream links untouched —
      no band change, no upstream `Attention` emission, no observable side
      effect (P6). Assert this, do not merely claim it.
- [ ] `LocationRegistry.onPublish`/`onUnpublish` handles and `remoteRefs()` are
      untouched — you verified they already exist rather than re-adding them.
- [ ] Every added public member carries KDoc naming this ticket (`V2-KERNEL`)
      and the reason it exists, matching the register of `isSuspended`
      (`ManagedHost.kt:220-231`) and `remoteRefs` (`LocationRegistry.kt:252-261`).
- [ ] Nothing under `inspect/` is in the diff. No generated/build output. No
      gap (`G-*`) or consistency (`C-*`) markers removed.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.*'
./gradlew :kernel:test
./gradlew test
```

`:kernel:compileKotlin` depends on `:gen:test`, so a generator regression would
surface here as a kernel compile failure (`AGENTS.md` §Repository map).

## Report on completion

- Checks run and their results.
- **The exact kernel diff**, in the format of the closing report's table
  (`90-progress-log.md:1076-1088`): file, addition, line count, justification.
  A wave-4 checkpoint (C4) audits this diff for P2/P6, read-only-ness and
  transport-neutrality before anything merges.
- The precise call sites you added notifications to, and the thread each runs
  on — the P2 claim must be checkable, not asserted.
- The signature and semantics of both seams, verbatim, so `V2-BE` can be
  written against them without reading your diff.
- Confirmation that the two T21 seams were already present (with the line
  numbers you verified), and any *other* stale claim you hit in
  `10-design-notes.md` or `90-progress-log.md`.
- Whether your transition set is sufficient to retire the inspector's 1 Hz
  lifecycle poll entirely, given that `Heat.of` (`Cold.kt:109-117`) also reads
  `LocationRegistry.isHeld` (`LocationRegistry.kt:341`) and `locate`. If any
  observable lifecycle change is still only reachable by polling, say which —
  `V2-BE` needs that answer to decide whether the poll can be deleted.
- Anything specified here you could not do, and why.
