# V1C-CONCORD — the bounded read becomes conformance surface: three EARS requirements, one deliberate concord schema change, three scenarios — authored in that order, stopping if a stage's premise fails

**Status**: Implemented — merged (`5d3c444`, C11). All three stages ran. Stage 1 landed
four ids, not three: `[21-PULL-02]` and `[21-PULL-03]` in `21-propagation.md`
§Pull, `[24-BOUND-01]` **and a minted `[24-BOUND-02]`** in `24-data-cells.md`
(the drafted `[24-BOUND-01]` conflated a per-page obligation with a whole-walk
one, and the shipped `StatePage` contract distinguishes them). Stage 2 landed
exactly one step verb (`read-state`), two checks (`wave-plane-unchanged`,
`pages-equal-view`) and two driver verbs (`readState`, `wavePlane`); the honesty
gate was **met** — `wave-plane-unchanged` is stated in the spec's own per-source
wave-position vocabulary in `concord/schema/scenario.md`. Stage 3 authored three
scenarios (`21-PULL-02`, `24-BOUND-01`, `24-BOUND-02`). **`[21-PULL-03]` is
filed in `concord/corpus/DISPUTES.md`, not covered**: its family antecedent is
unsatisfiable by any catalog cell (every tag-frontier family is an
observed-remove set; since computenet-v2ka a local retraction mints its own
del-dot and moves the frontier, but the surviving counterexample — a reordered
remote `dels` entry whose dot is below a per-source max the replica already
holds — is narrower still and just as unauthorable), and the script model
cannot interleave a mutation with a walk — so the only authorable scenario would
have asserted the trivial instance while reading as `covered`.
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 11 · **Branches:** `ticket/v1c-concord`

Runs alone. Branches from `main` after wave 10 merged — i.e. after the kernel
primitive (`V1C-KERNEL`, wave 8), the cell implementations (`V1C-CELLS` /
`V1C-OPS`, wave 9) and the inspector rewiring (`V1C-BE`, wave 10) have all
landed. You are conformance-testing a primitive that already exists on `main`;
read the shipped code, not the sketch.

## Context

`concord/` is ComputeNet's **executable specification**: an
implementation-neutral conformance suite over the normative text in
`doc/spec/`. Its layering (see `doc/ARCHITECTURE.md` §5 and
`AGENTS.md`'s `:concord` entry):

- **L0** — EARS requirement ids (`[NN-SLUG-nn]`) declared inline in the spec
  chapters themselves. `concord/schema/provenance.md:9-19` gives the id scheme;
  `:21-29` gives the five EARS templates; `:31-38` gives the gate a statement
  must pass to get an id at all ("checkable through the driver SPI").
- **L1** — the scenario language. Human contract: `concord/schema/scenario.md`.
  Serialization types: `concord/src/main/kotlin/civictech/concord/schema/`
  (`Scenario.kt`, `Step.kt`, `Check.kt`).
- **L2** — the corpus: one YAML document per scenario under `concord/corpus/`,
  each naming ≥1 L0 id in its `covers:` list.
- **L3** — the driver SPI (`concord/src/main/kotlin/civictech/concord/driver/Driver.kt`)
  and its one binding, `civictech.concord.driver.kernel`.
- **L4** — `doc/spec/CONCORDANCE.md`, **generated** by `./gradlew :concord:concordance`
  and never hand-edited.

Two rules govern everything you do here.

**The seam rule.** `concord/schema/scenario.md:6-9`: *"Growing the schema, the
step/verb set, the check vocabulary, or the catalogs is a **deliberate
schema-change ticket between waves** — not a corpus-authoring convenience."*
This ticket **is** that deliberate ticket. It is scheduled alone, between waves,
for exactly that reason.

**The neutrality rule.** Only `civictech.concord.driver.kernel` (and
sub-packages) may import `civictech.cell.*`. This is not prose — it is an
executable gate: `concord/src/test/kotlin/civictech/concord/provenance/NeutralityGateTest.kt:28-79`
walks `concord/src/{main,test}/kotlin` and fails the build on any other
package's kernel import. Everything you add to `Driver.kt`, `Check.kt`,
`Step.kt` and `Checks.kt` must be stateable in the spec's vocabulary, not the
kernel's.

### This ticket is the one exception to binding constraint 7

`doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md:140` states binding
constraint 7 for this whole plan: **"No edits under `concord/`; `:concord:check`
stays green untouched."** Every earlier ticket in this run — including
`V1C-KERNEL`, whose Decision 1 chose an opt-in interface partly *so that*
`concord/` would stay out of the diff — was forbidden from touching it.

**That constraint is lifted for this ticket alone**, granted by the C-replan
checkpoint, precisely because a schema change of this kind is only legitimate in
the form `scenario.md:6-9` requires: a deliberate, isolated, between-waves
ticket. It is not a licence to tidy `concord/`. Touch only what the three stages
below require, and leave every other scenario, catalog entry and evaluator
untouched.

### What wave 8 shipped that you are testing

`V1C-KERNEL` (`tickets/V1C-KERNEL.md`) added a **bounded state read**:
`BoundedStateful` / `StateRead` / `StatePage` / `Cursor` / `Provenance` /
`StateReadResult` in `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt`, and
`readState(ref, request)` on `ManagedHost` beside `snapshotOf`. Waves 9 and 10
implemented it across the data-cell and operator families and rewired the
inspector onto it.

Its nine decisions are the semantics you are pinning. **Decision 5 is the one
that shapes the requirement text**: page stability across a walk is *verifiable,
not promised*. The caller is promised per-page consistency, whole entries, no
duplicate entry within a key-ordered walk, and a frontier stamp; across pages it
is promised only that **if** every page carries an equal frontier, **then** the
union is exactly a snapshot at that frontier. A requirement that says "the union
of a walk equals the cell's state" without that antecedent is an overclaim the
implementation does not make and cannot honour under mid-walk mutation.

`doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md` **§6.3
in full** is the design note this ticket operationalizes: it drafts the three
requirements, sketches the three scenario shapes, and states why the schema must
grow first. Read it before you touch anything.

## Problem

### 1. The three requirements do not exist, and the lint will not force you to write them

A `covers:` id naming no L0 requirement is a **dangling reference** and is fatal:
`concord/src/main/kotlin/civictech/concord/provenance/Concordance.kt:207-218`
raises `Severity.FATAL` for it, and `concord/build.gradle.kts:61-73` wires
`concordanceGate` (the same scanner in fatal mode) into `check`. So a scenario
whose `covers:` names an unwritten requirement fails `./gradlew :concord:check`.
That is why the spec text must land **first**.

**The hazard, and you must not fall into it.** `ConcordanceScanner.scanRequirements`
(`Concordance.kt:42-56`) walks **every** `.md` file under `doc/spec/` — including
`doc/spec/90-roadmap/` — and its `idPattern` (`Concordance.kt:34`) matches the
bare bracketed form `[21-PULL-02]` anywhere in the raw text, backticks and all.
The design note already writes all three ids in that form
(`20-wave-neutral-read-design.md:755`, `:759`, `:762`), and so does this ticket.
**Consequence: the three ids are already "declared" as far as the scanner is
concerned, sourced from a roadmap document.** A `covers: [21-PULL-02]` would
resolve today, with zero chapter text, and the gate would stay green.

So the lint is not your forcing function — this ticket is. Land the requirement
text in the normative chapters anyway, and **verify it landed** by checking that
the regenerated `doc/spec/CONCORDANCE.md` attributes each id to its chapter file
(`ConcordanceScanner` keeps the first sighting in path order, and
`20-dataflow-semantics/…` sorts before `90-roadmap/…`, so a correct run shows
`20-dataflow-semantics/21-propagation.md` and
`20-dataflow-semantics/24-data-cells.md` as the source files, never the design
note).

### 2. The scenario language cannot express any of this

`concord/schema/scenario.md:146-153` documents a **closed step-verb table** —
`apply`, `quiesce`, `connect`, `disconnect`, `snapshot`, `restore`, `despawn` —
mirrored by the sealed `Step` hierarchy in
`concord/src/main/kotlin/civictech/concord/schema/Step.kt:19-107`. There is no
verb that reads a bounded page of a cell's state. `snapshot`
(`Step.kt:78-83`, `Driver.kt:59`) captures an **opaque blob** for a later
`restore`; it is a durability/migration verb, not a read, and its result is not
inspectable by a check.

`concord/schema/scenario.md:180-188` documents a **closed check vocabulary** —
`final-view`, `views-converge`, `incremental-equals-batch`,
`late-join-equals-early`, `observations-all-satisfy`, `observations-monotone`,
`replicas-converge`, `no-dead-letters`, `effect-count` — mirrored by the sealed
`Check` hierarchy in `concord/src/main/kotlin/civictech/concord/schema/Check.kt`.
None of them can express "no wave counter moved" or "the union of the pages
equals the view". Every one of them reads a *fold* (`Driver.readView`), an
*observation stream* (`Driver.observationLog`), the dead-letter list or the
effect log.

### 3. The check you need sits close to the neutrality line

The wave model **is** the spec's model: `(sourceId, counter)` timestamps,
`doc/spec/20-dataflow-semantics/22-consistency.md` §MessageContext rules and
§Wave completeness. So "the wave plane did not move" is a statement *about the
spec*, not about one implementation. But **reading an outlet's counter** is a
kernel-driver capability that no other driver may be assumed to have, and a
check that can only be evaluated by reaching into one implementation's internals
is not a conformance check — it is a kernel unit test wearing conformance
clothes. `V1C-KERNEL` already owns the kernel-side unit test for exactly this
(its "Wave neutrality, asserted not claimed" acceptance criterion, plus the
`StateRequest` contrast case). This ticket must not duplicate that test; it must
either state the property neutrally or say honestly that it cannot.

## Solution direction

Three stages, **in this order**. Each stage's premise is the previous stage's
output. If a stage's premise fails, **stop at that stage**, leave a coherent
partial result, and report — never weaken a later stage to make an earlier one
look finished.

---

### Stage 1 — the spec text (`doc/spec/`)

The three requirements land in the normative chapters, additively, before any
`concord/` file is touched.

**Verify the ids are still free** before using them:

```bash
grep -rn -E "\[21-PULL-0[0-9]\]|\[24-BOUND-[0-9]{2}\]" doc/spec/ concord/corpus/
```

At the time this ticket was written, `[21-PULL-01]` (`21-propagation.md:54`) and
`[21-CATCHUP-02]` (`:59`) are the only members of their families, `21-CATCHUP-01`
does not exist, and no `24-BOUND-*` id exists anywhere. If an ordinal has been
taken since, **mint the next free one and report the substitution loudly** — ids
are immutable once assigned and never reused (`provenance.md:19`).

**The drafted text** (from the design note §6.3). Adapt the wording to the
primitive that **actually shipped** — read the merged
`kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` and
`ManagedHost.readState`, not the design sketch — but do not weaken the
properties:

- **`[21-PULL-02]`**, in `21-propagation.md` §Pull (which begins at `:52`):
  *WHEN an instrument reads a cell's state under a cursor and limit, the
  framework SHALL answer without emitting, without linking, and without
  advancing any wave counter, delivered watermark or completeness set.*
- **`[21-PULL-03]`**, same section: *WHEN a bounded read is walked to completion
  and every page carries an equal frontier stamp, the union of its pages SHALL
  equal the cell's state at that frontier.* — Note the conditional: **"and every
  page carries an equal frontier stamp"** is load-bearing and comes straight from
  `V1C-KERNEL` Decision 5. Do not drop it; without it the requirement claims
  snapshot isolation the implementation deliberately does not provide.
- **`[24-BOUND-01]`**, in `24-data-cells.md`: *WHEN a data cell serves a bounded
  read, each page SHALL contain whole entries, no entry twice within a walk, and
  the cell's tag frontier at page time.*

**Placement.** `21-propagation.md` §Pull runs `:52-167` and already carries
`[21-PULL-01]`, `[21-CATCHUP-02]` and `[21-REBASE-01]` interleaved with
implementation prose; add the two new ids as their own short paragraphs inside
that section, in the register of the surrounding text. `24-data-cells.md`
already owns the per-family obligations — `[24-CATCHUP-01]` at `:101`, the shard
obligations `[24-SHARD-01]`–`[24-SHARD-04]` at `:677`, `:689`, `:698`, `:706`;
place `[24-BOUND-01]` where a reader looking for a data cell's read obligations
would find it.

**One piece of decided prose your change makes partly untrue — flag it, do not
quietly rewrite it.** `21-propagation.md:110-115` records an ⚠ EARS-GAP: *"the
without-relinking recompute has no driver-SPI trigger verb (the SPI exposes
`connect`/`apply`/`readView`, not a `requestState`), so only the link-based
catch-up (21-PULL-01 / 21-CATCHUP-02) is boundary-checkable; the no-relink pull
path is unobservable as the SPI stands."* Stage 2 changes how the SPI stands, for
the *read* half. You **may** add one additive sentence recording that a bounded
read verb now makes the no-relink read boundary-checkable. You **may not** delete
the EARS-GAP note: the without-relinking *recompute/re-emission* trigger is still
absent, and that half of the gap survives. Report whichever you did.

**docLints hazard.** `concord/src/main/kotlin/civictech/concord/lint/DocLints.kt:55`
resolves every backticked `cell.<pkg>.<Type>` pointer in `doc/spec/**` against
`kernel/src/main/kotlin/civictech/cell/<pkg>/`, fatally
(`DocLints.kt:84-133`). A pointer such as `cell.data.SetCell` resolves;
`cell.BoundedStateful` does not match the pattern at all (two-segment forms are
deliberately ignored, `DocLints.kt:43-53`); a pointer to a type in the wrong
package fails the build. Prefer file paths over dotted pointers in the new
requirement prose.

**Stage 1 stop condition.** If the shipped primitive turns out not to support one
of the three properties as stated — e.g. no page carries a frontier stamp for
some family — do **not** soften the requirement to match. Write the requirement
the spec means, and carry the mismatch into stage 3's stop handling (`DISPUTES.md`).

---

### Stage 2 — the schema change (`concord/schema/` + `concord/src/`)

The design note proposes the minimum vocabulary: **one step verb and two checks**.

```yaml
# step
- {type: read-state, on: s, limit: 2}          # cursor threading is the driver's, not the scenario's

# checks
- {type: wave-plane-unchanged, cell: s}
- {type: pages-equal-view, view: v}
```

Exact field sets are yours; the shape above is the design note's sketch, not a
contract. What is **not** yours is the size of the change: one step, two checks,
and the smallest driver surface that can serve them.

**Where a new step verb and a new check actually thread.** All of these move
together or the module does not compile:

| # | File | What changes |
|---|---|---|
| 1 | `concord/schema/scenario.md:146-153` | the step-verb table gains a `read-state` row (verb, canonical YAML, driver verb) |
| 2 | `concord/schema/scenario.md:180-188` | the check table gains two rows, each with the same precision as its neighbours |
| 3 | `concord/schema/scenario.md` (prose) | **what a conforming driver must be able to observe** to evaluate each new check — see the honesty gate below |
| 4 | `concord/src/main/kotlin/civictech/concord/schema/Step.kt:19-107` | a new `@Serializable @SerialName("read-state")` data class implementing `Step`; model it on `SnapshotStep` (`:78-83`) |
| 5 | `concord/src/main/kotlin/civictech/concord/schema/Check.kt` | two new `@SerialName`d data classes implementing `Check`. **`ObservationsWholeWaves` (`Check.kt:102-107`, evaluator `Checks.kt:168-198`) is your precedent** — it is the one check added after the vocabulary was frozen, and its KDoc is a model for *why* an existing check could not express the property |
| 6 | `concord/src/main/kotlin/civictech/concord/check/Checks.kt:38-49` | the `evaluate` dispatch is an exhaustive `when` over the sealed `Check` — it will not compile until both new arms exist; plus one evaluator function each, in the register of `Checks.kt:168-198` |
| 7 | `concord/src/main/kotlin/civictech/concord/check/Checks.kt:293-296` | `CheckContext` exposes only `driver` and `scenario`. If a check needs page material a *step* produced, this interface widens — its own KDoc licenses that ("W1-B may widen this — it is the check layer's own type"). Widening it also forces `RunContext` (`CorpusRunner.kt:62`) |
| 8 | `concord/src/main/kotlin/civictech/concord/driver/Driver.kt:18-72` | the neutral SPI verb(s). **This is the neutrality-critical file** — every word of its KDoc is about being implementation- and transport-neutral (`Driver.kt:5-17`) |
| 9 | `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelDriver.kt` | the one binding. Model on `snapshot` (`:267-273`) and `readView` (`:257-262`); the cell table is `cells` (`:50`, entries `Bound` at `:90-106`). The `set-source` catalog id binds to `SetCell` (`KernelCatalog.kt:77`) — the exact cell `V1C-KERNEL` made `BoundedStateful` — so no catalog change is needed for the scenarios below |
| 10 | `concord/src/test/kotlin/civictech/concord/runner/CorpusRunner.kt:194-214` | `runScript`'s `when (step)` is exhaustive over the sealed `Step` — a new step forces a branch here |
| 11 | `concord/src/test/kotlin/civictech/concord/check/FakeDriver.kt` (44 lines) | implements `Driver` for the check unit tests; a new SPI verb forces an implementation here |
| 12 | `concord/src/test/kotlin/civictech/concord/check/ChecksTest.kt` | unit tests for the two new evaluators against hand-built fixtures, in the style of the existing ones |
| 13 | `concord/src/test/kotlin/civictech/concord/schema/ScenarioParseTest.kt:16-69` | the round-trip freeze; add the new scenarios (or one of them) to the pilot list |

**Lenient parsing means a typo is silent.** `ConcordYaml` runs with
`strictMode = false` (`concord/src/test/kotlin/civictech/concord/yaml/ConcordYaml.kt:39-48`,
rationale at `:36-37`) — an unknown key is **ignored**, not rejected. A mistyped
field on your new step will therefore parse cleanly and silently do nothing. The
decode → encode → decode structural-equality round-trip in `ScenarioParseTest` is
the only mechanism that catches it. Use it.

**Stage 2 stop condition — read this before you start typing.** If the change
turns out to need materially more than *one step verb, two checks, and the
minimum driver verb* — a new driver capability interface, a new conformance
profile, a new `cell-catalog.md` id, a new `generator:` field, a second
`CheckContext` collaborator — **stop and report**. Do not grow the schema
opportunistically to make a scenario pass; that is exactly what
`scenario.md:6-9` forbids and exactly why this ticket exists as its own wave. A
stopped stage 2 with an honest report is a success; a quietly doubled vocabulary
is not.

#### The honesty gate — the most important requirement in this ticket

`wave-plane-unchanged` is the check that can go wrong. You must:

1. **State, in `concord/schema/scenario.md`, exactly what a conforming driver
   must be able to observe** in order to evaluate it — **in spec vocabulary, not
   kernel vocabulary**. "The driver reports its cell's outlet `waveCounter`" is
   kernel vocabulary and fails this bar. A statement in terms of the spec's own
   model — per-source wave positions `(sourceId, counter)`
   (`doc/spec/20-dataflow-semantics/22-consistency.md`), delivered watermarks,
   completeness sets — such that a second, non-kernel driver could implement it
   from the spec alone, passes.
2. **If you cannot state it without naming a kernel internal: STOP.** Leave
   `[21-PULL-02]` in the spec — it is a true, decided requirement and it stays.
   Do **not** weaken it into a scenario that passes for a weaker reason (e.g. a
   `no-dead-letters`-only scenario carrying `covers: [21-PULL-02]`, which would
   read as covered in the concordance while asserting nothing about the wave
   plane). File it in `concord/corpus/DISPUTES.md` instead, in the form the
   existing entries use: the scenario id it would have carried, the requirement
   it would cover, the missing capability, and the check to restore once the
   capability lands (`DISPUTES.md:3-7`).

`AGENTS.md` is explicit: *"A requirement that cannot be checked honestly is filed
in `concord/corpus/DISPUTES.md`, never weakened into a passing scenario."* Note
the ordering this ticket's stages create: `DISPUTES.md` is for requirements that
**exist** and cannot be checked. Before stage 1 these ids do not exist and filing
them would be a dispute against unwritten text (the design note says so at
`20-wave-neutral-read-design.md:790-793`). **After stage 1 they do exist**, so a
dispute filing is available to you — and is the correct outcome if the
neutrality bar cannot be met.

Note also that a dispute here costs little: `V1C-KERNEL`'s own wave-neutrality
test already pins the property in the kernel. What would be lost is the
*cross-implementation* obligation, and saying so plainly is worth more than a
scenario that pretends to carry it.

---

### Stage 3 — the three scenarios (`concord/corpus/`)

Mirror `concord/corpus/21-propagation/21-PULL-01.yaml` (24 lines — read it, it is
the exact shape): a header comment, `id`/`title`/`covers`/`profile: core`/
`kind: example`, a `narrative` given/when/then triple, a small `graph`, a
`script` ending in `{type: quiesce}`, and a check block closing with
`{type: no-dead-letters}`.

- **`21-PULL-02`** → `concord/corpus/21-propagation/21-PULL-02.yaml`. A
  `set-source` at quiescence with a `set-view` linked to it; a bounded read
  walked to completion. Checks: the source's wave plane unchanged; no dead
  letters.
- **`21-PULL-03`** → `concord/corpus/21-propagation/21-PULL-03.yaml`. Same graph.
  Check: the union of the walk's pages equals the golden view; plus no dead
  letters.
- **`24-BOUND-01`** → `concord/corpus/24-data-cells/24-BOUND-01.yaml` (that
  directory holds every `24-*` scenario; the layout is one directory per spec
  chapter). Sweep `limit` against a source of N entries, checking
  union-equals-view at every limit.

**A drift you must handle, not inherit.** The design note calls `24-BOUND-01` "a
generative variant". `kind: generative` in this corpus means something specific
and narrow: the scenario carries **no `graph:`** and a `generator:` block, and
`ScenarioGenerator` (`concord/src/main/kotlin/civictech/concord/generator/ScenarioGenerator.kt:64-80`,
exemplar `concord/corpus/24-data-cells/24-GEN-01.yaml:46-56`) synthesizes the
graph, the op script **and a hardcoded set of four property checks**. There is no
parameter-sweep facility and no way to inject a custom check. Making
`24-BOUND-01` generative in that sense means growing the generator — which is
squarely inside stage 2's stop condition.

**So author it as `kind: example`**, sweeping `limit` explicitly: several
`read-state` steps at different limits (1, 2, N−1, N, N+1 are the interesting
ones — the boundaries where a page-count off-by-one shows) against one source of
N entries, each walk checked. If that means three small scenarios rather than one
(`24-BOUND-01`, `-02`, `-03`), that is fine and is cheaper than a schema change —
but mint the extra ids in `24-data-cells.md` too, and say so in the report.
Record the drift from the design note either way.

Every scenario: `profile: core` (so it runs in every build and in the fast local
loop), a non-empty `covers:` (an empty one is a fatal orphan,
`Concordance.kt:200-206`), and honest checks only.

## Files expected to touch

- `doc/spec/20-dataflow-semantics/21-propagation.md` — requirement text only,
  additive (`[21-PULL-02]`, `[21-PULL-03]`, plus at most one additive sentence on
  the `:110-115` EARS-GAP note).
- `doc/spec/20-dataflow-semantics/24-data-cells.md` — requirement text only,
  additive (`[24-BOUND-01]`, and any further `24-BOUND-*` ordinal stage 3 needs).
- `concord/schema/scenario.md` — the step-verb table (`:146-153`), the check
  table (`:180-188`), and the driver-observability prose the honesty gate
  requires.
- `concord/src/main/kotlin/civictech/concord/schema/Step.kt` — the new step type.
- `concord/src/main/kotlin/civictech/concord/schema/Check.kt` — the two new check
  types.
- `concord/src/main/kotlin/civictech/concord/check/Checks.kt` — the dispatch arms
  and the two evaluators; `CheckContext` only if genuinely needed.
- `concord/src/main/kotlin/civictech/concord/driver/Driver.kt` — the neutral SPI
  verb(s).
- `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelDriver.kt` — the
  kernel binding of those verbs. **The only file in this diff that may import
  `civictech.cell.*`.**
- `concord/src/test/kotlin/civictech/concord/runner/CorpusRunner.kt` — the new
  `runScript` branch (and `RunContext` if `CheckContext` widened).
- `concord/src/test/kotlin/civictech/concord/check/FakeDriver.kt`,
  `ChecksTest.kt`, `concord/src/test/kotlin/civictech/concord/schema/ScenarioParseTest.kt`
  — the harness-side updates a schema change forces.
- `concord/corpus/21-propagation/21-PULL-02.yaml`,
  `concord/corpus/21-propagation/21-PULL-03.yaml`,
  `concord/corpus/24-data-cells/24-BOUND-01.yaml` (+ any extra ordinals).
- `concord/corpus/DISPUTES.md` — **only in the stop case.**
- `doc/spec/CONCORDANCE.md` — **regenerated, never hand-edited**
  (`./gradlew :concord:concordance`).
- This ticket's own `**Status**:` line.

**Explicitly NOT touched:** `kernel/**`, `inspect/**`, `wire/**`, `gen/**`,
`nature/**`, `testkit/**`, `demo/**`, `concord/schema/cell-catalog.md`,
`concord/schema/function-catalog.md`, any existing corpus scenario, any other
plan document, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md` (orchestrator-owned).

Touching anything outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/20-wave-neutral-read-design.md`
  **§6.3 in full** (`:744-793`) — the section this ticket operationalizes; and
  §3.4 (`:406-456`) for the stability contract the requirement text must not
  overclaim.
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V1C-KERNEL.md` — all nine
  decisions, **Decision 5 in particular** (`:248-280`).
- The **merged** `kernel/src/main/kotlin/civictech/cell/BoundedRead.kt` and
  `ManagedHost.readState` — the shipped shape wins over every sketch in every
  document above.
- `concord/schema/scenario.md` — **the whole file**; the seam rule at `:6-9`, the
  step table at `:146-153`, the script semantics at `:165-172`, the check table
  at `:180-188`, the value model at `:210-217`.
- `concord/schema/provenance.md` — **the whole file**; the id scheme `:9-19`, the
  EARS templates `:21-29`, the SPI-checkability gate `:31-38`, the concordance
  format `:41-56`, the lint rules `:58-68`.
- `concord/schema/cell-catalog.md` and `concord/schema/function-catalog.md` — so
  you know what you are *not* allowed to add without stopping.
- `concord/corpus/21-propagation/21-PULL-01.yaml` — all 24 lines; the shape you
  mirror.
- `concord/corpus/24-data-cells/24-GEN-01.yaml:1-56` — what `kind: generative`
  actually means here.
- `concord/corpus/DISPUTES.md:1-10` (the header; then skim the entries for the
  filing format).
- `concord/src/main/kotlin/civictech/concord/schema/Step.kt`,
  `Check.kt`, `Scenario.kt` — the serialization types.
- `concord/src/main/kotlin/civictech/concord/check/Checks.kt` — the dispatch
  (`:38-49`), `observationsWholeWaves` (`:168-198`) as the addition precedent,
  `CheckContext` (`:293-296`).
- `concord/src/main/kotlin/civictech/concord/driver/Driver.kt` — the whole file
  (121 lines); its KDoc `:5-17` is the neutrality contract in prose.
- `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelDriver.kt` —
  `snapshot` `:267-273`, `readView` `:257-262`, `cells` `:50`, `Bound` `:90-106`.
- `concord/src/main/kotlin/civictech/concord/driver/kernel/KernelCatalog.kt:77`
  (`set-source` → `SetCell`) and `:150` (`set-view`).
- `concord/src/test/kotlin/civictech/concord/runner/CorpusRunner.kt` —
  `runScenario` `:87-138`, `runScript` `:194-214`, `params` `:226-236`.
- `concord/src/test/kotlin/civictech/concord/provenance/NeutralityGateTest.kt` —
  the executable neutrality gate.
- `concord/src/main/kotlin/civictech/concord/provenance/Concordance.kt` —
  `idPattern` `:34`, `scanRequirements` `:42-56`, `buildConcordance` `:190-232`.
- `concord/src/main/kotlin/civictech/concord/lint/DocLints.kt:43-70`, `:162-197`
  — the package-pointer and Status-header lints your spec edits must survive.
- `concord/build.gradle.kts:25-30` (profile default is `core,dist,dur`), `:38-52`
  (`concordance`), `:61-73` (`concordanceGate`), `:82-97` (`docLints`, and
  `check` depending on both).
- `doc/spec/20-dataflow-semantics/21-propagation.md` §Pull — `:52-167` in full;
  `[21-PULL-01]` at `:54`, `[21-CATCHUP-02]` at `:59`, the ⚠ EARS-GAP at
  `:110-115`, `[21-REBASE-01]` at `:159`.
- `doc/spec/20-dataflow-semantics/24-data-cells.md` — `[24-CATCHUP-01]` at `:101`
  and the surrounding per-family obligations `:95-105`; the shard obligations
  `:667-714`.
- `doc/spec/20-dataflow-semantics/22-consistency.md` — the wave model your
  neutral phrasing of `wave-plane-unchanged` must be written in.
- `AGENTS.md` §"Start every task here", §"Core invariants to protect",
  §"Verification"; `doc/ARCHITECTURE.md` §5.
- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" — all ten, and the note above about constraint 7 being lifted for
  this ticket only.

## Acceptance criteria

- [ ] **Stage 1 first.** The three requirement ids exist in `doc/spec/`
      (`21-propagation.md` §Pull for the two `21-PULL-*`, `24-data-cells.md` for
      `24-BOUND-*`), are still unique, and are written in an EARS template from
      `provenance.md:21-29`.
- [ ] `[21-PULL-03]`'s wording retains the **equal-frontier antecedent**; no
      requirement claims snapshot isolation across pages that `V1C-KERNEL`
      Decision 5 deliberately does not provide.
- [ ] The wording was checked against the **merged** `BoundedRead.kt` /
      `ManagedHost.readState`, not against the design note's sketch, and any
      divergence is reported.
- [ ] The ⚠ EARS-GAP note at `21-propagation.md:110-115` is either left intact or
      extended additively; it is not deleted, and the outcome is reported.
- [ ] `concord/schema/scenario.md`'s step and check tables document the new verbs
      with the same precision as their neighbours — canonical YAML form, driver
      verb, semantics.
- [ ] `scenario.md` states **what a conforming driver must be able to observe**
      to evaluate each new check, in spec vocabulary, with no kernel type named.
- [ ] The schema growth is **one step verb and two checks** plus the minimum
      driver surface. Anything larger stopped and was reported instead of built.
- [ ] The three (or more) scenarios exist under the corpus directory matching the
      existing per-chapter layout, carry a non-empty `covers:`, `profile: core`,
      a `narrative` triple, a `script` ending in `quiesce`, and a check block
      closing with `no-dead-letters`.
- [ ] The scenarios **round-trip** (decode → encode → decode structural equality)
      through `ScenarioParseTest`, so a mistyped field cannot be silently dropped
      by the lenient parser (`ConcordYaml.kt:39-48`).
- [ ] The scenarios pass under the **default** profile set (`core,dist,dur`), on
      **every** run of the schedule sweep — not just run 0.
- [ ] The two new evaluators have unit tests in `ChecksTest.kt` against
      hand-built fixtures, including a **negative** case each (a fixture the
      check must fail), so neither can pass vacuously.
- [ ] `./gradlew :concord:check` is green: zero dangling `covers:` ids, zero
      orphan scenarios, `NeutralityGateTest` green (no `civictech.cell.*` import
      outside `civictech.concord.driver.kernel`).
- [ ] `doc/spec/CONCORDANCE.md` is **regenerated** (`./gradlew :concord:concordance`),
      not hand-edited, the three ids appear as `covered`, and their source-file
      attribution is the **chapter file**, not the design note.
- [ ] `./gradlew :concord:docLints` is clean — Status headers intact, no
      unresolved `cell.<pkg>.<Type>` pointer introduced by the new spec prose.
- [ ] Nothing under `kernel/`, `inspect/`, `wire/`, `demo/`, `gen/`, `nature/` or
      `testkit/` in the diff. No generated/build output. No existing corpus
      scenario modified. No gap (`G-*`) or consistency (`C-*`) marker removed.
- [ ] **If any stage stopped**, the stop is recorded — in `DISPUTES.md` for a
      requirement that exists and cannot be checked honestly, or in the report for
      an over-large schema change — and the partial result is coherent: no
      scenario that passes for a weaker reason than its `covers:` claims, and no
      requirement silently softened to fit a check.

## Verify

```bash
# fast local loop while iterating
./gradlew :concord:test -Pconcord.profiles=core

# the full acceptance corpus (core + dist + dur — the build default)
./gradlew :concord:test

# regenerate the concordance (writes doc/spec/CONCORDANCE.md)
./gradlew :concord:concordance

# the gates: concordanceGate (fatal dangling/orphan) + docLints, both wired into check
./gradlew :concord:check
./gradlew :concord:docLints

# repository-wide gate
./gradlew test
```

Inspect the regenerated `doc/spec/CONCORDANCE.md` by hand before finishing:
confirm the three new rows read `covered`, that the requirement source files are
the chapter files, and that your change removed no previously-covered row.

## Report on completion

- Checks run and their results, per command.
- **The final requirement text of all three ids, verbatim** — exactly as it now
  reads in `doc/spec/`, including the EARS template each uses.
- **The exact schema delta**: every file touched under `concord/`, and the new
  step/check definitions in full (the YAML form and the Kotlin type), plus the
  driver SPI verb signatures added.
- **Whether `wave-plane-unchanged` could be stated implementation-neutrally.**
  If yes: quote the sentence from `scenario.md` that states what a conforming
  driver must observe, and say why it is spec vocabulary rather than kernel
  vocabulary. If no: quote exactly what you filed in
  `concord/corpus/DISPUTES.md`, and state what capability would resolve it.
- **Any drift between the design note's §6.3 sketch and what the shipped
  primitive made expressible** — in particular the `24-BOUND-01` generative
  question, and anything the merged `BoundedRead.kt` shape changed about the
  drafted requirement wording.
- Whether `CheckContext` had to widen, and if so what it now carries and why the
  check layer (not the driver SPI) was the right place for it.
- Whether the concordance's source-file attribution for the three ids came out as
  the chapter files, and what `doc/spec/CONCORDANCE.md` gained or lost overall
  (row counts before/after, any new coverage-gap notes).
- Anything specified here you could not do, and why — especially any stage you
  stopped at, and what the next ticket would need in order to finish it.
