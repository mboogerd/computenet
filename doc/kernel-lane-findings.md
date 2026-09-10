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
LOCAL seam no longer resurrects — it only diverges. `computenet-pay7`'s fence is
recorded here as the SUSPECTED cause, because the bead asked for the relationship
explicitly and because two pieces of evidence point at it: the chronology (v2ka's
`12 of 200` was measured at a head that predates `36b889cff`, which
`git merge-base --is-ancestor f73c8a311 36b889cff` confirms) and the mechanism
above (`ReclaimedDots` fences exactly the re-delivery the resurrection witness
needed). What was NOT done, and what would settle it: no sweep was taken with the
fence reverted, so the attribution is inference from those two facts, not a
bisect. The bead's own run 4 excludes a different candidate — `computenet-9sm.6`'s
production trigger, neutralised at its arming point, left LOCAL resurrecting at
0 of 200 — but that rules a cause out rather than ruling pay7 in. On this reading
the arm has been resting on its divergence half alone since pay7 landed. The `resurrecting` half of the union is currently dead weight; it is
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
arm remains a low-rate witness.

That last sentence is the conservative reading and the numbers do not compel it,
which the feature review (`computenet-qbap`, same head, same host, load1 3.5-5.6,
2026-09-08) recorded rather than rewrote. `computenet-nwnl`'s accepted band, read
verbatim off `BS13_PIN_RETIRED`, is 5, 3, 3, 5, 4, 3, 3, 5, 3, 4 over ten runs —
minimum 3, mean 3.8. K = 10's twelve runs are minimum 3, mean 4.4. Measured
against the standard the nwnl fix was itself accepted on, the floor is EQUAL and
the mean is higher; what has not been restored is a margin nobody ever measured.
Three further independent whole-class runs taken during that review gave
fence-attributed 6, 4, 3 (resurrecting 0, 0, 0; STABLE membership divergence 6 of
200 against `MAX_STABLE_DIVERGING` = 12), which is 18 non-empty runs of 18 at
K = 10 across two agents. If it reddens again the answer is a further
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

## KE3-23-DWKPRATE — the BS-12 `stableFenceAttributed` class is GONE at current `main`: 0 of 50 sweeps (30 at `K = 10` + 20 at `K = 25`), against a same-host, same-session pre-fix control of 6 of 15 at `K = 25`

Recorded by: `computenet-dwkp` (bug, epic `computenet-9sm`). Base commit:
`5a5b455d3` (`origin/main`, "computenet-9sm.7: StateRequest(since) below the
compaction floor answers full state (#755)"). Host: darwin/arm64, 16 cores,
2026-09-08. Instrument: `scripts/flake-loop/run-method-loop.sh`, a FRESH JVM per
iteration, each iteration one whole `_BS12` arm over `SEEDS = 1..200` at
`BUDGET = 40_000` — i.e. one iteration is one 200-seed sweep.

This entry discharges `computenet-dwkp` acceptance clause 3 by its FIRST branch
("green on at least 10 consecutive 200-seed sweeps on one host"). Nothing was
relaxed to reach it: `SEEDS`, `BUDGET`, `PIN_RUNS`, `BS12_SEED`,
`MAX_STABLE_DIVERGING` and the `stableFenceAttributed` assertion are untouched
by this item, and the only source edit it makes is this file plus a dated KDoc
record. Clause 4's three prohibitions and clause 5's ruled-out dispositions are
honoured by construction — there is no code change to weaken anything with.

### The measurement

| # | arm / tree | K | runs | red | load1 (start → end) |
|---|---|---|---|---|---|
| A | `_BS12` at `5a5b455d3`, unmodified | 10 | 30 | **0** | 12.22 → 16.76 |
| B | `_BS12` at `5a5b455d3`, `K` restored to 25 | 25 | 20 | **0** | 10.80 → 7.64 |
| C | `_BS12`, `kernel` + `testkit` sources checked out at `311ad4f7b~1` | 25 | 15 | **6** | 5.94 → 6.37 |

Sweep wall time 4.4–4.7 s at K = 10 and 3.5–3.7 s at K = 25, JVM startup
excluded from neither.

**Run B exists because the adversary changed under the clause.** `computenet-qbap`
(`489851bbc`) moved `GcSafetySweep.K` from 25 to 10 in this very test earlier the
same session, so run A is measured against a DIFFERENT adversary from every rate
previously recorded for this class — including `computenet-r13k`'s 45/120 = 37.5 %
and `computenet-07vb`'s 160 consecutive greens, all taken at K = 25. Run B
re-runs the clause against the historical adversary so the comparison is like for
like. A rate quoted without its K is not comparable, which is the same defect
`computenet-qbap` existed to fix.

**Run C is the positive control, and it is what makes runs A and B mean anything.**
A green sweep is only evidence of a fix if the detector can still fire. C checks
out `kernel/` and `testkit/` at `311ad4f7b~1` — the last `main` commit before the
`computenet-fzd3` / `07vb` / `zgyt` / `92ek` / `s0tq` family — into the same
worktree, same host, same instrument, same hour, and the class returns at 6 of 15
(40 %) with the identical signature `FENCE-ATTRIBUTED diverging seeds=[12]`. That
is consistent with the 37.5 % this bead's history records and inconsistent with a
dead assertion. Under a 40 % per-sweep rate, 0 of 50 has probability ~1e-11.

**Read that exponent with its adversary attached.** The 40 % is a K = 25
measurement, so the strictly like-for-like arithmetic is run B alone —
`0.6^20 ≈ 3.6e-5`. Pooling runs A and B into "0 of 50" carries the K = 25 rate
over to a K = 10 arm that has no control of its own; that is defensible only
because K = 10 is the STRONGER adversary (`## KE3-20`, whose K sweep is where
that constant moved: a shorter compaction period discards the del-dot sooner), never because the two arms are
interchangeable.

**Independently reproduced at feature review** (2026-09-08, same host, `HEAD`
`737cd125d`): arm A re-run at 12 sweeps at `K = 10`, 0 red, load1 6.34 → 6.62;
the run C control re-created by the same `kernel/` + `testkit/` checkout at
`311ad4f7b~1`, at `K = 25`,
returned 4 of 10 red (40 %), every red carrying `seeds=[12]`, load1
7.74 → 10.61. The control fires and the fixed tree does not.

### Secondary finding, WITHDRAWN at feature review: `computenet-07vb` IS load-bearing

**A first version of this section claimed the fix family is REDUNDANT — that
`{92ek}` alone and `{07vb, s0tq}` alone each close the escape. That claim is
WITHDRAWN: it is refuted by measurement, and the section is kept, corrected,
rather than deleted, because "these fixes are redundant" is exactly the sentence
a later session would quote while removing one.**

The withdrawn rows were three per-member reverts into an otherwise-current tree,
each reported at 12 runs / 0 red. Row A named its mutation
`computenet-07vb (SetCell.kt, −43 lines)` — but `computenet-07vb` (`8efd47388`)
touches `CausalStability.kt` (+45/−4), `StabilityFreezeDetector.kt` (+15/−4) and
`Replication.kt` (+13/−3) and **does not touch `SetCell.kt` at all**;
`SetCell.kt +43/−6` is `computenet-fzd3`'s (`311ad4f7b`) shape. So rows A and B
most likely reverted `fzd3` — a deliberately protocol-inert bounds/record change
— under a `07vb` label, which makes them vacuous mutations rather than evidence.
That is the same failure family as the merge-commit revert recorded below.

**The correcting measurement** (feature review, 2026-09-08, darwin/arm64
16-core, `main` at `5a5b455d3`, `HEAD` `737cd125d`, same instrument
`scripts/flake-loop/run-method-loop.sh`, revert proved landed by a non-empty
`git diff HEAD --stat` before the run and the tree restored clean after):

| mutation | reverted | K | runs | red | load1 (start → end) |
|---|---|---|---|---|---|
| A′ | `computenet-07vb`'s `CausalStability.kt` + `Replication.kt` restored to `8efd47388~1`, with `92ek` and `s0tq` still present | 10 | 12 | **6 (50 %)** | 8.66 → 13.41 |

Every red carried the identical `FENCE-ATTRIBUTED diverging seeds=[12]`
signature. (`StabilityFreezeDetector.kt` was left at `main` because `92ek` edited
it after `07vb`, so A′ reverts `07vb`'s two exclusively-owned production files,
not all three — an UNDER-revert, which can only weaken the effect, not
manufacture it.)

So `{92ek}` alone does NOT close the escape and `07vb`'s frontier fix is
load-bearing. Rows B and C of the withdrawn table are unverified and should not
be relied on either: row B shares row A's suspect mutation, and row C's 0/12 is
a single unreplicated sample whose companion row is now known to be wrong.

**So exactly one member of the family has been measured.** `07vb` is necessary
(A′ above). Whether `92ek` and `s0tq` are individually necessary is
**UNMEASURED** — not "probably yes" and not "probably no"; no valid mutation of
either exists. Do not read the withdrawal of the REDUNDANT claim as evidence
that every member is load-bearing, and do not read a green tree as evidence that
any member is removable. The open question is filed as `computenet-0ade`; answer
it there, by mutation, before touching any member of the
`fzd3`/`07vb`/`zgyt`/`92ek`/`s0tq` family.

Note also the withdrawn table's stated bound: "n = 12 bounds each row at ~0.7 %
under a 40 % rate" is arithmetically wrong — `0.6^12 ≈ 0.22 %`, not 0.7 %.

A first attempt at mutation A used `git revert --no-commit 0be48970f` on a MERGE
commit; git refused for want of `-m`, the working tree stayed pristine, and the
12 green runs that followed were an unmutated re-run wearing a mutation's label.
It was caught only by the `git diff HEAD --stat` landed-check that
`.claude/skills/work/references/mutation-check.md` §3 requires *before* the test
result is read. The check earned its keep here; run it — and note it proves the
mutation LANDED, never that it was the mutation you MEANT, which is what rows A
and B needed and did not have.

### What is NOT claimed

- Nothing here identifies WHICH schedule the fix family closed. The mechanism is
  recorded by `## KE3-23-CLOSEDROW`, `## KE3-23-CLOSEDPREMISE` and
  `## KE3-23-QUORUMCLOSED`; this entry only measures that the observable escape
  is gone.
- Mutation A′ is an UNDER-revert of `computenet-07vb`: it restores
  `CausalStability.kt` and `Replication.kt` but leaves `StabilityFreezeDetector.kt`
  at `main`, because `92ek` edited that file after `07vb`. It therefore shows that
  `07vb` is necessary; it does not measure the size of `07vb`'s contribution, and
  a full revert could only redden further.
- darwin/arm64 only. Linux is unverified here.
- Runs A and B were taken while the loop itself was the dominant load (load1
  7.6–16.8); C at 5.9–8.4. Earlier records on this bead suggest load pushes this
  rate UP, so A and B are the conservative direction and C — the control that had
  to fire — ran at the LOWER load. That asymmetry favours a false RED, not a
  false GREEN, so it does not soften the result.

### Update (`computenet-0ade`): `92ek` and `s0tq` are each measured — neither is individually necessary for the BS-12 rate at `n = 20`

This answers the open question left above ("`92ek` and `s0tq` are
**UNMEASURED**"). Host: this machine, darwin/arm64 16-core, 2026-09-09. Base
`item/computenet-0ade` at `095980199` (`origin/main`,
"computenet-1cuq: the newest comment on a parked bead is not the state
(#768)"). Same instrument, same test method, same `K = 10` BS-12 arm as A′
above. Every reverted file list was compared against `git show --numstat
<sha>` for that member before the run, and every revert's landed-check
(`git diff HEAD --stat`, non-empty) was read before the result was read, per
`.claude/skills/work/references/mutation-check.md` §3.

**First, the overlap A′ left open was resolved rather than repeated.**
`git diff 8efd47388~1..8efd47388 -- .../StabilityFreezeDetector.kt` shows
`07vb`'s entire contribution to that file is KDoc prose — zero executable
lines; its executable changes are wholly in `CausalStability.kt`
(`removeAll(closed - memberSlots)`, both `stableFrontier` and `openSlots`)
and `Replication.kt` (`removeAll(closed - instanceSlots)`). A plain reverse
apply of `07vb`'s `StabilityFreezeDetector.kt` diff against a tree that also
carries `92ek` conflicts (`git apply -R`, exit 1); a 3-way reverse apply
(`git apply -3 -R`) resolves but leaves inline conflict markers inside a doc
comment, confirming the collision is comment-only. So this measurement's
`07vb` row reverts `CausalStability.kt` + `Replication.kt` in full — the same
files as A′ — and leaves `StabilityFreezeDetector.kt` at `HEAD`; unlike A′
this is a full FUNCTIONAL revert of `07vb` (only non-executable KDoc prose
diverges from a byte-for-byte revert), not a partial one.

| mutation | reverted | K | runs | red | load1 (start → end) |
|---|---|---|---|---|---|
| `07vb` alone (`92ek`, `s0tq` present) | `CausalStability.kt` + `Replication.kt` restored to `8efd47388~1` | 10 | 20 | **7 (35 %)** | 7.35 → 6.17 |
| `92ek` alone (`07vb`, `s0tq` present) | `StabilityFreezeDetector.kt`'s `92ek` diff reverse-applied (`git apply -R`; `92ek` is the LAST commit to touch this file, so no overlap) | 10 | 20 | **0** | 10.42 → 7.31 |
| `s0tq` alone (`07vb`, `92ek` present) | `ReplicaQuorum.kt` restored to `5ff9507f4~1` (only commit touching this file since) | 10 | 20 | **0** | 12.13 → 14.57 |

Every `07vb` red carried the identical `FENCE-ATTRIBUTED diverging
seeds=[12]` signature. This reproduces A′'s reading (6/12, 50 %) at a larger
`n` and with the `StabilityFreezeDetector.kt` overlap resolved rather than
left as an under-revert: **`07vb` is individually necessary** — the class
returns at a rate consistent with this bead's own positive control
(run C above, 40 % at `K = 25`; A′, 50 % at `K = 10`) once `07vb` alone is
removed.

**`92ek` and `s0tq` each land at 0 of 20.** Under a control rate anywhere
near 35–50 %, `P(0/20 | rate = 0.4) ≈ 3.7e-5`, so this is a real bound, not
an underpowered null: at `n = 20`, neither member's removal reopens the
BS-12 escape on its own. That is NOT the same claim as "these members do
nothing" — `92ek` fixes a distinct, deterministically-reproduced flap
(`StabilityFreezeDetectorTest`'s `open`/`closed`-overlap case, unrelated to
this sweep's `stableFenceAttributed` assertion) and `s0tq` fixes a distinct,
deterministically-reproduced quorum defect (`## KE3-23-QUORUMCLOSED`'s
three-peer mesh reproduction) — both reachable and verified by their own
targeted tests, neither exercised by the BS-12 rate this table measures.
The honest statement is the bound: at `n = 20`, reverting `92ek` alone, or
`s0tq` alone, does not reopen the BS-12 `stableFenceAttributed` class that
`{07vb}` closes.

No assertion was relaxed, `stableFenceAttributed` is untouched, the class was
not absorbed into `MAX_STABLE_DIVERGING`, and `SEEDS` was not narrowed to
reach any of the three rows above — `computenet-dwkp` clause 4's prohibitions
bind here exactly as they did at `08efd47388`/`5a5b455d3`.

## KE3-42-ORMAP-BS13 — the OR-map has no reliable BS-13 witness: the wrong seam reclaims strictly more, and harms nothing reproducibly

Recorded by: `computenet-9sm.8.7` (task, parent `computenet-9sm.8`). Base
commit: `3bdacc7e7` (`feature/computenet-9sm.8`, branch
`task/computenet-9sm.8.7`). Host: darwin/arm64, 16 cores, load1 7–9.
Measured 2026-09-08. All figures are whole-class runs of the new
`OrMapGcSafetySweepTest` (`./gradlew :kernel:test --tests
'civictech.cell.replication.OrMapGcSafetySweepTest' --rerun`), seeds `1..200`,
budget `40_000`, compaction period `K = 10`.

This is the OR-map twin of `## KE3-20`, filed for the same reason and in the
same shape: a control that the bead required to reproduce OR to be recorded as
unreachable. It did not reproduce. That is a result, not a gap to work around,
and no seed range, budget or workload was searched for a friendlier one.

### What was asked

`computenet-9sm.8.7` clause 3 (feature decision 9sm.8-D10): the LOCAL arm —
`OrMapCell.compactBelow(localDeliveredFrontier)`, the wrong seam — must either
record a resurrecting seed reproducing `PIN_RUNS` of `PIN_RUNS`, or record the
negative result here and fall back on the sweep-level discriminator that is
observable either way.

### What was measured

Three consecutive whole-class runs, all three arms in each:

```
                              run 1        run 2               run 3
  STABLE resurrecting         []           []                  []
  STABLE fence-attributed     []           []                  []
  STABLE membership-diverging [43,89,       [76,148,151,154]    [4,76,154,165]
                               145,146]
  STABLE value-diverging      []           []                  []
  STABLE value-fold-drift     []           []                  []
  STABLE discarded            5571         5557                5521
  CONTROL discarded           0            0                   0
  CONTROL membership-div.     []           [151,173]           [151,173]
  LOCAL resurrecting          []           []                  []
  LOCAL membership-diverging  []           [4,12,32,89,181]    []
  LOCAL value-harmed          []           []                  []
  LOCAL discarded             7395         7383                7408
  wall time STABLE/LOCAL      4.8/2.6 s    5.7/2.6 s           4.7/2.6 s
```

**The resurrection witness is dead on the OR-map, 0 of 200 in all three runs** —
the same disposition `## KE3-20` records for the OR-set, and for the same
mechanism: `9sm.8-D6`'s `(key, dot)` re-admission fence plus its repair emission
means a re-delivered discarded dot is fenced and repaired, never re-admitted.
The OR-map fence landed with the payload, so unlike the OR-set there was never a
pre-fence build here whose witness could be re-derived.

**The divergence witness is present but not reproducible**: non-empty on 1 of 3
runs, empty on the other two, and the seeds it named on that run (`4, 12, 32,
89, 181`) do not intersect the STABLE arm's diverging seeds in any run. The bead
forbids recording a seed below `PIN_RUNS` of `PIN_RUNS`, and a class that is
empty on two runs in three cannot supply one. So no `BS13_SEED` is recorded.

### Why the OR-map LOCAL seam finds nothing at `K = 10` — the counting argument

The wrong seam's harm needs a schedule in which a replica reclaims a `dels`
entry that a *straggler* has not delivered, AND the straggler then re-delivers
the covered put-dot, AND nothing repairs it. On this rig the chances per seed
are bounded by the REMOVE COUNT, not by the compaction period — the same bound
`GcSafetySweep.K`'s KDoc derives for the OR-set, and the reason `K = 5` bought
nothing there. `removeSchedule` issues twelve removes per seed (every
odd-ordinal write of twenty-four), and `minRemovesOnASeed` measured 3, so the
per-seed budget of candidate schedules is single-digit. Against that budget the
fence removes the re-admission half outright and the repair emission removes
most of the divergence half, leaving a residue that this seed range samples at
roughly one run in three rather than reliably.

The OR-map does not add a route the OR-set lacks. Its extra observable —
per-key `value(key)` agreement, both across live replicas and against each
replica's own emitted fold — fired on ZERO seeds on every arm of every run,
including the LOCAL arm. That is expected rather than surprising for this
workload and is recorded as a limit of the rig, not as evidence of safety: each
key is written exactly once by exactly one peer, so the add-wins pick over
concurrent dots is never exercised on a contended key and the value observable
can only catch a wrong live-dot set, not a mis-resolved concurrent write. A
multi-writer-key workload would be a different rig. **It was since built, as the
sweep's fourth arm** (`computenet-rjue`): see `## KE3-42-ORMAP-SHARED`, which
records the measurement and the mutation showing the widened observable
discriminates where this one cannot. The figures in THIS entry are unchanged by
it — the arm is additive and shares no counter with the three recorded here.

### The discriminator that IS observable, and is asserted

**LOCAL's summed `discarded` strictly exceeds STABLE's on the same seeds** —
7395 > 5571, 7383 > 5557, 7408 > 5521, i.e. 3 of 3 with a ~33 % margin.
`localDeliveredFrontier` drops the MIN over the other open members that
`stableFrontier` takes, so a reclaimer driven from it discards at or ahead of
the stable one by construction; the measurement says the gap is large and stable
on this rig. The BS-13 arm asserts that inequality. It is a statement that LOCAL
is the wrong seam — it reclaims what the mesh has not certified — that holds
whether or not the extra discards happen to break anything on this seed range,
which is exactly the property a witness-free control needs.

`OrMapGcSafetySweepTest`'s `ORMAP_BS13_WITNESS` constant carries this
disposition in the source, so the next reader finds the measurement rather than
an absence.

### The STABLE arm is not vacuous, and the mutation was run

Non-vacuity is in-line and asserted, not printed: `discarded` summed over the
run is 5521–5571 against a CONTROL arm whose `discarded` is **0 on every seed**,
so a STABLE arm whose `snapshot()` found no installed stability read would
report 0 and redden.

Additionally, `OrMapCell.compactBelow`'s every-dot rule was mutated to a per-dot
one (`delDots.all { covered }` → discard each covered dot individually) as a
local, reverted edit, and the STABLE arm **saw it**: the run failed on
`FENCE-ATTRIBUTED diverging seeds=[4]` — a live replica lacking a key whose live
dot is in that replica's own `ReclaimedDots` — with `resurrecting` still empty.
So the sweep discriminates the discard rule this feature turns on, and it does
so through the attribution read rather than through resurrection. The
deterministic backstop for the same rule remains
`OrMapCellCompactBelowTest`'s LOST-del pin.

### Disposition

No assertion was weakened and no seed, range or budget was re-derived. The LOCAL
arm ships with the `discarded`-inequality discriminator in place of a per-seed
pin, and `[KE3-20]`'s OR-map half stays OPEN as a bounded-schedule negative:
this rig, at this range, does not reach the wrong seam's harm reliably. Widening
the adversary was NOT attempted here — `## KE3-20`'s own record has four
widenings built, measured and rejected on the OR-set for making the rig's floor
worse rather than the discriminator sharper, and re-running that search on the
OR-map is its own item, not this task's.

## KE3-42-ORMAP-SHARED — the OR-map value observable now resolves a REAL add-wins pick: a multi-writer-key arm, and what it found

Recorded by: `computenet-rjue` (bug, direct child of epic `computenet-9sm`),
the residual the feature review of `computenet-9sm.8` (PR #758) filed against
its own value observable. Base commit: `cbe3ca90b` (`origin/main`), branch
`feature/computenet-rjue`. Host: darwin/arm64, 16 cores, load1 4.8–8.6.
Measured 2026-09-08. All figures are whole-class runs of
`OrMapGcSafetySweepTest` (`./gradlew :kernel:test --tests
'civictech.cell.replication.OrMapGcSafetySweepTest' --rerun`), seeds `1..200`,
budget `40_000`, compaction period `K = 10` — the same range, budget and `K`
the three original arms use, and none of their numbers moved.

### What was asked, and why

`## KE3-42-ORMAP-BS13` records, honestly, that the OR-map sweep's per-key VALUE
observable fired on zero seeds on every arm of every run and that this was a
LIMIT OF THE RIG rather than evidence of safety: `MeshPeer.write` puts
`key = "$name-$ordinal"`, so each key is written exactly once by exactly one
peer, every live key has exactly one live dot, and `value(key)`'s add-wins pick
over CONCURRENT dots is never exercised. What that observable could still catch
was a wrong live-dot set; what it could not catch was a mis-resolved concurrent
write. The residual asked for a workload where at least two peers write the SAME
key concurrently, ADDITIVELY, so the recorded OR-map numbers stay comparable.

### What was built

`OrMapGcSafetySweep.Trigger.SHARED`, a FOURTH arm: its own graph, check id and
artifact root, reclaiming at the same production frontier `Trigger.STABLE` does
(`OrMapCell.snapshot()` through the installed stability read) — it is the
WORKLOAD that differs, not the seam. On top of the ordinal write script its step
hook has EVERY member peer put the SAME key at the SAME step, one fresh key per
round, 47 rounds at stride 100 from step 350 (last round 4950). A step hook runs
inside one controller step and a put is delivered as a scheduled delta, so no
putter has folded another's dot when it mints its own; `OrMapCell`'s put is a
reset-remove that tombstones only what the PUTTER sees live, so the round's dots
survive each other and the key carries one live dot per putter, each with a
distinct value. `MeshPeer.put(key, value)` is the new testkit primitive — like
`remove`, deliberately NOT an `AcceptedOp` and not a `recordWrite`, because
`BatchReference` models an add-only per-`(peer, ordinal)` ledger that a contended
key does not fit.

Nothing in the STABLE, CONTROL or LOCAL arms changed. Their figures below sit
inside the run-to-run spread `## KE3-42-ORMAP-BS13` recorded, which is the check
that the widening was additive.

### What was measured — three consecutive whole-class runs

```
                                  run 1        run 2        run 3
  SHARED contendable seeds        197/200      197/200      197/200
  SHARED contended puts issued    16388        16388        16388
  SHARED max live dots on a key   3            3            3
  SHARED resurrecting             []           []           []
  SHARED fence-attributed         []           []           []
  SHARED membership-diverging     []           [78]         []
  SHARED value-diverging          []           []           []
  SHARED value-fold-drift         []           []           []
  SHARED discarded                3879         3901         3858
  SHARED wall time                11.1 s       11.3 s       10.8 s
  (unchanged arms, for comparison)
  STABLE discarded                5547         5512         5548
  LOCAL  discarded                7387         7388         7382
```

**The property holds on a real pick: both VALUE classes are empty on every seed
of every run, now over keys whose value IS an add-wins resolution over mutually
concurrent dots.** That is the result the residual asked for, and unlike the
previous recording it is not vacuous — see the mutation below.

**Three seeds cannot contend at all, and the arm says so rather than passing
quietly.** On seeds `22`, `37` and `191` the churn plan leaves a SINGLE member
for the whole contention window (52–53 puts issued against a possible 141, i.e.
one putter per round), so no two peers ever put one key and no concurrent dot
exists to resolve. The arm therefore splits the witness in two: it asserts the
strong per-seed invariant — *wherever two peers put one key in one round, two
live dots carrying two distinct values survive to quiescence* — over the 197
CONTENDABLE seeds, and separately holds a floor (`MIN_CONTENDABLE_SEEDS = 190`)
against the workload silently ceasing to contend. This split was measured, not
designed: an earlier form asserting contention on every seed reddened on those
three.

**A contended round issued too late to drain produces a real-looking value
divergence.** An earlier shape ran 56 rounds to step 5850. It reddened on seed
132 with `shared-55={peer0=peer0#5850, peer2=peer2#5850}` — the two live replicas
agreeing on membership, each holding only its OWN final-round dot, with
`value-fold-drift` empty (so each cell agreed with its own emitted history: a
delta had not arrived, nothing was mis-resolved). Moving the last round back to
4950, where the ordinal write script ends, removes it in three runs of three.
The reading recorded here was that this is the rig's DRAIN WINDOW, not a
reclamation defect — stated as a reading, not as proof, since no separate
no-reclaimer control of the contended workload was run. **That reading is
CORRECTED, and the control has now been run: see `### The contended workload's
own divergence floor` below (computenet-pa5l). The conclusion — a rig artifact,
not a reclamation defect — survives; the MECHANISM in the words "drain window"
does not.**

**An earlier shape also cost 46 of 200 seeds their witness, for a mechanism worth
recording**: with a small key space cycled over (3 keys, 24 rounds), each round
tombstones its predecessors on the same key — put IS a reset-remove — so the only
contention surviving to quiescence is the final round's, on one key, hostage to
how many peers happened to be members at that one step. One key per round makes
every round's concurrency permanent.

### The contended workload's own divergence floor, and what the seed-132 shape really was (computenet-pa5l)

Recorded by: `computenet-pa5l` (bug, direct child of epic `computenet-9sm`), the
residual the feature review of `computenet-rjue` (PR #760) filed against the
paragraph above. Base commit: `ffc2f3ebb` (`origin/main`), branch
`feature/computenet-pa5l`. Host: darwin/arm64, 16 cores, load1 7.3–10.3.
Measured 2026-09-08, whole-class runs of `OrMapGcSafetySweepTest`, seeds
`1..200`, budget `40_000`, `K = 10`.

**Why the reading above was in tension with the harness.** `DstRun.execute()`
sets `quiesced` only when `world.controller.step()` returns `false` — the
controller has nothing left to DISPATCH — and the check runs only on a quiesced
run. So on a quiesced run a delta cannot be merely "in flight", and "the rig's
drain window" cannot be the mechanism. Two candidates remained, and they are
different findings: an adversary-withheld frame (the class the arms already
tolerate on MEMBERSHIP at `MAX_STABLE_DIVERGING = 12`), or a genuinely dropped
live dot.

**What was built to tell them apart** (both in
`kernel/src/test/kotlin/civictech/cell/replication/OrMapGcSafetySweepTest.kt`):

- `Trigger.SHARED_NONE`, the **contended no-reclaimer control** — the SHARED
  arm's workload, the same contended-put hook on the same key schedule, with the
  reclaimer off (`discarded == 0`, asserted). `Trigger.NONE` could not do this
  job: it controls the ORDINAL workload, whose every key holds one live dot, so
  its VALUE classes are unreachable by construction and it bounds nothing about
  the SHARED arm's zero-tolerance value assertion.
- two reads on every divergence: the per-replica **live dot sets** on the
  disagreeing key, and the per-seed **stranded frame** count of the
  `ormap-gc-reorder` fault. `ReorderFault.strandedFrames` is `held − released`:
  frames the reorder buffer swallowed and never let go of because traffic on its
  edge stopped inside the window. A stranded frame is a PERMANENT withholding
  and it is invisible to the quiescence test — a frame in an interposer's buffer
  is not dispatchable — which is exactly how a quiesced run can look like a delta
  that "had not arrived".

**The floor, at the shipped 47-round shape** (three whole-class runs; the
control is `@Order(5)`, `ORMAP-SHARED-CONTROL`):

```
                                   run 1     run 2     run 3
  CONTENDED-CONTROL discarded      0         0         0
  CONTENDED-CONTROL contendable    197/200   197/200   197/200
  CONTENDED-CONTROL membership-div []        [24]      []
  CONTENDED-CONTROL value-div      []        []        []
  CONTENDED-CONTROL value-drift    []        []        []
  CONTENDED-CONTROL wall           5.7 s     6.2 s     5.7 s
  reorder-stranded seeds           107/200   109/200   109/200   (max 2 frames)
  (the SHARED arm, same runs)
  SHARED membership-diverging      [89]      []        []
  SHARED value-div / value-drift   [] / []   [] / []   [] / []
  SHARED discarded                 3903      3922      3911
```

So **the contended workload's own divergence floor on both VALUE classes is
0 of 200**, measured with the reclaimer off, in each of three runs — and its
membership floor is 0–1 of 200, well inside the `MAX_SHARED_DIVERGING = 12`
ceiling the arm already carries. Note the reorder buffer strands at least one
frame on more than half the seeds even at the shipped shape: permanent
withholding is ordinary in this rig and by itself does not produce a value
divergence.

**The 56-round shape does NOT reproduce at this base.** With `CONTEND_ROUNDS`
put back to 56 (last round at step 5850, keys `shared-0..shared-55` — the exact
shape the paragraph above describes), three whole-class runs gave
`value-diverging=[]` and `value-fold-drift=[]` on both the SHARED arm and the
contended control, on every one of 200 seeds: 0 of 600 seed-runs, seed 132
included. The sweep is not run-to-run deterministic (see the membership columns
above), so this bounds the per-seed-run rate of that class at roughly 0.5% at
95% confidence rather than excluding it.

**But the withheld-frame mechanism was reproduced directly, and it is
sufficient.** A diagnostic run widened `ormap-gc-reorder`'s window from 3 to 64
on `peer0<->peer2` — the same edge, the same fault, more stranding — at 56
rounds. The **contended control**, with `discarded=0`, then reddened on seed 61
with exactly the recorded signature:

```
[ORMAP-SHARED-CONTROL VALUE] seed=61 live replicas agree on membership but not
on value: shared-55={peer0=peer0#5850, peer2=peer2#5850}
liveDots={peer0=[6ad7101a#48], peer2=[2aa3a97d#62]}; discarded=0
... reorder stranded frames on VALUE-diverging seeds={61=48}
```

Same key `shared-55`, same step 5850, memberships agreeing, `value-fold-drift`
empty, each replica holding ONLY its own final-round dot — produced with the
reclaimer switched off entirely, on a run where 48 frames were permanently
stranded in the adversary's reorder buffer. (The SHARED arm of the same
diagnostic reddened on seed 12, a partial form of the same shape.)

**Disposition of the two hypotheses.** The seed-132 occurrence is an
ADVERSARY-WITHHELD FRAME, not a dropped live dot: the mechanism is demonstrably
sufficient without any reclamation, it is the same class the arms already
tolerate on membership, and the contended control shows reclamation contributes
nothing to it at the shipped shape. What is corrected is the mechanism's name —
the frame is not draining, it is **permanently stranded** in a reorder buffer
that stopped seeing traffic on its edge, which is why moving the last contended
round back to 4950 (where the ordinal write script still generates traffic that
flushes the buffer) removes it. "Drain window" implied a delta that would have
arrived given more steps; no number of steps would have delivered it.

**The zero-tolerance VALUE assertion is KEPT, with this control as its stated
justification**, rather than replaced by a measured ceiling: the rig's own floor
under it is 0 of 200 on three runs, so a ceiling would be a tolerance for
nothing measured. The assertion now names the control in its own failure message
and in the comment above it, so a future red run is routed to the control arm
and to the printed stranded-frame counts before it is read as a reclamation
defect. The membership class keeps its measured ceiling
(`MAX_SHARED_DIVERGING = 12`) unchanged — the control's 0–1 of 200 does not
justify tightening a ceiling that exists for a non-reproducible rig behaviour.

What is NOT claimed: that no schedule can produce a value divergence here. The
diagnostic above shows one can, under a widened adversary; the claim is that at
this arm's adversary and range the floor is measured at zero and the one
recorded occurrence is attributed.

### The arm is not vacuous — the mutation evidence

The add-wins resolution has exactly one implementation, `TaggedMapDelta.value`,
which `OrMapCell.value` delegates to; both are `kernel/main` and outside this
item's file claim. Two things follow, the first of which matters more than the
scope limit:

**The obvious production mutation would not discriminate anyway.** Replacing
`ordered.last()` with `ordered.first()` in `TaggedMapDelta.value` is
deterministic in `DOT_ORDER`, so every replica mis-resolves IDENTICALLY and a
cross-replica comparison stays green. The mis-resolution class this observable
can catch is a FOLD-ORDER-dependent pick, which differs per replica.

So the mutation was applied in-claim, at the check's read site, and is exactly
that class: `cell.value(key)` → `cell.values(key).firstOrNull()`, i.e. the
first-folded live value instead of the `DOT_ORDER` maximum. Result (a local,
reverted edit, `--rerun --no-build-cache`):

```
[KE3-23] OR-map reclamation must be invisible to `value(key)` even where the
key's value is an add-wins pick over CONCURRENT dots:
crossReplica=[1, 2, 5, 6, 7, 8, 9, 10, ... 197, 199, 200]   (153 of 200 seeds)
vsOwnFold=[]
```

**and the three original arms PASSED under the same mutated read.** That is the
whole finding in one line: a fold-order-dependent resolution is invisible to the
ordinal workload and visible on 153 of 200 seeds to this one. The property left
unproven by the substitute is that a defect introduced INSIDE
`TaggedMapDelta.value` propagates here; the delegation is single-sourced
(`[KE1-08]`, `j2x.1-D4`) so the two sites read the same live dots, but this
mutation does not itself exercise the kernel edit.

### Disposition

The residual is closed as a POSITIVE result: the value observable now resolves a
real add-wins pick, is demonstrably discriminating, and finds no harm at this
range. `## KE3-42-ORMAP-BS13`'s figures and its BS-13 disposition are untouched —
the fourth arm neither shares their seeds' schedules nor their counters. What is
NOT claimed: that the OR-map's add-wins pick is safe in general. This is a
bounded-schedule check over 200 seeds with three peers, the same honesty clause
`## KE3-42-ORMAP-BS13` and `GcSafetySweep` carry, filed under the same DISPUTES
entry.

## KE3-42-ORMAP — feature close-out: what the OR-map seam + reclaimer delivered, the three corrected premises, and the u7fi trigger check restated in code

Recorded by: `computenet-9sm.8.8` (task, parent `computenet-9sm.8`, close-out
task). Base commit: `0254a53e7` (merge of `computenet-9sm.8.7`), branch
`task/computenet-9sm.8.8`. Host: darwin/arm64. Recorded 2026-09-08.

This is the feature-level companion to `## KE3-42-ORMAP-BS13` (recorded by
sibling task `computenet-9sm.8.7`) — that entry is the BS-13 witness result;
this one is the close-out record for the whole feature. No content is
repeated from it beyond citation.

### What the feature delivered

`computenet-9sm.8` ported the OR-set's stability-scoped reclamation and
delivered-frontier machinery to the dot-shaped `OrMapCell` across its eight
tasks (`.1`-`.8`): the del-dot (`[24-TAG-04]`, decision 9sm.8-D5), both lanes
of the delivered frontier (decision 9sm.8-D1), stability-scoped `compactBelow`
reclamation with a per-key re-admission fence (`[KE3-30]`/`[KE3-31]`,
decisions 9sm.8-D6/D7), checkpoint-driven and crash-recovery test coverage,
an oracle-side `DotModel` correspondence check, and a churn/DST reconvergence
harness extension.

### Three corrected premises

- **The del-dot.** A `[MapOps.remove]` mints its OWN dot from the cell's dot
  counter (`OrMapCell.kt:465`, `Timestamp(dotSource, ++dotCounter)`) and a
  `[MapOps.put]` over a key with live dots mints a retract del-dot FIRST and
  its put-dot SECOND (`:431-432`) — a re-put therefore consumes two counters
  for one delta. Without the del-dot a `dels` entry carried only the put-dots
  it covered, so a stable frontier certified the PUT's delivery and said
  nothing about the REMOVE — the reclamation hazard `computenet-v2ka` measured
  on the element-shaped sibling (`SetCell`).
- **The derived floor.** The compaction floor is DERIVED from the persisted
  `reclaimed` fence (`ReclaimedDots`), never a snapshot key of its own — so
  restart cannot desynchronize the floor from the fence that gates
  re-admission.
- **No `readBounded`.** Reclamation reads the causal-stability frontier
  through the existing `StabilityReclaim`/delivered-frontier seam
  (`Replication.trackDeliveries`, per `## KE3-CKPT-TRIGGER`); no new bounded
  read primitive was added or is needed.

### The u7fi decision and trigger-check result

`computenet-u7fi`'s 2026-09-06 15:07 KE3 decision (superseding feature design
9sm.8-D3): accept the re-baseline fence residual PROVISIONALLY, do not build
the fenced-source lattice under KE3, do not file it as a new bead — it is
already filed twice, in `concord/corpus/DISPUTES.md` §42-WM-R14 and
`doc/spec/40-distribution/42-replication.md` §Open interactions (decision
9sm.8-D11; this task files nothing new, per D11's instruction). u7fi's
acceptance was amended the same day to require the revisit trigger be
restated in `OrMapCell.applyReBaseline`'s KDoc (grep anchor `fenced-source`)
before the bead closes.

That restatement is now in place: a "**Revisit trigger (computenet-u7fi, KE3
decision 2026-09-06...)**" paragraph naming the two edits that reopen u7fi
(`ReBaselineEmitting` entering `OrMapCell`'s supertype list, or a
superseded/rotated `dotSource`) and the lattice's filing location, committed
at `3a005eab8`.

The trigger grep over the WHOLE feature diff (`git diff --name-only
origin/main...HEAD`, 19 files, captured into a bash array rather than
interpolated bare — the zsh unquoted-multi-path trap this repo has hit
before) returns real hits, all classified as pre-existing `dotSource` reads,
KDoc prose (including this file's own `## KE3-42-ORMAP-BS13` entry describing
the check), and test fixtures exercising the existing `reBaseline` test seam.
None adds `ReBaselineEmitting` to any production supertype list, reassigns
`dotSource`, or attaches a `ReBaselineNotice` to the repair/del-dot emission
paths. Full command, hit list and classification posted to `computenet-u7fi`
(comment of 2026-09-08); the three code facts it states — no
`ReBaselineEmitting` supertype, `dotSource` an unassigned `val`, repair/del-dot
deltas carrying no notice — are restated there from the code, not assumed.

### Sweep numbers

Cited, not restated: `## KE3-42-ORMAP-BS13` (this file) for the OR-map BS-13
sweep result (resurrection witness dead, 0/200; divergence witness
non-reproducible; LOCAL-vs-STABLE `discarded` discriminator holds 3/3 with a
~33% margin). `## KE3-CKPT-TRIGGER` for the checkpoint-driven reclamation
trigger measurement.

### What this discharges

Feature clauses 6 and 7 of `computenet-9sm.8.8`'s acceptance: the KDoc
restatement, the classified trigger-check comment on `computenet-u7fi`, and
this findings entry. `computenet-u7fi` itself is closed by the orchestrator,
not by this task (cross-bead close is a reserved action) — see the comment
posted there for the commit sha the closing note should cite.

## KE3 — epic close-out: corrected premises, dispositions, and what the pins showed

Recorded by: `computenet-9sm.9.5` (task, feature `computenet-9sm.9`, epic
`computenet-9sm`). Base commit: `301c91ad2` (merge of main into
`feature/computenet-9sm.9`). This entry is append-only and touches no other
entry; it records what the epic's own tasks and their sibling comments already
established, citing rather than re-deriving.

### Corrected premises

Six premises the epic's breakdowns or feature reviews found false or weaker
than filed, corrected in the tasks that found them rather than inherited
silently:

1. **`MapCell` is untagged.** `OrMapCell` is the tagged, dot-shaped map type;
   plain `MapCell` carries no `TagState`. (computenet-9sm.8's breakdown.)
2. **`SetCell` is not on `TagState`.** The tag-shaped lattice lives on the
   element-shaped `SetCell`/`OrMapCell` pair via their own dot machinery, not
   through a shared `TagState` supertype. (computenet-9sm.8's breakdown.)
3. **`StallReason` had three values before `computenet-9sm.5`** —
   `SUSPENDED`, `RESTARTING`, `DEAD_LETTERED` (`Suspension.kt`) — and
   `computenet-9sm.5` added the fourth, `STABILITY_FROZEN`. Confirmed again at
   this epic's own base by computenet-9sm.9's breakdown ("`StallReason` has
   four values at head, three pre-9sm.5").
4. **There is no scheduler timer.** The `computenet-9sm.2` heartbeat rides
   `SimWorld`/host cadence rather than a dedicated scheduler primitive — see
   `## KE3-HB` below, which is itself the measurement of what that heartbeat
   does and does not do.
5. **The findings path.** The epic's own citation of `doc/kernel/findings.md`
   was wrong; that file does not exist. This file, `doc/kernel-lane-findings.md`,
   is the actual kernel-lane findings log — corrected by `computenet-9sm.10`.
6. **[KE3-10]'s "existing `DeliveredWatermarkTest` assertion" does not exist.**
   [KE3-10] claims a test re-run pins "every replicated logical id has exactly
   one companion". `DeliveredWatermarkTest`'s three tests assert watermark
   VALUES only, via `watermarkOf(logicalId)!!.watermark(src)`; none assert
   "exactly one" or "a `WatermarkCell` is not itself tracked". Verified at this
   task's own base: `git grep -n 'replicate(.*WatermarkCell|not itself tracked|
   itself be tracked' HEAD -- 'kernel/src/*'` returns exactly one hit,
   `Replication.kt:576`, which is KDoc, not a test. What actually holds
   [KE3-10] in code is `Replication.watermarks` (a `Map<logicalId,
   WatermarkCell>`, `:118`) read by `watermarkOf` (`:126`) and the derived
   `watermarkRef` (`:135`). Established by computenet-9sm.9.3's evidence
   comment on computenet-9sm.9 and independently confirmed by its reviewer.
   computenet-9sm.9.4 judged [KE3-10] does not map onto any of BS-1..BS-20 (it
   names a requirement clause, not a BS-n behaviour, and `DeliveredWatermarkTest`
   is pre-KE3 CP-B2/E3.3 scope, not a BS-n test) — so it earns no DISPUTES row,
   and this entry is where the corrected premise is recorded.

### R14 disposition

`concord/corpus/DISPUTES.md` `` `42-WM-R14` `` — a `ReBaseline`-superseded
source's watermark column cannot be excluded from the stability MIN — is filed
as `kernel-gap`, unbounded-but-correct: the frozen column under-reports
stability rather than over-reporting it. Spec
`doc/spec/40-distribution/42-replication.md` §Open interactions carries the
same disposition. `## KE3-D4` below is the same shape recurring under sharding
rather than re-baseline.

### The BS-13 seed history

- `## KE3-GC` (original witness, `computenet-9sm.4.5`): `BS13_SEED = 62`
  reproduced [KE3-20] on the LOCAL (wrong-seam) trigger, chosen as the smallest
  of a stable multi-run intersection; not to be replaced with a friendlier seed.
- `## KE3-20` / `## KE3-GC-WITNESS` (computenet-qbap / computenet-nwnl): the
  per-seed pin died — the re-admission fence (`computenet-pay7`) fenced the
  exact re-delivery the witness needed, so LOCAL stopped resurrecting (0 of 31
  consecutive 200-seed sweeps at `computenet-qbap`'s head) and only diverges.
  Retired onto the sweep-level `fenceAttributed.isNotEmpty()` discriminator;
  the adversary was widened (disjoint parks on all three links, `K` moved
  25→10) per [KE3-20]'s own "widen the adversary, never weaken the check".
  **`## KE3-GC-WITNESS` supersedes `## KE3-GC`'s per-seed pin; `## KE3-20` is
  the current widening and rate.**
- `## KE3-42-ORMAP-BS13` (computenet-9sm.8.7): the OR-map has no reliable BS-13
  witness either — resurrection dead at 0/200 across three runs (same fence
  mechanism), divergence witness non-reproducible (1 of 3 runs). No
  `BS13_SEED` recorded for the OR-map; the asserted discriminator is LOCAL's
  summed `discarded` strictly exceeding STABLE's, 3 of 3 with a ~33% margin.

### The sharded-interest stability limitation

`## KE3-D4` (decision 9sm.3-D4, computenet-9sm.3.1): `CausalStability` takes
three injected reads and deliberately omits `interestOf` — the stability read
is per logical id, not per key, so there is no key to evaluate an `Interest`
against. Consequence: under PN-6 sharding, a member never delivers waves
outside its own slice, so every cross-slice source reads permanently bottom
and `stableFrontier` freezes for the whole logical id the moment the instance
set is sharded — conservative (under-reports, never over-reports) and
documented at the site, not fixed. An interest-scoped stability read is filed
as a design question, not an implementation gap; no spec section answers it.

### The u7fi decision

`computenet-u7fi`'s 2026-09-06 decision (superseding feature design 9sm.8-D3):
accept the re-baseline fence residual PROVISIONALLY, build no fenced-source
lattice under KE3, file no new bead — already filed twice, at `42-WM-R14` and
42-replication.md §Open interactions (decision 9sm.8-D11). Amended the same
day to require the revisit trigger restated in `OrMapCell.applyReBaseline`'s
KDoc (grep anchor `fenced-source`) before close; that restatement landed
(`3a005eab8`, per `## KE3-42-ORMAP`) naming the two edits that would reopen it
— `ReBaselineEmitting` entering `OrMapCell`'s supertype list, or a
superseded/rotated `dotSource`. A whole-feature-diff trigger grep at close-out
found no such edit.

### Pins

Five landed-half pins (`kernel/src/test/kotlin/civictech/cell/replication/`):
`ShardedReplicaFrontierTest`, `GlitchFreeReplicaFrontierTest`,
`UnknownJoinerFenceTest`, `DeliveredWatermarkTest`,
`MemberDepartureFrontierTest` — **all PASS**. computenet-9sm.9.3's evidence
comment on computenet-9sm.9 (2026-09-09) ran all five bound to one
`:kernel:test` invocation with `--rerun`: `BUILD SUCCESSFUL in 39s`, 14/14
tests green, 0 failures, 0 errors, `junit-count.py` confirming 5 files for 5
requested classes and a newest timestamp 49s old (not a cached replay). The
pins diff `053540fd8..HEAD` at that task's own head was EMPTY — unmodified
since the epic base.

BS-1 (`DeliveredFrontierTest`, [KE3-08]/[KE3-09]) — **PASS**.
computenet-9sm.9.1's closing comment: 3/3 tests green
(`./gradlew :kernel:test --tests
'civictech.cell.data.delta.DeliveredFrontierTest' --rerun`), unit and
integrated halves both present, no production defect found. Its reviewer's
mutation check (holdback loop's `while (pending.remove(thru + 1)) thru++`
replaced with an unconditional admit) turned all three assertions red with
distinct, traced failure messages — confirmed non-vacuous, `metadata.review=passed`.

BS-19 (`PreKe3WireFixtureTest`, [KE3-39]) — **PASS**. computenet-9sm.9.2's
closing comment: four byte fixtures captured at the epic base `053540fd8`
still decode/re-encode byte-identically; `WireCodec.VERSION` unchanged (`2`)
at both ends; `./gradlew :wire:test --tests
'civictech.wire.PreKe3WireFixtureTest' --rerun` → 4/4 PASSED. Its own mutation
check (flipping a decoded epoch byte in a fixture) turned the decode assertion
red with the expected diff, then reverted clean. The Stall half of [KE3-39]'s
premise ("WatermarkDelta changed under KE3") was itself corrected — only
`StallNotice.Stall.slot`/`StallReason.STABILITY_FROZEN` changed; `WatermarkDelta`
is byte-unchanged since `053540fd8`, already independently pinned by
`StallNoticeWireCompatTest`.

BS-20 (the `[KE3-05]` citation grep) — **PASS**, run by computenet-9sm.9.3:
`git grep -n 'Delivered watermarks' -- ':!doc/archive'` at that task's HEAD
returns 34 hits (37 at the epic's own breakdown observation, a drift explained
by the bead's own caveat, not a regression); every hit resolves to
`doc/spec/40-distribution/42-replication.md:223 ## Delivered watermarks and
causal stability` by section number or quoted heading text — no dangling
citation.

### Also worth recording

- **The KE3-23 fence-escape family.** `## KE3-23-*` entries
  (computenet-dwkp/07vb/s0tq/92ek) trace the BS-12 `stableFenceAttributed`
  escape from reproduction (`## KE3-23-DWKPRATE`: 0 of 50 sweeps at current
  `main` against a 6-of-15 pre-fix control) through mechanism
  (`## KE3-23-CLOSEDROW`, `## KE3-23-CLOSEDPREMISE`, `## KE3-23-QUORUMCLOSED`).
  A first redundancy claim in `## KE3-23-DWKPRATE` was WITHDRAWN after a
  mislabelled mutation; the corrected measurement (`## KE3-23-DWKPRATE`'s
  "Update (`computenet-0ade`)" subsection) found `computenet-07vb` is
  individually NECESSARY — reverting it alone returns the class at 7 of 20,
  all `seeds=[12]` — while `computenet-92ek` and `computenet-s0tq` are each
  BOUNDED AT 0 of 20 on their own: a statistical bound at that sample size, not
  a claim that either does nothing, since each fixes its own
  separately-reproduced, deterministically-triggered defect outside this
  sweep's reach.
- **Mint-count and SHARED-arm notes from `computenet-9sm.8`'s siblings.**
  `computenet-1383` measured and documented `RemoveAllDotModel`'s mint-count
  divergence from `DotModel` after a revive-then-re-remove sequence (latent
  today; `CTL-03`'s own script never exercises it). `computenet-rjue` built the
  OR-map sweep's fourth arm, `Trigger.SHARED`, giving the per-key `value(key)`
  observable a real multi-writer-key pick to resolve (`## KE3-42-ORMAP-SHARED`);
  `computenet-pa5l`'s residual on that arm resolved an apparent value
  divergence to an adversary-withheld (permanently stranded) frame, not a
  reclamation defect, and kept the zero-tolerance VALUE assertion rather than
  replacing it with a measured ceiling — the arm's own floor is 0 of 200.

### Cross-references

Every `## KE3*` entry in this file, in file order:

`## KE3-D4`, `## KE3-GC`, `## KE3-HB`, `## KE3-GC-DEL-DOT`,
`## KE3-GC-FENCE-KEY`, `## KE3-GC-WITNESS`, `## KE3-23-PROVENANCE`,
`## KE3-23-HOLDER`, `## KE3-23-ORDERING`, `## KE3-23-OPENSET`,
`## KE3-23-ROWCONTENT`, `## KE3-23-LANECONT`, `## KE3-23-CLOSEDROW`,
`## KE3-BS16-RETAINED`, `## KE3-CKPT-TRIGGER`, `## KE3-23-CLOSEDPREMISE`,
`## KE3-23-BS5-BLINDSPOT`, `## KE3-23-QUORUMCLOSED`, `## KE3-20`,
`## KE3-23-DWKPRATE`, `## KE3-42-ORMAP-BS13`, `## KE3-42-ORMAP-SHARED`,
`## KE3-42-ORMAP`, and this entry.

### Not resolved by KE3

G-25, G-39, G-40, G-42 and G-45 are not marked resolved by this entry or by
anything it cites.

## MEM1 — epic close-out: corrected premises, dispositions, and what the pins showed

Recorded by: `computenet-f7h.7.4` (task, feature `computenet-f7h.7`, epic
`computenet-f7h` = MEM1). Base commit: `48ba82309` (`computenet-f7h.6:
election safety and seeded leader-churn suites (#793)`, then `origin/main`).
Every line number, constant and count below was re-resolved against that sha
rather than copied from the epic or the breakdown; where the two disagree,
the observed value is what is written and the disagreement is the finding.
This entry is append-only and edits no other entry, no spec file, and no
production or test source: it reports, per this file's head note.

Every figure is labelled MEASURED (with the run and the machine that produced
it) or ESTIMATED. Every "landed" names its PR and sha.

### Corrected premises

Ten premises the epic, its breakdowns or its feature reviews carried that are
false, stale or weaker than filed at `48ba82309`. Each names the bead that
found it; those marked **found here** were falsified by this task while
re-resolving the epic's citations.

1. **The spec still says single-writer replication is "not built", and so does
   `LeaderMark`'s KDoc.** `doc/spec/40-distribution/42-replication.md:844`
   (MEASURED at `48ba82309` by `grep -n '^## Single-writer replication'`)
   reads `## Single-writer replication (decided in 93 I-25, not built)` while
   `SingleWriterReplication.kt` and `LeaderElection.kt` ship — landed by F1
   `ff52712c1` (#785) through F6 `48ba82309` (#793). The epic cited the same
   header at `:300`. Same family, one layer down:
   `kernel/src/main/kotlin/civictech/cell/host/LeaderMark.kt:14-16` still says
   "Automatic election that *mints* these marks is the deferred liveness half
   (G-44 residual, 95 §R1); explicit/orchestrated designation is the spec's
   declared default" — stale since F4 `41c702fff` (#789) landed opt-in
   `LeaderElection.EpochClaim`. **Neither is fixed here**: the spec header is
   barred by AGENTS.md's plan-editing exclusion and this feature's own
   out-of-scope clause, and the KDoc sits in a production file outside this
   feature's documentation-only diff. Recorded, not repaired; a later item
   owns both.
2. **The epic's findings path does not exist.** Epic §6 cites
   `doc/replication/findings.md`; `git cat-file -e 48ba823:doc/replication/findings.md`
   answers `fatal: path 'doc/replication/findings.md' does not exist in
   'HEAD'`. This file, `doc/kernel-lane-findings.md`, is the kernel lane's log
   (f7h.7-D1), and `doc/replication/` is deliberately **not** created. The KE3
   close-out above (`## KE3 — epic close-out`, premise 5) records the
   identical mistake made against `doc/kernel/findings.md` — the second epic
   in a row to cite a findings path that was never created, which is worth
   more than either instance alone.
3. **`Peering.Loopback.partition()` is symmetric, and the frame interposer
   already existed.** Epic §5.3 prescribes "asymmetric partition via
   `Peering.Loopback.partition()` on one side";
   `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt:752` is
   `fun partition() = closeInstance()` — it closes the connection instance for
   both directions, so no one-sided fault is expressible through it. F6 used a
   one-direction `Peering.FrameInterpose` drop instead (f7h.6-D3), pinned by
   `SplitBrainReconciliationTest`'s `5_3` test; found by computenet-f7h.6's
   breakdown and its `computenet-f7h.6.1`. The neighbouring premise in epic
   §5.8.4 — announcement loss "needs CHA1's frame interposer and is
   unestablished until it lands" — is stale in the other direction: the
   interposer seam is `Peering.kt:863` (`fun interface FrameInterpose`) with
   the composable set in `testkit/src/main/kotlin/civictech/testkit/dst/FrameInterposers.kt`,
   both present at this sha. The corrected dispute is `MEM1-WIRE-LOSS`, which
   narrows the gap to loss over a real socket.
4. **CHA3's churn rig is landed, and MEM1 consumed only a corner of it.** Epic
   §4 says "MEM2 consumes it"; `testkit/src/main/kotlin/civictech/testkit/dst/churn/`
   holds 11 files at `48ba82309` (`BatchReference`, `ChurnConfig`,
   `ChurnGenerator`, `ChurnMesh`, `ChurnPlan`, `ControlSeams`,
   `GossipInstruments`, `LastReplicaProbe`, `PeerHandles`,
   `ReconvergenceCheck`, `StabilityObservables`). MEM1 consumed it for seeds
   and for `SingleWriterChurnTest` only — F1's claim included that test file,
   and `computenet-f7h.8` `2d7ab11ed` (#792) fixed its KDoc. Its own class
   KDoc records why it drives the mesh directly rather than through
   `ChurnMesh`.
5. **BS-14's measured demoted-total duplication is repaired — and the epic's
   expected value is right for only one of the two arms.**
   `SingleWriterChurnTest`'s class KDoc (L99-111) records what
   `computenet-yqgd` found: read at *every* instance rather than at the
   successor alone, the DEMOTED instance duplicated exactly its 2
   pre-transition writes in both orders — promote-first 6 against an expected
   4, demote-first 4 against an expected 2. F3 `f72d3e6f6` (#788) repaired it
   (f7h.3-D1): the successor's catch-up now ships a `Stamped(baseline = true)`
   routed through `applyTo`'s `onBaseline` to `adoptState`, which REPLACES the
   demoted instance's state instead of adding to it, and the ex-leader's stale
   outbound link is unlinked at step-down. Quoted from the file at
   `48ba82309`, not from the bead:
   `const val MEASURED_DEMOTED_TOTAL_PROMOTE_FIRST: Long = 4` (L634) and
   `const val MEASURED_DEMOTED_TOTAL_DEMOTE_FIRST: Long = 2` (L663), each
   asserted equal to `report.expectedTotal` for its own arm with
   `peerAReading.duplicated(report.expectedTotal)` MEASURED 0 (L409-412,
   L502-505).

   **Found here — two corrections to the bead that scheduled this entry.**
   (a) The bead states both constants "equal `report.expectedTotal` (4)". They
   do not: `expectedTotal` is 4 in the promote-first arm (L392) and 2 in the
   demote-first arm, which has no post-transition write, so the demote-first
   constant is **2**, not 4. The property that holds in both arms is
   *demoted == successor*, which is exactly how `computenet-f7h.3`'s own
   acceptance-correction comment (2026-09-10) reads its literal "4 == 4 in
   both orders". (b) The file is
   `testkit/src/test/kotlin/civictech/testkit/dst/churn/SingleWriterChurnTest.kt`
   (665 lines), in `:testkit`, not under `kernel/src/test/`.
6. **`ReplicaConvergence` is typed over `Replicable`, so it could not be the
   seeded suite's invariant cell.** Epic §1.6 names
   `civictech.cell.verify.ReplicaConvergence` as "the right invariant cell for
   the seeded churn suite";
   `kernel/src/main/kotlin/civictech/cell/verify/ReplicaConvergence.kt:32` is
   `class ReplicaConvergence<D : Any, S>` bound to the mergeable `Replicable`
   contract, which a single-writer cell does not satisfy. F6 used a
   test-local single-writer fold harness instead (f7h.6-D2).
7. **`[MEM1-26]` (ack-from-k durability) is REFUSED, not deferred.** Two spec
   surfaces leave it open — G-44's proposal column at
   `doc/spec/90-roadmap/91-gap-analysis.md:95` ("an optional ack-from-k
   durability tier") and 93 I-25 §8's follow-on list, "Synchronous-ack
   durability tier (optional)" — while 93 I-25 §4.4 decides it "would be a
   second protocol and is explicitly **not** adopted". AGENTS.md's authority
   order puts an integrated decision in `93-feature-interactions.md` above
   both the gap-table prose and a §8 follow-on list, so the decision governs
   and there is nothing to test (f7h.6-D5). `ElectionIsolationTest:59` cites
   the id in KDoc only, saying so.
8. **`[MEM1-30]`'s composability evidence is the sealed hierarchy, not a
   test.** `kernel/src/main/kotlin/civictech/cell/replication/LeaderElection.kt:18`
   is `sealed interface LeaderElection` with exactly two arms — `object Manual`
   (the default: arms nothing, counts nothing, `observe` is a no-op) and
   `data class EpochClaim(val window: DetectionWindow)` (f7h.4-D1). Sealedness
   is the evidence: a G-67 park posture would be a third arm the claim rule
   must exhaust, which the compiler enforces rather than a test. Deliberately
   not tested (f7h.6-D5).
9. **Epic-vs-repo line drift.** Every line number the epic cites, against the
   number MEASURED at `48ba82309` by `git grep -n` in this worktree. The epic
   was written against an earlier tree; none of these is a defect, and the
   table exists so the next reader resolves a symbol rather than a number.

   | Epic cited | At `48ba82309` | Note |
   | --- | --- | --- |
   | `42-replication.md:300` — single-writer header | `:844` | moved; still says "not built" (premise 1) |
   | `91-gap-analysis.md:95` — G-44 row | `:95` | unchanged |
   | `91-gap-analysis.md:21` — C-13 row | `:21` | unchanged |
   | `SingleWriterReplication.kt:35` — `data class LeaderMark` | `host/LeaderMark.kt:32` | moved to the membership lane (f7h.1-D1); `SingleWriterReplication.kt:31` keeps a `typealias` |
   | `SingleWriterReplication.kt:161` — `leaderMarks` | `host/InstanceIndex.kt:87` | moved with the fold (f7h.1-D2); `markLeader` at `:102`, `leaderOf` at `:120` |
   | `SingleWriterReplication.kt:214` — `designateLeader` | `SingleWriterReplication.kt:637` | now `= registry.markLeader(mark)` — one write path (f7h.4-D4) |
   | `SingleWriterReplication.kt:44` — `Stamped` | `:61` | now carries `baseline: Boolean = false` (f7h.3-D1) |
   | `SingleWriterReplication.kt:246` — interest gate | `:966` | `registry.instances.interestOf(leader.ref).overlaps(targetInterest)` |
   | `Peering.kt:22-37` — `RegistryAnnounce` | `wire/Peering.kt:29` | fifth variant `leaderMarked` added by F2 `a3493440b` (#786) |
   | `Peering.kt:97` — mirror serve | `wire/Peering.kt:165` | `inlet.serve(object : RegistryAnnounce { … })` |
   | `Peering.kt:199` — `announceTo` | `wire/Peering.kt:1094` | |
   | `Replication.kt:285` — `evict` | `replication/Replication.kt:777` | |
   | `ManagedHost L1098-1124` — RESTART branch | `:1123` | `SupervisionPolicy.RESTART ->` |
   | `WireCodec L228` — `Stamped` subclass | `:228` | unchanged; `LeaderMark` registered at `:231` (F2) |

10. **The seeded sweep's wall time, MEASURED here.**
    `./gradlew :kernel:test --tests 'civictech.cell.replication.LeaderElectionTest' --rerun`
    at `48ba82309`: **MEASURED 29s wall** (`BUILD SUCCESSFUL in 29s`; 31
    actionable tasks, 16 executed, 15 from cache), of which the test class
    itself is **MEASURED 0.861s** across 11 tests, 0 failures, 0 skipped —
    read from `kernel/build/test-results/test/TEST-civictech.cell.replication.LeaderElectionTest.xml`,
    JUnit `timestamp="2026-09-10T15:35:53.222Z"`. The three 50-seed sweeps are
    **MEASURED 0.134s** (`two simultaneous claims converge on the greater
    instanceId across fifty seeds`), **0.207s** (`a parked write under two
    claims in flight is applied exactly once at the winner across fifty
    seeds`) and **0.146s** (`a parked write leaves the loser diverged from the
    winner on some seeds`). Machine: `NL-MGD6FQJW91`, `uname -sm` =
    `Darwin arm64`, load1 **7.81** at launch (`uptime`, 17:35 CEST
    2026-09-10) with sibling agents building concurrently on 16 cores. The
    wall figure is therefore an upper bound under contention, not a clean
    measurement; the class time is unaffected by it. Comparable figure from
    `computenet-f7h.6.2` on the same machine: MEASURED 33s wall, class time
    0.899s, sweeps 0.139s / 0.198s / 0.150s. ESTIMATED cost of a warm re-run
    with no contention: well under a minute.

### Also worth recording

- **F6's three falsified predicted counts**, each now pinned as a measurement
  rather than a prediction (`computenet-f7h.6.1`'s comment;
  `SplitBrainReconciliationTest` at `48ba82309`):
  - **(a) The §5.1 stale-baseline fence is MEASURED 1 at B and 0 at C**
    (L392-410), not the "one per rebuilt A→peer link" the breakdown predicted.
    Under this interleaving A re-links only to B before it folds `(2, bRef)`
    and steps down; by the time A's registry learns `cRef`, A is a follower
    and `onPeerPublished(cRef)` builds no A→C link, so nothing epoch-1 ever
    arrives at C. The epic's §5.1 fence clause therefore holds at C **by
    construction** — the fence itself is measured by the bridged arm
    (`5_1 bridged …`, L444), where A demonstrably still reaches C. This is the
    whole content of that pin, and it is why the bridged arm exists.
  - **(b) A send-counting interposer reports 1 for a frame delivered twice.**
    The shared `Counting` interposer records inside `apply`, i.e. per SEND
    (`SplitBrainReconciliationTest:188`), so §5.4's question — how many times
    the duplicated mark reached D — needs `Duplicating` (`:230`), which
    records once per emitted copy. Both are private test-local classes in that
    file, **not** in `testkit`'s `FrameInterposers.kt` (found here, correcting
    the bead's citation).
  - **(c) §5.3's follower shipments are MEASURED 2 and 2**, not the predicted
    3 and 2 (L659-660): one shipment per write at each of A and C. A's rebuild
    baseline is not among them — it was delivered during the `heal()` +
    `runToIdle` before the counters were read. The §5.3 `shipCountAmong`
    reading is MEASURED 2 at B and 0 at A (L634). The recorded verdict stands:
    a false positive costs 1 unnecessary failover, 0 lost, 0 duplicated.
- **`[MEM1-16]`'s "epoch-confirmed" shipped weaker than either the epic or the
  feature stated.** Epic §5.6 requires the parked write be "released only once
  a single epoch-confirmed leader exists"; the feature's own acceptance
  rendered that as "local-max + reachable". What F5 `31018ab6e` (#791) shipped
  (f7h.5-D2, `[SHIPPED]`) is a release on the FIRST fold-maximal mark with **no
  reachability gate at all**, with the apply-time epoch fence covering
  mistiming — `releaseParked`'s KDoc (`SingleWriterReplication.kt:750-776`,
  the function at `:777`) states the reasoning: a mark can be adopted for a ref
  this registry has not republished yet and D2 named no retry, so a
  reachability pre-check would strand the writes at a dead ref forever.
  "Epoch-confirmed" is rendered as two weaker facts — the mark is the fold's
  local maximum at release time, and every delivery is epoch-fenced at apply
  (93 I-25 §4.6). Both the epic's clause and the feature's own restatement of
  it are stale against what shipped. Filed as `MEM1-PARK-CONFIRM`.
- **`ElectionIsolationTest`'s grep-of-record defect is already fixed at this
  sha (found here).** The orchestrator's note on `computenet-f7h.6`
  (2026-09-10 12:27) records a KDoc printing a basic `grep -in
  'watermark|frontier|quorum'` — which reads `|` literally and answers a false
  ZERO — beside a stated count of 1. At `48ba82309` the KDoc
  (`ElectionIsolationTest:463-469`) already reads `grep -inE`, states the
  count as "exactly ONE hit", and explains the trap in line: "`-E` is
  load-bearing: basic `grep` reads `|` as a literal and answers a false ZERO,
  which is how the original '0 hits' claim arose". Re-MEASURED here:
  `grep -cinE 'watermark|frontier|quorum' SingleWriterReplication.kt` = **1**,
  the KDoc sentence about an unrelated per-inlet processed-frontier. So the
  entry records a *repaired* defect, not an outstanding one; nothing is left
  to fix. (It is the same `\s`/`|` family AGENTS.md documents for `git grep`,
  reached this time through plain `grep`.)

### Dispositions

One line per dispute id and per findings entry, naming what each files. The
five `concord/corpus/DISPUTES.md` entries are written by sibling tasks of
`computenet-f7h.7` concurrently with this one; at the moment this entry was
written `grep -n 'MEM1' concord/corpus/DISPUTES.md` at `48ba82309` returns
nothing, so each is cited **by id only** — the text is filed by
`computenet-f7h.7`'s DISPUTES tasks and is not quoted here.

- **`MEM1-CONCURRENCY`** files epic §5.8.1: `SimulationController` is
  single-threaded and step-ordered, so §5.2's "simultaneous claims" are
  interleavings, not races; the fold's behaviour under genuine parallel
  mutation is unestablished by anything in this epic.
- **`MEM1-LIVENESS`** files epic §5.8.2 and §5.8.3 together: no clock in
  kernel, wire or testkit and no `advanceTime`, so no "converges within T"
  statement is checkable here, and the dual-leader window has no bound without
  a synchrony assumption (93 I-25 §2). `[MEM1-07]`'s detection window is
  consequently specified in membership observations, never milliseconds — as
  `DetectionWindow`'s own KDoc says — and a wedged leader still holding its
  socket is undetectable (f7h.4-D6).
- **`MEM1-WIRE-LOSS`** files epic §5.8.4 **as corrected** (premise 3): the
  interposer exists, in-process announcement loss IS covered by F6's §5.3 and
  §5.4 arms, and what remains unestablished is loss over a real socket —
  retirement path is `:wire`'s two-JVM rig.
- **`MEM1-SPEC-ID`** files epic §5.8.5: no `[42-LEAD-nn]` id exists for the
  election rule and there is no single-writer binding in `KernelDriverDist`,
  so `[MEM1-07]`/`[MEM1-11]`/`[MEM1-22]` cannot become concord scenarios.
  Retired by a spec ticket minting the id **and** a driver binding; this epic
  edits no spec (§4), so it is a dispute-with-named-blocker rather than a
  scenario authored against an invented id.
- **`MEM1-PARK-CONFIRM`** files f7h.5-D2, above: "epoch-confirmed" is rendered
  as fold-local-max plus an apply-time fence, and no stronger guarantee is
  available without consensus.
- **`MEM1-52`** (this file, anchor `#mem1-52-dual-claim-divergence`) files the
  dual-claim divergence at an equal counter that `LeaderElectionTest`'s
  `@ExpectedFailure(signature = "MEM1-52-DUAL-CLAIM-DIVERGENCE", owner =
  "computenet-f7h.7")` cites: `Stamped.applyTo` fences on the counter alone
  and carries no instance-id tiebreak, so on the write-first branch the loser
  is left diverged from the winner without loss and without duplication —
  `[MEM1-20]`'s "the loser's deltas SHALL be fenced inert" does not hold at an
  equal counter. The entry itself is written by a sibling task of
  `computenet-f7h.7` (filed as `computenet-azro3`, which measured 42 of 50
  seeds diverging); this entry does not restate its figures.

### Pins

Epic §5's seven behaviours against the test that pins each, at `48ba82309`.
All five files are under
`kernel/src/test/kotlin/civictech/cell/replication/`. The per-`[MEM1-nn]`
requirement trace is **not** part of this task; it is appended by a dependent
task after `### Cross-references` below.

| Epic § | Test file | Test name |
| --- | --- | --- |
| 5.1 split brain | `SplitBrainReconciliationTest` | `5_1 split brain — the lower epoch loses completely and its writes surface as divergent` (:274); `5_1 bridged — a live epoch-1 delta reaching a folded peer is fenced, and surfaces on the heal` (:444) |
| 5.2 simultaneous claims | `LeaderElectionTest` | `two simultaneous claims converge on the greater instanceId across fifty seeds` (:1041) |
| 5.3 false-positive detection | `SplitBrainReconciliationTest` | `5_3 false-positive failover costs one failover and no write` (:545) |
| 5.4 epoch regression | `SplitBrainReconciliationTest` | `5_4 a returning peer's stale mark is inert and the canonical mark folds exactly once` (:686) |
| 5.5 RESTART is not an election | `SingleWriterReplicationTest`; `LeaderElectionRefusalTest` | `a RESTART of an ELECTED leader mints no claim and recovers by donor catch-up` (:779); `with no follower reachable a RESTART falls back to the checkpoint and re-baselines the follower on heal` (:907); `a supervised RESTART of the leader is invisible to the detection window` (:437) with its control `control - despawning the leader instead is visible and does claim` (:512) |
| 5.6 parked write in a contested window | `LeaderElectionTest` | `a parked write under two claims in flight is applied exactly once at the winner across fifty seeds` (:1101); the divergence half is `a parked write leaves the loser diverged from the winner on some seeds` (:1187), which is `@ExpectedFailure`-marked under `MEM1-52-DUAL-CLAIM-DIVERGENCE` and is a recorded finding, not a green pin |
| 5.7 last-reachable-peer refusal | `LeaderElectionRefusalTest` | `a sole survivor refuses to claim, and claims on the first observation that makes somebody reachable` (:274), with its control `control - the same partition and the same observe with one reachable peer does claim` (:370) |

`ElectionIsolationTest`'s four tests (:133, :182, :365, :480) are not one of
the seven; they pin the isolation properties `[MEM1-19]`/`[MEM1-33]` and the
rig premise the other arms rest on.

### Not resolved by MEM1

- **`MEM1-52`** — the dual-claim divergence at an equal counter. Recorded, not
  fixed: `Stamped` would need an instance-id tiebreak, which is a wire-format
  change no MEM1 item authorises. The fix bead is filed by the sibling task
  that writes the `## MEM1-52` entry; `computenet-azro3` is the bead that
  established the anchor was dangling and measured the rate.
- **`computenet-03kz7`'s three unasserted bounds** from F5's feature review —
  `LocationRegistry.forwardedWritePorts` grows and is never cleared; no
  release runs on a registry that hosts no replica of the id (a pure client),
  because nothing there subscribes to `onLeaderMark`; and a leader armed but
  despawned without stepping down leaves its `Divergence` entry and tap in
  place, since only `surfaceDivergentWrites` removes them. Bounds, not
  defects — the reviewer declined to file them as residuals.
- **The two stale headers in premise 1** — `42-replication.md:844`'s "not
  built" and `LeaderMark.kt:14-16`'s "deferred liveness half". Both need an
  owner outside this feature's documentation-only diff.
- **MEM2's last-replica handoff**, the `[MEM1-23]` boundary. §5.7 pins the
  refusal (the follower does not claim; writes park and the situation surfaces
  as the shipped suspend-when-partitioned condition); what happens *next* is
  MEM2's decision and G-25/G-45's residual, untouched here.
- **The failure-detector shape** (93 I-25 §8). The detection window counts
  membership observations by construction; a real detector — and with it any
  real-time liveness bound — is out of scope and is what `MEM1-LIVENESS`
  files.

### Cross-references

- DISPUTES ids filed by this feature: `MEM1-CONCURRENCY`, `MEM1-LIVENESS`,
  `MEM1-WIRE-LOSS`, `MEM1-SPEC-ID`, `MEM1-PARK-CONFIRM` — all in
  `concord/corpus/DISPUTES.md`, written by sibling tasks of
  `computenet-f7h.7`.
- `## MEM1-52` in this file, anchor `#mem1-52-dual-claim-divergence` — the
  string `LeaderElectionTest`'s `@ExpectedFailure(filedAs = …)` points at.
- `## KE3 — epic close-out: corrected premises, dispositions, and what the
  pins showed` (this file, L3014) — the exemplar this entry follows, and the
  first instance of premise 2's non-existent-findings-path mistake.
- MEM1's landed set, in order: F1 `ff52712c1` (#785), F2 `a3493440b` (#786),
  `computenet-rdh4q` `c577712da` (#787), F3 `f72d3e6f6` (#788), F4
  `41c702fff` (#789), `computenet-hu1ie` `f1eb2668c` (#790), F5 `31018ab6e`
  (#791), `computenet-f7h.8` `2d7ab11ed` (#792), F6 `48ba82309` (#793).
- Open at the time of writing: `computenet-03kz7`, `computenet-azro3`.

### Requirement trace

Task `computenet-f7h.7.5`, resolved at `e53d5408c` (`Merge computenet-f7h.7.3`,
this branch's base). Every citation below is a re-run
`git grep -n -o -E 'MEM1-[0-9]{2}'` at this sha against `kernel/src/test/*`,
`testkit/src/test/*` and `kernel/src/main/*`, and every named test was
confirmed present with `git grep -n -F '<name>'` against the file named —
neither is inferred from the breakdown's map, which predates this sha and
disagrees with it in places noted below.

One row per `[MEM1-01]`..`[MEM1-33]`: the feature that shipped the mechanism,
the test whose assertion most directly pins it (or the DISPUTES/findings id
that files the gap), and a note where more than one citation exists or the
pin is partial.

| id | feature | test / dispute | note |
| --- | --- | --- | --- |
| [MEM1-01] | f7h.1 + f7h.2 + f7h.4 | pinned by mechanism, no test names the id | See "Ids with no by-name citation" below. |
| [MEM1-02] | f7h.1 | `LeaderMarkFoldTest."total order, same counter"` | Also `SingleWriterReplicationTest."a fenced stale LeaderMark epoch is rejected"` (f7h.1-D2). |
| [MEM1-03] | f7h.1 | `InstanceIndexTest."a lower epoch, an equal-epoch lower instanceId, and a duplicate mark are all rejected"` | |
| [MEM1-04] | f7h.3 | `StepDownTest."the fence admits a unit at the replica's own epoch and refuses one below it"` | Also `StepDownTest."a delta applied under the old epoch and in flight at the fold never reaches the follower's state"` (mid-shipment arm, f7h.3.3). |
| [MEM1-05] | f7h.4 | `LeaderElectionTest."every replication source is free of clock, thread and timer identifiers"` | |
| [MEM1-06] | f7h.2 | `LeaderMarkAnnounceTest."a late joiner converges from catch-up alone and its stale mark changes nothing"` | Also pinned by consequence in `SplitBrainReconciliationTest`'s §5.4 arm (`"5_4 a returning peer's stale mark is inert and the canonical mark folds exactly once"`, f7h.6). |
| [MEM1-07] | f7h.4 + f7h.7 | `LeaderElectionTest."a Manual engine and an EpochClaim engine on one registry - only the EpochClaim id elects"` + `MEM1-LIVENESS` + `MEM1-SPEC-ID` | **Correction to this bead's own example**: it proposed `LeaderElectionTest."a follower claims the next epoch once the window closes, and the claim reaches its surviving peer"` as the [MEM1-07] pin. Re-grepped: that test's KDoc carries no `MEM1-07` citation — [MEM1-07] is cited only at the file-header scan comment (line 42) and directly at `"a Manual engine and an EpochClaim engine on one registry - only the EpochClaim id elects"` (line 333), which is used here instead. |
| [MEM1-08] | f7h.6 | `SplitBrainReconciliationTest."5_3 false-positive failover costs one failover and no write"` | Also cited by consequence in `StepDownTest` (§5.1 arm). |
| [MEM1-09] | f7h.1 | `SingleWriterChurnTest."BS-14 promote-first designation leaves no in-process split-brain window under one fold per registry"` | In-body: "a duplicate of the already-folded mark is rejected ([MEM1-09])". Also cited at `InstanceIndexTest`, `LeaderMarkFoldTest`, and by consequence in `SplitBrainReconciliationTest`'s §5.4 arm. |
| [MEM1-10] | f7h.3 | `StepDownTest."a superseded leader unlinks its shipping, catches up, and forwards its writes"` | Synchronous half, in-body comment "[MEM1-10], synchronous half". |
| [MEM1-11] | f7h.2 + f7h.7 | `LeaderMarkAnnounceTest."adoption announces once per direction and peers apply roles from the mirrored mark"` + `MEM1-SPEC-ID` | Section comment directly above the test reads "[MEM1-11] adoption announces once per direction". Also `LeaderMarkWireTest."a LeaderMark round-trips through the codec, and its encoding is ids-only and additive"`. |
| [MEM1-12] | f7h.6 | `SplitBrainReconciliationTest."5_1 split brain — the lower epoch loses completely and its writes surface as divergent"` | Cited only in the class KDoc's §5.1 line, not in a per-test comment; the class KDoc names this test as the §5.1 example (matching the "### Pins" table above). |
| [MEM1-13] | f7h.5 | `DivergentWriteSurfacingTest."divergent writes are surfaced once each on the dead-letter outlet and to every handler"` | Class KDoc: "[MEM1-13] example 4". Also cited in `SplitBrainReconciliationTest` (§5.1 arm) and `StepDownTest`. |
| [MEM1-14] | f7h.4 (`computenet-hu1ie`) | `LeaderElectionRefusalTest."a supervised RESTART of the leader is invisible to the detection window"` | Section comment: "[MEM1-14] RESTART invisible". Also `InstanceIndexTest`, `LeaderElectionTest`, `SingleWriterReplicationTest`. |
| [MEM1-15] | f7h.3 | `ShippingLinkIdempotenceTest."repeated partition and heal leaves exactly one shipping link per leader-follower pair"` | Also `LeaderElectionTest`, `LeaderMarkFoldTest`, `StepDownTest`. |
| [MEM1-16] | f7h.5 | `ParkedWriteReleaseTest."a write parked at a superseded leaderRef is released onto the winner exactly once"` + `MEM1-PARK-CONFIRM` | Class KDoc: "Owns feature rules [MEM1-16] (both clauses...)", section comment "[MEM1-16] example 1". `MEM1-PARK-CONFIRM` files that "epoch-confirmed" shipped weaker than the epic's release gate (corrected premise, "Also worth recording"). |
| [MEM1-17] | f7h.6 | `SplitBrainReconciliationTest."5_3 false-positive failover costs one failover and no write"` | Class KDoc's §5.3 line. Also `LeaderElectionTest`. |
| [MEM1-18] | f7h.3 | `StepDownTest."a delta applied under the old epoch and in flight at the fold never reaches the follower's state"` | In-body: "[MEM1-18], mid-shipment half". Also the steady-state arm, `StepDownTest."a superseded leader unlinks its shipping, catches up, and forwards its writes"`. |
| [MEM1-19] | f7h.6 | `ElectionIsolationTest."an election on a co-hosted single-writer set leaves the mergeable covering quorum unmoved"` | Class KDoc: "[MEM1-19]/[MEM1-33] (quorum non-interference)". |
| [MEM1-20] | f7h.4 | `LeaderElectionTest."two simultaneous claims converge on the greater instanceId across fifty seeds"` | Section comment: "[MEM1-02]/[MEM1-20]/[MEM1-15] — 5.2". **Does not hold at an equal counter**: `MEM1-52` (this file, `## MEM1-52 dual-claim divergence`) measures 42/50 seeds diverging on the write-first branch of `"a parked write leaves the loser diverged from the winner on some seeds"`, where the same KDoc states "[MEM1-20]'s 'the loser's deltas SHALL be fenced inert' does not hold". Also filed by `MEM1-CONCURRENCY` (interleavings, not races) and, at fold level, `SingleWriterChurnTest`. |
| [MEM1-21] | f7h.5 | `DivergentWriteSurfacingTest."a write the departed member already received is not counted as divergent"` | Class KDoc: "[MEM1-21] example 6". Also cited in `SplitBrainReconciliationTest`'s §5.1 and §5.3 arms; the frame-loss variant is filed by `MEM1-WIRE-LOSS`. |
| [MEM1-22] | f7h.2 + f7h.7 | `LeaderMarkWireTest."a local markLeader crosses once, folds on the peer, and is not re-announced back"` + `MEM1-WIRE-LOSS` + `MEM1-SPEC-ID` | In-body: "the fold is idempotent at registry level ([MEM1-22])". Also `InstanceIndexTest`, `LeaderMarkAnnounceTest`, `LeaderMarkFoldTest`, and consequence in `SplitBrainReconciliationTest`'s §5.3/§5.4 arms. |
| [MEM1-23] | f7h.4 (`computenet-hu1ie`) | `LeaderElectionRefusalTest."a sole survivor refuses to claim, and claims on the first observation that makes somebody reachable"` | Matches the "### Pins" table's §5.7 row above. Also `ElectionIsolationTest`, `LeaderElectionTest`, `SingleWriterReplicationTest`. MEM2's follow-on is out of scope (see "Not resolved by MEM1"). |
| [MEM1-24] | f7h.5 | `ParkedWriteReleaseTest."a Leased write mid-election is rejected, never parked and never released"` | Class KDoc: "[MEM1-24] example 7". |
| [MEM1-25] | f7h.4 | `LeaderElectionTest."every replication source is free of clock, thread and timer identifiers"` | Same test as [MEM1-05]; both are asserted by this one clock/thread/timer-identifier scan. |
| [MEM1-26] | f7h.7 | `MEM1 findings §7` (corrected premise 7, above) | **Refused, not tested.** `ElectionIsolationTest:59` cites the id in KDoc only ("f7h.6-D5: [MEM1-26] and [MEM1-30] are deliberately NOT tested here; F7 records them"); 93 I-25 §4.4 decides ack-from-k durability is a second protocol and not adopted. No test names this id. |
| [MEM1-27] | f7h.6 | `ElectionIsolationTest."an election on one shard leaves the other shard's leadership, links and writes untouched"` | Class KDoc: "[MEM1-27] (per-shard leadership)". |
| [MEM1-28] | f7h.5 | `DivergentWriteSurfacingTest."the dead-letter path stands alone, and a closed handle stops only its own handler"` | Class KDoc: "[MEM1-28] example 5". Also `SplitBrainReconciliationTest`'s §5.1 arm. |
| [MEM1-29] | f7h.6 | `ElectionIsolationTest."the effect authority stays exactly-once across a claimed handoff"` | Class KDoc: "[MEM1-29] (effect authority across a claimed handoff, spec 31 §Effects on instance sets / PN-17)". |
| [MEM1-30] | f7h.7 | `MEM1 findings §8` (corrected premise 8, above) | **Sealed-hierarchy evidence, not tested.** Same `f7h.6-D5` KDoc line as [MEM1-26]; sealedness of `LeaderElection` is the evidence the compiler enforces, not a test assertion. No test names this id. |
| [MEM1-31] | f7h.3 | `StepDownTest."a delta applied under the old epoch and in flight at the fold never reaches the follower's state"` | In-body: "[MEM1-31], [MEM1-04], [MEM1-18]'s mid-shipment half". Also `LeaderElectionTest`, `ReplicatedEffectTest`, `SingleWriterReplicationTest`, `SplitBrainReconciliationTest`'s §5.1 arm, `SingleWriterChurnTest`. |
| [MEM1-32] | f7h.3 | `SingleWriterReplicationTest."a rebuilt link's catch-up REPLACES a follower's state instead of adding to it"` | In-body: "catch-up is now a BASELINE ([MEM1-32], f7h.3-D6)" — this is the corrected-premise-5 mechanism (`Stamped(baseline = true)` routed through `onBaseline` to `adoptState`). Also `ReplicatedEffectTest`, `SingleWriterChurnTest`. |
| [MEM1-33] | f7h.6 | `ElectionIsolationTest."an election on a co-hosted single-writer set leaves the mergeable covering quorum unmoved"` | Same test and KDoc line as [MEM1-19]. |

**Ids with no by-name citation: `[MEM1-01]`, `[MEM1-26]`, `[MEM1-30]`.**
`[MEM1-26]` and `[MEM1-30]` are both explicit, deliberate non-tests
(`f7h.6-D5`) with their disposition recorded in corrected premises 7 and 8
above — they are not gaps, they are decided-out. `[MEM1-01]` ("a claim SHALL
be an ordinary LeaderMark announcement folded into the membership index —
never a vote, quorum, view number, lease, or second protocol") is different:
no test or main source cites it by id at all, at this sha or at `48ba82309`.
It is pinned by mechanism instead —

- F1's fold onto `InstanceIndex` rather than a separate election data
  structure (f7h.1-D1), asserted by
  `LeaderMarkFoldTest."leaderOf is the membership index's own mark, not an
  engine-side copy"`;
- F2's `RegistryAnnounce.leaderMarked` as a fifth, ids-only wire variant
  rather than a new message class (f7h.2-D1), asserted by
  `LeaderMarkWireTest."a leaderMarked announcement is a plain wire type -
  nothing else changed shape"`;
- the absence of clock, thread, timer, or quorum-count identifiers anywhere
  in the replication sources, asserted by
  `LeaderElectionTest."every replication source is free of clock, thread and
  timer identifiers"` (the same test that pins [MEM1-05]/[MEM1-25]).

No test asserts the negative ("this is not a second protocol") directly, and
none should be expected to — the claim is a design constraint on what the
implementation does NOT introduce, checked by these three tests collectively
showing what it DOES do instead.

**Tally**: of 33 ids, **26** are pinned primarily by a named test, **2**
(`[MEM1-26]`, `[MEM1-30]`) are pinned by an explicit deliberate-non-test
disposition recorded in this entry's corrected premises, and **1**
(`[MEM1-01]`) is pinned by mechanism across three tests with no by-name
citation. **9** ids (`[MEM1-06]`, `[MEM1-07]`, `[MEM1-09]`, `[MEM1-11]`,
`[MEM1-16]`, `[MEM1-20]`, `[MEM1-21]`, `[MEM1-22]`, `[MEM1-52]` via the
`[MEM1-20]` row) carry a `DISPUTES` id alongside their test, per the
"### Dispositions" section above; none of the 33 is pinned by a dispute
*alone* with no test at all.

## MEM1-52 dual-claim divergence

**at an equal counter: what the seeded sweep measured, and what it does not
establish**

Recorded by: `computenet-f7h.7.3` (task, feature `computenet-f7h.7`, epic
`computenet-f7h` = MEM1). Base commit: `c38332f0c` (`Merge
computenet-f7h.7.4`, the branch's cut point; the cited code paths are
unchanged since F6 `48ba82309` (#793) — nothing in `SingleWriterReplication.kt`
or `LeaderElectionTest.kt` moved between the two). This task absorbs
`computenet-azro3` (the dangling-anchor report); its acceptance is reproduced
by this entry. Every line number and count below was re-resolved against
`c38332f0c` rather than copied from the bead or the epic.

This is the anchor `LeaderElectionTest.kt`'s
`@ExpectedFailure(signature = "MEM1-52-DUAL-CLAIM-DIVERGENCE", owner =
"computenet-f7h.7", filedAs =
"doc/kernel-lane-findings.md#mem1-52-dual-claim-divergence")`
(`kernel/src/test/kotlin/civictech/cell/replication/LeaderElectionTest.kt:1179-1185`,
on `` `a parked write leaves the loser diverged from the winner on some
seeds` `` at `:1187`) points at. No entry in this file previously used this
string; the heading above is deliberately NOT this file's usual `## ID —
title` shape, because that shape's em dash slugs to a DOUBLE hyphen
(`mem1-52--dual-claim-...`) which does not match the annotation's fragment.
Verified: `python3 -c "import re; h='MEM1-52 dual-claim divergence';
print(re.sub(r'[^a-z0-9 -]','',h.lower()).replace(' ','-'))"` prints
`mem1-52-dual-claim-divergence`.

### What fails, and on which branch

`dualClaimRig(seed, park = true)` reaches quiescence by one of two scheduler
interleavings (KDoc at `LeaderElectionTest.kt:1121-1177`, restated here, not
re-derived):

- **forward-first** — B's bridge host folds `(2, cRef)` and demotes B before
  B's application host dequeues the released write. B's delegate forwards it
  to C, C applies it (`c.realWrites == 1`) and ships `Stamped(2, 7)` back, so
  `b.total == c.total == 7`. Converged.
- **write-first** — B's application host runs first. B applies the write
  under its own `(2, bRef)` (`b.realWrites == 1`, `b.total == 7`) and emits
  `Stamped(2, 7)`, which C applies at its own epoch 2 because `applyTo`
  compares the counter only and `Stamped` carries no tiebreak. C's promotion
  baseline `Stamped(2, 0, baseline = true)` then reaches B and replaces B's 7
  with 0; B folds `(2, cRef)` and steps down, and nothing re-baselines it
  because C's C→B link already existed. Quiescent state: `c.total == 7`,
  `b.total == 0`.

Mechanism: `Stamped.applyTo`
(`kernel/src/main/kotlin/civictech/cell/replication/SingleWriterReplication.kt:85`,
`if (epoch < currentEpoch) return null`) fences on the COUNTER only;
`Stamped<D>` (`SingleWriterReplication.kt:61`,
`data class Stamped<D>(val epoch: Long, val delta: D, val baseline: Boolean = false)`)
carries no instance-id or writer-identity tiebreak, so two claims that stamp
at the same counter are indistinguishable to the fence. `[MEM1-20]`'s "the
loser's deltas SHALL be fenced inert at every follower" does not hold AT AN
EQUAL COUNTER — 95 §R1's "prove or refute … in every interleaving" answered
in the negative for this case.

### What the sweep measured

Re-run at `c38332f0c`:
`./gradlew :kernel:test --tests 'civictech.cell.replication.LeaderElectionTest' --rerun`
— **MEASURED**: 11 tests, 0 failures, 0 skipped (`BUILD SUCCESSFUL in 13s`;
31 actionable tasks, 14 executed, 17 from cache). Read from
`kernel/build/test-results/test/TEST-civictech.cell.replication.LeaderElectionTest.xml`,
JUnit `timestamp="2026-09-10T15:58:52.230Z"`; the same run's
`reportExpectedFailures` task lists exactly one standing expected failure,
this one, with the `filedAs` fragment above.

**42 of 50 seeds diverge**, printed by the test itself
(`println("MEM1-52 write-first (diverged) seeds: ...")` at
`LeaderElectionTest.kt:1198`):

```
2, 3, 5, 6, 7, 8, 10, 11, 13, 14, 15, 16, 17, 19, 20, 21, 22, 23, 24, 25, 26,
27, 28, 29, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 47, 48,
49, 50
```

converging: 1, 4, 9, 12, 18, 30, 45, 46. Machine: `NL-MGD6FQJW91`, `uname -sm`
= `Darwin arm64`. This is identical in count and in the exact seed list to
the figure `computenet-f7h.6.2` measured at `48ba82309` (also 42 of 50, same
seeds) — the two code paths are unchanged between the two shas, so the
identical reproduction is expected rather than coincidental.

**Divergence without loss, and without duplication.** On every seed
`b.realWrites + c.realWrites == 1` and `c.total == 7`
(`LeaderElectionTest.kt:1119-1120`); the winner always holds the write, pinned
green by `` `two simultaneous claims converge on the greater instanceId
across fifty seeds` `` (`:1041`) and `` `a parked write under two claims in
flight is applied exactly once at the winner across fifty seeds` `` (`:1101`),
both in the same file. What fails is only that the loser's replica state does
not equal the winner's, and stays unequal until C's next write.

### What this measurement does NOT establish

- **`MEM1-CONCURRENCY`** — `SimulationController` is single-threaded and
  step-ordered, so these are scheduler interleavings, not races under genuine
  parallel mutation; the fold's behaviour under real concurrent writers is
  unestablished by this suite.
- **`MEM1-LIVENESS`** — no clock and no `advanceTime` exist in kernel, wire or
  testkit, so nothing here bounds HOW LONG a divergence persists in real
  time, only that it persists until C's next write in this sweep's fixed
  script.
- **`MEM1-WIRE-LOSS`** — the rig runs in-process; loss or reordering over a
  real socket is untested here.
- **f7h.6-D4** (`computenet-f7h.6`'s breakdown comment, 2026-09-10 — not a
  file citation; searched `git grep -n 'f7h.6-D4' -- .` at `c38332f0c` and it
  is absent from the tree, present only in that bead's comment thread): the
  50-seed sample is a MEASUREMENT, not a proof of the general property — it
  bounds nothing about seeds outside `1L..50L` or about the one
  `SimulationController` interleaving family this rig can produce. Read
  literally, D4 is about statistical generalization from a fixed sample, not
  about real-time liveness (that is `MEM1-LIVENESS`, above); the two are
  cited separately here because conflating them would overstate what D4
  decided.

### For the fixing lane: a harness laundering hazard

Recorded by the orchestrator on `computenet-f7h.7`'s comment thread
(2026-09-10 12:21), re-verified here by line: `forEachSeed`
(`testkit/src/main/kotlin/civictech/testkit/ForEachSeed.kt:38`) catches any
`Throwable` per seed and, if any failed, rethrows the first as `SweepFailure`
(`testkit/src/main/kotlin/civictech/testkit/dst/DstSweep.kt:42`), which
extends `AssertionError`. `withSignature`
(`kernel/src/test/kotlin/civictech/cell/repro/ExpectedFailure.kt:120`, catch
clause at `:123`) signs ANY `AssertionError` it catches with the
expected-failure token. So a non-assertion crash inside the sweep — a kernel
exception, not the `shouldBe` comparison — would be laundered into looking
like the recorded MEM1-52 failure, bypassing `[CHA2-43]`'s "a different
failure reddens the build" guarantee. Safe today only because the same
`dualClaimRig` also runs, un-annotated, in the two green tests cited above:
if either goes red for an unrelated reason, that is the signal something
changed beneath the sweep. Not fixed here — this task changes no Kotlin
file; recorded for whoever next touches the annotation.

### Disposition

The `@ExpectedFailure` annotation stands; its `owner` string still reads
`computenet-f7h.7` in the shipped file and is NOT edited by this task (this
feature's diff is documentation-only) — the fixing lane re-points `owner`
when it claims the fix. The production fix is filed as
**`computenet-7zssw`** ("Stamped's counter-only fence lets a lost
equal-counter claimant's delta apply at the winner (MEM1-52)"), parented
under `computenet-f7h`, priority 2, `files` left empty (`files unknowable
before diagnosis` — whether the fix is a tiebreak field on `Stamped` or a
fence on `(epoch, instanceId)` at apply is a design fork the fix bead owns).
Per `[CHA2-44]`, that bead's acceptance is the annotated test going green for
the right reason and the annotation being removed.
