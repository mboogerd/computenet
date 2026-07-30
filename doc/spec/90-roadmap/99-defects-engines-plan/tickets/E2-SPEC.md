# E2-SPEC — the observation frontier becomes normative spec text: internal consistency, aligned composites, absorb-acks, and non-monotone emission gating

**Status**: Specified — not-started
**Model:** claude-sonnet-5 (effort xhigh) · **Escalate to:** claude-opus-5, fresh session
**Wave:** B2 · **Branches:** ticket/e2-spec

## Context

ComputeNet is specification-led: code should make the cited specification true.
This ticket is the inverse repair — the roadmap decided a set of consistency
rules, part of the kernel already implements them, and the normative spec
chapters still don't state them. The complete content source is
`doc/spec/90-roadmap/96-incremental-engines-plan.md` **§Milestone E2**
(preamble, `:162-175`) and **§E2.1** (`:177-195`). Read both in full before
editing anything; every sentence you write must be traceable to that text plus
the research citations below. This ticket transcribes decided content into the
spec — it does not design.

**Sequencing.** This ticket runs in wave B2, after ticket E1-SPEC merged. Both
tickets edit `doc/spec/20-dataflow-semantics/24-data-cells.md`, so your
worktree branches from a `main` that already contains E1-SPEC's §Tagged maps
section in that file. Expect it to be there; leave it alone.

**The seam today.** `cell.observe.CompositeSink`
(`kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:387-393`) is
honestly documented as **point-consistent per outlet**, not wave-aligned — a
read may pair `common` at wave `t` with `filtered` at wave `t-1`. The
`observeAll` KDoc (`Observe.kt:535-554`) names the two blockers to the
wave-aligned composite: (1) name erasure — `cell.consistency.GlitchFreeCell`
replays raw deltas to one outlet, erasing which *named* source each came from
(`:541-544`); (2) absorbing edges that never advance watermarks (`:545-551`).
E2.1's four rules dissolve both at the spec level.

**The repo is ahead of the 96-plan here — know this before you start.**
96 §E2 describes the absorb-ack helper (E2.2) and the `WaveFrontier`
extraction (E2.3's first half) as future work, but both have **already
landed** via the archived composition run (tickets CP-A3/CP-A4,
`doc/archive/runs/COMPOSITION-TICKETS.md`):

- `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt:7-31` — a
  `fun absorbAck()` on `FanOutlet` that sends `cell.control.Progress`
  `(sourceId, thru)` down the outlet's links when a reactive wave is absorbed;
  adopted across the operator suite (`FilterCell`, `SemiJoinCell`,
  `IntersectSetCell`, `GroupByCell`, `CombineLatestCell`, …, all under
  `kernel/src/main/kotlin/civictech/cell/data/op/`), tested by
  `kernel/src/test/kotlin/civictech/cell/data/op/OperatorAbsorbAckTest.kt`.
- `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:58` —
  the wave-completeness fold extracted from `GlitchFreeCell` as a reusable
  per-inlet policy; it folds the `Progress` lane
  (`kernel/src/main/kotlin/civictech/cell/protocol/Protocols.kt:44`).

That code cites "spec 20/22 §Completeness over silent or stuck edges" — a
section whose normative rule **does not yet exist**; 20/22 still labels the
absorb-ack "decided design …, unimplemented" in two places. This ticket writes
the text the code already cites, and trues up the stale labels. The
`observeAligned` sink, `emitOnFrontier` gating, and the balanced-transfer
suite (96 E2.3-second-half/E2.4/E2.5) have **not** landed — those rules are
spec-ahead-of-code, which is the correct order.

## Problem

Four decided rules exist only as roadmap prose (96 §E2.1) and research
takeaways. Consequences today:

- `doc/spec/20-dataflow-semantics/22-consistency.md` has no statement of what
  guarantee a multi-view observation edge provides, so `CompositeSink`'s
  point-consistency has no spec-level ideal to be measured against, and the
  landed `WaveFrontier`/absorb-ack machinery implements an unwritten rule.
- 22's §Completeness over silent or stuck edges (`:175`) still says
  "(decided in 93 I-18, unimplemented)" and its chapter Status header (`:3`)
  says "`Progress` absorb-acks … decided design (CP-A2), unimplemented" —
  both stale against `AbsorbAck.kt`. A spec↔code divergence left implicit
  violates the repository's authority order.
- `doc/spec/20-dataflow-semantics/24-data-cells.md`'s operator-library
  section documents `SemiJoinCell` as "not glitch-free (22's wrapper is the
  remedy)" (`:170`) but never states *when* absence-asserting emission is
  correct, and does not document `cell.data.op.CombineLatestCell`'s
  null-extension hazard at all.
- `backlog/consistent-multiview-snapshot.md` (F-5) points at the roadmap
  (`:3-5`) but not at any normative spec section, because none exists.

## Solution direction

Make E2.1's four rules normative. The decided content, in full (96-plan
`:182-193` is authoritative where wording differs):

1. **The guarantee statement.** The observation edge provides **internal
   consistency** — "every output is the correct output for some subset of the
   inputs provided so far" (cite
   `doc/research/incremental-engines/04-cross-cutting-watermarks-consistency.md`
   §3, `:82-109`; the definition is quoted at `:84-85`) — achieved as
   **per-source-vector-frontier alignment**: every emitted composite output is
   the correct output for some per-source vector frontier of this replica's
   inputs. Scalar virtual time (Materialize's single total-order timestamp
   assignment at ingestion) is **explicitly rejected**, with a one-line
   rationale: it requires a coordination point (timestamp oracle) that
   ComputeNet's coordination-free replication model refuses (04 §4,
   `:111-137`, esp. the vector-frontier analog at `:131-137`; also
   `02-timely-differential-materialize.md` §7, `:126-132`).
2. **The aligned-composite rule.** A composite for wave `(s, t)` is assembled
   only after every contributing view has settled every wave ≤ the shared
   frontier; **per-name inlets preserve view identity** — dissolving
   `observeAll`'s documented blocker 1 (name erasure). This is the spec for
   the future `observeAligned` sink (96 E2.3), written before its code.
3. **The absorb-ack rule, made normative for operator cells.** An operator
   cell whose waved input yields no effective output MUST advance downstream
   watermarks via `Progress(sourceId, thru)` — dissolving blocker 2, the
   G-40 family. This is the rule `cell.control.absorbAck` already implements.
4. **Non-monotone emission gating.** Antijoin membership flips and outer-join
   null-extensions are **absence assertions** — non-monotone in the CALM
   sense (`03-lasp-crdt-lattice.md` §5, `:204-231`) — and emit only at wave
   completeness, **coalesced to the wave's net effect** (a transient
   enter+exit within one wave cancels). Per 96 E2.4 (`:232-247`) the gate is
   opt-in and the default stays ungated; the spec states the semantic rule
   and that ungated emission remains remediated by 22's wrapper, not a
   smarter convergent cell (research rejects that — absence-based emission is
   non-monotone; some sealing is unavoidable, and per-wave sealing is the
   cheapest ComputeNet has).

The **balanced-transfer suite** (research 04 §3, `:89-92` and `:102-109`;
transcribed by 96 E2.5, `:248-258`) is named in the new section as the
acceptance benchmark for the guarantee. Feldera's batch-prefix framing
(`01-dbsp-feldera.md` §6, `:135-153`) is the supporting citation for why
per-source-prefix batch-equivalence per replica is the right *shape* — cite
it where the guarantee statement is made.

### Edit 1 — `doc/spec/20-dataflow-semantics/22-consistency.md`: new §The observation frontier

Insert a new `## The observation frontier` section **after the whole §Local
glitch-freedom block** — i.e. between the end of the G-40 gap block (`:207`)
and `## Topology versioning` (`:209`). (`## Local glitch-freedom (opt-in)` is
`:125`; its subsections `### The glitch-free frontier (composition)` `:147`
and `### Completeness over silent or stuck edges …` `:175` are part of that
block. A `###` under §Local glitch-freedom is acceptable if you judge the
material subordinate, but a sibling `##` is the expected shape — this is the
multi-view observation edge, not the single-cell wrapper.)

Content: rules 1 and 2 above, the balanced-transfer benchmark naming, and the
research citations. Follow the chapter's existing register — normative prose
with decided/implemented honesty markers, RFC-2119 verbs where a rule binds.
Connect to what exists: the section should say that
`cell.consistency.WaveFrontier` is the landed completeness fold this section
governs, and that the aligned composite sink itself is specified here ahead
of its implementation (96 E2.3).

### Edit 2 — same file, §Completeness over silent or stuck edges (`:175-207`)

- Add the normative absorb-ack rule (rule 3) as a MUST on operator cells,
  alongside the existing `[22-LIVE-01]` (`:177-180`) and the watermark prose
  (`:182-196`) — the prose already describes the mechanism; what's missing is
  the binding obligation on the emitting side.
- True up the stale labels **only where this section's rule is concerned**:
  the heading's "(decided in 93 I-18, unimplemented)" (`:175`) and the
  chapter Status header's "`Progress` absorb-acks … decided design (CP-A2),
  unimplemented" clause (`:3`) should honestly reflect that in-process
  absorb-acks are implemented (`cell.control.absorbAck`, CP-A3) while the
  bridged (across-the-wire) leg retains whatever status you verify it to
  have. The Status line must keep beginning with a `:concord:docLints`
  vocabulary word (`Specified` today — keep it).
- **Do not remove or weaken the ⚠ GAP (G-40) block (`:198-207`).** The
  residual (Stall/DEGRADE contract, backstop calibration, generative
  harness) closes only when 96 E2.2's remaining scope lands. You may adjust
  its wording so it no longer claims the absorb-ack itself is unbuilt, but
  the marker stays.

### Edit 3 — `doc/spec/20-dataflow-semantics/24-data-cells.md`: §Operator library emission-gating paragraphs

The operator library lives as the `- ~~Operator library~~ **Implemented …**`
bullet at `:106` under `## Required next steps in the family` (`:95`) — there
is no `## Operator library` heading; the "section" is that bullet's nested
list. Two additions, both rule 4:

- Extend the `SemiJoinCell` entry (`:155-175`, ids `[24-OP-SEMIJOIN-01..03]`)
  with a paragraph: antijoin membership flips are absence assertions
  (non-monotone, CALM — cite 03 §5); gated emission at wave completeness
  coalesces to the wave's net minted enter/exit set; the gate is opt-in, the
  ungated default keeps current wrapper-remediated semantics. Cross-reference
  20/22 §The observation frontier.
- Add a `cell.data.op.CombineLatestCell` paragraph covering the outer-join
  null-extension case: a null-extension is an assertion that the other side
  is absent at this frontier; ungated it can be emitted and later retracted
  within one wave (the internal-consistency essay's exact outer-join failure,
  04 §3); gated, it emits only at wave completeness. Place it adjacent to the
  join entries in the same nested list, matching their style. (The cell
  exists — `kernel/src/main/kotlin/civictech/cell/data/op/CombineLatestCell.kt`
  — but has no entry in this list yet; scope your addition to the
  emission-gating rule, not a full catalog entry for the cell.)

Do not renumber or rewrite the existing `[24-OP-*]` requirements.

### Edit 4 — `backlog/consistent-multiview-snapshot.md`: absorbed-marker note

The file already carries an absorbed-into-the-roadmap blockquote (`:3-5`)
pointing at 96 §E2. Extend it (same convention as
`backlog/06-or-map-tagged-map-delta.md:3-5`) to record that the guarantee is
now **normative** at `doc/spec/20-dataflow-semantics/22-consistency.md` §The
observation frontier, keeping the roadmap acceptance pointer
(E2.3/E2.5/E2.6) intact.

### Edit 5 (optional, implementer judgment) — G-40 planned-realization pointer

`doc/spec/90-roadmap/91-gap-analysis.md:63` (the G-40 row) already carries
"**Planned realization (residual)**: absorb-acks from operator cells 96 §E2
(E2.2); …". You MAY extend that pointer to also cite the now-normative 20/22
rule. **No G-40 marker is removed** — kernel and inspect code cite it
(`inspect/src/main/kotlin/civictech/inspect/WaveHealth.kt:16`,
`Protocols.kt:41-44`) and the residual stays open.

### Requirement ids — optional, with the honest trade stated

Minting EARS ids for the new rules is OPTIONAL. If you mint, follow 22's
existing `[22-*]` conventions (`[22-GF-01]`/`[22-GF-02]`/`[22-LIVE-01]` are
the local exemplars; a fresh slug like `22-OBS-nn` fits) and
`concord/schema/provenance.md` §1 — note its L0 gate: an id is assigned only
when the statement is checkable through the driver SPI, and "progress acks"
are explicitly listed among internals that stay normative prose *without*
ids. The absorb-ack rule's boundary-observable consequence (a wave completes
at quiescence without a further write) is checkable; the frame-level
mechanism is not — mint at the observable level or not at all. New ids with
no covering scenario produce NOTE-severity coverage-gap rows in the
concordance (`concord/src/main/kotlin/civictech/concord/provenance/Concordance.kt:222-227`)
— that is acceptable and honest. If you mint any id, regenerate
`doc/spec/CONCORDANCE.md` via `./gradlew :concord:concordance`; never
hand-edit it.

### What NOT to do

- **Spec text only.** No kernel/`inspect`/`wire`/`demo` code, no tests, no
  `concord/corpus/**` scenarios.
- **No edit to `96-incremental-engines-plan.md`** — the roadmap stays as the
  historical decision record even where the repo has moved past it.
- **No edit to `Observe.kt`'s KDoc** (its blocker-2 text is now stale against
  `AbsorbAck.kt`, and that is a *code* comment — 96 E2.3's code ticket
  updates it, not this spec ticket).
- **No G-40 marker removal**, in docs or code.
- Do not touch E1-SPEC's §Tagged maps section in 24-data-cells.md.
- Do not "fix" other stale statuses you notice along the way; only the two
  labels named in Edit 2 are in scope.
- `:concord:docLints` lints every markdown file under `doc/spec`, including
  what you write: keep exactly one `**Status**:` line per file, beginning
  with `Specified|Partial|Implemented|Exploratory|Historical|Living`
  (`concord/src/main/kotlin/civictech/concord/lint/DocLints.kt:22-26,70`),
  and make every backticked `` cell.<pkg>.<Type> `` pointer resolve to a real
  declaration (`DocLints.kt:12-18`). Caution: a backticked, uppercase-led
  pointer to the absorb-ack helper (cell.control + the file's name,
  PascalCase) would FAIL that lint — the file declares only `fun absorbAck`
  (lowercase), no type of that name; write the lowercase function form
  (`cell.control.absorbAck`) or a file path.

## Files expected to touch

- `doc/spec/20-dataflow-semantics/22-consistency.md` — Edits 1 and 2.
- `doc/spec/20-dataflow-semantics/24-data-cells.md` — Edit 3.
- `backlog/consistent-multiview-snapshot.md` — Edit 4.
- `doc/spec/90-roadmap/91-gap-analysis.md` — Edit 5, optional, G-40 row only.
- `doc/spec/CONCORDANCE.md` — regenerated output only, and only if ids were
  minted.
- This ticket's `**Status**:` line.

Nothing else.

## Read first

- `doc/spec/90-roadmap/96-incremental-engines-plan.md:162-195` — the E2
  preamble and E2.1, the complete content source; skim E2.2-E2.5
  (`:197-258`) so your text neither pre-implements nor contradicts them.
- `doc/spec/20-dataflow-semantics/22-consistency.md` in full — especially
  `:3` (Status header), `:125-173` (§Local glitch-freedom + frontier),
  `:175-207` (§Completeness + G-40 block), and the chapter's EARS style.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:95-193` — the operator
  library bullet and the SemiJoin entry; note where E1-SPEC's §Tagged maps
  landed on your branch.
- `doc/research/incremental-engines/`: `04-…consistency.md` §3 (`:82-109`)
  and §4 (`:111-137`); `02-…materialize.md` §7 (`:126-132`);
  `01-dbsp-feldera.md` §6 (`:135-153`); `03-lasp-crdt-lattice.md` §5
  (`:204-231`).
- `kernel/src/main/kotlin/civictech/cell/control/AbsorbAck.kt` and
  `kernel/src/main/kotlin/civictech/cell/consistency/WaveFrontier.kt:40-70`
  — the landed machinery your text must describe truthfully (read-only).
- `kernel/src/main/kotlin/civictech/cell/observe/Observe.kt:382-405,523-559`
  — the two blockers, verbatim (read-only).
- `backlog/consistent-multiview-snapshot.md` and
  `backlog/06-or-map-tagged-map-delta.md:1-6` — the marker convention.
- `concord/schema/provenance.md` §1 and
  `doc/spec/90-roadmap/91-gap-analysis.md:63` (G-40 row).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: `kernel/**`, `inspect/**`, `wire/**`, `demo/**`, `gen/**`,
`testkit/**`, `concord/**` (CONCORDANCE.md regeneration excepted — that file
lives under `doc/spec/`), `doc/spec/90-roadmap/96-incremental-engines-plan.md`,
`doc/spec/90-roadmap/99-defects-engines-plan/00-orchestration.md`, any other
plan or spec chapter not named in the claim above.

## Acceptance criteria

- [ ] 22-consistency.md contains §The observation frontier, placed after the
      §Local glitch-freedom block and before §Topology versioning, stating
      rule 1 (internal-consistency guarantee as per-source-vector-frontier
      alignment, with the one-line scalar-virtual-time rejection) and rule 2
      (aligned-composite assembly + per-name inlets), citing research 04
      §3-4, 02 §7, 01 §6, and naming the balanced-transfer suite as the
      acceptance benchmark.
- [ ] §Completeness over silent or stuck edges states rule 3 as a normative
      MUST on operator cells; the two stale "unimplemented" labels named in
      Edit 2 are trued; the ⚠ GAP (G-40) block remains present.
- [ ] 24-data-cells.md's operator-library list carries the SemiJoin and
      CombineLatest emission-gating paragraphs (rule 4, citing 03 §5 and
      04 §3), with existing `[24-OP-*]` ids untouched.
- [ ] `backlog/consistent-multiview-snapshot.md`'s marker records the
      normative absorption at 20/22 §The observation frontier.
- [ ] Every normative sentence added is traceable to 96 §E2/E2.1 or a cited
      research anchor — no invented semantics, no scope from E2.2-E2.6's
      implementation clauses promoted into spec obligations beyond the four
      rules.
- [ ] `./gradlew :concord:docLints` is clean (status vocabulary, package
      pointers, id shape).
- [ ] If ids were minted: `doc/spec/CONCORDANCE.md` regenerated by
      `./gradlew :concord:concordance` and `./gradlew :concord:check` green
      (new coverage gaps are NOTE-severity and acceptable). If no ids:
      `:concord:check` still green and CONCORDANCE.md untouched.
- [ ] `git status --porcelain` shows only the files in the claim; no
      generated/build output, no code, no corpus files in the diff.

## Verify

```bash
./gradlew :concord:docLints
./gradlew :concord:concordance   # only if requirement ids were minted
./gradlew :concord:check
git status --porcelain           # only the claimed files
```

## Report on completion

- Where each of the four rules landed: file, section heading, and whether it
  is a new section, an extended section, or an extended list entry.
- Whether requirement ids were minted, which, and the SPI-checkability
  reasoning either way; whether new concordance gap rows appeared.
- Exactly which stale labels were trued in Edit 2 and what the bridged
  (wire) leg's status was verified to be.
- Whether the optional G-40 row pointer (Edit 5) was taken.
- Any divergence you found between 96 §E2's prose and the landed
  CP-A3/CP-A4 code that this ticket's text had to reconcile — stated, not
  silently smoothed.
- Anything specified here you could not do, and why.
