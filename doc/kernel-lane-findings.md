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
qualitatively safer, only slower to prove. **That is falsified.** Six
independent 200-seed sweeps (implementer x4, reviewer x2) agree: the STABLE
and LOCAL resurrecting sets overlap WITHOUT one containing the other, at
comparable rates. STABLE resurrected on 8, 10, 10, 11 seeds (implementer),
9 (reviewer, `[5, 20, 62, 70, 120, 136, 138, 154, 184]`) and 12 (reviewer's
sixth sweep, `[12, 43, 53, 54, 62, 63, 120, 133, 138, 154, 173, 184]`, recorded
on `computenet-9sm.4`); LOCAL resurrected on 12, 12, 15, 14 (implementer), 8
(reviewer, `[14, 62, 87, 107, 138, 170, 175, 181]`) and 11 (the same sixth
sweep, `[13, 26, 35, 62, 87, 93, 107, 131, 138, 170, 175]`). Both reviewer
runs' STABLE and LOCAL sets intersect at `{62, 138}` — two seeds resurrect
under BOTH triggers, which a strict-superset relation forbids.

STABLE branches into two failure classes, both required to be present by
the sweep's own assertions:

- **F-B (the headline): compaction at the STABLE frontier resurrects
  removed elements too**, on `BS12_SEED = 62` and 7-11 other seeds per
  200-seed run (8-12 total, matching the per-run figures listed above) — this
  is the pre-del-dot (unfixed) build, where `SetCell.foldDelivered` is fed
  only from `add()`'s local mint and from `applyRemote()`'s `newAdds` —
  `remove()` mints and folds nothing into the delivered lane. So
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

## KE3-HB — the idle-replica heartbeat repairs a LOST row emission, and nothing else

Measured by `kernel/src/test/kotlin/civictech/cell/replication/WatermarkHeartbeatTest.kt`
(BS-2 / BS-3 / BS-3′, `[KE3-13]`/`[KE3-15]`, feature `computenet-9sm.2`,
task `computenet-9sm.2.3`) at base commit `41494ecbf` ("Merge
computenet-9sm.2.2"), three peers A/B/C replicating one `SetCell` over a
loopback triangle, 30 seeds per scenario. `k = 4`, `t = 8`.

### The three measured outcomes

| scenario | heartbeat on C | C's last row emission | `stableFrontier[s]` on A and B |
| --- | --- | --- | --- |
| **BS-2** | on | destroyed on C→A and C→B | `k` until the first tick, `t` at every observation after it, `t+6` after a re-link |
| **BS-3** | off | destroyed on C→A and C→B | `k` at every one of the 12 observations, strictly below C's true row `t` |
| **BS-3′** | off | delivered (no drop at all) | `t` after phase 2 and at every phase-3 observation |

BS-2 and BS-3 differ only in the flag; BS-3 and BS-3′ differ only in the
loss. So the freeze is caused by the loss, and the flag is what repairs it.
The discrimination was checked by running BS-2's expectations with
`heartbeatOnC = false`: the phase-3 sequence assertion goes red at the first
post-tick observation, `expected:<[4L, 8L, 8L, …]> but was:<[4L, 4L, 4L, …]>`.

### The consequence, which narrows the feature's stated value

In this lattice **an idle member's silence never freezes `stableFrontier`
below that member's own row by itself.** `WatermarkCell.advance` emits the
raised *absolute* value, `applyRemote` re-emits every raised entry, and
`outlet.catchUpOnLinked` ships full state on every (re)link — so in a
lossless mesh every peer's view of an idle member's row already equals that
row, and the MIN reads identically with or without a heartbeat. BS-3′ is
that measurement: C idle behind a cut inbound, heartbeat off, and the read
still sits at C's true row `t`. Only a **lost row emission** produces the
stale view the feature's `## Today` describes as "missed the last gossip",
and repairing that is the whole of what the heartbeat does for lane 1.

The practical reach is therefore narrower than the feature's `## Why`
states: no shipped transport in this repository produces such a loss without
a re-link that catches up — a socket drop re-links, `Peering.Loopback.heal()`
re-links — so today the loss is reachable only through a test interposer.
The mechanism is cheap, correct and idempotent, and it is the right insurance
for a transport that later loses a frame without re-linking; it is not, on
the evidence here, load-bearing for any mesh this repository ships.

### Side observation, `unverified:` as to mechanism

Lifting C's inbound drop and driving further traffic does **not** catch C up:
the A→C edge delivers the new frame (its delivered counter rises) but C's own
watermark row stays at `t`, because the data deltas destroyed while its
inbound was cut are gone and nothing on the data path replays them. Only the
re-link does — `linkAC.heal()`/`linkBC.heal()`, after which the read settles
at `t+6`. The test pins the outcome; which mechanism holds C's set back
(inlet frontier alignment over the missing tag prefix is the obvious
candidate) was not verified here.

## KE3-GC-DEL-DOT — the del-dot: what it fixed, and what a re-admission floor costs

Recorded by: `computenet-v2ka` (epic `computenet-9sm`). Base commit:
`8d65b542b` (`main`). Host: 16-core macOS, load average ~5-7 (concurrent
agents). All figures below are seeds `1..200` at budget `40_000` through
`kernel/src/test/kotlin/civictech/cell/replication/GcSafetySweepTest.kt`.

### The mechanism

`SetCell.remove` mints a **del-dot** from the cell's own tag-source counter and
ships it inside the `dels` entry beside the tags it covers. `applyRemote` feeds
the del lane into the delivered frontier as well as the add lane, so the dot
rides the existing per-origin max-contiguous prefix. `compactBelow` discards an
entry all-or-nothing, so `[KE3-31]`'s "every tag `≤ stableFrontier`" reaches
the dot for free. `sinceFilter` ships a `dels` entry whole, so a since-pull
cannot hand a peer the dot without its covers.

### What moved

| build | STABLE resurrecting | STABLE membership-diverging | control diverging |
|---|---|---|---|
| unfixed (`8d65b542b`) | 10 of 200 | not measured | — |
| + del-dot | 8-10 of 200 | 1-2 of 200 | 3-5 of 200 |
| + del-dot + per-source re-admission floor | **0** of 200 | **31-33** of 200 | 2-5 of 200 |

The `+ del-dot` STABLE-resurrecting figure is a band across **three**
independent 200-seed runs (8, 9, 10), not a single run — same band as
`concord/corpus/DISPUTES.md`'s `## KE3-GC-DEL-LANE` entry,
`SetCell.compactBelow`'s KDoc, `GcSafetySweepTest` and
`doc/spec/20-dataflow-semantics/24-data-cells.md`.

Independently re-measured 2026-09-06 by a second session on
`feature/computenet-v2ka` after the five inherited expectation failures were
resolved (same seeds, same budget, same host class): the `+ del-dot` row reads
**9** resurrecting `[43, 99, 116, 126, 149, 154, 166, 168, 180]`, **2**
membership-diverging `[78, 181]`, control **3** `[108, 149, 173]`; LOCAL
(BS-13) resurrecting 12, diverging 0. Inside the recorded bands. Every
resurrecting detail line reads `adds=[n] dels=[n, n+1]` — an add-tag and the
del-dot minted immediately after it, re-delivered into an entry the reclaimer
had already discarded. That shape is the re-admission half and nothing else:
the del-dot IS present in each of them and IS below the frontier, so the entry
was legitimately certified delivered before the duplicate arrived.

Deterministically, `CompactionTriggerPinTest`'s `P2 LOST del` went from
`discardedA=2 discardedC=2` → `memberships=[[e],[e],[e]]`, `resurrected=[e]` at
A and C, `converged=false`; to `discardedA=0 discardedC=0` →
`memberships=[[],[],[]]`, nothing resurrected, `converged=true`. The stable
frontier reads `sA → 1` in **both** runs. The frontier did not move; the rule's
reach did.

### The per-source re-admission floor is not safe, and this is the record of it

`[24-TAG-04]`'s second clause is still open, and computenet-9sm.6-D2 plans a
persisted per-source floor for it. Measured here in three variants:

1. floor raised to the discarded counter — 33 of 200 diverging;
2. the same, capped at the replica's own max-contiguous delivered prefix — 31;
3. (2) with the del-dot made self-covering (present in `adds` as well as
   `dels`) so the delivered frontier can only advance on tags the replica
   actually holds — 31.

All three drove resurrections to zero. All three fenced **live** add-tags —
elements that were never removed went missing from one replica and stayed
missing. Disabling only the fence while keeping the discard returns divergence
to 2 of 200, the control floor: it is the fence, not the reclamation. A
per-source high-water cannot express "this tag was reclaimed" as distinct from
"this tag is below a position I have reached"; the fence needs a causal context.

### The observable that would have hidden all of it

`resurrected(cell, fold) = membership() − project(the cell's OWN emitted fold)`
cannot see a diverged mesh: where the tombstone-holders reclaim and a straggler
keeps the element live, every replica agrees with its own fold and the set is
empty at all of them. Every one of the 31-33 diverging seeds above reported
`resurrecting=[]`. `GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE` and the
no-reclaimer `Trigger.NONE` control arm were added by this bead for that
reason. **The divergence count is only meaningful against the control arm** —
the churn rig itself diverges on 2-5 seeds with no compaction at all, and that
floor moves between runs because the rig is not reproducible (see
`GcSafetySweepTest`'s pin-test KDoc).

### computenet-pay7 — the re-admission fence that closes it, and the one that did not

Base commit `b1180c935` (`main`, three commits past the row above). Host:
16-core macOS, load average ~9-16 (concurrent agents). Same seeds, same budget,
same harness.

**Re-measured baseline first, and it is OUTSIDE the band this entry recorded.**
On `b1180c935` the `+ del-dot` row reads **6** STABLE resurrecting
`[110, 117, 126, 144, 168, 197]`, **3** membership-diverging `[78, 165, 181]`,
CONTROL diverging **2** `[108, 149]`; LOCAL resurrecting 8, diverging 0. Six is
below the 8-10 band recorded above from three runs at `aaae37095`. The band was
never a bound — the rig is not reproducible and three commits touching these
files landed in between — so this is recorded as a fifth data point, not as a
contradiction. Every one of the six detail lines still reads `adds=[n]
dels=[n, n+1]`.

**The design.** `SetCell.compactBelow` records the exact dots it discards, in
`ReclaimedDots` — a per-source **dot set**, compressed as sorted contiguous
counter runs. `applyRemote` subtracts that set from the novelty it computes on
both lanes. A live add-tag can never enter the set (only a discarded `dels`
entry's tags do), which is the structural difference from the per-source floor:
a floor rejects everything below a position, and below any position reclaimed
and live counters interleave.

**A fence that only DROPS the replayed frame is not safe, and this is the
record of it.** The first build did exactly that. Measured:

| build | STABLE resurrecting | STABLE diverging | CONTROL diverging |
|---|---|---|---|
| `+ del-dot` (base `b1180c935`) | 6 | 3 | 2 |
| `+ dot-set fence`, no repair emission | **0** | **30** | 3 |
| `+ dot-set fence + repair emission` | **0** | 5-8 | 1-4 |

Thirty is the same order as the per-source floor's 31-33, and the mechanism is
related: a replica whose frame is fenced is one that still holds the add-tag
LIVE with no tombstone for it, so dropping the frame leaves the element live
there and absent here for ever. The resurrection is converted into a permanent
divergence rather than removed — visible only to
`GcSafetySweep.MEMBERSHIP_DIVERGENCE_FAILURE`.

**The repair emission.** A fenced add-tag is answered with a minimal `dels`
entry naming exactly that tag. The fence is itself the evidence the tag was
covered by a remove this replica saw certified delivered, so the covering entry
is reconstructible from the tag alone; no del-dot is minted, because no new
remove happened. The receiver folds it, drops the element, and later reclaims
and fences the tag in turn — the fence spreads rather than fragmenting the
mesh. It cannot loop: the repair fires only for a tag novel against `adds`
here, and a peer answering with a `dels` frame carries only tags this replica
already fences.

**The last row's figures**, seeds 1..200 at budget 40_000, four independent
runs: STABLE resurrecting `[]` in all four; STABLE diverging 5, 7, 7, 8 (the
recurring core `{18, 103, 114, 159, 169}`); CONTROL diverging 1, 2, 3, 4;
LOCAL resurrecting `[]`, LOCAL diverging 3-6.

**The excess over the control is recorded, not explained away.** It is bounded
evidence and it is not zero. What can be said checkably: `removeSchedule`
removes only ODD-ordinal writes, so an EVEN-ordinal element's add-tag never
enters a `dels` entry, can never be recorded by `compactBelow`, and cannot be
in the fence. Four of the seven seeds diverging on the last run differ by
exactly one such element (`peer1-22`, `peer2-20`, `peer1-16`); the other three
differ by `peer2-23`, the last-write straggler shape the CONTROL arm itself
produces. What compaction changes is the traffic: the fence removes the
discard/re-admit/re-emit churn, so `discarded` falls from ~64_000 to ~5_900
over the sweep and the rig's own late-write floor is reached on more seeds.
`GcSafetySweepTest`'s `MAX_STABLE_DIVERGING` was raised 10 → 12 on that
reading, with the band recorded in its KDoc.

**Cost.** The fence is not free and not a bound: reclamation exchanges
per-element tombstone maps for a per-source run list, which coalesces well on a
workload that removes what it adds and degrades to one run per reclaimed tag
under an adversarial interleaving. A bounded form needs epoch hygiene (G-42),
research-gated.

**Pins.** `BS13_SEED` was re-derived `126` → `18`: the LOCAL arm no longer
resurrects at all (the fence applies to whatever frontier the reclaimer is
driven from), so the wrong seam's observable harm is now a membership
divergence, and `18` is the one seed in every LOCAL diverging set taken on this
base. `BS12_SEED` deliberately STAYS `126` with its verdict inverted — it was
chosen because it resurrected on 5 of 5 dedicated re-runs, and it now
resurrects on none of them.

### Rig note

`BS12_SEED`/`BS13_SEED` were re-derived from `62` to `126`. The del-dot
consumes tag counters, so every schedule downstream of a remove shifts and the
old pin no longer reproduces. `126` is the one seed present in the resurrecting
set of every 200-seed run taken on this base, on both arms, and reproduced on
5 of 5 dedicated re-runs.

## KE3-GC-FENCE-KEY — the re-admission fence was keyed on the tag alone, and tag counters are reused across a replica's incarnations

**computenet-vhlm, 2026-09-06, darwin/arm64 16-core, `GcSafetySweepTest`, seeds
1..200, budget 40_000.** Filed against computenet-pay7's acceptance criterion 2
("membership divergence no worse than the Trigger.NONE control arm's, measured
in the same run"), which the shipped harness never checked: `MAX_STABLE_DIVERGING`
is an absolute bound and was raised 10 -> 12 rather than the gap closed.

### The measurement that replaced the inference

computenet-pay7 argued the 3x excess over the control was not the fence's harm,
from the workload: `GcSafetySweep.removeSchedule` removes only ODD-ordinal
writes, so an EVEN-ordinal element's add-tag never enters a `dels` entry, can
never be recorded by `compactBelow`, and is structurally un-fenceable. That
argument is CORRECT. It is also insufficient, and the difference was invisible
until it was measured rather than reasoned about.

`GcSafetySweep` now performs the read directly
(`FENCE_ATTRIBUTED_DIVERGENCE_FAILURE`): for every element the live replicas
disagree on at quiescence, take the tags making it live where it IS live and ask
every replica that LACKS it whether any is in that replica's own `ReclaimedDots`
(`SetCell.liveTagsOf` / `SetCell.fencedAmong`, two internal diagnostic reads).

    STABLE diverging        [43, 103, 145, 149, 173]  attribution NONE
    STABLE fence-attributed [18, 114, 159, 169]       attribution (all), every lacking replica
    CONTROL diverging       [43, 108, 173]            attribution NONE

All four attributed seeds differ by an EVEN-ordinal element — `peer1-22`,
`peer2-20`, `peer2-20`, `peer1-16` — the exact class the inference exonerated.

### The mechanism: tag-source counter reuse

`SetCell.tagSource` is `nameUUIDFromBytes("set-tags:${ref.id}:${ref.instanceId}")`
— derived, not random, so a recovered instance replaying its journal re-mints the
tags the network already observed. `tagCounter` restarts at 0 on any construction
that does not `restore()`. In the churn rig `MeshPeer.index` IS the `instanceId`
of every ref the peer owns, and a rejoin reuses the same `CellRef` by
construction (`MeshPeer`'s KDoc, "Rejoin determinism"), while a generation > 0
starts from a fresh `Replication` and no replica.

So a peer that departs and rejoins re-mints counters its previous incarnation
spent. `ReclaimedDots` was keyed on `(sourceId, counter)`, so a dot reclaimed
from the departed incarnation fenced a DIFFERENT, LIVE element minted by the
rejoin — permanently, at every replica holding that dot. Seed 18's `peer1-22` is
peer1's eighth write and carries counter 4.

### The fix, and what it measured

`ReclaimedDots` is keyed on `(element, tag)`. Nothing is lost: a replayed frame
carries the same pair that was discarded. Four 200-seed runs after the change:

    STABLE resurrecting     []  []  []  []     (branch G, unchanged)
    STABLE fence-attributed []  []  []  []
    STABLE diverging         2   3   1   4
    CONTROL diverging        3   3   3   1

and four more taken independently in the task review on the same host (load1
4.6-10.4, same date):

    STABLE resurrecting     []  []  []  []
    STABLE fence-attributed []  []  []  []
    STABLE diverging         1   5   3   4
    CONTROL diverging        3   3   3   4

Every remaining STABLE divergence differs by `peer2-23` with attribution NONE,
and the CONTROL arm (which now prints its own per-seed DIVERGE lines) produces
that shape and nothing else. `MAX_STABLE_DIVERGING` was NOT raised and `SEEDS`
was NOT narrowed.

**What that does and does not settle.** The two arms are now in ONE BAND — 1-5
STABLE against 1-4 CONTROL, where before the re-key they were 5-8 against 1-4 —
so the ~3x excess this item was filed over is gone. The per-run INEQUALITY
computenet-pay7's criterion 2 words ("no worse than the Trigger.NONE control
arm's, measured in the same run") nevertheless does not hold on every run: run 4
of the first table is 4 against 1, run 2 of the second is 5 against 3. On a rig
that is not reproducible and whose two arms each move by several seeds between
runs, a per-run count comparison is not assertable and the harness never
asserted it. What carries criterion 2 is the ATTRIBUTION assertion — empty on
8 of 8 runs above — with the absolute count bound behind it.

### Residual holes, stated rather than left to be discovered

- **Same element, colliding counter.** A rejoining incarnation that re-mints a
  colliding counter for *the same* element is still wrongly fenced. Closing that
  needs the tag source to be incarnation-unique, which is a change to the
  journal-replay contract `tagSource` exists to keep. Out of scope.
- **BS-13 lost its witness, and no friendlier seed was substituted.** The LOCAL
  arm — the wrong seam, `[KE3-20]` — no longer resurrects (the fence applies to
  whatever frontier drives the reclaimer) and, with the collision removed,
  diverges on 0-3 of 200 rather than 3-6. Its recorded pin `BS13_SEED = 18` no
  longer reproduces, and NO seed does: dedicated 5-run pins on the four
  candidates observed across four sweeps scored 2/5 (70), 3/5 (146), 0/5 (181)
  and 0/5 (90). The seed was left at 18 and the pin left RED rather than replaced
  by a seed that happens to fail today. What survives is a sweep-level
  discriminator that is arguably sharper than the pin was: every LOCAL divergence
  is `FENCE_ATTRIBUTED`, and every STABLE divergence is not.

## KE3-GC-WITNESS — BS-13's per-seed pin is retired onto a sweep-level discriminator, and the adversary is widened to make it reliable

computenet-nwnl, residual of computenet-pay7 / computenet-vhlm (`[KE3-20]`).
Ships inside PR #725. All numbers below are darwin/arm64, 16-core, load1 6-13,
seeds `1..200`, budget 40_000, 2026-09-06. **The churn rig is explicitly not
reproducible across hosts (computenet-l0gd), so every seed set here is ONE
host's sample.**

### The problem this closes

`GcSafetySweepTest` carried two RED assertions, both in the BS-13 / LOCAL arm,
both left red honestly by computenet-vhlm rather than papered over:

1. the sweep arm's "no seed was observably harmed by compacting at the LOCAL
   delivered frontier" — INTERMITTENTLY red; and
2. the per-seed pin `BS13_SEED = 18` — DETERMINISTICALLY red.

Both are the `(element, tag)` re-key's doing and not a regression: seed 18's
LOCAL harm WAS the tag-counter collision, so removing the collision removed the
witness. See `## KE3-GC-FENCE-KEY`.

### What was done, and why both routes were needed

**Route 1 — widen the adversary**, which is what `[KE3-20]`'s own failure
message prescribes. `GcSafetySweep.plan` folded ONE `PartitionFault.park` on
ONE of the mesh's three links for 600 of ~7000 steps, enclosing exactly two of
the twelve scheduled removes. Two more parks were added on the other two links,
in their own windows (`gc-park-b` on `peer0<->peer2` at 2400..3000,
`gc-park-c` on `peer1<->peer2` at 3600..4200), taking the enclosed removes from
two to six.

**The windows are DISJOINT, and that is load-bearing.** The obvious stronger
adversary — park both of one peer's links over one window so it is genuinely
severed — was built, measured and REJECTED, because it destroys the
distinction the sweep exists to measure. `ChurnMesh` declares
`LinkControl.severing` per pair, and severing un-mirrors each side's MEMBERSHIP
entry for the duration. An isolated peer is therefore not a straggler the
others still wait on; it is a NON-MEMBER, so `stableFrontier` stops requiring
its ack and advances exactly as `localDeliveredFrontier` does. Measured over
four sweeps with `peer2` severed from both neighbours at 2400..3000: the LOCAL
arm's harm rose (7, 1, 4, 3 of 200) and the STABLE arm went fence-attributed on
1 of the 4 — the widening made the RIGHT seam look wrong too. A sharper
adversary that blunts the discriminator is not a sharper adversary.

**Route 2 — promote the fence-attribution discriminator to an assertion**, and
retire the per-seed pin onto it. The BS-13 arm now asserts
`fenceAttributed.isNotEmpty()` alongside (never instead of) the existing
any-harm assertion, and the pin test keeps only its STABLE half.

### Why the pin could not be re-derived a fourth time

Dedicated 5-run pins on EVERY candidate observed across the widened sweeps:

    seed   4 -> 4/5     seed 132 -> 4/5     seed 145 -> 1/5     seed 181 -> 1/5
    seed  12 -> 0/5     seed  89 -> 0/5     seed 154 -> 0/5     seed 149 -> 0/5
    seed 165 -> 0/5

plus computenet-vhlm's own four on this host: 70 -> 2/5, 146 -> 3/5,
181 -> 0/5, 90 -> 0/5. **Nothing reaches 5 of 5**, and the bead forbids
recording a seed below that bar — rightly, since a 4-of-5 seed is a 20%-flaky
required check. The provenance now lives in `GcSafetySweepTest.BS13_PIN_RETIRED`
so the next reader finds the measurement rather than an absence.

### The measurement that justifies the replacement

Fence-attributed LOCAL seeds per 200-seed sweep:

    WIDENED   (ten sweeps)   5  3  3  5  4  3  3  5  3  4    non-empty 10/10, min 3
    UNWIDENED (seven sweeps) 2  2  1  1  0  2  2               non-empty  6/7

In all ten widened sweeps EVERY LOCAL divergence was fence-attributed, and the
LOCAL arm resurrected on none. The unwidened zero is exactly the intermittent
redness this item was filed for, which is why the widening is part of the fix
rather than optional polish. The STABLE arm's absolute divergence bound was
untouched and never approached: its counts over the same ten sweeps were
5, 3, 5, 2, 3, 4, 6, 3, 6, 2 against `MAX_STABLE_DIVERGING = 12`. `SEEDS` was
not narrowed, no assertion was deleted or weakened, and the diff only adds
assertions.

### Residual, NEWLY MEASURED and NOT this item's to fix

**BS-12's `stableFenceAttributed` assertion is itself intermittently red, and
it was already red before this change.** It fires on seed 12, differing element
`peer2-23`, `fencedAtLacking=peer2:[8](all)`.

MEASURED head-to-head in one session, the BS-12 arm alone, eight 200-seed
sweeps of each adversary back to back (the unwidened half taken by removing
`gc-park-b`/`gc-park-c` under a `.mutation-in-progress` marker, then reverted):

    WIDENED    3 of 8 sweeps red
    UNWIDENED  2 of 8 sweeps red

Over every sweep taken for this item the totals are 7 of 22 widened and 3 of 15
unwidened. **The unwidened reproduction at the merge base is the evidence that
it is not this change's doing** — a failure that occurs without the widening
was not caused by it, and that conclusion does not depend on the comparison.
**The comparison itself is too small to conclude anything** (task review,
computenet-nwnl): 32% against 20% on n=22 and n=15 is far from separable, the
observed direction is *upward* under the widening, and a real doubling of the
rate would not be detectable at these sample sizes. Read it as "not shown to
move the rate", never as "shown not to". There is also a mechanistic reason to
expect some increase: both new parks touch `peer2`, and every observed
occurrence is `peer2`/`peer2-23`. Sizing that belongs to computenet-dwkp.
A dedicated 5-run pin on seed 12 under STABLE scored **0/5**, so it is a
rare schedule rather than a property of that seed — which is also why
computenet-vhlm's eight consecutive green runs are consistent with a ~20-30%
per-sweep failure rate rather than evidence against it.

The shape is consistent with the same-element residual recorded under
`## KE3-GC-FENCE-KEY`: `peer2-23` is peer2's own last write, and a rejoining
incarnation replaying its journal re-mints the exact tag its previous
incarnation spent — which its own `ReclaimedDots` has already fenced. That is
the hole `tagSource`'s derivation deliberately keeps open, and closing it
changes the journal-replay contract. **Consequence for PR #725: `kernel-test`
is not yet reliably green** — a full `:kernel:test` can go red on this
assertion roughly one run in four. Filed as **computenet-dwkp** with the
head-to-head measurement, the candidate mechanism as an explicit hypothesis,
and the first step that would turn it into one. Not closed here, and not
weakened here.

## KE3-23-PROVENANCE — the BS-12 residual is NOT computenet-vhlm's cross-incarnation re-mint: the fencing replica minted the fenced tag itself, in the same incarnation, having never departed

**computenet-dwkp, 2026-09-06, darwin/arm64 16-core, load1 9-11, `GcSafetySweepTest`
BS-12 (STABLE), seeds 1..200, budget 40_000, branch `task/computenet-dwkp` at
`c657d1f8a` (cut from `task/computenet-nwnl` `d21ce1c7d`). Twelve consecutive
200-seed sweeps of the whole `GcSafetySweepTest` class in one session; **1 of 12
caught the signature**, run 10, seed 12 — consistent with the ~20-30% per-sweep
rate computenet-nwnl measured, and a reminder that a green sweep is not evidence
here.**

### The hypothesis under test, and why it was a hypothesis

computenet-dwkp's filing proposed that the residual is the SAME-ELEMENT hole
`KE3-GC-FENCE-KEY` above recorded as out of scope: `SetCell.tagSource` is derived
so a recovered instance re-mints the tags the network already observed, so a
*rejoining* peer2 would re-add `peer2-23` carrying counter 8 that peer2's own
`ReclaimedDots` had already fenced. That is plausible, and it is exactly the kind
of inference this chain has twice had overturned by a direct read (computenet-pay7's
ordinal parity, computenet-vhlm's "criterion 2 met in count").

### The measurement

`SetCell.fenceProvenance(element, tag)` — a third `internal` diagnostic read beside
`liveTagsOf`/`fencedAmong`, additive and consulted by no protocol path — reports, for
a tag the lacking replica fences: whether the tag's source is that replica's own
`tagSource`, that instance's `restore()` count, and **the element THIS instance
minted that counter for**. `GcSafetySweep`'s attribution detail prints it for every
fenced tag, together with the peer's own `lastDeparture`. Caught reading, verbatim
from run 10's `<system-out>`:

    [BS-12] FENCED-DIVERGE seed=12 live replicas disagree on membership at quiescence:
      peer0=[peer0-6, peer1-4, peer2-8, peer1-10, peer0-12, peer2-14, peer0-18, peer2-20, peer2-23],
      peer2=[peer1-4, peer0-6, peer2-8, peer1-10, peer2-14, peer0-12, peer0-18, peer2-20];
      differing=[peer2-23];
      attribution=[peer2-23 held=[peer0] liveTags=[8]
        provenance=[peer2{tag=8 own=true inc=11/11 restores=0 mintedHere=peer2-23
                    sameElement=true lastDeparture=null suspended=false}]
        fencedAtLacking=peer2:[8](all)]; discarded=39

### What that settles

**REFUTED, on three independent facts in one line:**

- `mintedHere=peer2-23 sameElement=true` — the very instance that holds the fence
  minted counter 8 for `peer2-23` itself. There is no second incarnation's tag.
- `restores=0` — that instance never ran `restore()`, so nothing it holds was
  re-minted by a journal or checkpoint replay.
- `lastDeparture=null` — peer2 never departed in that run, so it never rejoined and
  `MeshPeer.spawn` constructed its `SetCell` exactly once. (The rig re-derives its
  ids from the seed, so `inc=11/11` counts constructions for that `tagSource` across
  the BS-12 and BS-13 arms in one JVM; it is not a rejoin count, and the KDoc now
  says so. `lastDeparture` is the reliable read.)

So the fence is at the RIGHT seam and holds the RIGHT pair: peer2 added `peer2-23`,
removed it, and reclaimed its own `(element, del-dot + covered add-tag)` below the
stable frontier. `peer2-23` is peer2's ordinal-23 write, ODD — the class
`GcSafetySweep.removeSchedule` does remove — which is consistent with a legitimate
local remove and not with the even-ordinal shape `KE3-GC-FENCE-KEY` found.

### The question this moves the residual to, and what it does NOT settle

The open question is now on the HOLDER side: peer0 still has `peer2-23` live while
peer2 compacted it, and `compactBelow`'s every-tag rule means peer2's del-dot was
`<= stableFrontier` — i.e. certified delivered by every OPEN member. Either peer0 was
not an open member when that certificate was taken and later came back holding the
add without the del, or the open-member set the frontier is computed over does not
match the set that ends up live at quiescence. That is a membership/watermark
question, not a `tagSource` uniqueness one, and **disposition (a) in computenet-dwkp
(make `tagSource` incarnation-unique) would not address the case measured here.**

Not settled: whether every occurrence of this signature has this provenance. One
occurrence was caught in twelve sweeps and it is the only one read so far; the
instrumentation is committed so the next occurrence carries its own answer,
including the holders' departure history (`holderState=`), which run 10 predates.

## KE3-23-HOLDER — the holder of the fenced element really did depart: an eviction the kernel DESPAWNED, followed by a rejoin

**computenet-dwkp, 2026-09-06, darwin/arm64 16-core, load1 6-15, `GcSafetySweepTest`
(BS-12 + BS-13, seeds 1..200, budget 40_000), branch `task/computenet-dwkp` at
`9bafc27b0` then `a961dfa4b`. This branch descends from `task/computenet-nwnl`'s tip,
so every rate below is the POST-widening rate. One host, one session; not a
cross-host claim.**

`KE3-23-PROVENANCE` above closed the fence side and left the holder side open. Three
further occurrences were caught here, all seed 12, all `differing=[peer2-23]`, all
reproducing the provenance clause verbatim (`own=true inc=11/11 restores=0
mintedHere=peer2-23 sameElement=true lastDeparture=null`). Two carried the
`holderState=` print committed at `9bafc27b0`; the third carried the sharpened one.

### The reading

```
attribution=[peer2-23 held=[peer0]
  holderState=[peer0{lastDeparture=EVICT_CLEAN suspended=false evictDespawned=true member=true}]
  liveTags=[8]
  provenance=[peer2{tag=8 own=true inc=11/11 restores=0 mintedHere=peer2-23 sameElement=true
              lastDeparture=null suspended=false}]
  fencedAtLacking=peer2:[8](all)]
```

`lastDeparture=EVICT_CLEAN` alone would NOT have answered the question, and the first
two occurrences (which printed only it) did not. `Replication.evict` has two outcomes:
it returns false when `replicasOf(id) − {local}` is empty, and then **suspends** the
replica instead of despawning it, leaves `member` true with the fold **intact**, and
`PeerHandles.rejoin` no-ops through `refusedEviction()` (`PeerHandles.member` KDoc,
computenet-usmw; `DepartureGatesTest`: "a refused eviction never despawns — state is
retained"). `suspended` does not separate the two either — only `PARTITION_SUSPEND`
sets that flag. So `EVICT_CLEAN suspended=false` is equally consistent with "the
holder left and came back" and with "the holder never left at all".

`evictDespawned=true` settles it: the eviction **despawned**, so peer0 genuinely left
the mesh, and `member=true` says it is back. This is computenet-dwkp's shape **(i)** —
the holder was not an open member at certification time — and it is NOT shape (iii).

### What it does NOT settle

- **Where peer0's live `peer2-23` came from.** A despawn drops the replica and
  `PeerHandles.spawn` builds a fresh `SetCell`, so peer0 cannot be carrying its own
  pre-departure fold across; the add it holds at quiescence must have been re-acquired
  after the rejoin, from a replica that still had the add and not the del. That is an
  INFERENCE from the spawn path, not a read — nothing printed here observed the
  transfer.
- **The ordering.** `lastDeparture` carries a mode, not a step. Whether peer0's
  departure straddles the moment peer2's del-dot crossed `stableFrontier` — the thing
  that would make the certificate vacuously true for peer0 — is unmeasured. The
  natural next instrument is the STEP of peer0's depart and rejoin against the step of
  peer2's `compactBelow`.
- **Whether shape (ii) is also in play.** (i) and (ii) are not exclusive: a departed
  replica that returns is exactly the case where the open-member set the frontier is
  computed over can disagree with the set live at quiescence.

### Rate

Eleven sweeps at `9bafc27b0` produced two catches; fifteen at `a961dfa4b` produced two.
**26 sweeps, 4 catches — roughly 1 in 6**, against the 1-in-12 the previous session saw
at `9bafc27b0`. The two occurrences carrying the sharpened print are byte-identical to
each other, and all four agree on seed, element, holder and provenance. Full-class sweeps ran in 8-13s each on this host, not the minutes an
earlier note assumed. Seed-pinning remains a dead end (a dedicated 5-run pin on seed 12
scored 0/5); the schedule, not the seed, is what is rare.

## KE3-23-ORDERING — the holder's departure does NOT straddle the fence, and the frontier's certificate is FALSE about a live member

**computenet-dwkp, 2026-09-07, darwin/arm64 16-core, load1 7-11, `GcSafetySweepTest`
(BS-12 + BS-13, seeds 1..200, budget 40_000), branch `task/computenet-dwkp` at
`4af9a0909` then its successor. The branch descends from `feature/computenet-pay7`
at `5cd0a7218` (main merged in), so these rates are NOT directly comparable with
`KE3-23-HOLDER`'s, which were taken at `6448ab392`. One host, one session.**

`KE3-23-HOLDER` above established that the holder really departed and left two things
open: the ORDERING (a mode is not a step), and whether shape **(ii)** is also in play.
Both are answered here, and the answer moves the residual off the departure entirely.

### The instrument

Three additive, reporting-only reads, none consulted by any protocol path:

- `SetCell.fencesAny(element)` (with `ReclaimedDots.anyFor`) — the existence half of
  `fencedAmong`. A step hook cannot name the tag it is waiting for: it is precisely the
  del-dot `compactBelow` has just discarded, so it is live nowhere the hook can read.
- `MeshPeer.membershipLog` — every join / depart / rejoin / heal transition stamped
  from `DstWorld.step`, the same index every `TraceEvent` carries.
- `GcObservations.fencedAtStep` — `(peer, element)` -> the compaction step at which
  that peer's fence first held the element, **plus** `stillHeldBy=`: which OTHER live
  members still had the element in `membership()` at that very step.

The last field is the load-bearing one. `compactBelow` discards only what the frontier
certifies delivered to **every open member**, so a non-empty `stillHeldBy` is a direct
read that the certificate was false about a member that was live at the instant it was
acted on — not a deduction from the fact that the holder once departed. **Read with the
caveat below**: it is 51-of-2007 common even on a green sweep (55 of 2014 on the feature
review's independent re-run), so it carries its weight here only in conjunction with the
holder's `suspended=false` and the ordering.

### The reading

```
attribution=[peer2-23 held=[peer0]
  holderState=[peer0{lastDeparture=EVICT_CLEAN suspended=false evictDespawned=true member=true
              membership=[join@1088, EVICT_CLEAN@1623, rejoin@1885]}]
  liveTags=[8]
  provenance=[peer2{tag=8 own=true inc=11/11 restores=0 mintedHere=peer2-23 sameElement=true
              lastDeparture=null suspended=false
              fencedAt={step=5000 stillHeldBy=[peer0]} membership=[join@1412]}]
  fencedAtLacking=peer2:[8](all)]
```

Two occurrences carry it (the second adds `fencedAt=`); both agree on every field, and
both reproduce `KE3-23-HOLDER`'s provenance clause verbatim.

### The ordering, and what it excludes

peer0's only absence is **`[1623, 1885]`** — evicted at step 1623, back at 1885.
`peer2-23` is peer2's ordinal-23 write, added at step **4900** (`WRITE_START=300` plus
`23 x WRITE_STRIDE=200`) and removed at **4990** (`REMOVE_LAG=90`); peer2 fenced it at
step **5000**, the first compaction point (`K=25`) at or after the removal.

So the element did not exist until **3015 steps after peer0 was back**. The departure
window and the fence window are disjoint by a wide margin, and peer0 was an open,
unsuspended member for the whole life of `peer2-23`.

**Shape (i) is therefore excluded as the CAUSE.** The holder did depart — that reading
stands — but the departure is not contemporaneous with the certification, so "the
holder was not an open member at certification time" is false as stated. Shape (iii)
is excluded too, and now by a read rather than by the spawn-path inference: peer0 held
`peer2-23` live at step 5000 and still holds it at quiescence, so no del ever reached
it to be undone.

**What remains is shape (ii)**, and `stillHeldBy=[peer0]` is the direct measurement the
acceptance clause asked for: at the instant peer2 acted on the certificate, peer0 was a
live, unsuspended, state-retaining member that still had the element. Either the open
set `stableFrontier` computed over at step 5000 excluded peer0, or it included peer0
with a watermark peer0 had not earned — this reading does not separate the two (see the
next section), so the measured fact is that **the certificate was false about a live
member**, not yet which half of `open` produced it.

**How much `stillHeldBy` alone discriminates — measured, and less than it looks.**
Reviewer measurement on the same branch (`09a4d6b68`, darwin/arm64 16-core, 2026-09-07,
temporary counters reverted before commit): in ONE GREEN STABLE sweep the instrument
stamped **2007** `(peer, element)` fences, **51** of them with a non-empty `stillHeldBy`.
The feature review re-ran the same probe at `f3a838bbf` on a green sweep and measured
**55 of 2014** — the same rate, on a rig that is not reproducible seed-for-seed.
So a non-empty `stillHeldBy` is a *common* reading, not a rare one, and on its own it
does not distinguish the schedule that diverges permanently from the ~200 seeds that
converge. The reason that survives measurement is that a certificate which is false
momentarily is usually repaired by a later delivery.

A second reason was recorded here and **does not hold as stated** (feature review): the
filter is indeed `member`-only and does not test `suspended`, but suspended peers are not
what produces the 55. The same probe counted **0** of the 55 naming a peer suspended at
the instant of the stamp; and `CausalStability.stableFrontier` removes suspended slots
from `open` only under `degrade = true`, which the sweep's reclaimer
(`peer.replication.stableFrontier(peer.ref.id)`) does not pass — so a suspended member
would remain inside `open` at this call site and naming one would need its own argument
rather than being benign by construction. What makes the occurrence above
evidence is the *conjunction* — `stillHeldBy=[peer0]` **together with** the holder's
`suspended=false member=true` and a departure window disjoint from the element's whole
life — not the `stillHeldBy` field by itself.

### The mechanism this points at — NOT yet a measurement

`CausalStability.stableFrontier` builds its open set as

```
open = members ∪ announced − closed − suspended
```

`closed` is a join-semilattice set and nothing retracts it. `DepartureMode.EVICT_CLEAN`
evicts with `closeDepartedRow = true`, so peer0's watermark row entered `closed` at step
1623 — and `MeshPeer.ref` is `CellRef(dataId, index)`, **stable across rejoins**, so the
slot peer0 comes back on at 1885 is the slot that is already closed. From 1885 onward
every `stableFrontier` peer2 computes would silently exclude peer0, which is exactly the
observed certificate.

**This is a code-path argument, not a read**, and it is the same class of inference this
bead has twice replaced with a measurement. The competing explanation it does not
exclude is that peer0's row is present and open but carries a watermark at or above the
del-dot it never applied. Separating them needs a diagnostic on
`CausalStability`/`Replication` reporting the `open` set and the per-slot row used —
files outside computenet-dwkp's `metadata.files` claim, so it is filed rather than done
here.

The practical consequence is that the disposition menu on computenet-dwkp — (a)
incarnation-unique `tagSource`, (b) a fence tolerance, (c) accept and amend the
assertion — is aimed at the wrong seam. The fence is doing exactly what it is specified
to do with a frontier that is wrong; nothing about `ReclaimedDots` or `tagSource` is
implicated.

### Rate

84 full-class sweeps this session, **2 catches** (runs 29 and 84), on
`task/computenet-dwkp` after `feature/computenet-pay7` merged main at `5cd0a7218`.
That is ~1 in 42, against `KE3-23-HOLDER`'s 4 in 26 (~1 in 6) at `6448ab392`. The
sessions differ in both the branch point and the instrumentation, and neither sample is
large enough to separate a real shift from ordinary variance at these counts — 19
consecutive greens opened this session, which alone has probability ~3% under a 1-in-6
rate and ~63% under 1-in-42. **Do not read either figure as the rate of the defect.**
The one thing both sessions agree on is that the class is still reachable at the merge
base and a green sweep is not evidence.

## KE3-23-OPENSET — the excluding term is `closed`, and it is monotone across a rejoin

`computenet-typw`, branch `feature/computenet-typw`, on top of `36b889cff`.

computenet-dwkp measured that BS-12's fence-attributed divergence is a FALSE membership
certificate — `compactBelow` discarded a del-dot below a `stableFrontier` that certified
delivery to every open member, while `peer0`, a live and unsuspended member since its
rejoin at step 1885, still held the element. **Which half of `open` produced that was not
measured**, and the sweep cannot settle it: the occurrence is ~1 in 6 to 1 in 42 and a
green sweep is not evidence.

It is now settled deterministically, without the flake, by
`GcSafetySweepTest.kt`'s companion class `StabilityOpenSetOnRejoinTest`:

**Candidate (1) HOLDS.** A replica evicted with `closeDepartedRow = true` and then
re-replicated onto the *same* `CellRef` is still absent from the `open` set
`CausalStability.stableFrontier` runs its MIN over, and the term that excludes it is
`closed` — not absence, not suspension. The mechanism is two independently sound
properties meeting: `WatermarkCell.closed` is a grow-only set with no retraction (there
is no `reopen`), and the watermark slot is *derived from the ref*
(`WatermarkCell.slotId(watermarkRef(ref))`, replay-stable by M10.1), while
`MeshPeer.ref` is `CellRef(dataId, index)` and therefore stable across a rejoin. So the
slot a peer returns on is the slot already closed, and **every** subsequent
`stableFrontier` on **every** peer silently excludes a live member. The test asserts the
survivors agree, so this is not one peer's local view.

**Candidate (3) stays refuted**, now by a test rather than only by a code read: a
suspended slot remains INSIDE `open` at `degrade = false` — which is what
GcSafetySweep's reclaimer passes — and leaves it only under `degrade = true`.

**Candidate (2) — a present-but-lying row — is NOT excluded by this work.** It is a
different failure (a row at or above a del-dot the peer never applied) and would be
invisible to an open-set read, which is about set membership, not row contents. Nothing
here measures it; it stays open.

### The read

`CausalStability.openSlots(logicalId, degrade)` returns `OpenSlots` — the `open` set,
`memberSlots`, `announced`, `closed`, `suspended`, `degrade` and the per-slot row
consulted for the MIN — plus `exclusionOf(slot)`, which names the term that dropped a
slot. It is additive and protocol-inert: no path in `CausalStability`, `Replication`,
`SetCell` or any cell consults it, and it recomputes `open` by the same expression
rather than sharing a helper, so removing it changes nothing. `Replication.openSlots` is
the one-line facade, mirroring `stableFrontier` over `CausalStability.stableFrontier`.

`OpenSlots` is **nested** inside `CausalStability` rather than a top-level type in
`civictech.cell.consistency`, deliberately: PR #544 on this epic added one top-level
class to `civictech.cell.data` and went red in `:inspect` and `:oracle` on enumerators
the bead never named. (Checked here: `:oracle`, `:inspect` and `:concord` all reference
`civictech.cell.consistency` types by name, so a new top-level member of that package is
exactly the shape that would reach them; a nested type is not.)

`GcSafetySweep.compact()` now annotates its `fencedAtStep` stamp with the read, **onto
the existing `stillHeldBy` field** — each still-holding peer is printed as
`name(term)`, plus the whole `openSet={…}`. Not as a field of its own, because a
non-empty `stillHeldBy` already fires ~51-55 times per ~2007-2014 fence stamps on an
otherwise GREEN sweep; a second independently-firing field would add noise, not signal.
The force of the reading remains the CONJUNCTION.

### What this does not do

Nothing here changes behaviour. Whether the fix is a retractable `closed` (a per-slot
close *epoch*, the shape `suspendEpoch` already has), or an incarnation-distinct slot,
or a rejoin that must re-announce before it counts, is a **design decision this bead
does not take** — and all three touch `WatermarkCell`, outside this bead's claim. The
four computenet-dwkp prohibitions remain in force and untouched.

## KE3-23-ROWCONTENT — candidate (2) HOLDS: an open row certifies a del-dot it never applied, because the tag counter space is re-used across incarnations

`computenet-mahx`, branch `feature/computenet-mahx`, on top of `8a8aa1609`.

`## KE3-23-OPENSET` settled candidate (1) — the term of `CausalStability.stableFrontier`'s
open set that drops a rejoined replica is `closed`, and it is monotone — and left candidate
(2) explicitly open, because an open-set read is about set MEMBERSHIP and cannot see a slot
that is present and open but whose ROW is wrong. That is a second, independent
false-certificate shape.

**It is real.** Settled deterministically by `GcSafetySweepTest.kt`'s companion class
`StabilityRowCoverageOnReincarnationTest` — two tests, ~2s, no dependence on the ~1-in-6 to
~1-in-42 BS-12 flake, and no GcSafetySweep assertion, seed set, budget or `tagSource`
touched (computenet-dwkp's four prohibitions all still in force).

### The mechanism

The delivered lane is contiguity-guarded, which is why the answer is not obvious.
`DeliveredFrontier.deliver(source, counter)` holds a counter back until every counter below
it has arrived, and returns the raised prefix only then — so `row[source] = t` normally does
mean "counters `1..t` from `source` were applied here". `SetCell` feeds it from
`applyRemote`'s `newAdds + newDels` and from local mints, and `Replication.trackDeliveries`
wires `onDeliver` straight into `WatermarkCell.advance`. A plain max would lie trivially;
this one does not.

What defeats it is that the counter space is **re-used**. `SetCell.tagSource` is derived
from the `CellRef` (replay-stable, M10.1) while `tagCounter` is per *instance* and restarts
at zero — the same reuse `## KE3-GC-FENCE-KEY` recorded against the re-admission fence, here
reaching a different lattice. So:

1. Incarnation 1 of a replica burns `(T,1) (T,2) (T,3)`; every peer's prefix for `T` reaches 3.
2. The replica despawns and returns on the same `CellRef` — same `T`, `tagCounter` back at 0.
3. Incarnation 2 mints `(T,1)` for a *different* element and `(T,2)` as that element's del-dot.
4. A peer that misses the `(T,2)` frame absorbs nothing: `deliver` returns null for
   `counter <= current`. Its row does not move — **and it already stands at 3**.
5. `stableFrontier`'s MIN therefore hands `compactBelow` a frontier at 3, `3 >= 2` holds for
   every tag of the entry, the del-dot is reclaimed and fenced, and the peer that never
   applied the remove goes on holding the element live.

That is the BS-12 `FENCED-DIVERGE` shape — a fence at the right seam holding the right pair,
under a frontier that is wrong — reached in a dozen deterministic steps.

### The control, and what it excludes

The second test is the same two peers, the same one-way drop of the same remove, with **no**
reincarnation: the dot is minted at a fresh counter above every prefix. `coverageOf` then
answers `BELOW`, the frontier does not certify the dot, `fencedAmong` shows the reclaimer
left it alone, and no divergence follows. **Lost frames alone do not produce a lying row** —
the holdback does its job. It is the re-used counter space that defeats it.

### The read

`CausalStability.OpenSlots.coverageOf(slot, source, counter)` — the ROW-CONTENT read, beside
`exclusionOf`'s set-membership read — returns `NOT_OPEN` / `NO_ROW` / `ABSENT_SOURCE` /
`BELOW` / `COVERS`, which separates the acceptance clause's two shapes: a row MISSING an entry
for a source (`NO_ROW`, `ABSENT_SOURCE` — bottom, and `stableFrontier` drops the source from
the result entirely) from a row carrying an entry at or above the dot (`COVERS`).
`coverageAt(source, counter)` maps it over the whole open set. Both are additive and
protocol-inert, mirroring `openSlots` itself and `SetCell.liveTagsOf` / `fencedAmong` /
`fenceProvenance` / `fencesAny`: no path in `CausalStability`, `Replication`, `SetCell` or any
cell consults them.

**The read reports the CLAIM, not its truth**, and this limit is stated at the declaration as
well as here. Nothing in a `WatermarkCell` row records which counters a replica actually
applied — the row is a position — so `COVERS` alone cannot mean the delivery happened.
Whether it did is established in the test against the replica's own state
(`membership()` / `fencedAmong`), never against another watermark.

### Scope, stated as a limit

This measures that the class EXISTS and is reachable. It does **not** claim it produced
computenet-dwkp's caught BS-12 occurrence: there the fencing replica (`peer2`) had
`lastDeparture=null`, so its counters were never re-used, and candidate (1) already accounts
for that reading. Candidate (2) is a second live defect at the same seam, not a competing
account of the first. Nor is anything fixed here: whether the repair is an incarnation-distinct
tag lane (which computenet-dwkp's acceptance forbids *pursuing as this family's disposition*),
a counter that survives a despawn, or a frontier that will not certify across a
reincarnation, is a design decision no bead has taken — and all three touch `SetCell` or
`WatermarkCell`, outside this bead's claim.

**Taken since, by computenet-uju5**: the second of those three — a counter that survives a
despawn. See `## KE3-23-LANECONT` below for the disposition, its reasoning against the other
two, and the regression the first test above became.

## KE3-23-LANECONT — the disposition of candidate (2): the tag COUNTER is carried across a reincarnation, and the tag SOURCE is not touched

`computenet-uju5`, the disposition `computenet-mahx` deliberately did not take. Closes the
false-certificate shape recorded in `## KE3-23-ROWCONTENT`.

### What was chosen

**(b) — a `tagCounter` that survives a despawn and rejoin on the same ref.**
`Replication` records a departing replica's tag-lane high-water against its `CellRef`
(`departedTagLanes`, written at `evict` on the despawn path and at `supersedeLocalInstance`
for the crash-and-rebuild shape) and installs it on the next incarnation of that ref at
`replicate`. The cell side is `civictech.cell.data.delta.TagLaneContinuity`
(`tagLaneHighWater()` / `continueTagLaneAbove(counter)`), implemented by `SetCell`.

The direction is inverted exactly as it is for `StabilityReclaim`, and for the same reason:
the cell cannot see its own past incarnations, because `tagSource` is *derived from the ref*
and the second instance is indistinguishable from the first from inside. Only the component
that owns the ref's lifecycle across a departure knows a returning ref is a return — and it
is already the component that retains that ref's delivered-watermark companion across the
same departure. So `civictech.cell.data` acquires no dependency on
`civictech.cell.replication`; the seam is declared in `data/delta/StabilityReclaim.kt` beside
the other Replication-installed seam.

The mechanism is one line of arithmetic: mints from the returning incarnation are now
strictly above every counter any peer's row already covers, so
`DeliveredFrontier.deliver`'s contiguity holdback — which was doing its job all along —
answers honestly again, and `stableFrontier`'s MIN stops certifying a del-dot nobody
delivered.

### Why not (a), and whether computenet-dwkp's prohibition reaches this lattice

(a) is *an incarnation-distinct tag lane* — making `tagSource` itself unique per incarnation.

**computenet-dwkp's clause 5 forbids it, and the question this bead's acceptance asks is
whether that prohibition reaches HERE. It does not — but the answer changes nothing,
because (a) is rejected on its own merits anyway.** The prohibition's stated reason is
relevance, not cost: *"it does not address the case measured"*. What dwkp measured was
`mintedHere=peer2-23 sameElement=true restores=0 lastDeparture=null` — a fencing replica
that had NEVER departed, so its counters were never re-used and an incarnation-unique source
would have changed nothing about that occurrence. That premise is FALSE of this lattice: here
the reincarnation is the mechanism, established deterministically, and (a) would in fact
close it. The prohibition is therefore not binding on this bead by its own terms. It is
recorded that way rather than silently stretched or silently ignored.

(a) is nonetheless not taken, for the reason `SetCell.tagSource`'s own comment and
`ReclaimedDots`' KDoc both already give: the derivation exists so that *a recovered instance
replaying its journal re-mints the exact tags the network already observed* (M10.1). An
incarnation-unique source breaks that contract — a pre-crash remove could no longer cover a
re-minted add, which resurrects removed elements — and it is the wire-visible half of the
identity, where (b) is local bookkeeping. (b) reaches the same place at a fraction of the
blast radius, so (a) buys nothing here that (b) does not.

### Why not (c)

(c) is *a frontier that refuses to certify across a reincarnation*. It leaves the lying row
in place and teaches every reader of it to distrust it, which needs the reincarnation to be
visible in the watermark lattice — new row state, and a wire-format question — and it is a
refusal, so it degrades reclamation for every replica that has ever rejoined rather than
repairing the fact the row asserts. (b) makes the row's claim TRUE instead. `## KE3-23-OPENSET`
already settled that the `closed` term is the only exclusion the open set has; (c) would be a
second, weaker one beside it.

### Compatibility, stated where the acceptance asks for it

No frame, delta or snapshot key changes. The counter was *already* snapshot state — `"counter"`
in `SetCell.snapshotLocked`, and `restore` already reads it as `maps["counter"] as? Long ?: 0L`,
so an absent key is today's behaviour and a pre-existing checkpoint loads unchanged.
`continueTagLaneAbove` never LOWERS a lane, so a cell restored from a checkpoint (which carries
its own counter) is not disturbed by a stale `departedTagLanes` entry, and re-installation is
idempotent. `Replication.rebind`'s `carryTagState = false` control seam is untouched: `rebind`
does not go through `evict`, so it records nothing, and the PN-14 T2 fresh-epoch collision it
exists to reproduce still reproduces.

### What it costs, stated where the number is

One `Long` per `CellRef` that has departed or been superseded on this peer, never pruned — the
entry IS the fix, and the return can come at any time. `O(local replicas ever retired)`, beside
the `watermarks` companion this peer already retains across the same departure for the same
reason. It is not the unbounded-per-mint retention `computenet-fzd3` was filed against.

### Scope, stated as a limit

- This closes the shape `## KE3-23-ROWCONTENT` measured. It does **not** close
  computenet-dwkp's seed-12 BS-12 occurrence, which ROWCONTENT already states it does not
  account for (`lastDeparture=null` there) — and that is **observed, not assumed**: six
  consecutive `GcSafetySweepTest` sweeps at the fix (darwin/arm64 16-core, load1 5.7-9.4,
  2026-09-07) went 2 red, both `stableFenceAttributed … seeds=[12]`, the same signature and a
  rate indistinguishable from the ~20-30%/sweep recorded on computenet-dwkp. n=6 bounds
  nothing and is recorded as an occurrence, not a measurement: the class is unmoved, as
  expected, and computenet-dwkp stays open.
- The continuation is per `Replication` instance, i.e. per peer process. A replica that departs
  and returns in a DIFFERENT process carries its lane the way it always did — through
  `restore`, from a checkpoint. A process that loses its `Replication` and rebuilds a replica
  from nothing has no lane to continue and no checkpoint either; that is the pre-existing
  unclean-departure disposition (E3.6(c)), untouched here.
- `KeyedSetCell` re-uses its counter space across incarnations in exactly the same way and is
  deliberately NOT changed: it is not `Replicable`, has no `DeliveredFrontier`, no watermark row
  and no `Replication` lifecycle, so the lattice this closes does not exist for it. If it ever
  becomes replicable it needs the same seam.

## KE3-23-CLOSEDROW — the BS-12 seed-12 flake is a REAL escape at 37.5% per sweep, and candidate (1) — the monotone `closed` row — is its excluding term in 45 of 45 occurrences

**Bead**: `computenet-r13k`, under epic `computenet-9sm`. **Measured 2026-09-08**
on `NL-MGD6FQJW91/MacBoo`, a 16-core Apple-silicon macOS host, deliberately with
**no sibling agent running**: `uptime` load1 **4.02 at the start and 8.54 at the
end** (load5 7.52 → 8.36). That band matters and is why it is recorded here —
`computenet-dwkp`'s ~20-30% was taken at load1 4-13 and a later corroboration
read ~40% at load1 8-23, so a rate quoted without its load is not comparable
with either. The load on this host is host endpoint-security scanning
(`computenet-91xn`), not a build: Microsoft Defender and ManageEngine were the
only processes above 10% CPU and the only JVMs alive were idle Gradle/Kotlin
keepalive daemons.

**THE SAMPLE.** 120 iterations of the STABLE arm **alone** — one fresh JVM per
iteration, `scripts/flake-loop/run-method-loop.sh` driving
`SuiteLoop --method`, at base `87d46c4ee` (`computenet-uju5`'s `TagLaneContinuity`
fix merged):

    120 iterations, 45 red  =  37.5%

Every red is the same assertion and the same seed — `FENCE-ATTRIBUTED diverging
seeds=[12]` 45 times, `seeds=[]` 75 times, and no other failure of any kind in
the sample. Reproduce with:

    CP=$(./gradlew -q --no-configuration-cache \
          -I scripts/flake-loop/print-test-classpath.init.gradle.kts \
          :kernel:printTestClasspath | grep -v '^WARNING' | tr '\n' ':') \
    scripts/flake-loop/run-method-loop.sh 120 r13k-base \
      'civictech.cell.replication.GcSafetySweepTest#compaction at the stable frontier is GC-safe across a churn sweep_BS12'

One iteration costs ~3.6s against a Gradle sweep's ~40s, which is what makes
n=120 affordable at all; that ratio, not any new insight, is why the earlier
figures on this class were all n<=12.

**THE CLASSIFICATION: a REAL silent-fence escape, not a rig race.** This answers
the question `computenet-r13k` was filed to answer, and it answers it the same
way in 45 of 45 occurrences. The detail line, byte-identical across all 45 except
the fence step (5000 ×40, 5025 ×4, 5050 ×1 — which compaction point first held
the element, a scheduling detail):

    [BS-12] FENCED-DIVERGE seed=12 ... differing=[peer2-23]; attribution=[peer2-23
      held=[peer0]
      holderState=[peer0{lastDeparture=EVICT_CLEAN suspended=false evictDespawned=true
                         member=true membership=[join@1088, EVICT_CLEAN@1623, rejoin@1885]}]
      liveTags=[8]
      provenance=[peer2{tag=8 own=true inc=11/11 restores=0 mintedHere=peer2-23
                        sameElement=true lastDeparture=null suspended=false
        fencedAt={step=5000 stillHeldBy=[peer0(closed)]
                  openSet={open=[654381a9] members=[82f0e182, 654381a9]
                           announced=[654381a9, 82f0e182, 448a5f68]
                           closed=[82f0e182, 448a5f68] suspended=[] degrade=false
                           rows={654381a9->3}}}
                        membership=[join@1412]}]
      fencedAtLacking=peer2:[8](all)]; discarded=36

**READ IT TERM BY TERM, because the answer is one word.** `stillHeldBy=[peer0(closed)]`
— the annotation `computenet-typw` put on that field is the exclusion term, and it
says `closed`, not absence and not suspension. The `openSet` read confirms it
independently: peer0 (`82f0e182`) is in `members` **and** in `closed`, so
`open = members ∪ announced − closed − suspended` drops it, leaving
`open=[654381a9]` — peer2 alone. `rows={654381a9->3}` is the whole input to the
MIN. So `stableFrontier` handed `compactBelow` peer2's **own** delivered frontier
wearing the name of a quorum, and it certified peer2's del-dot as delivered to
"every open member" when the only open member was the sender. peer0, live and
holding the element, was not consulted because a `closed` entry from its
`EVICT_CLEAN` at step 1623 was never retracted by its `rejoin` at 1885.

**THIS IS `computenet-typw`'s CANDIDATE (1), reproduced in the sweep.** typw
settled candidate (1) deterministically (`StabilityOpenSetOnRejoinTest`, see
`## KE3-23-OPENSET`) but could not say it was the term operating in the wild;
this is that reading, at n=45. Note what it is **not**: `degrade=false` and
`suspended=[]` exclude candidate (3), and the exclusion is a **missing slot**,
not a lying row, so it is not candidate (2) either.

**CONSEQUENCE FOR `computenet-uju5`, stated carefully.** uju5's
`TagLaneContinuity` (candidate (2)'s disposition, `## KE3-23-LANECONT`) is in
this base and the class still fires at 37.5%. That is **not** evidence against
uju5 — the two are different defects on different terms of the same expression,
and uju5's own implementer read its 2-of-6 post-fix reds as this known class
rather than as a regression. This measurement upgrades that reading from a
plausible attribution at n=6 to a measured one at n=120: the residual uju5 left
behind is candidate (1), and uju5 was never scoped to touch it.

**THE FIX IS NOT TAKEN HERE, and this bead was scoped not to take it** — its
`metadata.files` covers the test, the churn rig, `scripts/flake-loop` and this
document, and deliberately not kernel production sources. typw named the three
candidate repairs (a retractable close epoch of the shape `suspendEpoch` already
has; an incarnation-distinct slot; a rejoin that must re-announce before it
counts), all of which touch `kernel/src/main/kotlin/civictech/cell/data/Watermark.kt`.
Filed as **`computenet-07vb`** with this measurement as its motivation and
`StabilityOpenSetOnRejoinTest` as its existing minimal reproducer.

**THE CI CONSEQUENCE, which follows from the rate and is worth stating plainly.**
The same class fired 3 of 5 full `./gradlew :kernel:test --rerun` runs taken as
this bead's own gate (load1 9-18; the sole failure in each was BS-12
`seeds=[12]`, everything else green — 1417 tests, 1 failure, 0 errors, 9
skipped). A 37.5%-per-sweep class sits underneath the `kernel-test` required
check, so **any** PR touching this repo has roughly a one-in-three chance of a
red check that has nothing to do with its diff. That is not a new defect and
this bead does not fix it, but it explains why red `kernel-test` runs keep being
re-attributed to whatever diff happened to be under them: `computenet-9sm.6.6`'s
reviewer isolated exactly that (the failure persisted with its own new test file
removed), and `computenet-9sm.6.4`'s reviewer saw it at both revisions of a
harness-only change. Read a red `kernel-test` whose only failure is BS-12
`seeds=[12]` as this class until shown otherwise.

**WHAT MUST NOT HAPPEN, unchanged and still binding** (`computenet-dwkp`'s four
prohibitions): the BS-12 attribution assertion is not relaxed, the class is not
absorbed into `MAX_STABLE_DIVERGING`, `SEEDS` is not narrowed, and
incarnation-unique `tagSource` is not pursued. Nothing in this bead touched an
assertion, a seed range, a budget or a bound; the diff is a harness selector, a
loop script and this document.

## KE3-BS16-RETAINED — reclamation is an EXCHANGE: retained state as a whole is `O(elements ever reclaimed)`, and no `O(in-flight window)` bound over the whole is reachable without G-42

**Bead**: `computenet-9sm.6.5`, under feature `computenet-9sm.6`, clause
`[KE3-37]` (BS-16). **Measured 2026-09-07** on `NL-MGD6FQJW91/MacBoo`, a
16-core Apple-silicon macOS host, with four sibling agents running
concurrently (`uptime` 1-minute load 5.1-6.6 during the measurement runs, 18.97
at dispatch). Reproduce with
`./gradlew :kernel:test --tests 'civictech.cell.replication.RetainedStateBoundTest' --rerun`
and read the `[BS-16]` lines out of
`kernel/build/test-results/test/TEST-civictech.cell.replication.RetainedStateBoundTest.xml`
(the run prints them to stdout, which Gradle's console swallows on a green run).

### What `[KE3-37]` asks, and why the answer over the whole is negative

BS-16 asks for a bound of the form `constant × (ops per checkpoint window)` — a
constant times the in-flight window, **not** a function of wall time or op
count — and `[KE3-37]` was rewritten to require it be stated over **retained
state as a whole**: tombstone tags PLUS `ReclaimedDots.runCount` PLUS
`ReclaimedDots.elementCount`. The rewrite closes a real trap: reclamation as
landed is an **exchange**, not a removal, so a bound over tombstone count alone
is satisfiable by moving the growth into the fence.

`SetCell.compactBelow` discards a `dels` entry and the `adds` tags under it,
and records exactly those tags in `ReclaimedDots` — **one element key per
element ever reclaimed**, plus a per-`(element, source)` list of contiguous
counter runs. Nothing prunes either. `compactBelow`'s own KDoc already says it:
"a reduction, not a bound", with a bounded form needing epoch hygiene (G-42,
research-gated, 95 R14).

That is confirmed by measurement rather than by reading the code. With the
in-flight window **pinned at one element** — add, remove, reclaim at a covering
frontier, repeated — a `constant × ops-per-window` bound is a constant, so
retained state must be flat as the op count rises. It is not:

| add/remove pairs | tombstoneTags | fenceRuns | fenceElements | total |
|---|---|---|---|---|
| 25 | 0 | 25 | 25 | 50 |
| 50 | 0 | 50 | 50 | 100 |
| 100 | 0 | 100 | 100 | 200 |
| 200 | 0 | 200 | 200 | 400 |
| 400 | 0 | 400 | 400 | 800 |

A **16x** rise in op count produces a **16.000x** rise in retained state, at a
constant window. The tombstone column is the half that *is* `O(window)`: it
returns to 0 at every reclaim point regardless of how many ops preceded it. The
two fence columns are the half that is not, and they are the whole of the
growth.

### The churn arm — the workload `[KE3-37]` actually names

The rig is `GcSafetySweep`'s, reused rather than rebuilt: 3 peers, `ChurnMesh`
with `EVICT_CLEAN`/unclean churn and a per-step heartbeat task, 24 strided
writes, every odd-ordinal one removed 90 steps later, and the **production
reclaim trigger** — `SetCell.snapshot()` reading the stability hook
(computenet-9sm.6.1/9sm.6.4) — fired every 25 controller steps. Retained state
is sampled per member replica at every one of those points, *after* the
reclaimer's hook at the same step. Seeds 1..20, budget 40 000.

**Three consecutive runs** (a fourth, earlier run agrees), each ~16 500 samples
per arm:

| run | load (1m) | wall | STABLE total min/med/max | STABLE tombstoneTags min/med/max | CONTROL total min/med/max | runs/elements |
|---|---|---|---|---|---|---|
| 1 | 5.41 | 1 260 ms | 0/14/28 | 0/3/15 | 0/21/36 | 1.000 |
| 2 | 5.38 | 1 170 ms | 0/14/26 | 0/3/15 | 0/21/36 | 1.000 |
| 3 | 5.38 | 1 173 ms | 0/14/26 | 0/3/15 | 0/21/36 | 1.000 |

The **spread is reported rather than a single number**, because
`GcSafetySweepTest`'s reviewer measured large run-to-run variance in that rig's
*divergence* counts (STABLE 4,5,5,5,8,9 against a control of 4,4,5,6,6,6 over
ten 200-seed runs, and a second reader independently got STABLE 2 / CONTROL 8).
This observable is far steadier — a retained COUNT read at a compaction point,
not a convergence verdict — but it is not perfectly deterministic either: the
STABLE total max moved 26/28/26/26 across four runs and the sample count moved
16 503-16 545, so a single-run figure here is a point in a small band, not a
constant. The tombstone max (15) and the control max (36) were identical in all
four.

**36 is the arithmetic ceiling** of the tombstone component on this workload —
12 removes, each retaining its covered add-tag and its del-dot in `dels` plus
the same add-tag still in `adds` — and the no-reclaimer control (`Trigger.NONE`)
reaches it exactly. That is the growth the control is asked to show. The STABLE
arm's 15 is a real reduction of that component.

**What the churn arm does NOT establish, stated because it would otherwise be
over-read**: 12 removes over a 5 190-step workload at a 25-step compaction
period is ~0.17 ops per window, so at this op count no constant times the
window is distinguishable from "every remove the workload ever issued". The
churn arm's 15-against-36 is a *recorded ceiling and a regression pin*; the
`O(window)` evidence for the tombstone component is the window-pinned table
above, where the op count varies 16x and the tombstone component does not move
off 0.

### `runs / elementCount` — the number `[KE3-37]` marked `unverified:`

**1.000 on both rigs**, on every run. The coalescing is total: the tags covering
one element are one add-tag and one del-dot minted adjacently by one source, so
they collapse into a single `[lo, hi]` run. The adversarial one-run-per-tag
degradation `ReclaimedDots`'s KDoc warns about is not reached by this workload —
neither by the deterministic window-pinned arm (where it is structural) nor by
the churn arm with duplication, reordering, three parks and full
`EVICT_CLEAN`/unclean departure churn. So the fence's cost is `2 ×` elements
ever reclaimed (one key + one run), not `1 + tags`.

**Consequence for the exchange**: at 1.000 the exchange saves the tag SETS and
keeps the element keys, which on this workload is 36 → 26 retained units at the
sampled maximum — a 28% reduction, permanent and non-growing in the numerator
but linear in op count in both terms.

### Excluded from the accounting, and why

- **The computenet-dwkp diagnostic maps** `mintedHere` (one entry per tag this
  instance ever mints) and `incarnations` (one counter per `tagSource` the
  process constructs) are unpruned and unreclaimable by `compactBelow`, i.e.
  `O(local mints)`. `SetCell.retainedState` excludes them by construction, so
  nothing above attributes their growth to the reclaimer. Bounding or
  build-gating them is **computenet-fzd3**, a separate open bead.
- **Live add-tags with no `dels` entry** are excluded for the opposite reason:
  they are `O(live elements)` and irreducible — an element that is present must
  carry the tag that makes it present.

### Disposition

Per `[KE3-37]`'s own instruction — "If no `O(in-flight window)` bound over the
whole is achievable without G-42, that SHALL be recorded as a finding and a
DISPUTE rather than asserted as a weaker passing bound" — **no bound over the
whole is asserted**. What is asserted in
`kernel/src/test/kotlin/civictech/cell/replication/RetainedStateBoundTest.kt`:

1. the tombstone component is `O(in-flight window)` — 0 at a pinned window
   across a 16x op sweep, and ≤ 24 (measured 15) against the control's 36 on
   the churn rig;
2. the control arm shows growth and never writes the fence;
3. the accounting's own definition — a LIVE re-added tag is not a tombstone tag,
   pinned deterministically because the churn workload cannot exercise it (see
   the mutation record below);
4. **the negative result itself**, as a live assertion: retained state as a
   whole rises with op count at a fixed window (`retainedRatio ≥ 0.9 ×
   opRatio`). If that assertion ever goes red because retained state stopped
   growing, the fence has acquired a pruning rule — that is G-42 landing, and
   this finding, the DISPUTE `KE3-GC-BS16-RETAINED`, and G-42's row in
   `91-gap-analysis.md` must all be revisited rather than the assertion
   relaxed.

**G-42 stays OPEN** in `doc/spec/90-roadmap/91-gap-analysis.md` (`[KE3-41]`),
and this finding is the measurement of *why*: reclamation as landed is a
reduction, not a bound. That file is not edited by this bead.

### Mutation evidence — that the assertions discriminate

Every production-side mutation was confined to `SetCell.retainedState`, this
bead's only production edit; `compactBelow`, `applyRemote` and the fence were
not touched (they are outside the claim, so "reclamation stopped" is simulated
at the test arm rather than by breaking the reclaimer). Each mutation was
applied after the deliverable was committed, its landing proved by a non-empty
`git diff HEAD -- <file>`, and reverted by `git checkout --` with `git status`
verified clean.

1. **"Reclamation stopped"** — the churn arm's STABLE sample source switched to
   the no-reclaimer `Trigger.NONE`. **RED**, on the non-vacuity assertion, which
   fires first: `[KE3-37]: the reclaimer never discarded anything on the STABLE
   arm, so this run proves nothing about reclamation: [BS-16] STABLE
   samples=16318 total(min/median/max)=0/21/36
   tombstoneTags(min/median/max)=0/21/36 maxFenceElements=0 maxFenceRuns=0
   runs/elements=n/a`. Note the report it prints: the tombstone max in that
   state is **36**, above the bound's 24, so the bound assertion is violated too
   — the non-vacuity line simply reaches it first.
2. **The accessor's live-tag filter** — `adds[e] ∩ dels[e]` replaced by
   `adds[e]`. **INERT on the churn arm**: the sweep's numbers came back
   byte-identical (`tombstoneTags` max 15, control 36, STABLE total max 26). The
   cause is the workload — `GcSafetySweep` never re-adds a removed element and
   `SetCell.remove` leaves every folded add-tag in `adds[e]`, so `adds[e] ⊆
   dels[e]` there and the filter cannot fire. **A surviving mutation is a
   property left unproven**, so it was proven directly instead: `the tombstone
   component excludes a live re-added tag` is a deterministic add/remove/re-add
   pin, and re-running the same mutation against it is **RED** — `…the tombstone
   component is 2 (the two `dels` tags) + 1 (the `adds` tag under them) minus
   nothing, and the live tag is NOT counted. Counting it would report 4.
   state=RetainedState(tombstoneTags=4, fenceRuns=0, fenceElements=0)`.
3. **The finding itself** — `retainedState` made to report a *pruned* fence
   (`minOf(reclaimed.runCount, 25)`), i.e. the G-42 outcome simulated. **RED**
   on the `retainedRatio ≥ 0.9 × opRatio` assertion: `…25 pairs -> total=50, 400
   pairs -> total=425 (opRatio=16.0 retainedRatio=8.5)`. So the finding is a
   live measurement, not an unexamined comment: the day the fence acquires a
   pruning rule, this test says so.

## KE3-CKPT-TRIGGER — the checkpoint-driven reclamation trigger: `SetCell.snapshot()` is now the sole production caller of `compactBelow`, and what it cost to prove

**Feature**: `computenet-9sm.6`, close-out task `computenet-9sm.6.7`. This
entry attributes every number to the sibling task that measured it; nothing
here was re-measured by this task. Before this feature, `git grep -n
'compactBelow' origin/main` showed ZERO production callers — every call site
on `main` was a test (`SetCellCompactBelowTest`, `CompactionTriggerPinTest`,
`GcSafetySweepTest`). After it, `git grep -n 'compactBelow' -- '*.kt'` at the
feature's head shows exactly one: `SetCell.kt`'s `snapshot()`
(`if (frontier != null) compactBelow(frontier)`, decision 9sm.6-D1). This is
the `[KE3-30]` closing evidence.

### What landed

- **The trigger (`computenet-9sm.6.1`).** A new `StabilityReclaim` interface
  (`kernel/src/main/kotlin/civictech/cell/data/delta/StabilityReclaim.kt`,
  `fun onStability(read: () -> TagFrontier?)`), kept off `DeliveryTracking` so
  `PnCounterCell` (no tags to reclaim) is not conscripted. `SetCell`
  implements it; `Replication.trackDeliveries` installs
  `{ stableFrontier(cell.ref.id) }` beside the existing `onDeliver`, guarded
  `if (cell is StabilityReclaim)`. `snapshot()` reads the frontier *outside*
  `stateLock` (a foreign call the lock's own contract forbids holding across),
  then compacts and serialises under one hold of the monitor —
  `compactBelow` re-enters it, reentrant `synchronized`. There is **no
  `compact: Boolean` flag**: reclamation rides every caller of `snapshot()`
  (`HostDurability.checkpoint`, `ManagedHost.snapshotOf`, migration,
  promotion, the concord driver's raw `snapshot` verb) because the frontier is
  `[KE3-30]`'s sole authority for a discard and the caller's identity is
  exactly the "other condition" that clause forbids adding.
- **Checkpoint-crossing (`[KE3-34]`, `computenet-9sm.6.2`).** Covered end to
  end by `CheckpointReclaimCrashTest`: reclaim → checkpoint → 10-op journaled
  tail → crash → restore → tail replay leaves `membership()` equal to
  pre-crash, and a replayed delta carrying a reclaimed tag is folded as
  already-observed and answered with the same repair `dels` entry, byte-equal
  to the pre-crash answer. A second test pins pay7's fail-safe direction: a
  checkpoint blob with the `"reclaimed"` key stripped restores an EMPTY fence
  (a recoverable resurrection window), never an invented one.
- **`[KE3-33]` BS-14 — concord scenario NOT delivered (`computenet-9sm.6.3`).**
  `42-GC-RECLAIM-01` was authored and run in full against the kernel driver's
  dist profile; it failed 20/20 on `emission-count(r2, since 7): expected
  exactly 1 emission(s) but observed 0`. Root cause is structural, not
  scenario error: `KernelDriverDist` holds one mesh-wide `Replication`, whose
  `trackDeliveries` memoises the delivered-watermark companion keyed on the
  *logical* cell id, so a second replica reuses the first's companion and
  contributes no row of its own; `CausalStability.stableFrontier` then drops
  every source with any null-slot member, so the dist frontier is
  permanently `TagFrontier(emptyMap())` and `compactBelow` never discards
  anything on that profile. The scenario was not weakened to pass — dropping
  only its `emission-count` check was independently confirmed to turn it
  green while reclaiming nothing, which is exactly the false-coverage
  AGENTS.md forbids. Filed as DISPUTE `KE3-GC-RECLAIM-FRONTIER` in
  `concord/corpus/DISPUTES.md`, carrying the scenario script verbatim so it
  can be committed unchanged once the wiring gap closes. Follow-up:
  `computenet-cthi` (give `KernelDriverDist` a per-host `Replication`).
- **`[KE3-37]` BS-16 — a finding and a DISPUTE, not a bound
  (`computenet-9sm.6.5`).** See `## KE3-BS16-RETAINED` above (this file) for
  the full measurement; not duplicated here. In one sentence: reclamation as
  landed is an EXCHANGE — it trades a tombstone entry for a `ReclaimedDots`
  fence run plus an unpruned element key — so retained state as a whole grows
  with op count at a fixed checkpoint window (a 16x op sweep produced a
  16.000x rise in fence size), and no `O(in-flight window)` bound over the
  whole is reachable without G-42. **G-42 stays open** in
  `doc/spec/90-roadmap/91-gap-analysis.md` for exactly this reason; this
  feature's diff does not touch that file (confirmed: `git diff
  origin/main...HEAD -- doc/spec/90-roadmap/91-gap-analysis.md` is empty).
- **BS-12/BS-13 substitution (`computenet-9sm.6.4`).** `GcSafetySweepTest`'s
  STABLE arm now reclaims by calling `cell.snapshot()` (the production
  trigger) rather than `cell.compactBelow(frontier)` directly; LOCAL keeps
  the direct call deliberately (`[KE3-30]` makes the stable frontier the sole
  discard authority, so there must be no production caller at the local
  seam). One run of the module gate: STABLE 0/200 resurrecting, 7/200
  membership-diverging (fence-attributed 0), against a `Trigger.NONE` control
  of 7/200 in the same run — `(stableDiverging + stableFenceAttributed) = 7
  <= MAX_STABLE_DIVERGING = 12`, not widened. `BS12_SEED` (126) held 5/5. No
  seed was re-derived by this substitution.

  **Caveat, measured by this task's review (2026-09-07, darwin/arm64, at the
  merged feature head + this entry):** that `fence-attributed 0` is **one
  run**, and the arm is **not deterministic**. Five runs of
  `:kernel:test ... GcSafetySweepTest --rerun --no-build-cache` on this machine
  failed **2 of 5**, each time on `seeds=[12]`, with the assertion the test
  itself says must never be absorbed — *"the re-admission fence CAUSED a
  membership divergence … Do not absorb it into MAX_STABLE_DIVERGING"*
  (`GcSafetySweepTest.kt:973`). The pinned-seed arm
  (`the recorded seed reproduces its verdict_BS12`, `BS12_SEED` 126) and the
  `Trigger.NONE` control passed in every run. So the sweep's clean single-run
  numbers above should be read as *a* sample, not as the arm's steady state.

  **Answered 2026-09-08 by `computenet-r13k` (see `## KE3-23-CLOSEDROW` below):
  seed 12 is a REAL escape, not a rig race**, and the caveat above should now be
  read as settled rather than open. 120 iterations of the STABLE arm alone at
  `87d46c4ee` failed **45**, all on `seeds=[12]`, and all 45 name the same
  excluding term: `stillHeldBy=[peer0(closed)]`. The `fence-attributed 0` figure
  above remains a truthful single sample and is left as recorded; what has
  changed is that the arm's steady state is now measured (37.5%) rather than
  guessed, and the mechanism is upstream of the fence rather than in it.
- **`[KE3-38]` BS-18 (`computenet-9sm.6.6`).** `CompactionExclusiveAccountingTest`
  shows zero consumes/releases/drops attributable to checkpoint-driven
  compaction AND to `applyRemote`'s repair emission (a new outbound `SetDelta`
  on a path that previously emitted nothing for a pure-duplicate frame). Key
  finding: the repair `dels` entry's key for an `Owned` element is the
  sender's own handle **by reference** (`=== elements[0]`), so no second live
  handle and no new obligation is minted; a journal checkpoint of an
  `Owned`-element replica cannot itself complete (`Owned` is not
  `java.io.Serializable`), so the span is driven through `snapshot()` directly
  — that is the whole of what `HostDurability.checkpoint` contributes over it
  for this element type, and nothing about ownership accounting depends on
  the journal write itself.
- **One CI-only integration failure, found and fixed
  (`computenet-9sm.6.8`).** `StabilityRowCoverageOnReincarnationTest`
  (`computenet-mahx`'s measurement arm) broke because its diagnostic tag read
  went through `snapshot()` — which, since `computenet-9sm.6.1`, IS the
  reclamation trigger — so the act of reading tag state for the assertion
  fired the reclaimer a line early and left nothing for the test's own
  explicit `compactBelow` call to discard (`Key z is missing in the map`).
  Fixed by paging `readBounded`'s `SetStateEntry.delTags` instead of
  `snapshot()`, mirroring the substitution `computenet-9sm.6.4` had already
  made for `CompactionTriggerPinTest`; every assertion, seed and schedule
  constant is byte-identical. A reviewer additionally proved the
  criterion-carrying assertion (`coverageOf(...) shouldBe COVERS`) actually
  discriminates: no in-claim test mutation could reach it without tripping an
  earlier precondition, and a single-file production mutation
  (`DeliveredFrontier.deliver` alone, discriminating reincarnation-scale reuse
  and poisoning the prefix) still passed both arms, because
  `Watermark.advance`'s monotone guard (`if (thru <= row[source]) return`)
  refuses to publish a lowered prefix. Only mutating **both**
  `DeliveredFrontier.deliver` (to publish the poisoned, lowered prefix) **and**
  `Watermark.advance`'s guard (to let a lowering through) turned the
  measurement arm red at the `COVERS` assertion while the control arm stayed
  green — establishing that the false certificate this arm exists to measure
  requires delivered-lane absorption of reused counters AND row monotonicity
  *together*; neither alone reproduces it.

### The u7fi trigger check, re-run over the whole feature diff

`computenet-9sm.6.1` posted a partial result on `computenet-u7fi` covering
only its own five files. This task re-ran the same grep over the feature's
complete diff (all 11 files touched across `computenet-9sm.6.1`–`.6.8`):
`git grep -n 'reBaseline\|ReBaselineNotice\|dotSource' -- <those 11 files>`.

**Corrected by the task review (2026-09-07); read the qualified result, not a
bare zero.** That command does **not** return zero over the 11 files. Run with
the eleven paths as eleven separate pathspecs it returns **five** hits, and
every one of them is outside what u7fi asks about:

- `concord/corpus/DISPUTES.md:378` (`ReBaselineEmitting.reBaseline(...)`) and
  `:1165` (`restoreBaselineDischarge`, a substring match on `…toreBaseline…`)
  are **pre-existing on `origin/main`** — verified with
  `git grep -n '…' origin/main -- concord/corpus/DISPUTES.md`, which returns
  the same two lines. They belong to the C-12 restart/re-baseline dispute, not
  to this feature, and
  `git diff origin/main...HEAD -- concord/corpus/DISPUTES.md | grep '^+'`
  matches the pattern on **no added line**.
- The remaining three are **this entry's own prose**, in
  `doc/kernel-lane-findings.md`, describing the grep.

That count of five is **as of the text this correction replaces**. The pattern
matches the words used to discuss it, so this correction raised its own prose
share: re-run over the same eleven paths at this commit and the same command
returns **eight** lines — the same two `DISPUTES.md` lines plus six in this
file. Only the `DISPUTES.md` half of the count is a claim about the code; the
rest is the entry citing itself, and it will keep growing if the passage is
edited again.

Restricted to the **nine `.kt` files** of the feature diff — the only ones
where an emission could live — the grep genuinely returns nothing (exit 1).
The bead's own instruction is the scope that matters, and it is met on it:
*no line this feature ADDED* introduces a `TaggedMapDelta` re-baseline
emission or a `dotSource` supersession.

The bare "zero hits" first recorded here (and in the corresponding comment on
`computenet-u7fi`) came from an invocation whose eleven paths reached `git
grep` as a **single** pathspec — zsh does not field-split an unquoted
expansion — so it matched nothing for the reason AGENTS.md names: *a zero
result from a grep is evidence about the grep before it is evidence about the
symbol*. The `OrMapCell.kt` control did not catch it, because a control run as
one single-path argument exercises neither the splitting nor the multi-path
form that failed. A control only discriminates if it shares the failing
invocation's **shape**, not just its pattern.

`SetCell` implements neither
`ReBaselineEmitting` nor anything `dotSource`-shaped; its repair emission
(`applyRemote`'s `SetDelta(newAdds, repaired)`) is a plain `SetDelta` naming
existing tags — not a `TaggedMapDelta`, mints no new dot, and touches no
`dotSource`. Full result posted to `computenet-u7fi`, superseding the earlier
partial scope.

## KE3-23-CLOSEDPREMISE — the disposition of candidate (1): `closed` is HONOURED ONLY WHERE ITS PREMISE HOLDS, and no lattice changes

`computenet-07vb`, 2026-09-08, base `eec4a4cdf`. `computenet-typw` settled that
`closed` is the term excluding a rejoined replica from `open`
(`## KE3-23-OPENSET`) and `computenet-r13k` measured it firing at 37.5% per
`GcSafetySweepTest` sweep, 45 of 45 occurrences byte-identical
(`## KE3-23-CLOSEDROW`). Neither took the repair. This is it.

**THE DISPOSITION IS A FOURTH**, argued against the three `computenet-typw`
named. `closed` (PN-0c) asserts exactly one proposition — *this row can never
advance again* (`WatermarkCell.close`: "its row stops constraining reads") — and
the defect is not that the proposition is recorded monotonically. It is that the
proposition became FALSE and the read kept applying it, because
`WatermarkCell.slotId` is derived from the `CellRef` (M10.1 replay-stability) and
is therefore stable across a rejoin. So the repair is to stop applying `closed`
where its premise is contradicted, at the read:

    open = memberSlots u announced - (closed - memberSlots) - suspended

`memberSlots` is this node's own `InstanceIndex.instancesOf` view. A slot backed
by a LIVE instance can advance again, so `closed`'s marker does not describe it.
Three lines in `CausalStability.stableFrontier`, mirrored in its `openSlots`
diagnostic and in `Replication.onStabilityStall`'s inline derivation of the same
set (whose KDoc already promised it derives the set "the way
`CausalStability.stableFrontier` derives its own" — it now does).

**Why not (a), a retractable close epoch of the shape `suspendEpoch` has.**
Two independent objections. It is a WIRE change: `WatermarkDelta.closed` is a
grow-only `Set<UUID>` and would become a per-slot epoch map, and the file's own
KDoc names `closed` "the degenerate terminal case (an epoch that never turns even
again)" — retractability is the property that distinguishes the two lanes, so
this is not an additive encoding but a redefinition of one. And it is
unnecessary: the CRDT is not wrong. Every replica correctly recorded that the
row closed; only the reader over-applied it. Repairing a correct lattice to
compensate for a reader is the more invasive of the two, and it would have to
converge a retraction across the mesh where the read already has the answer
locally.

**Why not (b), an incarnation-distinct slot.** `WatermarkCell.slotId`'s
replay-stability is a documented contract with a stated failure mode — "a
recovered instance replaying its journal credits the SAME row the network already
saw ... A random slot would resurrect a phantom replica row" (M10.1, mirroring
`PnCounterCell`). Making the slot incarnation-distinct breaks journal replay for
the watermark companion itself, which is a strictly larger blast radius than the
defect. **On whether `computenet-dwkp`'s fourth prohibition reaches this
lattice**, asked explicitly by this bead and by `computenet-uju5` before it: it
does NOT. dwkp forbids incarnation-unique `tagSource` on the ground that it "does
not address the case measured" — a statement about the FENCE question and the TAG
lattice. Here the analogous change WOULD address the case measured, so the
prohibition's own reasoning does not transfer, and (b) is ruled out on M10.1, not
on dwkp. Recorded so nobody re-derives the question a third time.

**Why not (c), a rejoin that must re-announce before counting toward `open`.**
It is (a) wearing different clothes. `members` is grow-only and the slot is
ref-derived, so `announceMember` on rejoin is already a no-op — making a
re-announce COUNT requires an announce epoch that outranks `closed`, i.e. a new
monotone lane and a new delta field, with the same wire cost as (a) and none of
its clarity. It also inverts the FU-2 asymmetry: `announced` exists to make the
open set larger than `instancesOf` alone, never to gate it.

**The correction is in the CONSERVATIVE direction, and that is what makes it
safe for every other reader.** `open` can only GROW under this change, so the
MIN runs over at least as many rows and the frontier can only FALL. No
`(source, counter)` that the read previously refused to certify becomes
certified — the failure mode this whole line has been chasing cannot be
introduced by it. The price is the mirror image of the FU-2 union's existing
staleness: while `instancesOf` still lags on a genuinely departed replica, its
`closed` marker is ignored and stability FREEZES on its row until the view
converges. A freeze, never a premature release, and self-healing. A departed
replica that does not return leaves `instancesOf` on despawn and stays excluded,
so PN-0c's own job is unchanged.

**WHAT `closed` NOW MEANS TO OTHER READERS** (acceptance clause 5, reported
rather than assumed local).

- `CausalStability.stableFrontier` — changed, as above; frontier can only fall.
- `Replication.onStabilityStall` — changed to match, deliberately. Its
  `StabilityFreezeDetector` will now report a freeze pinned on a rejoined slot
  that it previously could not see, which is the true state — **but it cannot
  HOLD that latch, and the notice flaps.** Corrected by the feature reviewer
  (`computenet-07vb`), who measured it rather than reading it: `evaluate`'s
  `@param open` still documents the caller's set as "minus `closed`", and its
  retraction arm clears a latch on `slot in closed` against the RAW grow-only
  set. Since the repair `open` and `closed` are no longer disjoint at that call
  site, so a rejoined slot latches `STABILITY_FROZEN` on the Hth evaluation and
  is handed a spurious `Resume` on the very next one with its row unmoved, then
  re-latches every H evaluations. Measured directly against
  `StabilityFreezeDetector(threshold = 2)` with `open = {A,B,C}`,
  `closed = {C}`, C's row pinned at 9: `e3=[Stall(STABILITY_FROZEN, slot=C)]`,
  `e4=[Resume]`. The direction is conservative — the freeze is over-reported,
  never hidden, and this state was unreachable before because the slot was not
  in `open` at all — so it does not touch certification, and it is filed rather
  than fixed here: `computenet-92ek`.
- `Replication.replicaFrontier` / **`ReplicaQuorum.frontier` — NOT changed, and
  it carries the SAME defect.** Its per-member check is
  `slot in closed || (rows[slot]?.get(source) ?: MIN) >= counter`, and `covering`
  is derived from `membersOf`, i.e. LIVE instances — so a rejoined member's stale
  `closed` marker makes it vacuously satisfy the covering-quorum predicate, and
  its `membershipBarrier` counts the same slot as `accounted`. That is a false
  certificate of the same family, on the per-wave settlement read rather than the
  GC read. `ReplicaQuorum.kt` is outside `computenet-07vb`'s file claim and the
  defect there has NOT been measured (only read), so it is filed rather than
  fixed. It is NOT a regression from this change: it behaved this way before.
- `WatermarkCell` / `WatermarkDelta` / the journal and the wire — untouched.
  `closed` is still grow-only, still gossiped as a `Set<UUID>`, still terminal in
  the lattice. Nothing about compatibility changes, and the `:oracle` reference
  model needs no mirror because no lane was added.
- `WatermarkCell.republish`'s `if (slotId in closed) return` — a replica's own
  local suppression of its heartbeat, not a membership read. Left alone: it is
  reached only while this replica is itself closed, and a rejoin constructs a new
  cell with an empty `closed`.

**TESTS.** `StabilityOpenSetOnRejoinTest`'s first test was INVERTED in place
(same rig, same seed, same eviction) from a characterisation of the defect into
its regression, and is joined by a new control — a cleanly departed slot that
does NOT rejoin stays out of `open` — so the repair cannot pass by ignoring
`closed`. Its pre-existing sibling (a suspended slot stays inside `open` at
`degrade=false`) is untouched and still green, keeping candidate (3) refuted.
`CausalStabilityTest`'s two synthetic pins were AMENDED: both built a state where
a slot is closed AND still named by `membersOf`, which since this change is the
REJOIN state rather than the departure state, so they now model a clean departure
the way the mesh actually presents one (gone from `membersOf`, still in the
grow-only announced set) and are joined by a live-member case pinning the new
semantics. `DepartureStabilityPinTest`, `MemberDepartureFrontierTest` and
`ShardedReplicationTest` — the three integration pins on departure/frontier
behaviour — required NO change and stayed green, which is the strongest evidence
that PN-0c's real path is unaffected.

**OPEN AND MEASURED, NOT SPECULATIVE — this repair turns `StableFrontierChurnSweepTest`
(BS-5) RED on 8 of 60 seeds, and the file that pins it is outside
`computenet-07vb`'s claim.** The check is "stableFrontier regressed under fixed
membership": a per-peer sample recording `(open, stable)` each time it reads, and
comparing successive reads **only when the `open` SET compares equal**. First
occurrence, seed 12: `step=1886 peer=peer0 source=d08c0e45 3 -> 2`, with a
`dst-crash` on `peer2` at step 1414 in the same plan — i.e. a crash-restart, which
is a rejoin onto the same `CellRef` and therefore exactly the state this repair
changes.

ATTRIBUTION IS MEASURED, not inferred, and it is not a pre-existing flake: with
`CausalStability.kt` and `Replication.kt` checked out at the base `eec4a4cdf` and
**every test file from this branch left in place**, `:kernel:test --tests
'…StableFrontierChurnSweepTest' --rerun --no-build-cache` is GREEN (60/60); with
the two source files restored it fails 8/60. The two runs differ only in the
repair.

WHAT IT MEANS, stated at the strength it is actually established. The repair
lowers the frontier when a rejoined slot re-enters `open` — that is its intent and
its safe direction (§ above) — and BS-5's guard only suppresses comparisons where
`open` compares *equal*, so a fall that is invisible to the guard reads as a
regression. Whether that makes BS-5's invariant WRONG for a rejoining mesh (the
frontier is not monotone once a member can re-enter, and the guard's set-equality
test cannot see a departure/rejoin round trip between two samples) or makes the
REPAIR wrong (some path really does lower the MIN at a genuinely fixed open set)
is **NOT settled here**: settling it means reading the rig's observation cadence
and amending its check, and `StableFrontierChurnSweepTest.kt` is not in this
item's `metadata.files`. Reported rather than worked around, per the item's own
clause 5 and its dispatch. Nothing in this entry should be read as licence to
relax that check; it is a live question, and the shape of the answer decides
whether the repair ships as written.

> **SETTLED** — see `## KE3-23-BS5-BLINDSPOT` at the end of this file. The
> invariant is right; the RIG's independent re-derivation of `open` was stale.
> The answer is not "relax the check": the amended rig is strictly STRONGER, and
> now catches the KE3-23 defect itself at sweep scale.

**THE RE-MEASUREMENT (acceptance clause 3).** Same instrument, same method, same
n as `## KE3-23-CLOSEDROW`'s 45/120, on the same host (darwin/arm64 16-core), one
fresh JVM per iteration:

    scripts/flake-loop/run-method-loop.sh 120 c07vb-fix \
      'civictech.cell.replication.GcSafetySweepTest#compaction at the stable frontier is GC-safe across a churn sweep_BS12'

    120 iterations, 0 red = 0.0%, loadStart=[5.13 8.13 8.98] loadEnd=[9.07 8.24 8.47]

against `computenet-r13k`'s **45/120 = 37.5% at load1 4.02 -> 8.54** on the same
class and arm. The load ranges overlap (this sample ran slightly HOTTER, and a
reviewer's independent n=14 at load1 10.4-13.4 scored 50%, so load moves this
number UPWARD — the hotter sample is the more conservative comparison, not the
more flattering one). Every one of the 120 iterations reported
`FENCE-ATTRIBUTED diverging seeds=[]`. If the rate were still 37.5%, 120
consecutive greens has probability `0.625^120` — of order `1e-24`; this is not the
"19 consecutive greens" hazard `computenet-dwkp` warned about, which was 19 at a
~20-30% rate (`0.75^19` ~ 0.4%, i.e. unremarkable). The deterministic tests, not
this sample, remain the primary evidence per clause 2; this is the corroboration
clause 3 asks for, and it is stated as a rate with its load, not as a green run.

## KE3-23-BS5-BLINDSPOT — BS-5's rig re-derived `open` with the term the repair corrected

`computenet-07vb`, settling the open question left in `## KE3-23-CLOSEDPREMISE`.
Two readings were named there and neither chosen: **(i)** BS-5's `[KE3-18]`
invariant is wrong for a mesh where a member can re-enter, or **(ii)** the repair
really does lower the MIN at a genuinely fixed open set. It is **(i)**, and more
precisely than that reading stated it: the invariant is fine, the **rig's own
open-slot derivation was stale**, and the fix makes the sweep strictly stronger
rather than weaker.

**What the rig does.** `StableFrontierChurnSweep.observe` deliberately re-derives
the open-slot set *independently of `CausalStability`* — that independence is what
makes the sweep a check on the read rather than a copy of it — from the two reads
spec 42 §"The stability read" names. It derived:

    open = replicasOf-slots ∪ companion.members() − companion.closed()

which is `stableFrontier`'s formula **as it was before this item**. The repair
changed that read to subtract `closed − memberSlots`. Independent re-derivation
means deriving the same *quantity* by a different route, not freezing a formula:
once the read changed, this hook was computing a different set from the one it was
asked to be the oracle for.

**The blind spot is structural, not probabilistic.** `[KE3-18]` compares two
consecutive frontier samples only when the open-slot set compares EQUAL — a
membership change exempts the step it lands on, because the FU-2 union makes the
frontier legitimately dip when membership grows. But a rejoined replica returns
onto its **ref-derived, replay-stable** slot (M10.1), which is already in the
grow-only `closed`; under the unqualified subtraction that slot could never enter
the rig's set, in either sample. So at the exact step where the production open
set GREW by the returning member, the rig's set compared equal, the exemption did
not fire, and the frontier's intended FU-2 dip onto that member's row was recorded
as a regression. Set-equality over a set that by construction omits the slot whose
membership changed cannot see a departure/rejoin round trip. That is why the
8 of 60 failures all sat one step after a rejoin — first `step=1886 peer=peer0
3 -> 2`, on a plan whose `dst-crash` on `peer2` fires at 1414.

**The amendment.** `observe` now derives `open` the way the read does, subtracting
`closed − memberSlots`, with the reasoning in its KDoc. Direction matters and is
the answer to "did you weaken the check": the qualified form makes the set
**LARGER**, so `[KE3-17]`'s arm compares strictly MORE `(peer, slot, source)`
triples — a rejoined slot's row is now checked against the frontier where before it
was not checked at all. Only `[KE3-18]`'s arm is relaxed, and only by exempting a
step on which membership genuinely changed, which is the exemption that arm
already documents in its own KDoc.

**Measured, one fresh Gradle run per figure, `--rerun`, on darwin/arm64 16-core:**

| rig | source | BS-5 result |
| --- | --- | --- |
| unqualified (before) | repaired | **8/60 red**, `[KE3-18]` regression arm |
| unqualified (before) | base `eec4a4cdf` | 60/60 green |
| **premise-qualified (now)** | **repaired** | **60/60 green**, load1 8.53 → 7.57, elapsedMs=5023 |
| premise-qualified (now) | base `eec4a4cdf` (mutation) | **RED — `[KE3-17]` violation arm** |

The last row is the load-bearing one and it settles (ii) as well as (i). With the
rig corrected and the DEFECT restored, the sweep fails on
`stableFrontier exceeded an open member's delivered row` — the *violation* arm, not
the regression arm. That is the KE3-23 false certificate itself, caught at sweep
scale: the frontier running ahead of a live, rejoined member's delivered row. The
unqualified rig could not see it at all. So the amendment did not buy a green by
softening BS-5; it gave BS-5 sight of the very defect this item repairs. (Mutation
discipline: the base checkout was proved to land with a non-empty
`git diff HEAD --stat -- kernel/src/main/kotlin/`, `BUILD FAILED` read from the
log, and both files restored from a pre-mutation copy with `git status --short`
verified clean afterwards.)

**And (ii) is refuted directly, not merely by absence.** If the repair genuinely
lowered the MIN at a fixed open set, the corrected rig — whose set now tracks the
production set step for step — would still record `[KE3-18]` regressions. Across
60 seeds it recorded zero over **1,779,655** actual `(peer, source)` comparisons.
The reason is structural: at a fixed open set the MIN runs over a fixed slot set
whose rows are join-monotone, so it cannot fall.

**Non-vacuity, so the corrected term cannot rot.** A new sweep-wide counter,
`premiseContradictions`, counts steps carrying a `closed` marker on a slot the
peer's own replica view reports as LIVE — exactly the state the qualified
subtraction exists for — and the test asserts it non-zero. Measured
**167,355** such steps out of 757,174 frontier reads (22%). Without that assertion
the rig would pass identically with the unqualified form restored, which is how
this staleness arose in the first place.

**Clause 5 note.** This is the fourth reader of `closed` to be reconciled with the
repair (after `stableFrontier`, its `openSlots` diagnostic, and
`Replication.onStabilityStall`), and the only one that is a test rig. The fifth,
`ReplicaQuorum.frontier`, carries the same defect READ but not measured and is
tracked separately as `computenet-s0tq`; it is deliberately not folded in here, so
that this item's verdict rests on one measured rate comparison rather than on a
measured and an unmeasured repair together.

**BS-12 after the rig amendment (confirmation).** This item's last change touches
no `src/main` file — only `StableFrontierChurnSweepTest.kt` and this document — so
the 0/120 above stands by construction. Re-sampled anyway, same instrument, same
method, HOTTER host: **40 iterations, 0 red = 0.0%, loadStart=[11.05 12.31 11.03]
loadEnd=[8.93 11.36 10.91]**, every iteration reporting `FENCE-ATTRIBUTED
diverging seeds=[]`. That is 160 consecutive greens on this branch against
`computenet-r13k`'s 45/120 = 37.5%, the later 40 of them at a load1 above the top
of r13k's own 4.02-8.54 range.

## KE3-23-QUORUMCLOSED — the per-wave read carries candidate (1)'s defect too, it is REACHABLE, and the repair here degenerates to deleting the `closed` arm

`computenet-s0tq`, 2026-09-08, base `0d2fd04d6`. `## KE3-23-CLOSEDPREMISE`
disposed of candidate (1) on `CausalStability.stableFrontier` and, under its
clause-5 inventory of the other readers of `closed`, recorded that
`ReplicaQuorum.frontier` — the per-wave settlement read — carries the same shape
but was outside that item's file claim and had been READ, not measured. This
entry settles it.

**NOT MEASURED, AND NOT PRESENTED AS MEASURED.** No sweep, no rate and no flake
was run for this. `computenet-07vb`'s 0/120-against-45/120 comparison is evidence
for the stable-frontier repair and is **not** evidence for this one; nothing below
leans on it. The instrument here is deterministic tests, which is what the item
asked for.

**REACHABLE — settled against the real mesh, not read off the code.**
`ReplicaQuorumTest`'s
`computenet-s0tq a replica rejoining the same CellRef is consulted by the covering
quorum, not excused by its stale closed marker` drives three peers over
`Peering.loopback` on a seeded `SimulationController` (seed 17, no faults) through
the three states, reading `Replication.replicaFrontier` at each:

1. **Before departure.** p2 is a covering member with no row for the wave's
   source, so the R13 creation fence reads it as bottom and the wave HOLDS.
2. **After `evict(closeDepartedRow = true)`.** p2 despawns and leaves
   `instancesOf`, so it is not in `covering` at all and the wave settles. PN-0c
   doing its job.
3. **After re-`replicate` onto the same `CellRef`.** The precondition is asserted
   off the mesh rather than assumed: the slot is back in `memberSlots` and the
   grow-only marker is still in `closed`. p2 still has no row for the source, so
   this is state (1) again — and against the unrepaired code the quorum returned
   `true`.

That third read is the false certificate. Against the unrepaired code the test
failed `expected:<false> but was:<true>`, together with three synthetic pins in
the same file (a live member whose row is at 3 for a wave at 5; a rowless
rejoined member under the R13 fence; a departed member `instancesOf` has not yet
dropped). The mechanism is the one `## KE3-23-CLOSEDPREMISE` established for the
sibling read and is a property of the marker, not of the reader:
`WatermarkCell.slotId` is ref-derived and replay-stable (M10.1), `closed` is
grow-only and nothing retracts it, so a rejoin lands on a slot already closed —
and `covering` is derived from `membersOf`, i.e. LIVE instances. The per-member
check `slot in closed || (rows[slot]?.get(source) ?: MIN) >= counter` then passed
on its left arm with the row never consulted. Note which switch this defeats: the
R13 creation fence exists precisely so a rowless covering member holds the wave,
and a rejoined member is rowless, so the vacuous arm defeated the fence in the
one case the fence was built for.

**THE REPAIR IS `computenet-07vb`'s SHAPE, AND HERE IT DEGENERATES TO DELETION.**
That shape is "honour `closed` only where no live instance contradicts it" —
subtract `closed - memberSlots` rather than `closed`. Applied here it is
unreachable rather than merely rare: `covering` is a filter over `members`, so
every slot the arm is ever evaluated against is a member slot, and
`(closed - memberSlots)` is disjoint from the covering set BY CONSTRUCTION. So
the arm is removed from both places it appeared (the `creationFence` filter and
the settlement `all`) rather than written as a branch that provably never fires.
The shape does real work in `CausalStability.stableFrontier` only because that
read unions the announced `members` set, which holds slots `instancesOf` does
not; this read has no such union. **This is the adoption answer the acceptance
clause asks for: the same shape, with its degenerate form stated rather than a
different one chosen.**

Nothing else changes. No lattice change, no delta field, no wire change,
`slotId` stays replay-stable, and `closed` stays grow-only and terminal — the
repair is entirely at this read, exactly as `computenet-07vb`'s was at its own.

**DIRECTION AND COST.** Strictly CONSERVATIVE for certification: a member that
previously passed on the marker alone must now show a row at or past the counter,
so `covering.all` can only become harder and no `(source, counter)` this read
previously refused becomes certified. The false-certificate family this whole
line has been chasing cannot be introduced by it. The price is the mirror image
of the stability freeze `## KE3-23-CLOSEDPREMISE` accepted: while this node's
`instancesOf` view still lags a genuinely departed replica, its `closed` marker
no longer excuses it and the wave HOLDS until the view converges. A hold under
WAIT semantics, never a premature release, and self-healing — a despawned replica
leaves `instancesOf` and stays out of `covering`. Both halves are pinned:
`computenet-s0tq a departed member this node has not yet dropped from instancesOf
holds the wave` for the cost, `a cleanly-departed member no longer constrains`
for PN-0c's unchanged job.

**WHAT `closed` NOW MEANS TO OTHER READERS** (the acceptance clause carried over
from `computenet-07vb`'s clause 5).

- `ReplicaQuorum.frontier`'s settlement check and its `creationFence` filter —
  changed, as above; the predicate can only get harder.
- `ReplicaQuorum.frontier`'s FU-2 `membershipBarrier` — **UNCHANGED, by
  derivation and not by omission.** Its `accounted` set is
  `known + closed + suspended`; the same shape gives
  `known + (closed - known) + suspended`, which is the same set. An announced
  slot that is closed and not a live instance still counts as accounted and still
  does not hold a keyed wave. Pinned by `computenet-s0tq the FU-2 barrier still
  accounts for an announced-but-closed non-member slot`.
- `Replication.replicaFrontier` — a one-line facade over the above; its call
  sites are unchanged and its four switches keep their meanings.
- `CausalStability.stableFrontier`, its `openSlots` diagnostic,
  `Replication.onStabilityStall` and `StableFrontierChurnSweepTest`'s rig — the
  four readers `computenet-07vb` already reconciled. Untouched here; this repair
  neither depends on nor alters them.
- `Replication.onStabilityStall`'s freeze-notice flap (`computenet-92ek`) — not
  reached by this change, and since FIXED on `main` by its own item. The defect
  WAS in `StabilityFreezeDetector`'s retraction arm testing the raw `closed` set
  on the STABILITY path; `computenet-92ek` merged as `2edab2990` and deleted that
  `slot in closed` disjunct, so the arm now retracts on `slot !in open` alone and
  never reads `closed`. The quorum has no latch and no notice, so neither repair
  touches the other's read. The two items were dispatched concurrently and are
  independent, as both beads state; nothing found here contradicts that, and this
  branch carries `2edab2990` by merge.
- `WatermarkCell` / `WatermarkDelta` / the journal and the wire — untouched.
  `closed` is still grow-only, still gossiped as a `Set<UUID>`, still terminal.
  No `:oracle` mirror is needed because no lane was added.
- `WatermarkCell.republish`'s `if (slotId in closed) return` — a replica's own
  local heartbeat suppression, not a membership read; unchanged, and a rejoin
  constructs a new cell with an empty `closed` anyway.

**TESTS.** `ReplicaQuorumTest` gains the mesh reachability/regression test above
and four synthetic pins, and its pre-existing `a cleanly-departed member no
longer constrains` was AMENDED in place the way `computenet-07vb` amended
`CausalStabilityTest`'s two: it named the departed member in BOTH `closed` and
`membersOf`, which is the REJOIN state rather than the departure state, so it now
models a clean departure the way the mesh actually presents one (despawned, gone
from `membersOf`, marker still in the lattice and asserted). `computenet-dwkp`'s
four prohibitions are untouched: no BS-12 fence-attribution assertion is relaxed,
no class is absorbed into `MAX_STABLE_DIVERGING`, no SEEDS list is narrowed, and
no incarnation-unique `tagSource` is pursued.

## KE3-20 — BS-13's resurrection witness is dead, and its divergence witness is thin

Recorded by: `computenet-qbap` (bug, parent `computenet-9sm`). Base commit:
`5ff9507f4` (`main`). Host: darwin/arm64, 16 cores, load1 3-16.
Measured 2026-09-08. All figures below are whole-class runs of
`GcSafetySweepTest` (`./gradlew :kernel:test --tests
'civictech.cell.replication.GcSafetySweepTest' --rerun -i`), seeds `1..200`,
budget `40_000`.

### What was expected

`GcSafetySweepTest`'s BS-13 arm — `[KE3-20]`, the WRONG-seam control — carried
two claims about the shipped tree. First, in the KDoc of its union assertion:
"On THIS tree LOCAL still RESURRECTS (measured 12 of 200 at head; the pre-v2ka
band was 8-15), so the `resurrecting` half alone is what currently fires and the
union costs nothing today." Second, in `BS13_PIN_RETIRED`'s KDoc, that the
sweep-level `fenceAttributed.isNotEmpty()` discriminator that REPLACED the arm's
retired per-seed pin was non-empty on 10 of 10 sweeps with a minimum of 3.

### What was found

**The resurrection claim is false, and has been since `computenet-pay7`.**
LOCAL `resurrecting` was EMPTY on 31 of 31 consecutive 200-seed sweeps at head
`5ff9507f4` — 7 with the adversary unchanged, 12 at the narrowed compaction
period adopted here, and 12 across the widenings rejected below. The sentence
was written by `computenet-v2ka` (`f73c8a311`) BEFORE `computenet-pay7`'s
re-admission fence (`36b889cff`) landed. With `ReclaimedDots` in place a
re-delivered discarded tag is fenced and repaired rather than re-admitted, so the
LOCAL seam no longer resurrects — it only diverges. The relationship is causal
and stated here because the bead asked for it explicitly: pay7's fence is what
removed the witness, and the arm has been resting on its divergence half alone
ever since. The `resurrecting` half of the union is currently dead weight; it is
kept because the union is what makes the assertion survive a fence landing, which
is precisely the case it was widened for.

**The divergence witness is thinner than `BS13_PIN_RETIRED` records.** With the
adversary exactly as `computenet-nwnl` left it and the compaction period at its
original 25, fence-attributed LOCAL seeds over seven runs were 6, 4, 4, 4, **2**,
5, 3 — non-empty on 7 of 7 here, but against `computenet-nwnl`'s recorded band of
3-5 over ten runs this is a lower floor, and the bead was filed on a
full-`:kernel:test` run that produced **0** at the branch head it was found on.

### Disposition

Widened the adversary, per `[KE3-20]`'s own failure message ("widen the
adversary, never weaken the check"). No assertion was relaxed, no seed was
re-derived, `SEEDS`, `BUDGET`, `BS12_SEED` and `MAX_STABLE_DIVERGING` are
unchanged.

The one widening adopted is the compaction period `GcSafetySweep.K`, 25 -> 10:
more compaction points per seed means more chances for the wrong seam to reclaim
below a frontier that certifies nothing while a straggler is behind, and it does
not touch the message-level rig floor the CONTROL arm measures.

    fence-attributed LOCAL seeds, seeds 1..200, budget 40000, head 5ff9507f4
    K = 25   7 runs   6, 4, 4, 4, 2, 5, 3                        min 2
    K = 10  12 runs   7, 4, 5, 4, 3, 5, 4, 3, 4, 6, 5, 3         min 3

Measured again in the context the bead's zero was actually observed in — a whole
`./gradlew :kernel:test --rerun` run, where this class competes with the rest of
the module for the host — three runs at K = 10 gave fence-attributed 4, 3, 3,
all green, at the same head. Fifteen runs at K = 10 in total, non-empty on 15 of
15, minimum 3.

**The honest reading of that pair is that the floor moved by one seed.** Means
are 4.0 and 4.4 and the ranges overlap heavily; twelve runs is not enough to
call the difference anything stronger than a raised minimum. This is an
improvement, not a restoration to the margin `computenet-nwnl` recorded, and the
arm remains a low-rate witness. If it reddens again the answer is a further
widening or a re-examination of whether a three-peer mesh can still produce this
harm at all — not a lowered assertion.

Four alternative widenings were built and measured and REJECTED. Each is
recorded because each is a plausible next idea that makes things worse:

- **Four more disjoint park windows** so all twelve removes are issued under a
  parked link instead of six: 1, 1, 3, 5 — worse. A three-peer mesh relays
  around a single parked link.
- **`ReorderFault` on all three links**: fence-attributed 2, 2, 5, 5 while STABLE
  membership divergence went to 17, 18, 18 of 200 and **reddened BS-12** against
  `MAX_STABLE_DIVERGING` = 12. Raises the rig floor without sharpening the
  discriminator.
- **`DuplicateFault` on all three links**: 0, 0, 1, 0 — it repairs the mesh, since
  a duplicated frame is a second delivery attempt.
- **Removing every ordinal rather than every odd one** (24 removes, stacked on
  K = 10): 6, 4, 3, 2 — no better, and it would falsify the ordinal-parity prose
  several KDocs still quote.

`K = 5` was also measured (3, 5, 6, 2) and rejected: no better than 10, and
STABLE divergence rose to 9 of 200. The returns diminish because once K is well
under the gossip latency the first compaction point past a remove has already
discarded the del-dot; per-seed chances are bounded by the remove count.
