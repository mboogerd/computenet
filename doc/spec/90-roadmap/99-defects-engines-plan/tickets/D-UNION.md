# D-UNION — one remove retracts a shared item: union-scoped observed-remove

**Status**: Specified — not-started
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5, fresh session
**Wave:** B1 · **Branches:** ticket/d-union

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. Its set family is an OR-set
(observed-remove) CRDT: every add mints a unique `Timestamp` tag, a remove
carries exactly the tags it observed, and merge is tag-set union — commutative,
associative, idempotent — so membership converges regardless of delivery order
and add-wins falls out for free
(`kernel/src/main/kotlin/civictech/cell/data/delta/SetDelta.kt:8-21`, spec
`doc/spec/20-dataflow-semantics/24-data-cells.md:42-68`, requirement ids
`[24-SET-01]`–`[24-SET-03]`, `[24-OP-UNION-01]`).

`demo/shopping` is the collaborative shopping list built on this: **per-user
writer `SetCell`s** (one durable, dynamically-keyed family, deterministic refs,
journaled ops — `demo/shopping/src/main/kotlin/civictech/demo/Main.kt:82-91`,
`:191-200`) stream into a shared `UnionSetCell`
(`kernel/src/main/kotlin/civictech/cell/data/op/UnionSetCell.kt:39-72`), whose
outlet feeds the derived views, the SSE UI, and — in two-JVM mode — the peer's
counterpart union over the real WebSocket wire (`Main.kt:150-171`).

The per-user writers are **load-bearing**: `SetCell` derives its tag source
deterministically from its ref (`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:50-57`),
which is what makes journal replay after `kill -9` re-mint the exact tags the
network already observed (M10.4; `kernel/src/main/kotlin/civictech/cell/host/KeyedCells.kt:27-33`,
`:86-93`). "Use one shared writer" is therefore **not** an available fix — it
would destroy the per-user identity that recovery depends on.

## Problem

From `backlog/union-scoped-observed-remove.md` (the design source for this
ticket — read it in full). In `demo/shopping`, removal is applied to the
caller's **own** per-user writer (`Main.kt:224`: `itemOps.remove(item)`).
Observed behavior, verified live:

```
alice add Coffee ; bob add Coffee     -> Coffee present (two OR-set tags)
alice remove Coffee                   -> Coffee STILL present (only alice's tag tombstoned)
bob   remove Coffee                   -> Coffee gone
```

An item added by N users needs all N to remove it, and a user's remove of an
item they didn't personally add is a **silent no-op** — yet the UI offers
"remove" on every item to everyone (`Main.kt:392-402`). This is correct
*per-replica* OR-set semantics — `SetCell.remove` can only tombstone tags in
its own local state (`SetCell.kt:97-103`: `liveTags(element)` over this cell's
`adds`/`dels` maps only) — but there is **no primitive** for the thing a shared
list actually wants: *"remove this element as it currently exists in the merged
view."*

The code already confesses the gap and names the upgrade path (`Main.kt:218-223`):

> ponytail: remove is writer-local — it tombstones only this user's own
> add-tags, so an item added by another user survives until that user removes
> it too. […] Upgrade path for shared removal: tombstone the element's
> currently-observed union tags across writers, not just the caller's.

Two more facts make this a real defect rather than a documented quirk:

- The kernel test suite asserts the surviving-element behavior as *intended
  per-writer semantics* — `element stays live while another source's tag
  survives` (`kernel/src/test/kotlin/civictech/cell/data/UnionSetCellTest.kt:40`)
  — which is right at the writer level and beside the point at the list level.
- The demo's own two-JVM test only ever exercises remover == adder (alice
  removes the apples alice added,
  `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt:62-66`),
  so the defect is invisible to every existing gate.

The demo currently conflates two distinct intents: **retract *my*
contribution** (writer-local; exists today) and **remove *the item***
(union-scoped; missing). This ticket adds the missing one.

## Solution direction

**Decided envelope** (not open for redesign): an observed-remove scoped to a
merged/union view. Given an element `e` and the union's currently-observed tag
set for `e` (`TagState.tags(e)`,
`kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt:34`), issue
tombstones covering **all** of those tags across their originating writers, so
one remove retracts every causally-preceding add the remover has seen.
Add-wins is preserved for genuinely concurrent (unobserved) adds — only tags
observed before the remove are tombstoned. Input: an element `e` and the
merged view's current tags for `e`. Output: a `SetDelta` whose `dels` cover
all observed tags of `e`; union membership for `e` drops iff no
unobserved/concurrent add remains.

### The shape choice — the one piece of explicit latitude

The backlog names two candidate shapes; **you pick one** and record a short
written rationale in your completion report. The acceptance criteria below are
shape-independent.

1. **Coordinating remove over the union:** `UnionSetCell.removeObserved(e)` —
   reads `e`'s live tags from the union's merged `TagState` and emits the
   covering tombstones (as a union-level tombstone delta the merge honors, or
   routed back to the writers that own them). Journaled like any other routed
   op, so replay stays deterministic.
2. **Authoritative shared "eraser" writer:** a dedicated cell that observes
   the union's tag stream and, on `remove(e)`, tombstones the observed tag set
   — leaving the per-user *add* writers untouched. Keeps writer identity for
   adds; centralizes deletes.

### A hazard the chosen shape must survive: catch-up resurrection

`TagState` deliberately keeps **no del tombstones** (`TagState.kt:13-16`): its
correctness argument is that per-link FIFO puts a tag's add before its del *on
the same stream*. A union-scoped remove breaks that assumption: the del is
minted at the union (or eraser) level, while the covered add-tags live on in
the originating writers' `SetCell` state — and those writers re-assert their
full tag state on every late-join catch-up (`SetCell.kt:170-176`) and on every
peer re-announce anti-entropy replay (`Main.kt:158-170`), on a *different*
stream than the one that carried the union-level del. Without tombstone
retention at the union/eraser level, a removed element resurrects on the next
catch-up. This is why the ticket's tombstone machinery lives at the
union/eraser level: additive extensions to `TagState.kt` and
`SetDelta.kt`/`delta/` (e.g. retained del-tombstones for union-scoped removes)
are **in scope**; the existing `TagState.apply`/`foldDels` fold
(`TagState.kt:51-78`) is the seam to extend, not bypass. Cover resurrection
with a test (re-deliver a writer's full state-as-delta after the observed
remove; the element must stay absent).

### The distributed boundary — decided, not open

A remove covers only tags **this node has observed**. A concurrently-arriving
remote add (unobserved at remove time) survives by add-wins. This is the
**intended boundary**, per `[24-SET-03]` — document it as such (KDoc on the new
primitive is sufficient; do not open a spec change) and cover it with a test.
It is not a bug to fix, and no coordination/consensus machinery may be added
to close it.

### What is NOT open

- **No edits to `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` or
  `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`.** Both are
  owned by the concurrent inspector-plan ticket `V1C-KERNEL` for the whole
  current run. If the shape you choose genuinely cannot avoid `SetCell.kt`,
  **stop and report** — that is a replan trigger, not a license to edit.
  (Both named shapes avoid it: the tombstone mechanics live at the
  union/eraser level, and writer tag state is *read* via the union's merged
  view, never mutated in place.)
- **No `concord/**` edits.** If the new primitive deserves corpus coverage,
  say so in the report; authoring it belongs to a follow-up.
- **No shared-writer redesign** — per-user writers stay (M10.4).
- **No remove-wins bias, no LWW, no epochs/compaction** — add-wins semantics
  are normative (`[24-SET-02]`/`[24-SET-03]`); tag compaction (G-25) and
  epoch hygiene (G-42) stay open exactly as they are.
- **No exclusive-payload drops** — `SetDelta` is a plain mergeable payload
  here; do not introduce `Owned`/`Leased` handling on this path.
- **No `gen/` edits expected.** A new `@Contract`/`@CellBase` surface (e.g. a
  remove-ops inlet) is generated by the existing processors at build time; if
  you find yourself changing the generator, stop and report.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/data/op/UnionSetCell.kt` — the new
  primitive (shape 1), or its observation seam (shape 2).
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt` and, if
  needed, `kernel/src/main/kotlin/civictech/cell/data/delta/SetDelta.kt` —
  **additive** extensions only (tombstone retention for union-scoped dels);
  keep wire encoding additive (stable `@SerialName`, no field removals).
- Possibly a new cell under `kernel/src/main/kotlin/civictech/cell/data/op/`
  (shape 2's eraser) — follow `TaggedSetOperator`'s composition pattern
  (`kernel/src/main/kotlin/civictech/cell/data/op/TaggedSetOperator.kt:24-39`).
- `kernel/src/test/kotlin/civictech/cell/data/` — new focused tests named for
  this ticket (see acceptance criteria; `UnionSetCellTest.kt` and
  `SetConvergenceTest.kt` are the style exemplars).
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt` — rewire the
  `"remove"` action (`:224`) onto the primitive; keep or add a distinct
  "remove mine" if you keep the writer-local op exposed; update the ponytail
  comment at `:218-223` (it documents the defect this ticket closes); UI
  button wiring at `:392-402`. `"vote"` semantics stay untouched.
- `demo/shopping/src/test/kotlin/civictech/demo/` — extend
  `TwoJvmConvergenceTest` (or add a sibling) for cross-user removal, and
  extend/verify `CrashRestartConvergenceTest` for post-remove replay.
- This ticket's `**Status**:` line.

## Read first

- `backlog/union-scoped-observed-remove.md` — the design source, in full.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt` — **read-only**:
  the writer-local remove (`:97-103`), the `adds`/`dels` OR-set maps
  (`:44-45`), the replay-stable deterministic tag source (`:50-57`), replica
  merge (`:107-122`), catch-up re-assertion (`:170-176`), snapshot (`:200-207`).
- `kernel/src/main/kotlin/civictech/cell/data/delta/TagState.kt` — the merged
  tag ledger; the no-tombstone ponytail and its FIFO assumption (`:13-16`),
  `apply`/`foldDels` (`:51-78`), `tags(e)` (`:34`), `asDelta` (`:37`).
- `kernel/src/main/kotlin/civictech/cell/data/op/UnionSetCell.kt` — the merge
  cell: `onInlet` fold + emit-or-absorb (`:48-56`), late-join catch-up
  (`:44-46`), re-baseline path (`:63-67`).
- `kernel/src/main/kotlin/civictech/cell/data/delta/SetDelta.kt` — the delta
  algebra and wire form (`:14-21`).
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt` — writer family and
  union wiring (`:74-91`), `writerFor` (`:191-200`), `handleOp` (`:205-229`),
  peering + anti-entropy (`:150-171`), the UI (`:371-406`).
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt` —
  the exemplar for criterion (d): `JvmPeer.launch` + `awaitUntil` + SSE-state
  polling, `@Tag("multi-jvm")`.
- `demo/shopping/src/test/kotlin/civictech/demo/CrashRestartConvergenceTest.kt`
  — the `kill -9` + `--journal` replay exemplar for criterion (c) at demo level.
- `kernel/src/test/kotlin/civictech/cell/data/UnionSetCellTest.kt` (`:40` — the
  test whose assertion your primitive must *not* weaken: writer-local remove
  still leaves the other source's tag live) and
  `kernel/src/test/kotlin/civictech/cell/data/SetConvergenceTest.kt`.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:14-68` — the normative
  OR-set pattern and requirement ids.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

**Do not modify:** `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt`,
`kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` (both owned by
`V1C-KERNEL` this run — hitting either is a stop-and-report replan trigger),
anything under `concord/**`, anything under `gen/src/**`, generated output
under `build/generated/`, and any plan document other than this ticket's
`**Status**:` line.

## Acceptance criteria

- [ ] **(a) Single remove suffices on observed adds:** after
      `alice add e; bob add e; alice removeObserved e`, `e` is absent in the
      converged union — one remove, not N. Kernel-level test.
- [ ] **(b) Add-wins on the unobserved tag:** after `alice add e` with a
      **concurrent** `bob add e` unobserved at remove time,
      `alice removeObserved e` leaves `e` present once both streams merge.
      Covered by a test, and the boundary documented (KDoc) as intended
      behavior per `[24-SET-03]`.
- [ ] **(c) Journal replay reproduces post-remove membership:** after
      `kill -9` and relaunch with the same `--journal` directory, the
      recovered state has identical membership — the removed element stays
      removed (deterministic tags, M10.4). Demo-level via
      `CrashRestartConvergenceTest`'s pattern, plus a kernel- or demo-level
      replay assertion for the new op itself.
- [ ] **(d) Two-JVM convergence:** a `removeObserved` on peer A converges on
      peer B for all tags A had observed at remove time, in the
      `TwoJvmConvergenceTest` harness style — and with a **different user
      than the adder** doing the removing (the case the existing test never
      exercises).
- [ ] **No catch-up resurrection:** re-delivering a writer's full
      state-as-delta (late-join catch-up / anti-entropy replay) after an
      observed remove does not resurrect the element. Explicit test.
- [ ] **(e) demo/shopping offers a working "anyone can remove"** built on the
      primitive, replacing the silently-inert behavior (optionally alongside a
      distinct "remove mine"); the ponytail comment at `Main.kt:218-223` is
      updated to describe the new reality.
- [ ] Existing suites stay green: `:kernel:test` (including
      `UnionSetCellTest`'s writer-local semantics, unweakened) and
      `:demo:shopping:test` (convergence + crash-restart).
- [ ] Delta wire encoding remains additive — no removed/renamed serialized
      fields, `@SerialName` stability preserved.
- [ ] The diff contains **no** changes to `SetCell.kt`, `ManagedHost.kt`,
      `concord/**`, `gen/src/**`, or generated/build output, and no unrelated
      file churn.
- [ ] The completion report records the chosen shape and a short written
      rationale for it.

## Verify

```bash
./gradlew :kernel:test --tests '<new tests>'   # substitute the ticket's new test classes
./gradlew :kernel:test
./gradlew :demo:shopping:test
git status --porcelain    # only the claimed files
```

Note: the two-JVM/crash-restart tests are `@Tag("multi-jvm")` and run in a
plain unfiltered invocation (the gate properties `-PmultiJvmOnly` /
`-PexcludeMultiJvm` are CI lanes — `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:29-41`);
a default `./gradlew :demo:shopping:test` runs everything.

## Report on completion

- The chosen shape (1 or 2), up front, with the written rationale — including
  how it survives the catch-up-resurrection hazard.
- Exactly which tests were added and which suites ran, with results.
- Confirmation that `SetCell.kt` and `ManagedHost.kt` are untouched — or the
  stop-and-report if a shape genuinely required them (do not implement past
  that point).
- The documented add-wins boundary: where the KDoc lives, what the test
  asserts.
- Whether the new primitive deserves concord corpus coverage and/or a spec 24
  paragraph (recommendation only — both are out of scope here).
- Anything specified here you could not do, and why.
