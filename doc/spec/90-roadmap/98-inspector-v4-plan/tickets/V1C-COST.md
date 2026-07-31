# V1C-COST — the O(page) resume becomes a ratchet across every paged family, not just `SetCell`

**Status**: Specified — not-started
**Model:** claude-sonnet-5 (effort xhigh) · **Escalate to:** claude-opus-5,
fresh session
**Wave:** unscheduled — queued by checkpoint C-replan-2 for the next planning
session · **Branches:** ticket/v1c-cost

## Context

The whole V1c bounded-read chain (waves 8–11) was authorised by one measurement
and one constraint. `30-bounded-read-measurement.md` measured that paging a
whole-state copy removes ~85–99% of the live-traffic stall it imposes, at a
total-work premium of only 1.7–2.4×, and checkpoint **C7 accepted that trade
conditionally**:

> **`V1C-KERNEL`'s cursor must resume in O(page), not O(n).** E3's
> counterfactual used a `List<Int>` stand-in with an O(1) seek; a cursor that
> rescans the tag map from the start on each page would turn the measured
> 1.7–2.4× premium into O(n²) and invalidate the trade this checkpoint
> accepted. (`00-orchestration.md`, C7's carried-forward finding 2)

The obligation is now normative in the kernel:
`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt:55-59` states it as
obligation 2 — a walk may pay a bounded number of O(n) passes, but *per page*
work must be O(`StateRead.limit`).

Checkpoint C-replan-2 verified the delivered chain against that constraint and
found it **honoured everywhere**: `SetCell`'s `SetWalk`, the wave-9 keyed
families' shared `KeyWalk`
(`kernel/src/main/kotlin/civictech/cell/data/BoundedWalk.kt:51-55`),
`OperatorPaging`'s `OperatorWalk`
(`kernel/src/main/kotlin/civictech/cell/data/op/OperatorPaging.kt:282-288`) and
`ListCell`'s positional integer all carry the frozen enumeration order *inside
the cursor token* and seek by index. Nothing rescans.

This ticket is not a fix. It is the **ratchet** that keeps that true.

## Problem

The constraint is enforced by an executable test for exactly one cell.

`kernel/src/test/kotlin/civictech/cell/host/BoundedReadCursorCostTest.kt` is a
real guard — a `CountingKey` (`:49-59`) counts every `hashCode`/`equals` the
fold performs, and the test asserts (`:95-100`) that the last page of a 40-page
walk over N = 4,000 costs no more than the first, with generous ceilings chosen
so *only a change of asymptotics* can break them. It covers `SetCell`.

Its own second test names the gap it leaves:

> Stated separately because it is the property `V1C-CELLS`/`V1C-OPS` must
> preserve when they copy the pattern onto their own state layouts.
> (`BoundedReadCursorCostTest.kt:106-108`)

`V1C-CELLS` and `V1C-OPS` did preserve it — and shipped **no cost assertion of
their own**. `MapCellBoundedReadTest`, `KeyedSetCellBoundedReadTest`,
`ShardCellBoundedReadTest`, `InstanceSetBoundedReadTest`,
`WatermarkCellBoundedReadTest`, `ListCellBoundedReadTest`,
`OperatorBoundedReadTest`, `OperatorCursorOrderTest` and
`OperatorBoundedReadEdgesTest` are all correctness tests. Every one of them
stays green under a purely asymptotic regression.

The regressions are one line each, and they are the kind a refactor makes
without noticing:

- Moving `subStates.map { it.freeze() }` out of the Elvis right-hand side on
  `OperatorPaging.kt:314-315` — where it currently sits precisely so it is
  evaluated only on a fresh walk — re-freezes every sub-state on **every**
  page. O(n) per page, O(n²) per walk, all thirteen operator cells at once,
  every existing test green.
- Dropping the `?:` on any `as? KeyWalk<…>` decode
  (`MapCell.kt:115`, `KeyedSetCell.kt:206`, `ShardCell.kt:250`,
  `InstanceSet.kt:197`, `Watermark.kt:274`) does the same for one family.
- Hoisting `currentFrontier(scope)` out of its `complete && !opening` guard
  (`SetCell.kt:370`, `ShardCell.kt:284`) restores the exact per-page frontier
  that C7 ruled out — the O(n)-rescan-per-page shape, arriving as a
  *correctness improvement*.

Second, smaller problem, carried here from checkpoint **C9's residual 2** so it
stops travelling: `ReadCaveat.STALE_FRONTIER`'s KDoc reasons from *"and a
`TagFrontier` is monotone, so the stability check of `StatePage` is
unaffected"* (`BoundedRead.kt:310-311`). `V1C-OPS` established that no cell in
`cell/data/op/**` is such a family — every `TagState` there is non-retaining
and both `JoinLedger` implementations' `exit` *removes* the advertised tag, so
a mid-walk removal mints nothing and can *lower* the stamp. Each affected
`readBounded` says so locally; the kernel-level KDoc still generalises. C9
recorded that "a later ticket owning `BoundedRead.kt` should soften that
sentence"; this is that ticket.

## Solution direction

**Part 1 — generalise the cost ratchet.**

Extend the `CountingKey` device from `BoundedReadCursorCostTest` to cover, at
minimum, one representative of each distinct cursor shape now shipped:

| shape | cursor | representative |
|---|---|---|
| single frozen key list, unsorted | `SetWalk` | `SetCell` (already covered) |
| single frozen key list, `EntryOrder`-sorted | `KeyWalk` | `MapCell` **and** one of `KeyedSetCell`/`ShardCell` |
| per-sub-state frozen lists, lexicographic `(subState, key)` | `OperatorWalk` | one `pageOver` cell (e.g. `JoinSetCell`) **and** one `TaggedSetOperator.page` cell (e.g. `FilterCell`) |
| positional, no freeze | boxed `Int` | `ListCell` |

Covering more than one cell per shape is welcome but not required; covering
fewer is not acceptable, because the shapes fail differently.

**The wrinkle you must design around, stated so you do not discover it as a
flake.** `EntryOrder.freeze`
(`kernel/src/main/kotlin/civictech/cell/data/BoundedWalk.kt:112-116`) sorts, and
`EntryOrder.compare` calls `hashCode()` on the keys (`:106`). A `CountingKey`
therefore records O(n log n) touches *inside the opening page* for every
`KeyWalk` family. That is **not** a violation — `BoundedRead.kt:55-59`
explicitly permits a bounded number of O(n) passes per walk, and `SetCell`
itself pays two. So:

- assert the per-page ceiling over pages **2..k**, not over page 1;
- assert a whole-walk ceiling that admits the opening pass (O(n log n) for the
  sorted families, O(n) for the rest) and still excludes a rescan, which costs
  ~n·(k/2) — at N = 4,000 / limit = 100 those are ~50k and ~80k versus ~4M, so
  a generous multiple separates them by two orders of magnitude;
- state both bounds' derivations in a companion-object KDoc the way the
  existing test does (`:141-151`). A ceiling nobody can re-derive is a ceiling
  someone will "fix" by raising.

Keep the existing test's register exactly: `SimulationController`,
`runToIdle`, bounded `get(TIMEOUT_MS, …)`, generous structural multiples, and a
`withClue` carrying the per-page vector. This is *"a correctness test with a
cost assertion, not a benchmark"* (`:34-36`) — do not add timing, allocation
counting, or anything sensitive to JIT, GC or machine load. Concord and the
repo gate run on contended machines.

Prefer **one new file** holding the generalised device, or an extension of the
existing one — your call, but the `SetCell` case must not be duplicated or
weakened. If you add a file, `kernel/src/test/kotlin/civictech/cell/host/` is
the right package (the existing test lives there because the walk is driven
through `ManagedHost.readState`, which is the surface the ratchet protects).

**Part 2 — soften the `STALE_FRONTIER` KDoc.**

Rewrite the monotonicity sentence at `BoundedRead.kt:305-311` so it states the
tag-frontier family case as the *conditional* it is, and names the operator
family as the counterexample. Two facts must survive the rewrite because
downstream text depends on both:

- the per-page rescan is O(n)/O(n²) and is what the caveat exists to avoid;
- the first and last page of a walk always carry an exact frontier.

And one fact must be added: for a family whose tags can be *removed*, a
mid-walk removal mints nothing and can lower the stamp, so equal endpoint
stamps are **necessary but not sufficient** for the union to be a snapshot —
the same qualification `StatePage`'s across-page KDoc already carries for the
observed-remove case (checkpoint C8's repair) and that `[21-PULL-03]` carries
in the spec after checkpoint C11's ruling 2.

Cross-check your wording against `concord/schema/scenario.md`'s
§"What a conforming driver must observe" — `pages-equal-view`'s equal-stamp
paragraph is the implementation-neutral statement of the same property, and the
two must not contradict. **Do not edit `concord/**`.**

**Latitude**: file/class/test names; which representative you pick per shape
beyond the minimum above; whether the device is a shared helper or duplicated
per family; the exact N and limit (keep them small enough that the suite stays
fast and large enough that a rescan cannot hide — the existing 4,000/100 is a
good default).

**NOT in scope:**

- **No production-code behaviour change.** Part 2 is KDoc only. If a cost
  assertion fails against shipped code, that is a **finding, not a licence to
  fix** — report it with the measured per-page vector, and do not adjust the
  cell. (C-replan-2's own reading says every family passes; a failure means one
  of us is wrong and that needs adjudicating, not patching.)
- No new `BoundedStateful` implementations, no `since`/`scope` support added
  anywhere, no change to `EntryOrder`, `KeyWalk`, `OperatorWalk` or any freeze.
- **The two intra-key orderings that coexist** (checkpoint C9's residual 1 —
  `V1C-CELLS` uses value-derived `EntryOrder`, `cell/data/op/**` freezes
  encounter order) are explicitly *not* unified here. C9 assigned that to
  whoever needs page-order comparability across instances. Your tests must
  therefore not assume the operator family's order is stable across a
  `snapshot()`/`restore()`.
- No `inspect/**`, no `concord/**`, no `demo/**`, no `wire/**`, no `gen/**`.
- `kernel/.../host/ManagedHost.kt` and `kernel/.../data/SetCell.kt`: read them,
  do not edit them.

## Files expected to touch

- **New** (or **modified**):
  `kernel/src/test/kotlin/civictech/cell/host/BoundedReadCursorCostTest.kt` and/or
  a sibling file in the same package.
- **Modified**: `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` — the
  `ReadCaveat.STALE_FRONTIER` KDoc only (`:305-311`). No signature, no
  behaviour, no new member.
- This ticket's `**Status**:` line.

Nothing else. No generated/build output in the diff.

## Read first

- `kernel/src/test/kotlin/civictech/cell/host/BoundedReadCursorCostTest.kt` —
  in full. It is the register, the device and the ceiling-derivation style you
  are generalising.
- `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt:40-70` (the obligations,
  including the `POSITIONAL_CURSOR` exception) and `:260-330` (`Cursor`,
  `StatePage`'s across-page contract, `ReadCaveat`).
- `kernel/src/main/kotlin/civictech/cell/data/BoundedWalk.kt` — `KeyWalk`
  (`:37-55`), `EntryOrder` (`:88-117`) including the `hashCode` call in
  `compare` that your ceilings must account for.
- `kernel/src/main/kotlin/civictech/cell/data/op/OperatorPaging.kt:280-370` —
  `OperatorWalk`, the Elvis-guarded freeze at `:314-315`, the `exact =
  complete && !opening` gate at `:360-366`. These three lines are what the
  operator-shape ratchet protects.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:265-405` — the
  reference walk, for the shape comparison.
- `doc/spec/90-roadmap/98-inspector-v4-plan/30-bounded-read-measurement.md` §6
  and §10 — the trade the constraint protects, and §"What could not be done"
  (`:297-309`), which is where the `List<Int>` stand-in is disclosed.
- `doc/spec/90-roadmap/98-inspector-v4-plan/00-orchestration.md` — C7's
  carried-forward finding 2 (the constraint), C9's residual 2 (the KDoc), and
  the C-replan-2 section (this ticket's verdict).
- `concord/schema/scenario.md` §"What a conforming driver must observe" —
  read-only cross-check for part 2.
- `AGENTS.md` §"Core invariants to protect" (last bullet: deterministic
  simulation tests, no friendlier seeds) and §"Verification".

Do not modify: `concord/**`, `inspect/**`, `demo/**`, `wire/**`, `gen/**`,
`kernel/.../host/ManagedHost.kt`, `kernel/.../data/SetCell.kt`, any
`cell/data/**` or `cell/data/op/**` source file, any plan document other than
this ticket's `**Status**:` line.

## Acceptance criteria

- [ ] A per-page cost assertion exists for all four shipped cursor shapes:
      `SetWalk`, `KeyWalk` (at least two families), `OperatorWalk` (at least
      one `pageOver` and one `TaggedSetOperator.page` cell), and `ListCell`'s
      positional cursor.
- [ ] Each asserts that pages 2..k cost O(limit) — the last page no more than
      an early one, under a ceiling whose derivation is written down — and
      that the whole walk stays under a bound that admits the opening pass but
      excludes an O(n)-per-page rescan.
- [ ] The `KeyWalk` families' opening-page `EntryOrder` sort is accounted for
      explicitly rather than absorbed into a raised ceiling.
- [ ] Each new assertion genuinely fails under a simulated rescan. Demonstrate
      it: state in the report, per shape, the mutation you applied (e.g.
      re-freezing per page) and that the test tripped. A cost assertion nobody
      proved can fail is decoration.
- [ ] The existing `SetCell` assertions are unchanged and still green.
- [ ] `ReadCaveat.STALE_FRONTIER`'s KDoc no longer asserts monotonicity
      unconditionally, names the non-retaining operator family, keeps the
      O(n²)-avoidance and first/last-page-exact facts, and does not contradict
      `concord/schema/scenario.md`'s equal-stamp paragraph.
- [ ] No production behaviour change: the diff outside test sources is KDoc
      only.
- [ ] `./gradlew :kernel:test` green; `./gradlew :concord:docLints` clean;
      `./gradlew test` green.
- [ ] `git status` shows only the claimed files.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.host.BoundedReadCursorCostTest'
./gradlew :kernel:test --tests 'civictech.cell.data.*BoundedReadTest'
./gradlew :kernel:test --tests 'civictech.cell.data.op.*'
./gradlew :kernel:test
./gradlew :concord:docLints
./gradlew test
git status --porcelain     # only the claimed files
```

## Report on completion

- The ceilings you chose per shape, and the derivation of each in one line.
- **The mutation-proof table**: per shape, the one-line regression you
  temporarily applied and confirmation the assertion tripped. This is the
  ticket's load-bearing evidence.
- Whether any family's measured per-page cost surprised you — in particular
  any per-page work that is O(n) for a reason C-replan-2's reading missed.
  Report it; do not fix it.
- The final `STALE_FRONTIER` KDoc text, verbatim.
- Anything specified here you could not do, and why.
