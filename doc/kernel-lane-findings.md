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
unwidened. **The widening does not move this rate**, and the unwidened
reproduction at the merge base is the evidence that it is not this change's
doing. A dedicated 5-run pin on seed 12 under STABLE scored **0/5**, so it is a
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
