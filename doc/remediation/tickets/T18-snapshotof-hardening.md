# T18 — Harden `snapshotOf`'s cross-thread scheduler contract

**Status:** not-started
**Model:** opus · **Escalate to:** opus (re-split on failure)
**Wave:** 1 · **Branches:** `ticket/T18`

## Context

`ManagedHost.snapshotOf` (`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1054`)
is the host-routed state read added for the inspector (spec 33 §Snapshot /
G-25, sanctioned in the inspector plan). Its KDoc
(`ManagedHost.kt:1032-1051`) documents it as deliberately off-thread-safe from
the *caller's* point of view: "captured on this host's execution context
rather than on the caller's thread". The real caller is
`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:210-219`
(`DataSearch.read`), invoked from HTTP request-handling threads, not the
host's own scheduler thread. `snapshotOf` routes the read through
`HostScheduler.submit` (`ManagedHost.kt:1057`):

```kotlin
scheduler.submit(0) { future.complete(runCatching { cell.snapshot() }.getOrNull()) }
```

`HostScheduler.submit` (`kernel/src/main/kotlin/civictech/cell/host/HostScheduler.kt:17-22`)
documents an ordering contract ("ascending priority, then submission order")
but says nothing about which thread may call it. One implementation,
`SimulatedScheduler.submit` inside `SimulationController`
(`kernel/src/main/kotlin/civictech/cell/host/SimulationController.kt:92-94`),
is a bare `queue.add(ScheduledTask(priority, ++sequence, action))` against a
plain `java.util.PriorityQueue` — and the class doc two lines above states the
class-wide contract plainly: *"Everything (submission, stepping, awaiting) is
expected on one thread; this class is not thread-safe by design."*
(`SimulationController.kt:26-27`). `PriorityQueue` is not thread-safe; a
concurrent `add` racing the controller's own draining thread can corrupt the
heap invariant or silently drop the task.

The two sibling any-thread accessors added in the same work
(`ManagedHost.suspendedCells`, `ManagedHost.state`) were hardened for this
exact exposure — `suspendedCells` backed by a `ConcurrentHashMap`, `state`
marked `@Volatile` — establishing that cross-thread safety for these
inspector-facing reads is a deliberate, already-paid cost in this file.
`snapshotOf` was not given the same treatment; it inherits whatever the
underlying `HostScheduler` implementation happens to do.

Today no wired call site exercises the dangerous path: `demo/*` production
hosts use `VirtualThreadScheduler`/`CoroutineScheduler` (thread-safe
underneath), and every inspector kernel test that drives `DataSearch`
constructs its `LocationRegistry`/`ManagedHost` without a
`SimulationController` (confirmed: no `SimulationController` reference
anywhere under `inspect/src/test`). The exposure is latent — the first
deterministic inspector test (or any future caller) that points `DataSearch`
at a `SimulationController`-backed host from a foreign thread hits it, and
the failure mode is silent heap corruption or a lost task, in the exact area
AGENTS.md's core invariants call out ("Preserve deterministic simulation ...
tests").

This is tracked as finding B10 in `doc/architecture-decisions.md:45` (Medium,
Status: planned, solution: "W5: fail fast on single-threaded schedulers (or
synchronized submit with determinism argument); honor cancellation") and as
item 4 of `doc/remediation/AUDIT-2026-07-28.md`'s W5 wave (§"W5 — Kernel seams
for observers", item 4, line 136).

## Problem

1. **Unsafe cross-thread submission.** `snapshotOf` calls
   `scheduler.submit(0) { ... }` from whatever thread the caller runs on
   (an HTTP thread via `DataSearch`). Against a `SimulationController`-backed
   host this races `SimulatedScheduler`'s unsynchronized `PriorityQueue`,
   which the class's own KDoc says must not be touched off the controller's
   thread. Nothing in `HostScheduler.submit`'s contract or `snapshotOf`'s
   KDoc states whether cross-thread submission is legal, so there is no
   documented contract to violate — and no test to catch a violation.
2. **Cancellation is not honored.** `DataSearch.read`
   (`inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:214-219`) times
   out a slow read with `pending.cancel(false)` (`mayInterruptIfRunning =
   false`), but the task already submitted to the host's scheduler
   (`ManagedHost.kt:1057`) does not check the future's cancelled state before
   running `cell.snapshot()`. The snapshot still executes and calls
   `future.complete(...)` on an already-cancelled/completed future — a wasted
   `snapshot()` call (a full state copy, per the existing KDoc's own cost
   note at `ManagedHost.kt:1046-1050`) that does nothing useful. Bounded to at
   most one orphaned task per abandoned search (established during this
   audit's refutation of the band-0-preemption finding, see Non-goals below),
   so this is a correctness/waste cleanup, not a resource leak.

## Solution direction

Two independent fixes, both required:

**(1) Make the cross-thread contract explicit and safe.** Today
`HostScheduler.submit` documents ordering but not thread-safety, and
`SimulatedScheduler` silently assumes single-threaded callers. Pick one of:

- **Fail fast**: `snapshotOf` (or `HostScheduler.submit` itself) detects a
  scheduler that has declared itself single-threaded/foreign-thread-unsafe
  and completes the future with `null` instead of submitting — mirroring the
  existing terminated-scheduler branch in `snapshotOf`
  (`ManagedHost.kt:1060-1062`, `catch (_: IllegalStateException)`), so an
  inspector pointed at a simulated host loses that one data-search read
  cleanly instead of corrupting anything.
- **Safe accept**: make `SimulatedScheduler.submit` itself safe to call from
  a foreign thread (e.g. guard the `queue.add` with a lock, or hand off
  through a structure that tolerates concurrent producers) while an explicit
  determinism argument is preserved: the queue's `(priority, sequence)`
  ordering is still what `step()`/`runToIdle()` drain, deterministically, on
  the controller's own thread — only the *enqueue* becomes cross-thread-safe,
  nothing about draining order changes.

Which of these two the implementer picks is latitude; what is **not**
latitude is the outcome — silent `PriorityQueue` corruption or a silently
dropped task must become impossible. Whichever is chosen, state it in KDoc on
**both** seams: `HostScheduler.submit` (the general contract — may
implementations assume same-thread callers, or must they accept foreign
threads?) and `snapshotOf` (what happens, concretely, when it is called
against a scheduler that does/doesn't support this).

**(2) Honor cancellation.** Inside the task submitted by `snapshotOf`
(`ManagedHost.kt:1057`), check `future.isCancelled` (or equivalent) before
calling `cell.snapshot()`; skip the snapshot entirely when the future is
already cancelled. This is local to `ManagedHost.kt` — `DataSearch.kt` is not
touched; its `pending.cancel(false)` call is already correct, it's the
consumer side (the submitted task) that needs to check.

### Non-goals — explicitly out of scope

**Scheduler bands/priorities are settled; do not touch them.** A prior
finding claimed `snapshotOf`'s band-0 submission preempts management work and
that a 50-wide fan-out starves data dispatch. That finding was **refuted**
and is recorded in `doc/architecture-decisions.md`'s Declined table: "`snapshotOf`
at band 0 preempts management; 50-wide fan-out starves data dispatch" —
refuted because `DataSearch.read` blocks per read (at most one in-flight
snapshot per search), snapshots are shallow copies, the cited
`34-scheduling.md` §5 attention-banding text is marked unimplemented, band 0
already carries non-management work, and the band-0 submission was sanctioned
twice in the inspector plan's progress log
(`90-progress-log.md:359,1031`). Do not add scheduling bands, do not change
`snapshotOf`'s priority argument (it stays `0`), do not otherwise touch band/
priority semantics anywhere in this ticket. The only residual from that
declined finding — an orphaned task after an abandoned search — is exactly
what this ticket's fix (2) above closes.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — `snapshotOf`
  only: cancellation check before `cell.snapshot()`, and whatever call
  changes fix (1) requires at this call site; KDoc update on `snapshotOf`.
- `kernel/src/main/kotlin/civictech/cell/host/SimulationController.kt`
  and/or `kernel/src/main/kotlin/civictech/cell/host/HostScheduler.kt` —
  wherever fix (1)'s contract is implemented and documented.
- New test(s) under `kernel/src/test/kotlin/civictech/cell/host/`.

Touching files outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/remediation/AUDIT-2026-07-28.md` §"W5 — Kernel seams for observers",
  item 4 (line 136) — the ticket's source item.
- `doc/architecture-decisions.md:45` (finding B10) — Medium severity, planned
  solution matches this ticket.
- `doc/architecture-decisions.md`'s Declined table, the `snapshotOf` band-0
  row — read this before touching anything band/priority-related; it is why
  bands are out of scope.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:1032-1063` —
  `snapshotOf` and its full KDoc (the existing cost/thread rationale to
  extend, not replace).
- `kernel/src/main/kotlin/civictech/cell/host/SimulationController.kt:1-27`
  — class KDoc, "not thread-safe by design"; `:82-100` — `SimulatedScheduler`,
  `submit` at `:92-94`.
- `kernel/src/main/kotlin/civictech/cell/host/HostScheduler.kt` — the
  `submit` contract to extend with a thread-safety statement.
- `inspect/src/main/kotlin/civictech/inspect/DataSearch.kt:200-225` —
  `DataSearch.read`, the real caller, and its `pending.cancel(false)` at
  `:219`. Do not modify this file — the cancellation check belongs on the
  kernel side (inside the submitted task), not the caller.

Do not modify: `FanOutlet.kt`/`Flow.kt` (T17), `LocationRegistry.kt` (T21),
anything under `inspect/**` (`DataSearch.kt` stays untouched — the
cancellation fix is entirely kernel-side).

## Acceptance criteria

- [ ] A test drives `snapshotOf` against a `SimulationController`-scheduled
      host, submitting from a foreign thread, and gets the contracted outcome
      (null-completion, or safe execution — whichever fix (1) chose) with no
      corruption of the simulation's state: existing `SimulationController`/
      `SimulatedScheduler` determinism tests remain green.
- [ ] A test proves a snapshot task whose future is cancelled before the task
      runs does not call `snapshot()` on the cell.
- [ ] KDoc on `HostScheduler.submit` and on `snapshotOf` both state the
      cross-thread contract explicitly (which threads may call submit; what
      happens when the scheduler doesn't support it).
- [ ] `snapshotOf`'s priority argument is unchanged (`0`); no scheduling band
      or priority semantics were added or altered anywhere in the diff.
- [ ] `./gradlew :kernel:test` passes.
- [ ] No unrelated files in the diff.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.*'
```

## Report on completion

- Checks run and their results.
- Which of the two fix-(1) options was chosen (fail-fast vs. safe accept) and
  why.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why.
