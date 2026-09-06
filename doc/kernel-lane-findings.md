# Kernel-lane findings

Findings log for the kernel lane (`lane:kernel`). Each entry records an
audit result and its disposition — this file reports; it never edits specs,
plan documents, or the gap-analysis table itself. See AGENTS.md: "Do not
edit plan documents unless the task explicitly asks for documentation
maintenance."

## KFX — the Effectful processed-frontier is landed; the C-9 gap row is stale

Recorded by: `computenet-yh6.1.1.1` (feature `computenet-yh6.1.1`, epic
`computenet-yh6.1`). Base commit: `37a7f1c` (`main`).

### What was expected

The milestone plan's kernel-lane text (`doc/spec/90-roadmap/96-incremental-engines-plan.md`
§1) describes KFX as building "a minimal G-59 processed-frontier for
`Effectful`" — implying the mechanism does not yet exist and needs to be
built.

### What was found

The mechanism described is **already merged on `main`**. Verified by reading
every cited site at base commit `37a7f1c`:

- `interface Effectful` — `kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:22`.
- Per-`(cellRef, portName)` processed-frontier map (`processedFrontier: MutableMap<Pair<CellRef, String>, MutableMap<UUID, Long>>`),
  KDoc "G-59, fixes C-9; spec 20/24, 30/31, 50/52" —
  `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:100-106`.
- `alreadyProcessed(cellRef, portName, timestamp)`, an at-or-behind test —
  `HostDurability.kt:253-258`.
- `advanceAndJournalFrontier(cellRef, portName, timestamp)` (advance then
  journal, per-cell tee) — `HostDurability.kt:266-276` (body through 277).
- `FrontierRecord` / `RECORD_FRONTIER` durable record, plus frontier folded
  into the checkpoint payload — `HostDurability.kt:21,38-45,192-235,244`.
- The suppression at the `Effectful` inlet, consulted by both journal replay
  and post-recovery live delivery, KDoc "Effectful processed-frontier
  (G-59, fixes C-9)" — `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:797-859`
  (guard check at line 824, frontier advance at line 858). **Line-number
  note**: the epic (`computenet-yh6.1`) and feature (`computenet-yh6.1.1`)
  cite `ManagedHost.kt:785-815` for this block; at this task's base commit
  (`37a7f1c`) the actual span is `797-859`, because sibling feature
  `computenet-yh6.1.3` (`e5bb05f`, "The Effectful inlet guard is sound")
  landed first and inserted the KFX-16 commentary ahead of it. This entry
  cites the re-verified numbers, not the epic's.
- Kernel test `kernel/src/test/kotlin/civictech/cell/durability/EffectfulRecoveryTest.kt`
  (exists, exercises the frontier through recovery).
- Corpus scenario `concord/corpus/15-durability/DUR-REPLAY-01.yaml` —
  `covers: [24-DUR-01, 24-DUR-02, 24-DUR-05]`, asserts
  `{type: effect-count, sink: esink, exactly: 1}`.
- Normative markers, all present and reading "resolved"/"implemented":
  - `doc/spec/20-dataflow-semantics/24-data-cells.md:816` — "G-59 resolved, W2.6, closes C-9".
  - `doc/spec/30-execution-model/31-hosts.md:101` — "G-59 resolved in part, W2.6, closes C-9".
  - `doc/spec/50-development-process/52-verification.md:168` — "processed-frontier implemented, W2.6, closes C-9".
- Honesty ledger `concord/corpus/DISPUTES.md:511-535` ("The boundary (`kernel-gap`
  / design ceiling, G-59 / C-9) — not faked, respected") records the *residual*
  G-59/C-9 boundary precisely: the frontier keys on `MessageContext.timestamp.sourceId`,
  which a `FanOutlet` mints per-instance rather than ref-derived, so a
  *journaled source feeding an effectful sink* would double-fire on recovery.
  That residual is explicitly owned by a sibling feature
  (`computenet-yh6.1.2`), not by this task. A second, distinct boundary
  (`DISPUTES.md:537-558`, KFX-16 — the frame with no `MessageContext` never
  gets a frontier position and re-fires) is likewise already recorded and
  owned by `computenet-yh6.1.3`, which has already merged its guard-soundness
  work (`e5bb05f`).

### Which documents disagree

`doc/spec/90-roadmap/91-gap-analysis.md:18` (the C-9 row) still reads:

> Decided rule ([93 I-7](93-feature-interactions.md)): `Effectful` sinks
> journal a processed frontier and replay suppresses re-driving them;
> **code diverges; fix pending**

This is stale relative to specs 20/24 (`24-data-cells.md:816`), 30/31
(`31-hosts.md:101`), 50/52 (`52-verification.md:168`), and the corpus
(`DUR-REPLAY-01.yaml`, `DISPUTES.md:511-535`), all of which record the
frontier as landed and the divergence as the narrower, already-documented
G-59/C-9 residual (double-fire only when a journaled source feeds an
effectful sink), not an absence of the mechanism.

### Disposition

Report, do not edit. Per milestone plan §6, only the periodic integration
pass writes `doc/spec/90-roadmap/91-gap-analysis.md`; per AGENTS.md, plan
documents are not edited without explicit documentation-maintenance
authorization. This task's diff touches only this findings file.

### BS-01 / [KFX-02] — review gate: exactly one processed-frontier implementation

Enumerated on base commit `37a7f1c`:

```
$ grep -rn "alreadyProcessed\|processedFrontier\|advanceAndJournalFrontier" kernel/src/main
kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:824:   hostDurability.alreadyProcessed(...)
kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:858:   hostDurability.advanceAndJournalFrontier(...)
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:54: * ... reads [alreadyProcessed]/[advanceAndJournalFrontier] ...
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:106: private val processedFrontier = ...
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:216: val frontier = processedFrontier
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:244: record.frontier.forEach { ... processedFrontier ... }
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:257: fun alreadyProcessed(...)
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:258: ... processedFrontier[...]?.get(...) ...
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:262: ... processedFrontier.getOrPut(...) ...
kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:273: fun advanceAndJournalFrontier(...)
```

(10 matches total, i.e. `… | wc -l` is 10 and `grep -rc` reports
`HostDurability.kt:8`, `ManagedHost.kt:2`. Paths and line numbers above are
verbatim and the match set is complete; the matched *text* is elided to keep
the block readable, and the two files are grouped rather than left in walk
order. Line 258 is `alreadyProcessed`'s body — the second line of its
two-line declaration — and is listed so the count reconciles.)

**Exactly one** per-`(cellRef, portName)` processed-frontier mechanism
exists: the `processedFrontier` map owned by `HostDurability`, with its
`alreadyProcessed`/`advanceAndJournalFrontier` accessors, consulted from
exactly one call site (`ManagedHost.kt`'s `Effectful` inlet guard).

**Standing review gate for the remainder of epic `computenet-yh6.1`**: no
diff landed by any sibling feature (`computenet-yh6.1.2` through
`computenet-yh6.1.5`) may introduce a second per-`(cellRef, portName)`
processed-frontier mechanism, a second frontier map, or a second
`alreadyProcessed`/`advanceAndJournalFrontier`-shaped pair. After the epic
merges, re-running the grep above against the merged tree must still show
exactly the one implementation site in `HostDurability` plus its one call
site in `ManagedHost`. Reviewers of those sibling features: check the diff
against this gate before approving.

That grep is keyed on the three current identifiers, so on its own it would
miss a duplicate built under different names. Two name-independent anchors
close that hole; both are cheap and both are pinned to base commit `37a7f1c`:

1. **Where a duplicate would have to sit.** Any per-invocation dedup at an
   effect boundary needs an `Effectful` type test. In `kernel/src/main` there
   are exactly five, of which only two are dedup-related:

   ```
   $ grep -rn "is Effectful\|is civictech.cell.evolve.Effectful" kernel/src/main
   kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:57:        if (cell is Effectful) suppress(cell)
   kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:823:                        if (cell is Effectful && timestamp != null &&
   kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:855:                            if (cell is Effectful && timestamp != null) {
   kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:53: * PORT_API branch, which tests `cell is Effectful && timestamp != null` and does
   kernel/src/main/kotlin/civictech/cell/replication/Replication.kt:190:        if (cell is civictech.cell.evolve.Effectful) {
   ```

   (Verbatim, 5 matches. Reading them: `ManagedHost.kt:823` is the guard and
   `:855` the advance — the only two dedup sites. `Evolution.kt:57` is shadow
   suppression, `Replication.kt:190` a replication-admission refusal, and
   `CatchUp.kt:53` is KDoc prose, not code.)

   A new `is Effectful` site in a sibling diff is the signal to look closely:
   it is either a duplicate frontier (gate violation) or a deliberate,
   argued extension of the single one.

2. **What a duplicate would have to persist.** A second frontier that
   survives recovery needs its own durable record type. The journal has
   exactly three record tags at `HostDurability.kt:19-21` (`RECORD_FRAME`,
   `RECORD_CHECKPOINT`, `RECORD_FRONTIER`) plus the frontier folded into
   `CheckpointRecord` (`HostDurability.kt:41-45`). A fourth record tag, or a
   second frontier field on a checkpoint payload, is a gate violation unless
   it is demonstrably the *same* frontier being re-encoded.

   Note the converse is not a pass: a duplicate that is purely in-memory
   (never journaled) still violates the gate and is caught only by anchor 1
   or by the identifier grep.

Sibling `computenet-yh6.1.2` is the one to watch hardest — it changes
recovered-outlet wave identity, i.e. the `sourceId`/`counter` the single
frontier is keyed on. Changing what the existing frontier is keyed on is in
scope for it and is *not* a gate violation; adding a parallel structure that
records "already acted" per inlet is.

## KE3-D4 — the stability read is interest-blind, so PN-6 sharding freezes cross-slice sources

Recorded by: `computenet-9sm.3.1` (feature `computenet-9sm.3`, epic
`computenet-9sm`). Base commit: `ac146f7b2` (`main`).

### What was decided

Decision 9sm.3-D4: `civictech.cell.consistency.CausalStability` takes three
injected reads (`watermarkOf`, `membersOf`, `watermarkRefOf`) and does **not**
apply `interestOf`, unlike its sibling `ReplicaQuorum`, which takes four. The
stability read is per logical id, not per key, so there is no key against
which an `Interest` could be evaluated; carrying `interestOf` would leave a
dead constructor parameter.

### The limitation this leaves

Under PN-6 sharding — an instance set whose members carry disjoint
`Interest`s — a member never delivers waves outside its own slice, so its
watermark row never gains a column for the sources belonging to other slices.
`[42-WM-05]` reads an absent column as bottom, so **every cross-slice source
is permanently bottom** and `stableFrontier` never advances for it. Stability
therefore freezes for the whole logical id as soon as the instance set is
sharded, even though each slice is individually making progress.

This is the conservative direction — the read under-reports stability and
never runs ahead of the true global frontier — so it is safe, and it is the
same shape as the R14 supersession freeze recorded in
`concord/corpus/DISPUTES.md` entry `42-WM-R14`: unbounded-but-correct.

### Disposition

Documented, not fixed. The KDoc on `CausalStability` states it at the site.
An interest-scoped stability read — MIN taken per interest slice, or the
covering-subset filter of `ReplicaQuorum.frontier` lifted to a key-less read —
is a **design question**, not an implementation gap: it needs a decision about
what "stable" means for an id whose replicas hold disjoint state, which no
spec section in `doc/spec/40-distribution/42-replication.md` answers today.
A consumer that needs stability under sharding should be filed against that
design question rather than against this class.

## KE3-GC — the GC proof harness: what the stable and local triggers did

Recorded by: `computenet-9sm.4.5` (feature `computenet-9sm.4`, epic
`computenet-9sm`). Base commit: `5882eb930` (`main`), which is
`computenet-9sm.4.4`'s merge — `kernel/src/test/kotlin/civictech/cell/replication/GcSafetySweepTest.kt`
(`GcSafetySweep`/`GcSafetySweepTest`) is the harness this entry reports on.

### The adversary and the seed range

`SEEDS = 1L..200L`, `BUDGET = 40_000`, over `StableFrontierChurnSweep.config` /
`.churnPlan(seed)` (the sibling BS-5 sweep's own generated churn, reused
verbatim so the two runs are comparable like for like) — strided writes
paired with removes (odd-ordinal writes, `REMOVE_LAG = 90`, remover ==
adder) and a reclaimer step hook (period `K = 25`, `SetCell.compactBelow`
driven from either seam) — plus the three folded CHA1 faults `gc-park`,
`gc-dup`, `gc-reorder`. Wall time, seeds 1..200: BS-12 (STABLE) 4.4-5.0 s,
BS-13 (LOCAL) 4.2-4.4 s across the implementer's and reviewer's independent
runs on 16-core macOS.

### BS-13 (`[KE3-20]`) — reproduced, `BS13_SEED = 62`

`BS13_SEED = 62L`. Under the LOCAL trigger (reclaimer driven from
`Replication.localDeliveredFrontier`, the wrong seam), seed 62 makes
`SetCell.compactBelow` discard a tag that a later frame re-delivers as new
information, so the removed element is back in `membership()` at
quiescence — the `resurrected(cell, fold)` observable fires — on every one
of 5 pinned re-runs (`GcSafetySweepTest`'s `..._BS12_BS13` test). The
harness records this as a per-seed pass/fail on the `resurrected(...)`
observable; it does not capture which element or which replica resurrected
on seed 62 specifically (that granularity is not part of `GcObservations`),
so this entry states the property, not an element/replica pair. Four
independent 200-seed LOCAL sweeps found 12, 12, 15 and 14 resurrecting
seeds respectively, with `{62, 87, 107, 138, 170, 175}` in the intersection
of the first three; 62 is the smallest and was chosen for that reason. Seed
62 is **not to be replaced with a friendlier seed** (AGENTS.md): the pin
re-runs it, not the sweep's per-run failing set, because the rig is not
trace-reproducible (see below) and the per-run set churns.

`[KE3-20]` (E3.5(iii)'s control: "reclaiming at the merely-locally-delivered
frontier resurrects a removed element on at least one seed") is therefore
**reproduced**. No D3 widening of the adversary was needed.

### BS-12 (`[KE3-23]`) — branch F: the feature's own containment claim is FALSIFIED

The feature's empirical expectation was that LOCAL's resurrecting-seed set
strictly contains STABLE's — i.e. that reclaiming at the stable frontier is
qualitatively safer, only slower to prove. **That is falsified.** Five
independent 200-seed sweeps (implementer x4, reviewer x1) agree: the STABLE
and LOCAL resurrecting sets overlap WITHOUT one containing the other, at
comparable rates. STABLE resurrected on 8, 10, 10, 11 seeds (implementer)
and 9 (reviewer, `[5, 20, 62, 70, 120, 136, 138, 154, 184]`); LOCAL
resurrected on 12, 12, 15, 14 (implementer) and 8 (reviewer,
`[14, 62, 87, 107, 138, 170, 175, 181]`). The reviewer's own two sets
intersect at `{62, 138}` — two seeds resurrect under BOTH triggers, which a
strict-superset relation forbids.

STABLE branches into two failure classes, both required to be present by
the sweep's own assertions:

- **F-B (the headline): compaction at the STABLE frontier resurrects
  removed elements too**, on `BS12_SEED = 62` and 7-10 other seeds per
  200-seed run. Mechanism: `SetCell.foldDelivered` is fed only from
  `add()`'s local mint and from `applyRemote()`'s `newAdds` — `remove()`
  mints and folds nothing into the delivered lane. So
  `del-tag ≤ stableFrontier` certifies that every open member has *delivered
  the add*, not that any member has delivered the matching remove. A member
  that held the add but missed the remove (a partition opened between the
  two) re-ships the add-only state at heal, and a replica that has already
  compacted the tombstone below the stable frontier re-admits it as new
  information. This directly contradicts
  `doc/spec/20-dataflow-semantics/24-data-cells.md`'s `[24-TAG-04]`
  sentence: "reclaiming at the locally delivered frontier can resurrect a
  removed element on some schedule … where reclaiming at the stable
  frontier cannot, because every covering replica has already converged
  past it." It bears on `[KE3-23]` (E3.5(iii), the GC safety property
  itself) and on `[KE3-31]` ("dels entries whose every tag is
  `≤ stableFrontier` … SHALL be discarded" at checkpoint time), and
  therefore on features computenet-9sm.6 and the OR-map half
  computenet-9sm.8, which are the consumers that will wire a reclaimer
  against this same rule. It is corroborated **deterministically**, not
  just seeded, by `CompactionTriggerPinTest`'s `P2 LOST del` scenario (the
  del genuinely lost, not merely severed-and-healed) — see that class's
  KDoc for the schedule. What would make the spec sentence true: certifying
  the *remove* as delivered, not just the add — a del-side delivered lane,
  or removes minting their own dot. That is a design question for the
  epic, not for this feature.
- **F-A: `ReplicaConvergence` cannot express compaction.** It folds emitted
  deltas and keeps every tombstone the cell ever emitted, so it disagrees
  with a cell that has legitimately compacted one away; this is an
  expressiveness limit of the diagnostic, not a safety violation, and fires
  on 122-126 of 200 seeds per run. The feature forbids a bespoke
  replacement check (`[KE3-23]`), so this is recorded, not patched around.

`[KE3-23]` must **NOT** be reported green: the property it names — a
reclaimer discarding del-tags `≤ stableFrontier` never breaks convergence —
does not hold on the schedules this harness reaches.

### The P2 pin, seed-free

`CompactionTriggerPinTest`'s `P2 LOST del - compacting at the STABLE
frontier resurrects a removed element` is the deterministic form of the
same fact BS-12/F-B measures seeded: with the del genuinely lost (not
merely severed-and-healed, which is the `P2 PARKED del` control and does
NOT resurrect), compacting at the stable frontier resurrects. It needs no
seed because it is a single hand-built schedule, and it is what BS-12's
seeded sweep corroborates at scale.

### The rig is not trace-reproducible — filed separately, upstream of this feature

`DstRun.assertDeterministic()` fails on every churn-mesh configuration
tried on this graph, including with both of `GcSafetySweep`'s step hooks
removed and the full fault plan in place, and again with both hooks
installed and no folded faults (bare `churnPlan`). The sibling BS-5 graph —
carrying none of this feature's hooks — is likewise not deterministic on a
`ChurnGenerator`-drawn plan (seed 62), which puts the cause upstream of
this feature. `BS13_SEED = 62` and `BS12_SEED = 62` are therefore pinned by
**5x re-run stability** (5 of 5, and the six candidate seeds each 8 of 8 on
a dedicated check), not by trace determinism. Filed as **computenet-l0gd**
(bug, parent `computenet-9sm`); its scope is narrower than "the churn mesh
is not reproducible" — `ChurnMeshTest`'s own determinism test passes today
on a hand-built plan, and only a `ChurnGenerator`-drawn plan is affected,
and even then only on some seeds (62 and 87 reproduced out of
1/8/9/19/62/87/107 tried).

### A residual bar, reported and not repaired

`gc-park` and `gc-reorder` (alongside `gc-dup`) are asserted **sweep-wide**
rather than per seed — the same relaxation applied to `gc-dup`, for which
it is justified (`gc-dup` is a probability-0.5 duplicator that legitimately
draws nothing on an idle seed, measured: seed 91 of 200 in every run).
Unlike `gc-dup`, both `gc-park` and `gc-reorder` fired on 200 of 200 seeds
on both arms in the reviewer's independent run, so a per-seed assertion
would have held for them without loosening. The sweep still catches an
adversary that never fires at all (the clause's purpose), so this is a
lower bar than the bead asked for, not a defect — recorded here as a known
gap between what was required and what was checked, rather than stretched
into this task's own scope to repair.
