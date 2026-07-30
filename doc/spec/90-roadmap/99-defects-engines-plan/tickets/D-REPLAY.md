# D-REPLAY — quorum fan-ins install replayed baseline frames as recovered arm state instead of silently dropping them

**Status**: Implemented — on branch `ticket/d-replay`, awaiting merge.
**Model:** claude-opus-5 (effort xhigh) · **Escalate to:** claude-fable-5,
fresh session
**Wave:** B1 · **Branches:** ticket/d-replay

## Context

ComputeNet is a Kotlin/JVM dataflow runtime. Durable recovery replays a
journaled cell's write-ahead frames through the ordinary intake, and PN-2
decided that this replay is a **baseline**, not a live wave. The normative
sentence is `[24-REPLAY-01]`
(`doc/spec/20-dataflow-semantics/24-data-cells.md:653-657`, in the PN-2
section that opens at `:645`):

> WHEN a journaled mid-graph cell's replayed frames carry a non-null wave
> context, it SHALL re-emit its restored deltas flagged
> `MessageContext.baseline`, such that a downstream glitch-free join installs
> them as arm state without waiting for a volatile sibling arm to replay the
> same wave (Event-driven).

The stamping half of this works and is **not in question**:

- `HostDurability.recoverFrom`
  (`kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:120`) replays
  under `replayAsBaseline = true` (the production default, `:97`) and stamps
  every replayed mid-graph frame's wave context with a non-null
  `MessageContext.baseline` via `HostedPortInvocation.baselined` (`:184-187`).
  The `baseline` field itself is
  `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:54`.
- The **generic glitch-free join** honors it: `WaveFrontier.offer()`
  (`kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:231`)
  has a dedicated baseline branch (`:239-247`) — a baseline delivery is
  released immediately, never buffered, excluded from every wave-completeness
  set. The KDoc at `:197-230` explains the three delivery dialects (unwaved
  push catch-up, baseline pull catch-up/replay, ordinary waved).
- Proven end to end by
  `kernel/src/test/kotlin/civictech/cell/durability/DurableGlitchFreeReplayTest.kt`
  (100 seeds, plus a stall control and a PN-1-reverted control).

But `WaveFrontier` is not the only glitch-free fan-in mechanism in the
kernel. `QuorumSetCell`
(`kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt`) and
`PresenceCountCell` share `PresenceLanes`
(`kernel/src/main/kotlin/civictech/cell/data/op/PresenceCountCell.kt:44-116`)
— a **lane-counting fan-in**: one `TagState` per open source link, an
element's presence count is the number of distinct live lanes asserting it,
and `QuorumSetCell.evaluate` (`QuorumSetCell.kt:85-114`) admits an element to
the advertised view only when `count >= threshold(liveSources)` (`:87`,
`:94`). This is architecturally different from `WaveFrontier`'s
wave-buffer-and-release, and PN-2 patched only the latter.

## Problem

**Empirically confirmed defect** — the `kernel-gap` recorded in the
`24-REPLAY-01` entry of `concord/corpus/DISPUTES.md:451-472` (under "Not
covered", `:444`). Read that entry in full; it is the authoritative statement
of both the defect and the decided fix. In summary:

- `QuorumSetCell`/`PresenceLanes` **never consult `MessageContext.baseline`**
  — grep confirms zero references in either file (the only `baseline` hits
  under `kernel/.../cell/data/` are `SetCell.kt`, `TagState.kt`, and
  `UnionSetCell.kt`, all other mechanisms).
- Verified failure, from the dispute's own throwaway kernel test: one
  **journaled arm** + one **volatile arm** feeding a quorum-set with
  threshold k=2; pre-crash view `[e1, e2]`; after crash + `recoverFrom`, the
  replayed baseline frames fold into their lane but stick at lane-count
  1 (< 2) forever, and the view is silently **empty**. Recovered state is
  dropped, not installed as baseline arm state — a silent data loss on the
  recovery path, and a direct violation of `[24-REPLAY-01]`.
- k=1 passes trivially regardless of baseline handling (any single lane meets
  the threshold), so a k=1 test is **uninformative**. The honest test must use
  k >= 2 so it genuinely tears.

This also breaks the system-wide invariant that in-process replay preserves
the same observable semantics as the uninterrupted run, and the invariant
that no path silently drops delivered state.

## Solution direction

The fix is **decided**, by the dispute entry's own Resolves clause
(`concord/corpus/DISPUTES.md:468-472`) — this is settled, not open for
redesign:

> `PresenceLanes.fold` / `QuorumSetCell.evaluate` must consult
> `CurrentContext.get()?.baseline` and, when set, install the delta as
> authoritative recovered arm state bypassing the live-threshold — the
> SET-fan-in analogue of `WaveFrontier.offer()`'s baseline branch.

What that means, concretely:

- `PresenceLanes.fold` (`PresenceCountCell.kt:90-94`) already attributes a
  contexted delivery to its lane by `ctx.sourcePort` (`laneFor`, `:96-103`),
  and under production defaults PN-1's stable port derivation
  (`PortIdentity.kt:41`, `deriveRefs = true`) makes a replayed frame's
  `sourcePort` match the rebuilt lane. So the lane fold itself lands; what is
  missing is the **disposition**: a baseline delivery's elements must enter
  the advertised view (`ledger.enter`, the `AdvertisedLedger` of
  `JoinLedger.kt:63`) as authoritative recovered arm state even though the
  live count is below `threshold(liveSources)` — exactly as `WaveFrontier`
  releases a baseline without waiting for sibling arms.
- The live path stays **byte-identical**: for deliveries with
  `baseline == null`, threshold evaluation, the effective-only discipline
  (`TagState.apply` returns the effective delta, `TagState.kt:63`), the
  advertised-tag exit discipline (exit deletes exactly the advertised tags),
  and the absorb-ack funnel (`emitOrAbsorb`, `Emit.kt:19`,
  `QuorumSetCell.kt:109-113`) are unchanged. The existing
  `QuorumSetCellTest`/`PresenceCountCellTest` suites are the regression
  harness for this.
- A baseline delivery must never be silently dropped either: it mutates the
  view, or it is accounted through the same absorb-ack discipline. No
  exclusive (`Owned`/`Leased`) payload handling changes.
- The re-emission that `evaluate` produces *while handling* a baseline
  delivery rides the current `CurrentContext` (`MessageContext.kt:166`), so
  it carries the baseline stamp downstream automatically — do not strip it; a
  chained glitch-free consumer further downstream depends on it (this is the
  same context-copy propagation `DurableGlitchFreeReplayTest` observes at its
  sink).
- Elements installed by baseline remain subject to **later live
  re-evaluation** (a subsequent delta touching them, or an
  `EdgeOpen`/`EdgeClose` shifting `n`) under the ordinary live threshold.
  Convergence back to live semantics after recovery is expected behavior, not
  a bug to prevent. How the implementation remembers or ceases to
  special-case baseline-installed elements is implementer latitude — the
  non-negotiable is: recovered state is installed, live semantics are
  untouched, nothing is silently dropped.
- **`PresenceCountCell`**: the dispute names the shared substrate, so decide
  with evidence whether the count cell needs its own treatment. It has no
  threshold gate — once `fold` attributes the baseline delta to its lane,
  `recompute` (`PresenceCountCell.kt:166-184`) may already emit the correct
  (post-crash-truthful) count. Verify rather than assume; if it needs the
  fix, include it; either way state the conclusion and the evidence in your
  report.

**Not open for redesign here:**

- Do not embed a `WaveFrontier` inside the quorum family, journal the quorum
  cell itself, or otherwise "fix" this by changing where durability applies.
- Do not touch the stamping side: `HostDurability`, `WaveFrontier`,
  `ManagedHost`, `MessageContext` are correct and out of scope.
- Do not conflate this with the RESTART **re-baseline**
  (`ReBaselineNotice`/`TagState.applyReBaseline`, `TagState.kt:89`, consumed
  by `UnionSetCell.kt`) — that is a different mechanism on a different plane
  (tag-source supersession, D-C12 territory), not `MessageContext.baseline`.
- No spec edits; no `concord/**` edits. The follow-up ticket D-CONCORD
  authors the `24-REPLAY-01` corpus scenario and resolves the DISPUTES.md
  entry **after this merges** — this ticket is kernel-only.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt`
- `kernel/src/main/kotlin/civictech/cell/data/op/PresenceCountCell.kt`
  (`PresenceLanes`, and the count cell itself only if the evidence says so)
- **New**:
  `kernel/src/test/kotlin/civictech/cell/durability/DurableQuorumReplayTest.kt`
  — the focused recovery test (name is a suggestion; keep it in
  `civictech.cell.durability` beside `DurableGlitchFreeReplayTest`)
- This ticket's `**Status**:` line.

Nothing else.

## Read first

- `concord/corpus/DISPUTES.md:444-472` — the `24-REPLAY-01` "Not covered"
  entry: defect, verification, and the Resolves clause this ticket executes.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:645-663` — the PN-2
  section and `[24-REPLAY-01]`, including the tag-continuity note (the
  baseline marks the *wave-plane* disposition of the replay, not the
  *state-plane* merge, which stays ordinary tag-set union).
- `kernel/src/main/kotlin/civictech/cell/data/op/QuorumSetCell.kt` — whole
  file (149 lines): `onInlet` `:81-83`, `evaluate` `:85-114`,
  snapshot/restore `:116-123`, the `kOfN`/`intersection` factories
  `:129-146`.
- `kernel/src/main/kotlin/civictech/cell/data/op/PresenceCountCell.kt` —
  whole file: `PresenceLanes` `:44-116` (`fold` `:90-94`, `laneFor`
  `:96-103`, restore `:108-115`), the count cell `:139-197`.
- `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:197-247`
  — the three-dialect KDoc and the baseline branch you are writing the
  SET-fan-in analogue of.
- `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:81-187` —
  how frames are stamped on replay (`replayAsBaseline` `:97`, `recoverFrom`
  `:120`, `baselined` `:177-187`).
- `kernel/src/test/kotlin/civictech/cell/durability/DurableGlitchFreeReplayTest.kt`
  — the build/crash/rebuild/recoverFrom harness shape (per-cell `journalFor`
  selector, `InMemoryJournal` as "the disk", `SimulationController` +
  `runToIdle`, `forEachSeed`), and the control-test discipline. Reuse its
  session structure for the new test.
- `kernel/src/test/kotlin/civictech/cell/data/QuorumSetCellTest.kt` and
  `kernel/src/test/kotlin/civictech/cell/data/PresenceCountCellTest.kt` —
  the live-path regression harness; also the idiom for wiring a `SetDelta`
  fan-in graph.
- `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:50-54`, `:166`
  (`CurrentContext`).
- `testkit/src/main/kotlin/civictech/testkit/ForEachSeed.kt:14`,
  `testkit/src/main/kotlin/civictech/testkit/AwaitUntil.kt:11` — seed sweep
  and bounded-wait conventions.
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `concord/**` (single-writer, owned by D-CONCORD),
`kernel/.../cell/host/ManagedHost.kt` and `kernel/.../cell/data/SetCell.kt`
(owned by the concurrent inspector-plan ticket V1C-KERNEL),
`kernel/.../cell/host/HostDurability.kt`,
`kernel/.../cell/consistency/WaveFrontier.kt`,
`kernel/.../cell/data/op/UnionSetCell.kt` (RESTART re-baseline plane),
`doc/spec/**` (including `CONCORDANCE.md`), `wire/**`, `demo/**`,
`inspect/**`, `gen/**`, any existing test file beyond additive imports it
already permits.

## Acceptance criteria

- [ ] A focused kernel test reproduces the confirmed failure and passes with
      the fix: one **journaled** arm + one **volatile** arm feeding a
      `QuorumSetCell` with threshold **k=2** (`kOfN(2)` or `intersection` over
      two sources); pre-crash view `[e1, e2]`; crash discards everything but
      the journal; after `recoverFrom` the quorum's advertised view **equals
      the pre-crash view**. All waits bounded
      (`SimulationController.runToIdle` / `awaitUntil`), no sleeps.
- [ ] The test genuinely tears without the fix — demonstrated by a control in
      the style of `DurableGlitchFreeReplayTest`'s "control a" (e.g. the same
      session asserted red against the pre-fix behavior via
      `replayAsBaseline = false`, or a documented run of the test on the
      unpatched tree). A test that would pass on the unpatched kernel fails
      this criterion.
- [ ] No k=1 quorum as the main assertion (uninformative per the dispute);
      k=1 may appear only as an auxiliary control.
- [ ] A seeded variant mirrors `DurableGlitchFreeReplayTest`'s style
      (`forEachSeed` over a stated range, randomized `controller.step()`
      interleaving) where sensible — if you judge it not sensible, say why in
      the report instead of silently omitting it.
- [ ] The `PresenceCountCell` question is answered with evidence: either it
      needs the baseline treatment and got it (with its own recovery
      assertion), or the test/report shows its recovered counts are already
      truthful without it.
- [ ] Live-path behavior for non-baseline traffic is unchanged:
      `QuorumSetCellTest` and `PresenceCountCellTest` pass **unmodified**;
      effective-only emission, advertised-tag exit discipline, and the
      absorb-ack funnel are intact.
- [ ] No delivery is silently dropped: a baseline delivery either mutates the
      view or is absorb-acked; no `Owned`/`Leased` handling changes.
- [ ] The diff touches only the files in "Files expected to touch"; nothing
      under `concord/**`, no `ManagedHost.kt`, no `SetCell.kt`, no
      `HostDurability.kt`, no `WaveFrontier.kt`, no `doc/spec/**`, no
      generated/build output. `git status --porcelain` shows only the claimed
      files.
- [ ] `./gradlew :kernel:test` is green.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.durability.DurableQuorumReplayTest'
./gradlew :kernel:test
```

(Substitute the first command's FQN if you named the new test differently;
state the actual FQN in your report.)

## Report on completion

- The new test's FQN, its seed range, and confirmation it was red before the
  fix (and how that was demonstrated) and green after.
- The `PresenceCountCell` verdict — treated or provably unnecessary — with
  the evidence.
- Exactly which test tasks ran (`:kernel:test` filtered and full) and their
  results.
- Any observed divergence between the dispute entry's description and what
  the code actually did, however small — D-CONCORD consumes this report when
  it authors the corpus scenario and resolves the ledger entry.
- Anything specified here you could not do, and why.
