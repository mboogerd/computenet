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

#### Residual 1 — exclusive-bit reach. WAS REAL; FIXED by `computenet-ulss`.

**As recorded at `46ed020`**: `Proxy.discharge` recursed into `Map`, `Iterable`
and `Array` and **nothing else**, and `ContractProcessor.carriesExclusive`
recursed through a type's *arguments* only. An `Owned` nested inside a plain
data-class payload was therefore never marked exclusive and, even where the
method was marked, was dropped undischarged by a proxy that believed it
discharged. The widening was decided and unimplemented —
`doc/spec/10-programming-model/12-ports.md`:

> The exclusive bit's KSP scan is decided to widen (decided in 93 I-6 and
> I-8, unimplemented).

**Now implemented** by `computenet-ulss`, both cooperating halves, because
either alone leaves the invariant violated (widening only the scan marks the
method and hands the payload to a `discharge` with no branch for it; widening
only `discharge` is never entered because the method is not marked):

- `gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt` —
  `carriesExclusive` also walks a payload declaration's **declared
  properties**, guarded by a fully-qualified-name `seen` set (so a
  self-referential payload terminates) and skipping `kotlin.*`/`java.*`
  declarations, which can only hold an exclusive as a type argument the
  existing argument walk already covers.
- `kernel/src/main/kotlin/civictech/cell/proxy/Proxy.kt` — `discharge` walks
  an arbitrary payload object's fields reflectively, with an **identity**
  `seen` set (aliased payloads discharged once, cycles terminate), not opening
  `Borrowed`/`Frozen` (non-consuming views, spec 23 §Taps) or platform
  classes, and swallowing per-field reflection failures rather than throwing
  out of a cleanup path.

Limits of that claim, stated here because they bound what the fix guarantees:
reach is *field-and-element* reach from the argument graph. An exclusive
reachable only through a computed getter with no backing field, or held by a
platform class the walk declines to open, is still not discharged, and an
exclusive reachable through a field the JVM refuses to make accessible is
skipped silently rather than reported.

Three further limits were **measured under review** (2026-08-16,
`computenet-ulss`). The first was fixed on this branch; the other two are
filed rather than fixed here, because each is a semantic decision rather than
a wording one:

- The two halves disagreed about `Borrowed`/`Frozen`. `Proxy.discharge`
  declines to open them (spec 23 §Taps, fan-out safe); `carriesExclusive` did
  not, so `tap(view: Borrowed<OwnedEnvelope>)` was marked **exclusive** where
  it was not before the widening — a new false positive on a fan-out-safe
  port. Filed as `computenet-yzsc` and **fixed here**: `carriesExclusive` now
  stops at `KernelFqn.NON_CONSUMING_VIEWS`, checked before the type-argument
  walk, and `ContractProcessorTest` pins both directions.
- A `kotlin.*`/`java.*` container that is not `Map`/`Iterable`/`Array` —
  `Pair`, `Triple`, `Result`, `Optional` — is marked exclusive by the scan and
  then skipped by the walk, so the exclusive is dropped by a proxy that
  believes it discharged. Filed as `computenet-woto`.
- Reach is also *over*-reach: any exclusive reachable through a non-payload
  reference an argument happens to hold is discharged too, and one already
  consumed throws out of the walk. Filed as `computenet-h6sf`.

**Consequence for `computenet-umx.1.4`'s BS-8** (`[CHA2-21]`): its body is
unchanged and its `@ExpectedFailure(signature = "CHA2-BS-8")` annotation is
removed — `[CHA2-44]` requires exactly that when a recorded expected failure
starts passing. `ExclusiveDischargeReproTest`'s BS-8 is now the acceptance
test for this fix. Feature risk 6 never materialized: KSP accepted the
nested-exclusive `@Contract` shape, so no `DISPUTES.md` entry (`[CHA2-46]`)
was needed.

#### Residual 2 — suppression granularity. FIXED by `computenet-3jv2`.

As adjudicated at `46ed020`: `Shadow.spawn` suppressed only
`if (cell is Effectful)` (`Evolution.kt:64`), so an effect-carrying *contract*
on a non-`Effectful` cell was shadowed with no suppression at all.
`91-gap-analysis.md:107` (G-32 row) records the decision and the divergence in
its own words: suppression "cuts at the `@Contract(effect=true)` boundary
contracts, the cell-level marker demoted to a coarse fallback … (landed
cell-granularity NoOp diverges)" (93 I-17).

`computenet-3jv2` implemented that cut. `Shadow.spawn` now reads
`if (cell is Effectful) suppress(cell) else suppressEffectContracts(cell)`:
`Shadow.suppressEffectContracts(cell)` NoOp-serves every `FanInlet` whose
`ContractRegistry.descriptor(inlet.clazz)?.effect == true`, and the cell-level
`Effectful` marker survives exactly as the coarse fallback the decision keeps —
it still suppresses every inlet, including ones whose contracts carry no effect
bit (and including contracts with no generated descriptor, which carry no bit
to read).

**Consequence for BS-9** (`[CHA2-22]`): the reproduction started passing, which
is `[CHA2-44]`'s deliberate red build. Its `@ExpectedFailure` annotation was
removed and the test kept, unweakened, as this fix's acceptance test — it is now
regression protection for the contract-granularity cut. The `withSignature`
wrapper stays, so a regression still fails carrying `CHA2-BS-9`. Evidence, from
the run against the unfixed code with the annotation removed:
`BS-9 a non-Effectful cell serving an effect-carrying contract is shadowed
without suppression() FAILED — ExpectedFailureSignal: CHA2-BS-9: Unexpected
elements from index 0, expected:<[]> but was:<["effect-1"]>`.

**Not fixed by `computenet-3jv2`**: residual 1 above (BS-8) is independent —
different decision (93 I-6/I-8), different code site (`Proxy.discharge` + the
KSP scan). It was still a standing expected failure when `computenet-3jv2` was
written; `computenet-ulss` has since fixed it (see residual 1 above), and the
two land together with no expected failure left standing.

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

Both residuals are now carried and fixed: residual 1 by `computenet-ulss`
(93 I-6/I-8 exclusive-bit widening, with `computenet-yzsc` correcting the
scan's reach over `Borrowed`/`Frozen`), residual 2 by `computenet-3jv2`
(93 I-17 contract-granularity suppression). Neither was CHA2's to fix
(`[CHA2-50]`); CHA2 reproduced them and handed them on.

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
retransmits) really were replayed and really reached the guard, rather than
never being replayed at all.

That counter is **origin-blind** — `ManagedHost.deliver` increments it on one
branch whose condition is `alreadyProcessed(...) || alreadyDischargedBaseline(...)`
— so on its own it does not attribute the nine suppressions to the frontier.
BS-1's *pair* of assertions does: nothing was discharged as a baseline before the
crash (the seven applied frames are live, `baseline == null`, so they advance the
frontier and record no discharge, and the two retransmits were never delivered so
they recorded nothing either), and any replayed frame that fired during recovery
would have appended to the external effect log, which the equality on the line
above forbids. Read the count alone and it is weaker than it looks; that is why
both assertions stand together.

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

---

## `computenet-umx.1.7` — CHA2 integration gate: suite green, ledger reconciled, hand-off for KFX (BS-16 end-to-end)

Recorded by: `computenet-umx.1.7` (feature `computenet-umx.1` — CHA2). Realizes
`[CHA2-50]`, `[CHA2-52]`, the end-to-end half of `[CHA2-45]` (BS-16 against real
reproductions, not the self-test stubs), and this hand-off. Non-goal, honoured:
no new reproductions, no `DISPUTES.md` retirement or narrowing (`computenet-yh6.1.5`'s
`[KFX-23]`), no unblocking of the rig-gated sweep sibling, no kernel or corpus
changes.

**Base commit `ff7ccb5`** ("Merge computenet-umx.1.3"), which carries sibling
tasks `computenet-umx.1.1`, `.1.2`, `.1.3`, `.1.4` and `.1.5` merged onto `main`
at `f73fb7f`. Merge-base with `main`, computed inside this worktree: `f73fb7f`
(`git merge-base origin/main HEAD` — unchanged, confirming no later `main`
commits landed between dispatch and this run). This task's own diff is confined
to this file; no other path changed.

### 1. Gates run, with proof of execution

Every command below was run in the foreground, sequentially (no concurrent Gradle
invocation from this task), with `--rerun`/`--rerun-tasks` so the relevant test task
is provably not a cached replay. Full logs kept in this session's scratchpad; the
lines quoted here are copied verbatim from them.

- **`./gradlew :kernel:test --tests 'civictech.cell.repro.*' --rerun`** —
  `BUILD SUCCESSFUL in 6s`; `27 actionable tasks: 12 executed, 15 from cache`.
  `> Task :kernel:test` at line 56 carries no `UP-TO-DATE`/`FROM-CACHE` marker (it
  ran). 26 `PASSED`, 0 `FAILED`. Freshest `repro`-package JUnit XML timestamp:
  `2026-08-16T13:23:13.955Z`.
- **`./gradlew :kernel:test --rerun`** — `BUILD SUCCESSFUL in 44s`;
  `27 actionable tasks: 2 executed, 25 up-to-date` (the 2 executed are
  `:kernel:test` and `:kernel:reportExpectedFailures`; everything else — compile,
  ksp, resources — was legitimately unchanged since the prior run seconds earlier,
  not a stale replay of the test task itself). `> Task :kernel:test` at line 56, no
  marker. JUnit XML totals across `kernel/build/test-results/test/*.xml`: **1110
  tests, 0 failures, 0 errors**, newest timestamp `2026-08-16T13:24:17.865Z`. 1101
  `PASSED` lines, 0 `FAILED` in the console log.
- **`./gradlew :concord:test --rerun`** — `BUILD SUCCESSFUL in 4s`;
  `24 actionable tasks: 2 executed, 2 from cache, 20 up-to-date`. `> Task
  :concord:test` at line 55, no marker. 254 `PASSED`, 0 `FAILED` — corpus untouched
  by CHA2 (no scenario, no schema change; verified in §2 below), so this count
  matches `computenet-umx.1.3`'s own recorded 254.
- **`./gradlew :demo:exchange:test --rerun`** — `BUILD SUCCESSFUL in 8s`;
  `30 actionable tasks: 4 executed, 4 from cache, 22 up-to-date`. `> Task
  :demo:exchange:test` at line 73, no marker. 14 `PASSED`, 0 `FAILED`.
- **`./gradlew test --rerun-tasks`** (repository-wide; `--rerun-tasks` rather than a
  single `--rerun` because this is the whole-repo gate) — `BUILD SUCCESSFUL in 5m
  45s`; **`83 actionable tasks: 83 executed`** — every task in the build actually
  ran, the strongest proof available. 1952 `PASSED`, 0 `FAILED` across the whole
  repository. `:kernel:test` runs at line 1646 of this log, part of the same
  all-executed build.

No gate failed. No gate's proof is a cached replay standing in for a real run —
the two lightest gates (`:concord:test`, `:demo:exchange:test`) show `up-to-date`
only on unrelated upstream tasks (compile, ksp, resource processing) that
genuinely had nothing to redo between consecutive runs seconds apart; the test
task itself carries no cache marker in any of the five runs, and the fifth run
forced literally everything.

### 2. The expected-failure ledger, end-to-end against real reproductions

`./gradlew test --rerun-tasks`'s own `reportExpectedFailures` output (identical in
all three runs that exercise the full `:kernel:test` — the repro-only run, the
full `:kernel:test` run, and the repository-wide run) reads:

```
Standing expected failures (@ExpectedFailure): 2
  - civictech.cell.repro.ExclusiveDischargeReproTest.BS-8 an Owned nested in a data-class parameter escapes a shadow-suppressed discharging proxy  [owner computenet-ulss, signature CHA2-BS-8]
      reason:  an Owned nested in a plain data-class parameter crosses a shadow-suppressed discharging proxy undischarged: carriesExclusive walks type arguments only, and Proxy.discharge has no branch for an arbitrary payload object
      filedAs: doc/evidence-lane-findings.md#c-11--shadow-suppression-drops-exclusives
  - civictech.cell.repro.ExclusiveDischargeReproTest.BS-9 a non-Effectful cell serving an effect-carrying contract is shadowed without suppression  [owner computenet-3jv2, signature CHA2-BS-9]
      reason:  Shadow.spawn's guard is `if (cell is Effectful)`, so a non-Effectful cell serving an @Contract(effect = true) inlet is shadowed with no suppression at all and acts on the world a second time; the decided cut (93 I-17 / G-32) is the contract bit, which kernel reads nowhere
      filedAs: doc/evidence-lane-findings.md#c-11--shadow-suppression-drops-exclusives
```

**Reconciled: this matches the standing findings above exactly — count (2), reasons
(verbatim) and owners (`computenet-ulss` for BS-8, `computenet-3jv2` for BS-9), as
recorded under `computenet-umx.1.4`'s "Owners filed: one bead per residual" and
the C-11 adjudication's "Residual 1"/"Residual 2" sections.** No mismatch to fix,
in either direction — this file was not touched to make the ledger agree, and no
reproduction's assertions were touched either.

This is the **real, non-self-test** ledger: `ExpectedFailureSelfTest`'s own
fixtures (`ExpectedFailureSelfTest.kt`) are internal proof of the extension's
inversion behaviour (BS-14/BS-15) and are excluded by construction — each fixture
class carries a project-local `@SelfTestFixture` annotation whose
`SelfTestFixtureCondition` (an `ExecutionCondition`) only admits the class while
`SelfTestFixtures.isDriving` is raised, which happens solely inside the nested
`EngineTestKit.engine("junit-jupiter")` execution the outer self-test drives —
never during the ordinary `:kernel:test` discovery, which has no way to interpret
an inverted verdict (deliberately not `@Disabled`/a tag exclusion, which is what
`[CHA2-40]` forbids for reproductions and the fixture file states explicitly it
avoids even on a fixture). Their `@ExpectedFailure` uses never reach
`ExpectedFailureLedger`'s report during a real run. The 2 above are the
only two `@ExpectedFailure`-annotated production reproductions in the repository —
confirmed by `grep -c '^\s*@ExpectedFailure($' kernel/src/test/kotlin/civictech/cell/repro/*.kt`:
zero in every file except `ExclusiveDischargeReproTest.kt` (2 — BS-8, BS-9, the
two above) and `ExpectedFailureSelfTest.kt` (8 — the self-test fixtures, excluded
from real runs as above). `BS-4` (`EffectReplayReproTest.kt`) and `BS-10`
(`DenialDischargeReproTest.kt`) each merely *mention* `@ExpectedFailure` in KDoc
prose narrating that they were originally expected to need the annotation and
turned out not to (recorded under `computenet-umx.1.3`/`.1.5` above) — neither
file carries an actual invocation.

### 3. Boundaries verified — `git diff f73fb7f HEAD` (merge-base with `main`)

- **`[CHA2-50]` zero kernel main-source edits**: `git diff --stat f73fb7f HEAD --
  kernel/src/main` is empty. The only `kernel/` paths in the diff are
  `kernel/build.gradle.kts` (the `reportExpectedFailures` task and its
  `finalizedBy`/`doFirst` wiring — build configuration, not source) and eight
  files under `kernel/src/test/kotlin/civictech/cell/repro/`.
- **`[CHA2-51]` zero concord scenarios or schema changes, `DISPUTES.md`
  extension only**: `git diff --stat f73fb7f HEAD -- concord/schema` is empty;
  the only path under `concord/` in the diff is `concord/corpus/DISPUTES.md`
  (+23 lines), and reading that diff (recorded under `computenet-umx.1.3`'s
  entry above) shows it is an appended bullet under the existing "RESOLVED"
  heading — no new heading, no new scenario file, no schema change.
- **`[CHA2-03]` zero `doc/spec/**`, `CONCORDANCE.md`, `91-gap-analysis.md` or
  plan-document edits**: `git diff --stat f73fb7f HEAD -- doc/spec` is empty.
  The only `doc/` path in the diff is this findings file.
- **`[CHA2-30]` zero C-12 reproductions**: `C12AdjudicationRecordTest.kt`
  contains no `@Test` whose name or body constitutes a reproduction and no
  `@ExpectedFailure` annotation anywhere in the file (`grep -n '@Test\|@ExpectedFailure'`
  shows **seven** plain `@Test`s — corrected here from an earlier miscount of six,
  which dropped one from the enumeration — each asserting over a checked-in
  artifact: the gap row's own text, `D-C12.md`'s presence, `21-REBASE-01.yaml`'s
  `covers:` id, the `DISPUTES.md` RESOLVED heading, this file's own out-of-scope
  naming of G-43/G-42, the findings file naming all three ledger rules against
  the pinned base commit, and the absence of a C-12 reproduction in the `repro`
  package itself. No kernel behaviour is exercised; this is exactly the
  documentation-of-record shape `computenet-umx.1.1`'s entry above specifies.
- No generated/build output in the diff (`kernel/build.gradle.kts` is
  hand-written build configuration, not generated output); no `gen/` change.

No violation found. Nothing was handed back to an owning task.

### 4. `[CHA2-52]` — no gate regressed relative to `main`

The four module gates and the repository-wide gate above are the whole of what
`main` itself runs for this feature's paths; §1 shows all five green with fresh
execution. Nothing in this task's own diff (confined to this file) can regress a
gate by construction, and nothing in the feature's cumulative diff (§3) touches
`kernel/src/main`, `gen/`, or the concord corpus in a way that could change a
scenario's or a kernel test's outcome.

### 5. What was adjudicated fixed since the feature was filed

Recorded in full above, under each rule's own section; consolidated here for the
reader who wants the delta rather than the derivation:

- **C-9 journaled-source double-fire** — fixed by `34892d9`
  (`computenet-yh6.1.2`, "A recovered outlet re-emits under replay-stable wave
  identity", PR #15): a durable outlet's `sourceId` became ref-derived
  (`OutletWaveState.durable`) instead of random. `computenet-umx.1.3`'s BS-4
  pins this unweakened and it passes.
- **C-11 boundary-denial silent drop** — fixed by `ab69412`
  (`computenet-usd.2`, "Exclusive payloads are discharged exactly once on every
  `BoundaryPolicy` denial path"), extended by `1b9653b` and by this feature's own
  base commit `46ed020`. `computenet-umx.1.5`'s BS-10 pins this unweakened and it
  passes.
- **C-9 baseline exemption, decided rather than left a hazard** — `[24-DUR-07]`/
  `[24-DUR-08]` landed by `computenet-yh6.1.3.4`: a PN-2 replay-baseline a sink
  acts on records its own discharged-baseline state rather than advancing the
  wave-position frontier, so a baseline at or behind the frontier is suppressed,
  not exempted. `computenet-umx.1.3`'s BS-5 records this as the recorded answer,
  not an assumed one, and it passes.
- **C-12 RESTART aliasing** — adjudicated genuinely resolved, D-C12, before this
  feature was filed; recorded, not reproduced, by `computenet-umx.1.1`'s entry
  and pinned as documentation-of-record by `C12AdjudicationRecordTest`.

### 6. Which pins pass (the suite's green half)

`BS-1` (frontier suppression, C-9 core), `BS-4` (journaled-source double-fire,
now fixed), `BS-5` (baseline exemption, now decided), `BS-7`/`BS-12` (discharging
proxy, C-11 core, plus the loud missing-descriptor failure), `BS-10` (boundary
denial, now fixed), `BS-11` (ADMIT/dead-letter discharge baseline), and the
documentation-of-record `C12AdjudicationRecordTest` (C-12, closed). All pass
unweakened, unannotated, with no manufactured divergence — every one of them was
originally filed expecting to reproduce a failure and instead pins a fix that
landed on `main` between the feature's filing (2026-08-08) and each task's own
run.

### 7. Which expected failures stand, with owners (the suite's red-marked half)

Exactly the two in §2: **BS-8** (`Owned` nested in a data-class parameter escapes
a shadow-suppressed discharging proxy — `Proxy.discharge` has no branch beyond
`Map`/`Iterable`/`Array`), owner **`computenet-ulss`** ("Widen the exclusive bit
and `Proxy.discharge` reach to nested exclusives, 93 I-6/I-8, C-11 residual 1");
and **BS-9** (a non-`Effectful` cell serving an effect-carrying `@Contract` is
shadowed without suppression — `Shadow.spawn`'s guard reads only `cell is
Effectful`), owner **`computenet-3jv2`** ("Shadow suppression cuts at
`@Contract(effect=true)`, not at `Cell is Effectful`, 93 I-17/G-32, C-11 residual
2). Both beads were filed by `computenet-umx.1.4` and both carry the instruction
that the fix must remove the `@ExpectedFailure` annotation and keep the test —
`[CHA2-44]` makes the build fail the moment either reproduction starts passing.

### 8. What remains rig-gated — the open residual this task does not solve

`computenet-umx.1.6` (sweeping BS-2/BS-3/BS-6/BS-17 and the **strict** form of
`[CHA2-26]`) is **blocked**: CHA1's DST rig (`civictech.testkit.dst` —
`CrashFault`, `JournalFault`, `RestartAtFrontierFault`, seed capture/replay/
shrink, exclusive-payload accounting) does not exist on `main`, as recorded
above under "CHA1's rig does not exist, and five CHA2 clauses are gated on it".
Re-verified by this task: `grep -rn 'FaultPlan\|CrashFault\|JournalFault\|RestartAtFrontierFault\|DstRun\|DstReplay\|testkit.dst' --include='*.kt' .`
returns exactly one hit at this branch's tip — a KDoc comment in
`ExclusiveDischargeReproTest.kt` (line 43) that itself narrates the rig's
absence ("That rig does not exist on `main` (no `civictech.testkit.dst`)") —
and no hit that is rig code, a rig usage, or a rig import. Corrected here from
an earlier overclaim of "zero matches": the substance stands (no
`civictech.testkit.dst` package, no `FaultPlan`/`CrashFault`/`JournalFault`/
`RestartAtFrontierFault`/`DstRun`/`DstReplay` type anywhere in the tree), only
the grep-result count was wrong. Epic `computenet-umx` (CHA1)
still has no rig-building child. This is the recorded scheduling decision of the
unattended `/work` session (2026-08-16, on `computenet-umx.1`): proceed on
`.1`-`.5` plus `.7`, leave `.6` blocked pending CHA1. **Consequence, stated
explicitly again here so this hand-off does not read as a clean close: CHA2 does
not meet its own acceptance criteria on this branch.** The five clauses named in
that section (`[CHA2-11]`/BS-2, `[CHA2-12]`/BS-3, `[CHA2-15]`/BS-6, `[CHA2-47]`/
BS-17, and the strict form of `[CHA2-26]` across BS-7..BS-12) remain
unsatisfiable until CHA1 ships its rig. This is not this task's to fix or paper
over — it is named so `computenet-yh6.1.5` and any later reviewer see it as a
known, recorded gap rather than an oversight.

### 9. What `computenet-yh6.1.5` (`[KFX-22]`) consumes from this hand-off

- Two owned, standing, unweakened expected failures to flip green: `computenet-ulss`
  (BS-8) and `computenet-3jv2` (BS-9) — neither is KFX's own scope
  (`computenet-yh6.1`'s closing feature already names C-11 out of scope), but
  both are cross-referenced here so KFX's closing review knows the C-11 side of
  the ledger is accounted for and not silently dropped.
  `[CHA2-44]`/`[CHA2-43]` mean either bead's fix must remove the annotation and
  keep the test; a signature change instead of a fix reddens the build honestly.
- Confirmation that KFX's own scope (C-9, source identity under replay) is
  **already closed on `main`** by `34892d9`, `computenet-yh6.1.3.4` and
  `computenet-yh6.1.3.5` — `computenet-yh6.1.5` closes the epic on that basis, not
  on any further CHA2 deliverable.
- The recorded, open scheduling gap: `computenet-umx.1.6`'s rig-gated sweep, named
  in §8, which is CHA1's to unblock, not KFX's and not this hand-off's to solve.

### Disposition

Report, do not edit any other file. This task's diff touches only this file
(`doc/evidence-lane-findings.md`) — no kernel, `gen/`, `concord/`, or `doc/spec/`
change; no generated/build output. One cross-bead write, authorized by this
bead's own acceptance criteria: a summary comment on `computenet-umx.1` (posted
via `bd comment computenet-umx.1 --file <path>`, per the dispatch's explicit
authorization). No other bead touched; `computenet-umx.1` itself is not closed,
re-prioritised, reassigned or re-parented by this task.

---

## `computenet-yh6.1.5` — `[KFX-22]` acceptance run: CHA2's C-9 reproductions observed at THIS commit, not inherited

Recorded by: `computenet-yh6.1.5.1` (feature `computenet-yh6.1.5`, epic
`computenet-yh6.1` — KFX). Realizes `[KFX-22]`/BS-50: CHA2's three C-9
reproductions observed passing unweakened at the feature-branch commit, with
BS-4 re-confirmed mutation-discriminating at that same commit. Feeds
`computenet-yh6.1.5.2`'s `[KFX-23]`/BS-51 DISPUTES.md reconciliation — this
entry states outcomes only, and makes no retire/narrow/unchanged call itself.

**Commit observed: `6ebbcff`** ("CHA2: adjudication record and load-bearing
expected-failure harness (computenet-umx.1) (#228)", PR #228 — the tip of
`origin/main` at this task's dispatch and this branch's base commit; `git
merge-base feature/computenet-yh6.1.5 HEAD` inside this worktree returns
`6ebbcff`, confirming no later `main` commit landed between dispatch and this
run). Every command below ran against the tree at that commit, before this
entry's own edit.

### 0. Why this run, and not the inherited one

`computenet-umx.1.3` recorded all three reproductions passing at `bf18284`, a
different commit on a different branch (CHA2's own task branch, merged onto
`main` as squash commit `6ebbcff` via PR #228). This task re-observes at
`6ebbcff` itself rather than trusting that record, because `[KFX-22]` is this
epic's own acceptance gate. First check: is the reproduction file the squash
actually shipped the same one CHA2 wrote, or did the squash silently reshape
it? `EffectReplayReproTest.kt` was introduced once in this repository's history,
at `4b69332` ("C-9 effect-replay reproductions stand unweakened…",
`computenet-umx.1.3`'s own commit); `git diff 4b69332 6ebbcff --
kernel/src/test/kotlin/civictech/cell/repro/EffectReplayReproTest.kt` is empty,
and `git show <rev>:<path> | md5` is `5eb8e25bf6acd6276bd1595a02fa4746` at both
revisions — byte-identical. The squash introduced no weakening.

### 1. The suite as it stands: no annotation, no re-seeding, no narrowing

Read in full at `6ebbcff`. No `@ExpectedFailure` on BS-1, BS-4 or BS-5;
confirmed independently by `reportExpectedFailures`' own count for the
repro-only run (§2 below): `Standing expected failures (@ExpectedFailure): 0`.
Seeds (91–96, one per `SimulationController` instantiation) are the values
`computenet-umx.1.3` shipped, sequential per-crash-generation values, not a
rotation away from a discovered failure. Assertions are full-log equalities
over the external effect list (`effects shouldBe listOf(...)`), not "at most
once" counts a zero-fire regression would satisfy vacuously — unchanged from
the byte-identical comparison in §0.

### 2. Gates run, with proof of execution

All four commands ran in the foreground, sequentially, one Gradle invocation
at a time; no invocation was backgrounded. Full logs kept in this session's
scratchpad; lines quoted below are copied verbatim from them.

- **`./gradlew :kernel:test --tests 'civictech.cell.repro.EffectReplayReproTest' --rerun`**
  — `BUILD SUCCESSFUL in 8s`; `27 actionable tasks: 12 executed, 15 from cache`.
  `> Task :kernel:test` carries no `UP-TO-DATE`/`FROM-CACHE` marker (it ran).
  JUnit XML (`TEST-civictech.cell.repro.EffectReplayReproTest.xml`):
  `tests="3" skipped="0" failures="0" errors="0"`, timestamp
  `2026-08-16T14:27:32.829Z`. `reportExpectedFailures` in this same run:
  `Standing expected failures (@ExpectedFailure): 0`. All three PASSED: BS-1,
  BS-4, BS-5.
- **`./gradlew :concord:test -Pconcord.profiles=core,dur --rerun`** —
  `BUILD SUCCESSFUL in 3s`; `24 actionable tasks: 2 executed, 2 from cache, 20
  up-to-date`. `> Task :concord:test` carries no marker. Aggregate JUnit XML
  across `concord/build/test-results/test/*.xml`: 248 tests, 0 failures, 0
  errors; newest timestamp `2026-08-16T14:27:48.687Z`
  (`TEST-civictech.concord.runner.CorpusRunner.xml`). That file's own testcases
  include `DUR-SRCID-01 (15-durability)` and `DUR-SRCID-02 (15-durability)`,
  both present with no failure recorded against them — the corpus-level
  construction of the same journaled-source/effectful-sink case
  (`covers: [24-DUR-04, 24-DUR-05]`) passes alongside the kernel-level
  reproduction.
- **`./gradlew :concord:check`** (run as `--rerun-tasks` for unambiguous proof)
  — `BUILD SUCCESSFUL in 29s`; **`26 actionable tasks: 26 executed`** — every
  task in this module's `check` lifecycle actually ran, including
  `:concord:concordanceGate` and `:concord:test`; no dangling `covers:` id
  (the gate would otherwise fail the build).
- **`./gradlew test`** (run as `--rerun-tasks`, repository-wide) — `BUILD
  SUCCESSFUL in 5m 20s`; **`74 actionable tasks: 74 executed`** — every task in
  the full build ran, the strongest available proof. 0 `FAILED` across the
  whole repository (`grep -c FAILED` on the full log: 0).

No gate failed. No gate's proof is a cached replay standing in for a real run.

### 3. Mutation re-check, at this commit: BS-4 is load-bearing here too

Marker written first (`.mutation-in-progress`, naming the exact call site and
what was removed), per the procedure `computenet-umx.1.3` used.
`HostDurability.installDurableEpochs` (kernel/src/main) was neutered by
inserting an unconditional `return` as its first statement, so the ref-derived
durable outlet wave identity (`OutletWaveState.durable`) is never installed on
a journaled cell's outlets. Re-ran
`./gradlew :kernel:test --tests 'civictech.cell.repro.EffectReplayReproTest' --rerun`:

```
EffectReplayReproTest > BS-1 a mid-drain crash replays the journal without re-firing or losing an effect() PASSED
EffectReplayReproTest > BS-5 a PN-2 replay-baseline at or behind the restored frontier is suppressed, not exempted() PASSED
EffectReplayReproTest > BS-4 a journaled source feeding an Effectful sink fires each logical invocation once across a crash() FAILED
    io.kotest.assertions.AssertionFailedError: Unexpected elements from index 13
    expected:<[1, 2, 3, 4, 5, 6, 7]> but was:<[1, 2, 3, 4, 5, 6, 7, 1, 2, 3, 4, 5, 6, 7]>
3 tests completed, 1 failed
```

Verbatim match to `computenet-umx.1.3`'s own mutation transcript and to
`DISPUTES.md:522`'s "as filed" double-fire description. BS-1 and BS-5 stayed
green under this mutation, as expected — neither of their sources is a
journaled cell, so neither exercises `installDurableEpochs`.

Reverted immediately: `git diff -- kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt`
is empty after the revert, `git status --short` clean, `.mutation-in-progress`
removed before this commit. Re-ran the repro test once more post-revert to
confirm the green state was not disturbed: `BUILD SUCCESSFUL`, 3 tests, 0
failures, JUnit timestamp `2026-08-16T14:39:58.775Z`, standing
`@ExpectedFailure` count still 0.

### 4. Agreement with the earlier record, stated explicitly

`computenet-umx.1.3` (at `bf18284`) recorded BS-1/BS-4/BS-5 all passing, BS-4
mutation-confirmed with the identical failure signature quoted above. This
task's independent observation at `6ebbcff` — a different commit, reached via
a different branch history — agrees in every particular checked: same
pass/fail per test, the same double-fire signature verbatim, the same absence
of `@ExpectedFailure` on all three, and the same file content (§0). No
divergence was found between the two observations; none is reported here as
one.

### 5. `[KFX-24]` — scope of the exactly-once claim, held to its boundary

Every exactly-once statement above and in the reproduction suite itself is
against the in-process external effect log — a `MutableList` that outlives the
host, the registry and every cell instance, but not the JVM process. This task
adds no end-to-end external exactly-once claim. The external actuator's
idempotency remains 93 I-7's stated ceiling and CON1's territory, untouched by
anything run here.

### Verdict, for `computenet-yh6.1.5.2`'s `[KFX-23]`/BS-51 consumption

**Closed, observed at `6ebbcff`, unweakened:**
- **BS-1** (frontier suppression pin, `[CHA2-10]`) — PASS.
- **BS-4** (the journaled-source → `Effectful`-sink double-fire, `[CHA2-13]` —
  this is the exact construction `concord/corpus/DISPUTES.md:522`'s G-59/C-9
  boundary entry recorded as broken, and the one `[KFX-22]` exists to close)
  — PASS, and re-confirmed discriminating by a reverted mutation at this
  commit: neutering `HostDurability.installDurableEpochs` reproduces the
  double-fire verbatim; the fix (`34892d9`) is what makes it pass.
- **BS-5** (PN-2 baseline-suppression pin of the `[24-DUR-07]`/`[24-DUR-08]`
  decided rule, `[CHA2-14]`) — PASS.
- All three: no `@ExpectedFailure`, no re-seeding, no narrowing, same
  assertions and scope as CHA2 shipped (§0, §1).

**Not touched or closed by this task:**
- `DUR-REPLAY-01`'s two-independent-subgraph construction — untouched; whether
  it can now fold into one subgraph is `computenet-yh6.1.5.2`'s/BS-51's own
  decision, not made here.
- C-11 (BS-8/BS-9, owned by `computenet-ulss`/`computenet-3jv2`) and C-12
  (adjudicated separately, D-C12) — out of this suite's scope, unaffected by
  this run.
- `computenet-umx.1.6`'s rig-gated sweep (BS-2/BS-3/BS-6/BS-17, the strict form
  of `[CHA2-26]`) — still blocked on CHA1's DST rig, which does not exist on
  `main` at this commit; not exercised here.
- No end-to-end external exactly-once claim (§5) — that ceiling stands
  unmoved.

### Disposition

Report, do not edit any other file. This task's diff touches only this file
(`doc/evidence-lane-findings.md`) — no kernel, `gen/`, `concord/`, or
`doc/spec/` change; the one mutation applied in §3 was reverted before this
commit and the `.mutation-in-progress` marker was removed, never committed. No
`DISPUTES.md`/corpus/`CONCORDANCE.md` edit — that is `computenet-yh6.1.5.2`'s.
No tracker item created; no bead other than `computenet-yh6.1.5.1` touched.

---

## `computenet-umx.1.6` — rig-gated C-9 sweeps: BS-2 finds a residual, BS-3 renders the deferred verdict, BS-6 holds

Recorded by: `computenet-umx.1.6` (feature `computenet-umx.1` — CHA2; epic
`computenet-umx` — CHA1). Realizes `[CHA2-11]`, `[CHA2-12]`, `[CHA2-15]`,
`[CHA2-47]` and the strict form of `[CHA2-26]`; BS-2, BS-3, BS-6, BS-17. The
suite is
`kernel/src/test/kotlin/civictech/cell/repro/EffectReplaySweepTest.kt`.

This entry is what `EffectReplaySweepTest`'s `@ExpectedFailure` names in its
`filedAs`, and the reasoning `concord/corpus/DISPUTES.md`'s G-59/C-9 boundary
entry points at for this suite's reproduction ids.

### 0. The rig gate, discharged before anything was written

The bead's own opener — "BLOCKED — DO NOT CLAIM until CHA1's rig exists" — was
written 2026-08-16 against `origin/main` `46ed020`, and is stale. CHA1
(`computenet-umx.3`) squash-merged as `67399fc23`. Verified at this task's base
commit `76acebfa1`, in this order and before any code was written:

- `testkit/src/main/kotlin/civictech/testkit/dst/` is non-empty and carries
  `RestartAtFrontierFault.kt`, `JournalFault.kt`, `JournalSurgery.kt`,
  `DstRun.kt`, `DstReplay.kt`, `DstArtifact.kt`, `DstSweep.kt`,
  `PlanShrinker.kt`, `ExclusiveAccounting.kt`, `FailureReport.kt` and the rest;
- every requirement id the bead gates on is present in the tree —
  `[CHA1-19]`..`[CHA1-22]`, `[CHA1-30]`..`[CHA1-40]`, `[CHA1-50]`..`[CHA1-53]`,
  each cited from at least two files (`git grep -l '\[CHA1-NN\]'`, 2 to 14 files
  per id);
- `doc/dst-rig.md` — the CHA1 authors' consumer contract — was read in full
  first, and this suite follows it: the six seams and nothing else, a fault
  value with no lambda, and a check message split into a stable identity plus a
  `DstFailureDetail` (§3, computenet-umx.4).

Nothing here introduces a fault injector, journal decorator, crash harness,
replay artifact format or shrinker of its own (`[CHA2-04]`). What the suite
adds is a graph and a property, which is what a rig consumer supplies.

### 1. The fixture, and the record layout every claim rests on

`DurableEffectSweepGraph`: one `Effectful` sink on a journaling `ManagedHost`,
fed one frame per controller step by a volatile off-host source, restarted
through `RestartAtFrontierFault`. The external effect log records **wave
positions** — `(sourceId, counter)` — not values, because `[CHA2-11]` asks
whether a *position* was acted on twice and two distinct emissions may
legitimately carry the same value.

The census, pinned by its own test so a later fixture change fails there rather
than silently weakening every sweep below it:

```
[census] seed=101 JournalCensus(16 records in 8 steps: frames=8, frontier=8, checkpoints=0, tags={1=8, 3=8})
```

Frames and frontier advances are the whole log and strictly alternate: the frame
for counter `c` is at record `2(c - 1)`, its advance at `2(c - 1) + 1`. BS-2's
finding is stated in terms of that layout, which is why the layout is asserted
rather than assumed.

### 2. BS-2 (`[CHA2-11]`) — FAILS. A crash between an effect and its dedupe record re-fires it

Seed **101**, pinned. `prefixRestartSweep` over every `k in 0..R`:

```
[BS-2] DST prefix-restart sweep graph=c9-prefix-restart-sweep host=durable journal=sink-journal
       seed=101 prefixes=0..16 (executed 17); failed on 6 of 17; failing k=[1, 3, 5, 7, 9, 11]
```

Every **odd** prefix inside the log the host had written by the restart step
fails, and every even one passes. Each failure is a single duplicated position,
always the frame whose frontier advance the prefix cut off — `k=1` re-fires
`(s,1)`, `k=3` re-fires `(s,2)`, `k=5` → `(s,3)`, `k=7` → `(s,4)`, `k=9` →
`(s,5)`, `k=11` → `(s,6)`. Recovery reports `recovery-complete@k` in all six:
this is a clean replay, not a damaged one.

The mechanism follows from §1's layout. `ManagedHost` journals a hosted frame at
intake (write-ahead), delivers it on a later scheduler task, and journals the
`Effectful` frontier advance beside the delivery — so the external effect fires
*between* two journal records, and an odd prefix is exactly a crash inside that
window. The frame is durable, the "already acted on" advance is not,
`HostDurability.alreadyProcessed` says no, and the effect fires a second time.

**Disposition**: `@ExpectedFailure(signature = "CHA2-BS-2-prefix-restart-refire",
owner = "computenet-xxeo")`, no kernel patch (`[CHA2-50]`). Filed as
**`computenet-xxeo`**, which owns the *decision* as much as the fix: whether
`[24-DUR-05]` intends at-least-once across this window (today's behaviour),
at-most-once (journal the advance before invoking the handler), or a
construction that commits the effect together with its dedupe record. The
annotation fails the build when its body passes (`[CHA2-44]`), so whichever way
that lands, this test flips and cannot be missed.

**Resolved by `computenet-xxeo` (2026-08-25) — as a decision, not a patch.** The
guarantee is **at-least-once**, now stated normatively as `[24-DUR-09]` (spec 24
§Effectful) with the resolution recorded on `concord/corpus/DISPUTES.md`'s
G-59/C-9 entry. It applies a criterion the spec already decides to a
window the spec does not itself name, rather than picking a fresh one:
`[24-DUR-07]` fixed the criterion for this trade — a duplicate is loud and bounded,
a suppression is a silent unrecoverable omission — `[24-DUR-08]`'s eviction bound
re-applies it in the same direction, and 93 I-7's external-effect idempotency
ceiling puts exactly-once across an arbitrary external world outside the kernel
seam. No kernel `main` change was needed: `[24-DUR-05]`'s antecedent never held for
a position whose advance was not durable. BS-2's `@ExpectedFailure` is removed and
its property rewritten (seed 101 and `0..R` untouched); BS-3 was re-read and keeps
passing unchanged, exactly as §3's second review note predicted for this branch.
The transcript below is the finding as measured, and is left as it stood.

**Nothing was softened to reach that state.** The sweep asserts the unweakened
`[CHA2-11]` property over the full `0..R` range on the pinned seed;
`PrefixRestartSweepReport`'s `init` refuses a report that does not cover its
whole declared range, so narrowing to the passing prefixes is unconstructible.
Three conditions are asserted *outside* the recorded failure — no broken
experiment, no budget exhaustion, no inert restart at any `k` — so that a sweep
which stops being executable reddens the build instead of being absorbed by the
annotation (`[CHA2-43]`).

### 3. BS-3 (`[CHA2-12]`) — the deferred verdict: the re-delivered invocations DO re-fire

Seed **202**, pinned; two runs differing in exactly one field,
`keepFrontierAdvances`, with the journal prefix `null` (the whole log) in both —
which is `[CHA1-22]`'s independence claim exercised directly.

```
[BS-3 control] counters=[1, 2, 3, 4, 5, 6, 7, 8]                outcome=PASSED
[BS-3 rolled ] counters=[1, 2, 3, 4, 5, 6, 4, 5, 6, 7, 8]       outcome=FAILED
```

Recorded answer: **they re-fire.** Positions 4, 5 and 6 — the ones just past the
three retained advances — are delivered again and acted on again; the control,
frontier intact, acts on each position exactly once. This is the verdict CHA1's
BS-11 deferred to CHA2, and it is rendered from the run rather than presumed.

**On the rollback point not being literally `(s, 3)`.** The bead's prose says
"frontier rolled back to `(s,3)`". `[CHA1-22]` as landed cannot name a
`(sourceId, counter)`: `HostDurability`'s `FrontierRecord` is a
`private data class` whose body is Java-serialised, so from `:testkit` only a
record's tag byte is readable and a rollback selects by *counting* advances
(`FrontierRollbackJournal`'s KDoc — `computenet-umx.3`'s reported structural
limit, not a shortcut taken here). Keeping the first three advances is the rig's
expression of that construction, and the test asserts the consequence it can
observe (which position re-fires first) rather than a decoded frontier it cannot
read.

**Interpretation, and how BS-2 changed it.** Read alone, BS-3 could be dismissed
as an artificial injury: the frontier *is* the exactly-once mechanism, and the
fault deletes durably-recorded state the kernel wrote and never lost by itself.
BS-2 removes that escape — an ordinary journal truncation at any odd prefix
reaches the same state with no frontier surgery at all. So both are the same
finding seen through two faults, they share the owner `computenet-xxeo`, and the
honest statement of scope is that `[24-DUR-05]`'s exactly-once effect delivery is
exactly as durable as the frontier journal and no stronger.

BS-3 carries **no** `@ExpectedFailure`: it asserts the observed behaviour and
therefore passes, and that annotation fails the build when its body passes
(`[CHA2-44]`). BS-2 holds the standing claim for both.

### 4. BS-6 (`[CHA2-15]`) — both halves hold

Torn tail, seed **303** pinned:

```
[BS-6 torn tail] recovery=recovery-complete@12 counters=[1, 2, 3, 4, 5, 6, 8]
```

Recovery completes, exactly one record fewer is offered than the log held at the
restart, nothing dead-letters, and **counter 7 — the torn record's own
invocation, journaled at intake and not yet delivered when the host was
discarded — never fires.** Counter 8 is a live post-recovery emission and still
lands. The exact list is asserted rather than a weaker distinctness property,
because "no effect fired for the torn record" and "some effects are missing" are
different claims and only the first is `[CHA2-15]`'s.

Corrupted interior record, seed **404** pinned, `CorruptAt(1)`:

```
[BS-6 corrupt] recovery=recovery-incomplete@1/13 counters=[1, 2, 3, 4, 5, 6, 1, 8]
```

`RecoveryIncomplete(recordIndex = 1, total = 13)` — index and total both
asserted, and both re-read off the run report's trace as well as off the
exception (`[CHA1-20]`). The partial replay is not treated as complete, and the
corrupted record is dead-lettered rather than swallowed. The effect half: the
frame for counter `c` sits at record `2(c - 1)`, so exactly one frame — counter
1, at record 0 — lies before the abort point, and it is the only invocation the
replay re-delivers. **No effect fires for any record at or beyond record 1**,
which is `[CHA2-15]`'s second clause, and the assertion is written as a set
equality against the records the layout says are reachable, so a re-fire that
crept past the abort point would fail it.

That single re-delivery does re-fire, so the composed run's own check FAILS —
same mechanism, same owner (`computenet-xxeo`): record 0's frontier advance *is*
record 1, the corrupted one, so the replay applies the frame and never reaches
the advance that would have suppressed it. It is recorded here rather than
double-counted as a separate finding, and BS-6's assertions are written to
separate the two claims explicitly.

### 5. BS-17 (`[CHA2-47]`, `[CHA1-50]`, `[CHA1-51]`) — pinned seeds, artifacts, replay commands

Every seed is a named constant in the suite's `Seeds` object — 101, 202, 303,
404 — each recorded once, none replaced, narrowed or reordered. BS-2's seed in
particular found the residual on its first authoring run and is now
`computenet-xxeo`'s acceptance seed.

Every failing run writes a `DstArtifact` under `kernel/build/dst/failures/`
(`[CHA1-54]`, enforced by `DstArtifacts.requireUnderBuildDirectory`) and prints
a full `FailureReport` — plan with activation steps, dead letters, exclusives,
artifact path and a copy-pasteable replay command — into the test log. The run
transcribed above wrote:

```
kernel/build/dst/failures/c9-prefix-restart-k1/101.json
kernel/build/dst/failures/c9-prefix-restart-k3/101.json
kernel/build/dst/failures/c9-prefix-restart-k5/101.json
kernel/build/dst/failures/c9-prefix-restart-k7/101.json
kernel/build/dst/failures/c9-prefix-restart-k9/101.json
kernel/build/dst/failures/c9-prefix-restart-k11/101.json
kernel/build/dst/failures/c9-frontier-rollback/202.json
kernel/build/dst/failures/c9-effect-replay-sweep/202.json
```

**These paths are regenerated, not archived.** They live under `build/` and are
rewritten by the next run; the replay command printed beside them embeds *that
JVM's* classpath and is invalidated by the next build. Both are deliberate
(`ReplayCommands`' own KDoc: "not a portable artifact and does not belong in a
bead"), which is why this entry records the *shape* of the command and how to
regenerate it rather than a pasted command line:

```bash
./gradlew :kernel:test --tests 'civictech.cell.repro.EffectReplaySweepTest' --rerun
# then copy the `replay` line out of the failure report the run prints, e.g.
#   "<java>" -cp "<this run's classpath>" civictech.testkit.dst.DstReplayCli \
#     "<abs path>/kernel/build/dst/failures/c9-frontier-rollback/202.json" \
#     --register civictech.cell.repro.C9SweepRegistrar
```

`C9SweepRegistrar` is the `--register` target: it constructs and registers every
graph and the one check this suite uses, because a graph constructed inside a
test method would not exist in the replaying JVM and the replay would fail with
"unknown graph id" instead of reproducing anything. The check is resolved from
the world (`EffectLogs`, `ExclusiveLedgers`), which is what lets one registered
check id grade all seven graphs and lets a replay grade against a log the
replaying JVM actually wrote.

### 6. `[CHA2-26]` — the rig's exclusive accounting is enabled, and what it can reach

Every run in this suite composes `ExclusiveLedgers.check()` with the C-9
property in one `DstCheck`, and the graph mints one tracked `Owned` per emission
through the ledger. So `[CHA1-53]`'s accounting is live for every sweep here
rather than being replaced by a bespoke assertion — which is `[CHA2-26]` in its
strict form. Composed into one check rather than run as two, because `DstRun`
grades one check per run and an exclusive lost during a fault-injected run must
fail the same run the C-9 property is measured on.

**The limit, measured rather than asserted.** The exclusive leg is a *volatile*
(off-host) sink, not the journaled one. A journaled frame is Java-serialised —
`kernel/src/main/kotlin/civictech/cell/host/HostDurability.kt:336`,
`ObjectOutputStream(it).use { out -> out.writeObject(record) }` — and neither
`Owned` nor the rig's `TrackedExclusive` is `Serializable`, so **an exclusive
payload cannot ride a write-ahead journal at all**. What the accounting covers
here is therefore every exclusive this graph mints; what it cannot cover is an
exclusive crossing a durability boundary, because no such payload is
constructible. That is a property of the kernel's journal encoding, not a gap in
the rig.

**And the sharper statement, added at review** (`computenet-umx.1.6` review, so a
green ledger is not read as evidence it is not): no fault in this suite can
perturb the exclusive leg at all. `RestartAtFrontierFault` and `JournalFault` act
on the host and its journal, while the `exclusives` outlet is subscribed by a
plain off-host consumer that mints and consumes inside one controller step and
never crosses the host. The accounting here is therefore *enabled and honest*
rather than *load-bearing*: a standing tripwire that would catch a future graph
change routing an exclusive through the host, and not a check any fault in this
file can make fail. `[CHA2-26]`'s strict form asks that these sweeps run under the
rig's accounting instead of a bespoke assertion, and that is what is delivered —
it is **not** evidence about exclusive handling under crash and replay, and must
not be cited as if it were. Recorded here rather than in
`concord/corpus/DISPUTES.md`, because `[CHA2-26]` is a CHA2 acceptance criterion
and this file is the evidence lane's own ledger, whereas `DISPUTES.md` is the
corpus's **spec-requirement** honesty ledger: its entries are keyed on spec
requirements and gap markers (`G-59` / `C-9`, `[24-DUR-05]`), and a `[CHA2-*]` id
appears there only as *provenance* inside such an entry — as in this task's own
`[CHA2-51]` extension of the G-59/C-9 boundary entry — never as an entry's
subject. (Corrected at second read: an earlier draft of this paragraph said
`DISPUTES.md` "carries no `[CHA2-*]` entries", which a grep contradicts — the
file cites eleven of them, including the bullet this task added.)

**A second review note, on what a fix might move, and what it will not.** BS-2's
`@ExpectedFailure` is the designed tripwire — remove it, keep the test. Whether
`computenet-xxeo` also flips **BS-3** depends on which resolution it takes, and
that is not knowable from here:

- If it decides `[24-DUR-05]` is at-least-once as written, or fixes only the live
  write-ahead **ordering** (advance durable before the effect fires, or the
  effect committed atomically with its dedupe record), **BS-3 keeps passing
  unchanged.** BS-3's fault does not race that window: it deletes frontier
  advances the host had already made durable, so on replay `alreadyProcessed` says
  no for counters 4..6 whatever order the live path wrote them in, and the
  re-fire BS-3 records survives the fix. BS-2 and BS-3 are one *finding* about
  `[24-DUR-05]`'s scope, but they are not one *mechanism*, and only BS-2's is an
  ordering window.
- Only a resolution that changes **replay-time** delivery — suppressing an
  `Effectful` re-delivery whose frontier advance is absent, rather than
  reordering the write — reddens BS-3, at `repeats.isNotEmpty()` and at the
  `DstOutcome.FAILED` assertion, whose messages would then misdiagnose the cause
  ("the rollback never reached the frontier").

So: whoever fixes `computenet-xxeo` owns a **re-read** of BS-3, and owns the edit
only in the second branch. If it does flip, re-record the verdict against the new
behaviour on the same pinned seed 202 — not a re-seed, not a narrowed assertion,
and not a change to `FrontierRollbackJournal`.

Consequently this suite **does not** claim to retire the C-11 siblings'
bespoke-assertion deviation (`computenet-umx.1.4` §"`[CHA2-26]` deviation",
`computenet-umx.1.5` likewise) on the durable plane. It retires it *for its own
runs*, where the rig now covers it. The siblings' reproductions live in their own
files, which are outside this task's `metadata.files` claim, and amending them
was not attempted.

### 7. Verification

```
./gradlew :kernel:test --tests 'civictech.cell.repro.EffectReplaySweepTest' --rerun
  6 tests completed, 0 failed
  Standing expected failures (@ExpectedFailure): 1
    - EffectReplaySweepTest.BS-2 …  [owner computenet-xxeo, signature CHA2-BS-2-prefix-restart-refire]
```

`:testkit` was not modified, so the rig is unchanged by this task; the
repository-wide `./gradlew test` and `./gradlew :concord:check` gates were run
because this entry and the `DISPUTES.md` extension are part of the change.

### Disposition

- **BS-2**: reproduction landed, failing, annotated, owned by
  `computenet-xxeo`. No kernel change (`[CHA2-50]`).
- **BS-3**: verdict rendered and recorded; test passes on the observed answer.
- **BS-6**: both halves hold; test passes.
- **BS-17**: seeds pinned, artifacts written, replay path documented and
  regenerable.
- **`[CHA2-51]`**: `concord/corpus/DISPUTES.md`'s G-59/C-9 boundary entry
  extended with this suite's reproduction ids — an extension of the existing
  entry, not a duplicate entry, and no corpus scenario or schema change.
