# Evidence-lane findings

Findings log for the evidence lane (`lane:evidence`) — the CHA1/CHA2/CHA3
reproduction and harness work under epic `computenet-umx`. Each entry records
an adjudication result and its disposition. **This file reports; it never
edits specs, plan documents, or the gap-analysis table.** See AGENTS.md ("Do
not edit plan documents unless the task explicitly asks for documentation
maintenance") and `computenet-milestone-plan.md` §6 (a periodic single-agent
integration pass is the only writer of `doc/spec/90-roadmap/91-gap-analysis.md`
and `doc/spec/CONCORDANCE.md`).

Append-only. New entries go at the end; existing entries are amended in place
only to record a later re-verification, and the amendment says so.

---

## CHA2 — the C-9 / C-11 / C-12 adjudication against `main`

Recorded by: `computenet-umx.1.1` (feature `computenet-umx.1` — CHA2; epic
`computenet-umx` — CHA1). Realizes `[CHA2-01]`, `[CHA2-03]`, `[CHA2-05]`,
`[CHA2-30]`, `[CHA2-31]`, `[CHA2-32]`, `[CHA2-33]`; BS-13.

**Base commit: `46ed020`** ("Pin an UNHOSTED `mediateOutlet`'s exactly-once
discharge under disclosure Deny (computenet-e5mn) (#227)"). Every file:line
below was re-read at that commit by this task. Where a citation carried by the
feature body (`computenet-umx.1` §0, written 2026-08-08) has drifted, the
drift is called out inline — the numbers here are the re-verified ones, not
the feature's. Line numbers drift; the symbol names and heading text are the
durable anchors, and a later reader should re-verify by symbol.

### Why this entry exists before any reproduction is written

The CHA2 milestone-plan row names C-9, C-11 and C-12 as
"decided-but-divergent". Verified at `46ed020`, **the row is stale on all
three, in three different ways** — and it went *further* stale during CHA2's
own filing week: two behaviours the feature's §0 predicted would reproduce as
failures have since been fixed on `main`. A reproduction suite that
manufactures failures to match a stale planning row is worse than no suite, so
the adjudication is the blocking first phase and the sibling reproduction
tasks (`computenet-umx.1.3`/`.4`/`.5`) consume the verdicts recorded here
rather than the feature's prose.

---

### C-9 — effect replay

**Verdict: the named mechanism is landed. The residual the feature planned to
reproduce as a failure has ALSO been fixed. C-9's reproduction task inherits a
pin, not a defect.**

#### What the ledger says

`doc/spec/90-roadmap/91-gap-analysis.md:18` (the C-9 row) still reads:

> Decided rule ([93 I-7](93-feature-interactions.md)): `Effectful` sinks
> journal a processed frontier and replay suppresses re-driving them; **code
> diverges; fix pending**

#### What is in the code at `46ed020`

The processed-frontier exists, is durable, and is consulted:

- `interface Effectful` — `kernel/src/main/kotlin/civictech/cell/evolve/Evolution.kt:29`.
- `FrontierRecord(cellRef, portName, timestamp)`, KDoc "G-59, fixes C-9; spec
  20/24, 30/31, 50/52" — `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:40,43`.
- `processedFrontier: MutableMap<Pair<CellRef, String>, MutableMap<UUID, Long>>`
  — `HostDurability.kt:167` (KDoc `:161-163`).
- `alreadyProcessed(cellRef, portName, timestamp)`, the at-or-behind test
  `(processedFrontier[cellRef to portName]?.get(timestamp.sourceId) ?: -1L) >= timestamp.counter`
  — `HostDurability.kt:426-427` (KDoc `:422`).
- `advanceAndJournalFrontier(cellRef, portName, timestamp)` —
  `HostDurability.kt:442-444`.
- Frontier folded into the checkpoint payload and restored on recovery —
  `HostDurability.kt:319, 389`; single-record restore at `:412`.
- The `Effectful` inlet guard in `ManagedHost`'s PORT_API branch: contextless
  refusal at `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:984`
  (`[24-DUR-06]`), suppression test at `:1005-1006`, post-delivery advance at
  `:1047, 1068`.

**Drift from the feature's citations**: `computenet-umx.1` §0 cites
`ManagedHost.kt:770-820` and `:785-815` for this block; at `46ed020` it sits
at `:984-1068`. The sibling kernel-lane audit
(`doc/kernel-lane-findings.md`, recorded by `computenet-yh6.1.1.1` at base
`37a7f1c`) already cited `:797-859`, so this block has moved twice. Cite by
symbol.

Normative markers all read "resolved":
`doc/spec/20-dataflow-semantics/24-data-cells.md:816` ("G-59 resolved, W2.6,
closes C-9"), `doc/spec/30-execution-model/31-hosts.md:101` ("G-59 resolved in
part, W2.6, closes C-9"),
`doc/spec/50-development-process/52-verification.md:168` ("processed-frontier
implemented"). Conformance: `concord/corpus/15-durability/DUR-REPLAY-01.yaml`
(`covers: [24-DUR-01, 24-DUR-02, 24-DUR-05]`).

#### The residual the feature planned to reproduce — now closed

`computenet-umx.1` §0 and BS-4 planned a standing **expected failure** for the
journaled-source double-fire: the frontier keys on
`MessageContext.timestamp.sourceId`, which a producing `FanOutlet` minted with
`UUID.randomUUID()`, so a journaled source's replayed re-emission carried a
fresh `sourceId` the sink's restored frontier could not match. The feature
cites `concord/corpus/DISPUTES.md:511` for it.

At `46ed020` that entry has been rewritten. The heading now sits at
`concord/corpus/DISPUTES.md:522` and reads:

> ### The boundary (`kernel-gap` / design ceiling, G-59 / C-9) — RESOLVED for the journaled-source double-fire (KFX, commit `34892d9`)

The body states the fix: commit `34892d9` (`computenet-yh6.1.2`, "A recovered
outlet re-emits under replay-stable wave identity", PR #15) made a durable
outlet's `sourceId` ref-derived (`OutletWaveState.durable`,
`UUID.nameUUIDFromBytes`) rather than random, installed at spawn for journaled
cells only (`HostDurability.installDurableEpochs`), and carries the outlet's
whole epoch — `sourceId` *and* counter high-water — across a crash. The
surface is `kernel/src/main/kotlin/civictech/cell/port/FanOutlet.kt:351`
(`waveState(): OutletWaveState`), `:358` (`adoptWaveState`), `:368`
(`mintFreshEpoch`).

**Consequence for `computenet-umx.1.3`**: BS-4 must be written **unweakened**
— the same journaled-source-into-effectful-sink construction the DISPUTES
entry describes — with **PASS as the accepted outcome**, and it must not
acquire an `@ExpectedFailure` annotation. Writing it as an expected failure
would now break the build the moment it runs (`[CHA2-44]`), and softening it
into something that still fails would be exactly the manufacture BS-13
forbids in the other direction.

**What is NOT closed**: `DISPUTES.md:579-584` says so explicitly — "The G-59 /
C-9 gap rows themselves are not closed by this." What `34892d9` retired is the
journaled-source double-fire only. G-59's row also spans non-deterministic
sources and partial-wave buffers; C-9's "effects re-fire" phrasing spans
paths `34892d9` never touched. Those stay open and are not this feature's.

#### The narrower residual: the baseline exemption

Still true at `46ed020`.
`kernel/src/main/kotlin/civictech/cell/link/CatchUp.kt:55` records, in KDoc
prose, that the `Effectful` frontier check does "**not** test `ctx.baseline`
— the only counter observer that does not exempt baselines", benign under
in-order arrival and reachable only where counters can regress in a lane.
Note the KDoc's own example of such a lane is "the landed-RESTART defect
C-12" — which is itself adjudicated closed (below), which narrows the
reachability argument further.

Two adjacent hazards named in the feature were also decided and implemented
since: the baseline-exemption decision by `computenet-yh6.1.3.4` and
crash-stable ingress identity by `computenet-yh6.1.3.5`.

**Consequence for `computenet-umx.1.3`**: BS-5 is a **pin plus a recorded
answer**, per `[CHA2-14]`. Record what the frontier check actually does with a
baseline-stamped frame at or behind the restored frontier. Do not assume the
answer, and if the case turns out to be unreachable in-order, say so in this
file and write no test (`[CHA2-46]`) rather than constructing an artificial
counter regression to make one fire.

#### Owner of the remainder

KFX (epic `computenet-yh6.1`), whose only open child at the time of writing is
`computenet-yh6.1.5` — the epic-closing feature that consumes CHA2.

---

### C-11 — shadow suppression drops exclusives

**Verdict: the named mechanism is landed. Two of the three residuals the
feature recorded are still real at `46ed020` and are the suite's genuine
expected failures. The third was fixed by SEC1.**

#### What the ledger says

`91-gap-analysis.md:19` (the C-11 row):

> Decided rule (93 I-20): shadow suppression of exclusive-carrying contracts
> is a discharging sink — Owned taken, Leased released, obligations discharged
> exactly once; **code diverges; fix pending**

Three ⚠ CONFLICT (C-11) blocks still stand in the spec:
`doc/spec/10-programming-model/12-ports.md:155`,
`doc/spec/20-dataflow-semantics/23-ownership.md:139`,
`doc/spec/50-development-process/52-verification.md:145`.

#### What is in the code at `46ed020`

The discharging proxy is landed:

- `Shadow.spawn(host, cell)` — `Evolution.kt:62`, with the suppression guard
  `if (cell is Effectful) suppress(cell)` at `:64`.
- `Shadow.suppress(cell)` — `:69`; per-inlet `inlet.serve(suppressionProxy(inlet.clazz))`
  at `:85`; `suppressionProxy(clazz)` at `:88`.
- `Proxy.discharging(clazz)` — `kernel/src/main/kotlin/civictech/cell/proxy/Proxy.kt:101`,
  with `requireNotNull(ContractRegistry.descriptor(clazz))` at `:102-104` — the
  loud failure `[CHA2-24]`/BS-12 asserts, not a silent fallback to `noop`
  (`Proxy.kt:96`).
- `internal fun discharge(value)` — `Proxy.kt:123-134`: `Owned.take()` at
  `:125`, `Leased.release()` at `:126`, recursion through `Map` at `:127-130`,
  `Iterable` at `:131`, `Array` at `:132`.
- Reference test: `kernel/src/test/kotlin/civictech/cell/evolve/ShadowOwnershipTest.kt`
  (W1.2 exit test).

So the three ⚠ CONFLICT blocks are themselves stale prose — the same failure
mode D-C12 adjudicated for C-12. **Correcting them is documentation
maintenance and requires its own authorization; this file records the finding
and edits nothing** (`[CHA2-03]`, AGENTS.md).

#### Residual 1 — exclusive-bit reach. STILL REAL.

`Proxy.discharge` (`Proxy.kt:123-134`) recurses into `Map`, `Iterable` and
`Array` and **nothing else**. An `Owned` nested inside a plain data-class
payload is dropped undischarged by a proxy that believes it discharged. The
widening is decided and unimplemented — `doc/spec/10-programming-model/12-ports.md:167-168`:

> The exclusive bit's KSP scan is decided to widen (decided in 93 I-6 and
> I-8, unimplemented).

**Consequence for `computenet-umx.1.4`**: BS-8 (`[CHA2-21]`) stands as a
genuine `@ExpectedFailure`, anchored to `12-ports.md:167-168` and to this
entry. Feature risk 6 stands too: if KSP refuses the nested-exclusive
`@Contract` shape, the honest outcome is a `DISPUTES.md` entry
(`[CHA2-46]`), not a weaker test.

#### Residual 2 — suppression granularity. STILL REAL.

`Shadow.spawn` suppresses only `if (cell is Effectful)` (`Evolution.kt:64`),
so an effect-carrying *contract* on a non-`Effectful` cell is shadowed with no
suppression at all. `91-gap-analysis.md:107` (G-32 row) records the decision
and the divergence in its own words: suppression "cuts at the
`@Contract(effect=true)` boundary contracts, the cell-level marker demoted to
a coarse fallback … (landed cell-granularity NoOp diverges)" (93 I-17).

**Consequence for `computenet-umx.1.4`**: BS-9 (`[CHA2-22]`) stands as a
genuine `@ExpectedFailure`.

#### Residual 3 — boundary-denial silent drop. FIXED by SEC1.

`computenet-umx.1` §0 records, from the KHYG epic, that every `BoundaryPolicy`
denial was a bare `return null` at `MediateProxy.kt:42,52-57` and
`CompositeCell.kt:205-215,229-249`, discharging no `Owned`/`Leased` while the
KDoc claimed "dead-lettered".

At `46ed020` that is fixed. `kernel/src/main/kotlin/civictech/cell/BoundaryDenials.kt`
exists and carries `dischargeRefusedArgs(deniedArgs)` at `:317`, the
`deny(...)` entry point at `:281`, and the `BoundaryDenialReporter` seam at
`:193-194`; `MediateProxy.kt:131` passes `deniedArgs = refused` through it;
`CompositeCell.kt:107` owns a `BoundaryDenials` per composite (`:96` KDoc).
Landed by `ab69412` ("Exclusive payloads are discharged exactly once on every
BoundaryPolicy denial path", `computenet-usd.2`), extended by `1b9653b` (the
wire crossing consults the `Exposure`'s `BoundaryPolicy`) and by the base
commit `46ed020` itself (`mediateOutlet` exactly-once discharge under
disclosure Deny, `computenet-e5mn`).

**Consequence for `computenet-umx.1.5`**: BS-10 (`[CHA2-23]`) is written
**unweakened**, with **PASS as the accepted outcome** and no
`@ExpectedFailure` annotation — same reasoning as BS-4. Its value is now
regression protection for SEC1's fix, and its cross-reference to KHYG stays.

#### The behavioural baseline is unchanged

The ADMIT drop tier does sanitize — `kernel/src/main/kotlin/civictech/cell/port/InletPolicy.kt:110`
(`invocation.args.forEach(Proxy::discharge)`, "T05 finding 3" commentary at
`:103`), which is why `Proxy.discharge` was promoted from `private` to
`internal` (`Proxy.kt:117-122`). BS-11 (`[CHA2-25]`) pins it, establishing
that "denied ⇒ discharged" is the kernel's own standard.

#### Owner of the remainder

Residuals 1 and 2: KFX, or whoever carries the 93 I-6/I-8 exclusive-bit
widening and the 93 I-17 contract-granularity suppression. Neither is CHA2's
to fix (`[CHA2-50]`).

---

### C-12 — RESTART aliasing

**Verdict: genuinely closed, adjudicated D-C12. No reproduction will be
written — not a failing one, not an expected-failure one, not a "records the
divergence" one.** (`[CHA2-30]`, BS-13.)

This is the one of the three that means "nothing to reproduce", and it is
recorded here so that a later reader or ticket asserting C-12 is still
divergent meets counter-evidence rather than having to re-derive the argument
(`[CHA2-32]`).

#### The evidence, all checked in and all re-read at `46ed020`

- **The gap row itself.** `doc/spec/90-roadmap/91-gap-analysis.md:20` strikes
  the divergence claim through and calls it "**stale**, transcribed from the
  M3.5 prose rather than the code (adjudicated D-C12)"; the row's status cell
  reads "**Resolved (W2.1 core, adjudicated + conformance-covered D-C12)**".
- **The gap-analysis footnote.** `91-gap-analysis.md:115` says it outright:
  C-9..C-12 record where landed code diverges "except C-12, which on
  adjudication (D-C12) turned out to record where the *prose* had gone stale
  rather than where the code diverged."
- **The adjudication ticket.** `doc/spec/90-roadmap/99-defects-engines-plan/tickets/D-C12.md`.
- **The corrected spec sites.**
  `doc/spec/20-dataflow-semantics/21-propagation.md:250` ("Conflict C-12
  resolved, W2.1 + D-C12 — the core landed, the residuals stayed"),
  `doc/spec/20-dataflow-semantics/22-consistency.md:108` ("C-12 resolved, W2.1
  + D-C12"), `doc/spec/20-dataflow-semantics/24-data-cells.md:693`
  ("Implemented, W2.1; C-12 resolved in D-C12"), and
  `24-data-cells.md:638-642`, where the ⚠ EARS-GAP that once stood there is
  replaced by "**Answered (D-C12): the suspicion was right, for
  `[24-TAG-02]`**".
  **Drift**: the feature cites `24-data-cells.md:640,693-694`; at `46ed020`
  the literal "C-12 resolved" token appears at `:693` only, and the `:640`
  site names D-C12 without the C-12 token. Grep for `D-C12`, not for `C-12`,
  when re-verifying that site.
- **Conformance coverage.** `concord/corpus/21-propagation/21-REBASE-01.yaml`,
  `covers: [21-REBASE-01]` at `:15`, green at `doc/spec/CONCORDANCE.md:76`
  (`| 21-REBASE-01 | 21-REBASE-01 | covered |`). CHA2 cites this and authors
  no duplicate scenario (`[CHA2-31]`).
- **The honesty ledger.** `concord/corpus/DISPUTES.md:360`:
  "### `21-REBASE-01` / `15-RESTART-01` — **RESOLVED (D-C12,
  `driver-surface-gap` + `spec-stale`)**".

The mechanism, for the record: the supervision RESTART branch bumps the
host-held generation, mints a fresh per-epoch `sourceId` on every outlet
(collecting the superseded ids), restores the checkpoint, and emits
`ReBaseline(supersedes, supersede = true)` over the ordinary catch-up path;
`TagState.applyReBaseline` drops un-reasserted tags of superseded sources and
fences them as dead lanes.

#### What is explicitly NOT closed: G-43 and G-42 are out of scope (`[CHA2-33]`)

D-C12 left two residuals open. **They are not C-12, they are not resolved, and
they are out of scope for CHA2 — no reproduction, no pin, no coverage claim.**
They belong to their own G-items:

- **G-43** — `91-gap-analysis.md:83`. The freshest-checkpoint tiers (the
  landed restore is the local supervision-time checkpoint, I-22's own
  degenerate case) and the pull-merge direction (`supersede` is always
  `true`). Also 93 I-25.
- **G-42** — `91-gap-analysis.md:65`. Epoch/generation reclamation: epoch
  source-ids and restart generations accrete unboundedly.

The C-12 row's own status cell states this in the same words:
"**Residuals unchanged, and NOT closed by this row**".

#### The deliverable

`kernel/src/test/kotlin/civictech/cell/repro/C12AdjudicationRecordTest.kt` — a
**documentation-of-record** test, not a reproduction. It asserts over the
checked-in artifacts listed above (the gap row's Resolved marker, `D-C12.md`,
`21-REBASE-01.yaml` and its `covers:` id, the `DISPUTES.md` RESOLVED heading,
and this file's naming of G-43/G-42 as out of scope), and it asserts that the
`repro` package contains no C-12 reproduction. It exercises no kernel
behaviour, by design: there is no divergence to exercise.

It lives in `:kernel`'s test source set with the rest of the suite
(`[CHA2-05]`) because the PN-2 baseline seam
`ManagedHost.replayAsBaseline` is `internal` (`ManagedHost.kt:640`) and
unreachable from `:testkit`. **That visibility is not to be widened to move
the suite** (CHA1 risk 3).

---

### CHA1's rig does not exist, and five CHA2 clauses are gated on it

**Finding: `computenet-umx` (CHA1) has shipped no rig, and has no children
that would build one.** Verified at `46ed020`:

- No `testkit/src/main/kotlin/civictech/testkit/dst/` directory. `:testkit`'s
  entire main source set is `AwaitDrained.kt`, `AwaitUntil.kt`,
  `ForEachSeed.kt`, `HttpProbe.kt`, `JvmPeer.kt`, `SimWorld.kt`,
  `SseProbe.kt`.
- `grep -rn 'FaultPlan\|CrashFault\|JournalFault\|RestartAtFrontierFault\|DstRun\|DstReplay\|testkit.dst' --include='*.kt' .`
  returns **zero matches** across the repository.
- Epic `computenet-umx` has exactly two children, both *consumers* of the rig:
  `computenet-umx.1` (CHA2) and `computenet-umx.2` (CHA3). No rig-building
  child is filed.

Every CHA2 clause naming a `[CHA1-nn]` deliverable is therefore unsatisfiable
as written today. Specifically gated:

| Clause | Needs | BS |
|---|---|---|
| `[CHA2-11]` | `RestartAtFrontierFault`'s arbitrary-journal-prefix sweep (`[CHA1-21]`) | BS-2 |
| `[CHA2-12]` | Independent processed-frontier rollback (`[CHA1-22]`) — the verdict CHA1's BS-11 defers to CHA2 | BS-3 |
| `[CHA2-15]` | `JournalFault` mutations (`[CHA1-19]`, `[CHA1-20]`) | BS-6 |
| `[CHA2-47]` | Seed capture / pinning / seed-invariant shrink (`[CHA1-30]`…`[CHA1-40]`, `[CHA1-35]`, `[CHA1-38]`) | BS-17 |
| `[CHA2-26]` **strict form** | CHA1's exclusive-payload accounting (`[CHA1-53]`) failing the run through the rig's own check rather than a bespoke assertion | BS-7..BS-12 |

`[CHA2-26]`'s *weak* form — a C-11 reproduction asserting exclusive
discharge through its own bespoke assertions — is satisfiable today and is
what `computenet-umx.1.4`/`.5` implement. Its strict form is not, and the
distinction must not be blurred: a bespoke assertion that happens to catch
one drop is not the rig-wide accounting the clause asks for.

CHA2 must not build any of this itself (`[CHA2-04]`; CHA1 §6 owns the scope
split). The gated work is parked on `computenet-umx.1.6`, which stays blocked.

**Scheduling decision of record** (orchestrator, unattended `/work` session,
2026-08-16; recorded on `computenet-umx.1`): proceed on tasks `.1`-`.5` plus
`.7`, leaving `.6` blocked pending CHA1's rig. The explicit consequence,
recorded so no later reviewer reads it as an oversight: **CHA2 cannot meet its
own acceptance criteria this way**, since the criteria require every listed
requirement id to be realized by a named test and the five clauses above will
not be. The feature does not close on `.1`-`.5`+`.7` landing; a feature review
should return DRAFT on that basis alone rather than treating the gap as met.

---

### Consequences for the plan that this lane reports and does not act on

Three planning statements are stale as of `46ed020`. CHA2 records them here
and edits nothing (`[CHA2-03]`, milestone plan §6):

1. **The C-9 gap row** (`91-gap-analysis.md:18`, "code diverges; fix pending")
   — the frontier is landed, and the recorded G-59/C-9 journaled-source
   residual is itself resolved by `34892d9`.
2. **The C-11 gap row** (`:19`, "code diverges; fix pending") and the three ⚠
   CONFLICT (C-11) spec blocks — the discharging proxy is landed; what remains
   is reach (nested exclusives) and granularity (contract vs cell), both
   already acknowledged in spec text as decided-and-unimplemented.
3. **The KFX plan row** ("C-9 fix + minimal G-59 processed-frontier for
   `Effectful`") — describes work already in `main`. Re-scoping KFX is a
   planning call, not this lane's.

### Disposition

Report, do not edit. This task's diff touches this file and
`kernel/src/test/kotlin/civictech/cell/repro/C12AdjudicationRecordTest.kt`,
and nothing else. No `doc/spec/**` edit, no `CONCORDANCE.md` edit, no
`91-gap-analysis.md` edit, no plan-document edit, no concord scenario or
schema change, no kernel main-source change.

---

## `computenet-umx.1.5` — BS-11 baseline pin + BS-10 boundary-denial reproduction (denied ⇒ discharged)

Recorded by: `computenet-umx.1.5` (feature `computenet-umx.1` — CHA2). Realizes
`[CHA2-25]` (BS-11) and `[CHA2-23]` as adjudicated above (BS-10). Deliverable:
`kernel/src/test/kotlin/civictech/cell/repro/DenialDischargeReproTest.kt`. Base
commit unchanged from the rest of this file: `46ed020`.

### BS-10's routing record

The boundary-denial silent drop this task's own item was originally filed to
reproduce as an `@ExpectedFailure` — the KHYG finding recorded above under
"C-11 — Residual 3 — boundary-denial silent drop" (every `BoundaryPolicy`
denial was a bare `return null` at `MediateProxy.kt:42,52-57` and
`CompositeCell.kt:205-215,229-249`, discharging no `Owned`/`Leased` while the
KDoc claimed "dead-lettered") — **is FIXED on `main`**, exactly as that
section already adjudicates: commit `ab69412` (`computenet-usd.2`,
"Exclusive payloads are discharged exactly once on every `BoundaryPolicy`
denial path"), extended by `1b9653b` (the wire crossing consults the
`Exposure`'s `BoundaryPolicy`) and by this task's own base commit `46ed020`
(`mediateOutlet` exactly-once discharge under disclosure Deny). Re-verified
independently by this task, not merely inherited: `DenialDischargeReproTest`'s
BS-10 test constructs its own hosted, mediated `CompositeCell` boundary
(`BoundaryDenialMembrane`) and denies a badly-signed `SignedDelta` carrying an
`Owned`, and it passes.

Per that adjudication, BS-10 is written **unweakened, with PASS as the
accepted outcome, and carries no `@ExpectedFailure` annotation**. Annotating a
passing reproduction would itself break the build the moment it ran
(`[CHA2-44]`), and softening it to manufacture a failure matching the
feature's 2026-08-08 prose is exactly what the no-manufactured-failure
principle (BS-13, AGENTS.md) forbids in the other direction. If a later change
reopens the drop, this test fails honestly and the annotation is added then —
not pre-emptively now.

### Citation, not duplication

`kernel/src/test/kotlin/civictech/cell/membrane/MediateProxyIntegrityTest.kt`
(landed with `ab69412`) already asserts the **identical shape** BS-10 names —
a hosted, mediated `CompositeCell` boundary whose `BoundaryPolicy` denies an
inbound invocation carrying an `Owned`, with the refused payload frozen and
dead-lettered exactly once (`BS-5 an integrity refusal freezes an Owned
carried inside the SignedDelta envelope exactly once`), plus its `Leased`
twin immediately below it (`BS-5 an integrity refusal releases a Leased
carried inside the envelope exactly once, back to its pool`), plus an
unattached-sink variant and a no-sink-at-all variant further down the same
file.

`DenialDischargeReproTest`'s own BS-10 test is the evidence lane's
independent pin of that same fact — kept in its own suite, under its own
package, so the fixing lane's tests and the reproduction lane's tests stay
separately verifiable rather than one silently standing in for the other —
and it deliberately does **not** re-derive the `Leased`, unattached-sink, or
no-sink-at-all variants: `MediateProxyIntegrityTest` already covers all three
exhaustively, and duplicating them here would be exactly the kind of
manufactured redundancy this lane's citation discipline (`[CHA2-31]`'s C-12
precedent, above) exists to avoid.

### BS-11's baseline

The ADMIT tier (`kernel/src/main/kotlin/civictech/cell/port/InletPolicy.kt:110`,
`Admit.offer`, "T05 finding 3") and the dead-letter capture path
(`kernel/src/main/kotlin/civictech/cell/host/DeadLetters.kt:231`,
`sanitizeForDeadLetter`) both already treated "dropped/denied ⇒ discharged" as
the kernel's own standard, predating SEC1's fix entirely — this is the
baseline BS-10 above is now shown to meet too.

`kernel/src/test/kotlin/civictech/cell/port/AdmitDischargeTest.kt` already
pins the ADMIT tier's `Owned` discharge (`a dropped Owned-carrying invocation
is discharged, not leaked`), but it does not carry a `Leased` in the same
invocation, does not install `onDrop` at all (so never asserts that "the drop
is counted in the tier's own accounting" — BS-11's literal wording), and does
not touch the dead-letter path. No existing test in the repository, checked
by `git grep -n "sanitizeForDeadLetter\|Redacted(\"" -- kernel/src/test`,
routes a genuine *fault* (as opposed to a boundary denial) carrying a live
`Owned`/`Leased` through `DeadLetters.sanitizeForDeadLetter`:
`LifecycleAndDeadLetterTest`'s throwing-cell tests use plain payloads, and
`MediateProxyIntegrityTest`'s BS-5 pair captures a boundary *denial*
(`DeadLetters.boundaryDenial`), a different sink entirely from the per-fault
capture (`DeadLetters.deadLetter`) BS-11 asks about.

`DenialDischargeReproTest`'s two BS-11 tests fill exactly that gap:

- one ADMIT-tier drop — `Owned` and `Leased` in a single invocation, both
  discharged, with `onDrop` counting the drop;
- one dead-lettered fault — routed through `ManagedHost.enqueueHostedInvocation`
  directly, not `host.routerInlet.call.route(...)`. The latter was tried
  first and verified empirically (not merely assumed) to dispatch through
  `routerInlet`'s raw management-shortcut path (`invocation.invoke()` inside
  the host's bare `enqueue()`, `ManagedHost.kt:1420-1425`), which never
  attaches a `HostedPortInvocation` to the resulting dead letter — the
  captured `DeadLetter.invocation` came back `null` even though the fault
  itself, cause and description, was reported correctly. Building the
  `HostedPortInvocation` directly and calling `enqueueHostedInvocation` routes
  through the ordinary attention-staged dispatch into `ManagedHost.deliver`'s
  own fault catch, which does attach it — the only path that lets
  `sanitizeForDeadLetter`'s per-argument capture (`Owned` → `Frozen`, `Leased`
  → `Redacted`) be asserted at all.

### `[CHA2-26]` deviation, as adjudicated above

No rig code, no CHA1 exclusive-payload accounting through a rig's own check —
both BS-11 tests and the BS-10 test assert discharge directly (a second
`take()`/`release()` throwing `IllegalStateException` is the observable), the
weak form this task and `computenet-umx.1.4` implement, per the strict-form
gate recorded above ("CHA1's rig does not exist").

### Disposition

Report, do not edit. This task's diff touches this file and
`kernel/src/test/kotlin/civictech/cell/repro/DenialDischargeReproTest.kt`, and
nothing else. Zero kernel main-source changes ([CHA2-50]). No SEC1 fix
specification or requirement restatement — the fix is `computenet-usd.2`'s and
stays owned there; this entry only pins and independently cites it.

---

## `computenet-umx.1.4` — C-11 reach: BS-7/BS-12 pinned, BS-8/BS-9 standing as expected failures

Recorded by: `computenet-umx.1.4` (feature `computenet-umx.1` — CHA2). Realizes
`[CHA2-20]` (BS-7) and the C-11 half of `[CHA2-02]`, `[CHA2-21]` (BS-8),
`[CHA2-22]` (BS-9), `[CHA2-24]` (BS-12); records the `[CHA2-26]` deviation
again from this task's side. Deliverables:
`kernel/src/test/kotlin/civictech/cell/repro/ExclusiveDischargeReproTest.kt`
and its fixtures
`kernel/src/test/kotlin/civictech/cell/repro/ReproContracts.kt`.

**Base commit `0169fd7`** (`feature/computenet-umx.1`, which merges
`computenet-umx.1.1`, `.1.2` and `.1.5` onto `main` at `46ed020`). Every
citation below was re-read at that commit by this task.

### The adjudication was re-verified, not inherited

`computenet-umx.1.1`'s C-11 verdict above is the input to this task, and the
task's own instruction was to check each direction against the code before
choosing it. Re-verified at `0169fd7`:

- **BS-7 / BS-12 — the landed core.** `Shadow.spawn` → `suppress(cell)` →
  `suppress(inlet)` → `suppressionProxy` (`Evolution.kt:62`, `:69`, `:84`,
  `:88`), and `Proxy.discharging`'s `requireNotNull(ContractRegistry.descriptor(clazz))`
  (`Proxy.kt:101-104`). Both reproductions **pass**, and neither carries an
  annotation.
- **BS-8 — still genuinely divergent.** `Proxy.discharge`'s `when` at
  `Proxy.kt:123-134` still has exactly `Owned`, `Leased`, `Map`, `Iterable`,
  `Array` and no case for a plain payload object.
- **BS-9 — still genuinely divergent.** `Evolution.kt:64` is still literally
  `if (cell is Effectful) suppress(cell)`.

No contradiction with the recorded adjudication was found, so nothing is
disputed here. What this task *adds* to the record is the second, independent
half of BS-8's mechanism, which the adjudication did not name: the divergence
is **two cooperating layers**, not one.

### BS-8 is two layers, and the reproduction pins both

`ContractProcessor.carriesExclusive`
(`gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt:70-73`) tests the
parameter's own declaration against `EXCLUSIVE_MARKERS` and then recurses
through `type.arguments` — **type arguments only**. An `Owned` reached through
a *field* of a data-class parameter is therefore never marked on the generated
`MethodDescriptor.exclusive` at all, quite apart from `Proxy.discharge`'s
missing branch. The consequence for whoever fixes this is that **neither half
alone closes it**: widening only the KSP scan marks the method exclusive and
hands the envelope to a `discharge` with no branch for it; widening only
`discharge` never gets called, because the method is not marked. Both
assertions were verified to fail independently, by running the signature block
in both orders (2026-08-16) — the behavioural one is placed first in the test
because it is the one that stays failing until both layers land.

The reproduction's contract (`ReproNestedExclusivePush`) is deliberately
two-method: a bare-`Owned` `pushDirect` alongside the nested `pushNested`. The
direct method makes `descriptor.methods.any { it.exclusive }` true, so
`suppressionProxy` (`Evolution.kt:88-93`) selects `Proxy.discharging` and not
`Proxy.noop` — so the escape is observed crossing a **discharging** sink,
which is the shape `[CHA2-21]` names, rather than crossing a proxy that was
never going to discharge anything. Both assertions that are not the divergence
(the direct method *is* marked exclusive; the sink's own handler did **not**
run) sit outside the signature block, so a regression in either reddens the
build honestly instead of being absorbed as "expected".

**Feature §9 risk 6 did not materialize.** KSP accepted the nested-exclusive
contract shape without complaint — `exclusiveRequiresKey`
(`ContractProcessor.kt:131-147`) does not fire, precisely because
`carriesExclusive` cannot see the nested exclusive. The `[CHA2-46]`
`DISPUTES.md` fallback was therefore **not** triggered and
`concord/corpus/DISPUTES.md` is deliberately left untouched: that file is the
concord corpus's honesty ledger for requirement-id coverage, and an entry
recording a dispute that did not happen would be noise on a single-writer
surface.

### BS-9's marker, verified by symbol

The effect-carrying marker is `@Contract(effect = true)`
(`nature/src/main/kotlin/civictech/gen/wire/Contract.kt:22-26`, "Marks a
world-touching boundary that shadow execution must suppress"), surfaced as
`ContractDescriptor.effect`
(`nature/src/main/kotlin/civictech/nature/ContractDescriptor.kt:31`) and
populated at `ContractProcessor.kt:369`. There is no other effect marker in
`nature/` or `gen/`. **Nothing in `kernel/` reads that bit** — checked by
`git grep` over `kernel/src/main` — which is the mechanical form of G-32's
"landed cell-granularity NoOp diverges". The reproduction asserts the
descriptor bit is set *outside* its signature block (if the fixture ever stops
being effect-carrying the reproduction has lost its subject and must fail
loudly, not "as expected") and asserts the absence of the side effect inside
it.

### Owners filed: one bead per residual

Judgment recorded as the ticket asks: **two beads, not one**. The residuals
share the C-11 gap row and nothing else — different decisions (93 I-6/I-8 vs
93 I-17), different code sites (`Proxy.discharge` plus the KSP scan vs
`Shadow.spawn`), and either can land without the other, so a single bead would
be unclosable until both did.

- `computenet-ulss` — "Widen the exclusive bit and `Proxy.discharge` reach to
  nested exclusives (93 I-6/I-8, C-11 residual 1)". Owner of BS-8.
- `computenet-3jv2` — "Shadow suppression cuts at `@Contract(effect=true)`,
  not at `Cell is Effectful` (93 I-17/G-32, C-11 residual 2)". Owner of BS-9.

Neither is KFX's: `computenet-yh6.1` is scoped to C-9 and its closing feature
names C-11 out of scope, as the adjudication above records. Both beads carry
the instruction that the fix must **remove** the `@ExpectedFailure` annotation
and keep the test — `[CHA2-44]` makes the build go red the moment either
reproduction starts passing, which is the mechanism by which a reproduction
becomes the fixing lane's acceptance test.

### Citation, not duplication

`kernel/src/test/kotlin/civictech/cell/evolve/ShadowOwnershipTest.kt` (the
W1.2 exit test) already covers the landed BS-7 core, and this suite does not
rewrite it: BS-7 here is the evidence lane's own pin, under its own fixtures
in its own package, so the two suites stay independently verifiable — the
discipline `computenet-umx.1.5` applied to `MediateProxyIntegrityTest`. What
BS-7 adds beyond the exit test is the *exactly once* half stated as such: a
lease's `returnToPool` callback counts one release and no more, alongside the
second-`take()`/`release()` evidence the exit test already carries.

The fixtures are likewise not reused from `ShadowOwnershipTest` — a change to
the landed exit test must not be able to silently reshape a reproduction, or
the reverse.

### `[CHA2-26]` deviation, as adjudicated

Unchanged from the "CHA1's rig does not exist" section above and from
`computenet-umx.1.5`'s entry: there is no `civictech.testkit.dst` on `main`, so
`[CHA1-53]`'s exclusive-payload accounting cannot be the detector. Every
assertion in this suite observes the payload directly instead — a second
`take()`/`release()` throwing `IllegalStateException`, and a lease's own
`returnToPool` counter. When CHA1's rig lands it should **adopt** these tests
rather than re-derive them.

### One observation, recorded and not tested

`Shadow.suppress` reaches `Proxy.discharging` only via `suppressionProxy`'s
`ContractRegistry.descriptor(clazz)?.methods?.any { it.exclusive } == true`
(`Evolution.kt:88-93`). For a contract with **no** generated descriptor the
elvis short-circuits to `false`, so the shadow path silently selects
`Proxy.noop` and never reaches BS-12's loud `requireNotNull`. Whether that is
a divergence depends on whether a descriptor-less contract on a hosted inlet
is reachable at all, which this task did not establish. It is recorded here as
an observation rather than reproduced either way: manufacturing a failure from
an unadjudicated reading is exactly what BS-13 forbids.

### Disposition

Report, do not edit. This task's diff touches this file,
`kernel/src/test/kotlin/civictech/cell/repro/ExclusiveDischargeReproTest.kt`
and `kernel/src/test/kotlin/civictech/cell/repro/ReproContracts.kt`, and
nothing else. Zero kernel main-source changes (`[CHA2-50]`), zero `gen/`
changes, no `doc/spec/**` edit, no `91-gap-analysis.md` edit, no
`CONCORDANCE.md` edit, no plan-document edit, no concord scenario or schema
change, and no `DISPUTES.md` entry (its trigger did not fire, above). Two
tracker items created (`computenet-ulss`, `computenet-3jv2`); no other bead
touched.

---

## `computenet-umx.1.3` — C-9 reproductions: BS-1 and BS-5 pinned, BS-4 unweakened and passing

Recorded by: `computenet-umx.1.3` (feature `computenet-umx.1` — CHA2). Realizes
`[CHA2-10]` (BS-1), `[CHA2-13]` (BS-4), `[CHA2-14]` (BS-5), `[CHA2-51]`, and the
C-9 half of `[CHA2-02]`. Deliverable:
`kernel/src/test/kotlin/civictech/cell/repro/EffectReplayReproTest.kt`, plus the
`[CHA2-51]` extension of the `concord/corpus/DISPUTES.md` G-59/C-9 boundary entry.

**Base commit `bf18284`** (`task/computenet-umx.1.3`, cut from
`feature/computenet-umx.1`, which merges `computenet-umx.1.1`, `.1.2`, `.1.4` and
`.1.5` onto `main` at `f73fb7f`). Every citation below was re-read at that commit
by this task. Line numbers drift; symbol names are the durable anchors.

### The adjudication was re-verified, not inherited

`computenet-umx.1.1`'s C-9 verdict above is this task's input, and each of its
three directions was checked against the code before being acted on:

- **The frontier is landed** — `HostDurability.processedFrontier`,
  `alreadyProcessed` (the at-or-behind test), `advanceAndJournalFrontier`,
  `FrontierRecord` (KDoc "G-59, fixes C-9"), folded into the checkpoint payload
  and restored on recovery. The `PORT_API` branch of `ManagedHost.deliver`
  consults it and advances beside the delivery. BS-1 pins it and **passes**.
- **The journaled-source double-fire is fixed** — `installDurableEpochs` puts a
  journaled cell's outlets on `OutletWaveState.durable(outlet.ref)`
  (`UUID.nameUUIDFromBytes`, not `randomUUID`), and `restoreOutletWave` carries
  the epoch across compaction. BS-4 therefore **passes**, unweakened and
  unannotated.
- **The baseline exemption is decided, not open** — the `CatchUp.kt` KDoc still
  records the `Effectful` frontier check as "the only counter observer that does
  not exempt baselines", and that is now the *decided* rule rather than a hazard:
  `computenet-yh6.1.3.4` landed `[24-DUR-07]`/`[24-DUR-08]` (spec 24 §Effectful),
  whose rule (3) is that a PN-2 replay-baseline keeps `[24-DUR-05]` verbatim.

No contradiction with the recorded adjudication was found, so nothing is disputed
here.

### BS-5's answer, which `[CHA2-14]` asks to be recorded rather than assumed

**Recorded answer: the frontier check does not exempt a baseline, and that is the
decided behaviour.** A replayed frame arriving at an `Effectful` inlet at or
behind the restored frontier is suppressed even though PN-2 has stamped it
`MessageContext.baseline`; a replayed frame *ahead* of the frontier — journal tail
the sink never acted on — fires. What `[24-DUR-07]` changed is the other half: a
baseline the sink does act on records its exact `(sourceId, counter)` in the
sink's own discharged-baseline state (`recordAndJournalBaselineDischarge`) instead
of advancing the wave-position frontier, because a baseline is anchored at the
stamped link-install event and advancing a high-water from it would swallow
genuine live frames below it.

The reproduction had to solve one evidence problem to state that honestly: a
suppression happens **before** delivery, so the sink's handler never runs and the
suppressed frame's context is not observable at the sink at all. Asserting only
"the effect did not re-fire" would leave the antecedent of `[CHA2-14]` unchecked —
the test would pass identically if the replayed frames had carried no baseline.
So a plain, non-`Effectful` `ContextRecorder` is co-hosted on the same journal and
driven under the *same* `(sourceId, counter)` as the sink: it has no frontier
guard, is replayed through the same `recoverFrom` staging where
`HostDurability.baselined` stamps every context-carrying frame, and records that
positions 1 and 2 did arrive baseline-marked. The sink suppressed them anyway.

`[CHA2-46]`'s "unreachable ⇒ write no test" escape did **not** apply: the case is
reachable through an ordinary crash and replay, with no manufactured counter
regression.

### BS-4 was expected to fail when CHA2 was filed, and passes

`computenet-umx.1`'s §0 and `[CHA2-13]` both prescribe a standing
`@ExpectedFailure` owned by KFX for this construction. It is not annotated,
because `@ExpectedFailure` fails the build when its body passes (`[CHA2-44]`) and
this body passes: commit `34892d9` landed between CHA2's filing and this task.
The dispatch's alternative disposition — tag it, record it, patch no kernel code
(`[CHA2-50]`) — was not needed. The construction itself is untouched: same
journaled source into an `Effectful` sink on the same host, same crash-and-replay,
no re-seeding and no narrowing (`[CHA2-47]`, BS-13).

Two assertions beyond "at most once" are deliberate, because "at most once" is
satisfied by firing **zero** times — the effect-loss direction that this file's
own `DISPUTES.md` neighbour records as having passed vacuously in `DUR-REPLAY-01`
until the computenet-61w amendment. Every reproduction here asserts a full
equality over the external effect log, and each adds a post-recovery delta that
must land.

### The reproductions discriminate — measured, not asserted

Both mutations were applied to `kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt`,
run, and reverted; the committed diff contains no kernel main-source change
(`[CHA2-50]`).

- `installDurableEpochs` neutered (early unconditional `return`): **BS-4 FAILED**
  `expected:<[1, 2, 3, 4, 5, 6, 7]> but was:<[1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7]>`
  — the exact double-fire the `DISPUTES.md` G-59/C-9 entry recorded. BS-1 and BS-5
  stayed green, which is right: their sources are not journaled cells.
- `alreadyProcessed` forced to `false`: **all three FAILED** — BS-1
  `expected:<[1, 2, 3, 4, 5, 6, 7]> but was:<[1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7]>`,
  BS-5 `expected:<[1, 2, 3]> but was:<[1, 2, 1, 2, 3]>`, BS-4 as above.

One detail of the second mutation is worth recording because it shapes what BS-1
asserts. Under it, BS-1's two *retransmitted* frames still did not re-fire, so the
log grew by seven rather than nine: with the frontier disabled, each replayed
frame fires, and because PN-2 marks it a baseline its firing records a
`[24-DUR-08]` discharge at that exact position — which then suppresses the
retransmit of the same position later in the same replay. In other words
`[24-DUR-08]`'s exact-position set partially masks a frontier regression. BS-1
therefore also asserts `effectfulSuppressionsDischarged shouldBe 9L`, which is
what proves all nine journaled frames (seven applied plus two undelivered
retransmits) really were replayed and really were suppressed by the frontier
rather than never reaching the guard.

### What "crash mid-drain" is realized as, and its limit

There is no fault-injection rig (`[CHA2-04]`, and this file's "CHA1's rig does not
exist" section), so a crash is the idiom the landed durability tests use: abandon
the `SimulationController` and build a fresh one and a fresh `ManagedHost` over the
same `InMemoryJournal` and the same `CellRef`s. "Mid-drain" is realized concretely
— `ManagedHost` journals a hosted frame synchronously at intake and delivers it on
a later scheduler task, so frames pushed and not drained sit in the WAL having been
acted on by nobody, and the abandoned controller never runs them.

BS-1's undelivered frames are **retransmits of positions 6 and 7**, not novel
positions. That is a real constraint on what BS-1 pins, stated here rather than
only in the test: a novel undelivered frame is journal tail that `[24-DUR-05]`
*requires* to fire on replay, so it would make BS-1's "external effect log
unchanged in size across replay" clause false against a correct kernel. The
journal-tail-fires direction is covered instead by BS-5's position 3, and the
arbitrary-prefix generalisation of it is BS-2 (`[CHA2-11]`), which is rig-gated
and not this task's.

### Citation, not duplication

`kernel/src/test/kotlin/civictech/cell/durability/` already holds the fixing
lane's exit tests for all three mechanisms — `EffectfulRecoveryTest` (the
frontier), `OutletWaveRecoveryTest`/`OutletHighWaterRecoveryTest` (`34892d9`'s
replay-stable identity) and `EffectfulBaselineGuardTest`
(`[24-DUR-07]`/`[24-DUR-08]`) — and this suite rewrites none of them. It is the
evidence lane's own pin, under its own fixtures in its own package, so the two
suites stay independently verifiable: a change to a fixing lane's exit test must
not be able to silently reshape a reproduction, or the reverse. The same
discipline `computenet-umx.1.4` applied to `ShadowOwnershipTest` and
`computenet-umx.1.5` to `MediateProxyIntegrityTest`.

What the reproductions add beyond those exit tests: BS-1 adds the mid-drain
retransmit and the suppression-count accounting; BS-4 adds nothing to
`OutletWaveRecoveryTest`'s mechanism but re-states it as CHA2's own unweakened
evidence for `[KFX-22]`; BS-5 adds the `ContextRecorder` observation of the PN-2
stamp on a frame the `Effectful` inlet suppressed, which no existing test makes
observable.

### `[CHA2-26]` deviation, as adjudicated

Unchanged from `computenet-umx.1.4`'s and `.1.5`'s entries: there is no
`civictech.testkit.dst` on `main`, so `[CHA1-53]`'s exclusive-payload accounting
cannot be the detector. Nothing in this suite carries exclusives, so the deviation
is inert here; detection is the in-process external effect log (`[KFX-24]`) plus
`SupervisionAccounting.effectfulSuppressionsDischarged`. No end-to-end external
exactly-once claim is made anywhere in this suite — that ceiling is 93 I-7's and
belongs to CON1.

### Verification

`./gradlew :kernel:test --tests 'civictech.cell.repro.EffectReplayReproTest' --rerun`
— `> Task :kernel:test` with no marker, 3 tests, 0 failures, 0 errors.
`./gradlew :kernel:test --rerun` — BUILD SUCCESSFUL, 1110 tests, 0 failures, 0
errors, newest JUnit XML timestamp `2026-08-16T12:58:37.120Z`.
`./gradlew :concord:test --rerun` — BUILD SUCCESSFUL, 254 `PASSED` lines, corpus
unchanged (no scenario added, no schema change).

### Disposition

Report, do not edit. This task's diff touches this file,
`kernel/src/test/kotlin/civictech/cell/repro/EffectReplayReproTest.kt` and
`concord/corpus/DISPUTES.md` (an **extension** of the existing G-59/C-9 boundary
entry with this suite's test ids, per `[CHA2-51]` — not a duplicate entry, and
retiring or narrowing nothing, which is `computenet-yh6.1.5`/`[KFX-23]`'s). Zero
kernel main-source changes (`[CHA2-50]`; the two mutations above were reverted),
no `doc/spec/**` edit, no `91-gap-analysis.md` edit, no `CONCORDANCE.md` edit, no
plan-document edit, no concord scenario or schema change. No tracker item created;
no bead other than `computenet-umx.1.3` touched.
