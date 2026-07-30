# E1-SPEC — the decided OR-map design (96 §E1.1) becomes normative spec text

**Status**: Partial — implementation complete on `ticket/e1-spec`, awaiting
orchestrator merge (`:concord:docLints` and `:concord:check` green)
**Model:** `claude-sonnet-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** B1 · **Branches:** `ticket/e1-spec`

## Context

ComputeNet is a specification-led Kotlin/JVM dataflow runtime: code makes the
cited spec true, and the first item of every engines milestone makes the spec
text exist before any code ticket runs (AGENTS.md authority order;
`doc/spec/90-roadmap/96-incremental-engines-plan.md` "Spec-first" rule).

Milestone E1 of the engines plan closes **G-23** for keyed structures: today
`MapDelta` carries no causal tags, so every map-shaped edge is
single-writer-or-diverge — the documented `[24-OP-MAP-01]` limit
(`doc/spec/20-dataflow-semantics/24-data-cells.md:86-93`). The
incremental-engines research (2026-07-23) settled the answer: the
Riak-map/delta-ORMap design, adapted to ComputeNet's existing tag machinery
(`Timestamp(sourceId, counter)` tags are already dot-shaped; `SetCell` already
keeps tombstoned dels; `KeyedSetCell` already does per-key observed-remove
with atomic retract+add).

**Every design decision this ticket transcribes is already made.** The
complete, authoritative content source is
`doc/spec/90-roadmap/96-incremental-engines-plan.md`:

- the **E1 milestone preamble** (`:44-61`) — the additive-new-delta-type
  decision resolving backlog 06, the tombstoned-idiom choice that makes
  Riak's deferred-operations list unnecessary, and the R10 exclusion;
- **§E1.1** (`:62-80`) — the `TaggedMapDelta` definition, its merge/presence/
  value/remove laws, and the five recorded decided points.

Read both in full before writing a word. Your job is precise transcription
into the normative chapters, in each chapter's own voice — not design. Where
the 96-plan text is already exact (the `TaggedMapDelta` shape, the four
semantic laws), carry it over verbatim or near-verbatim; where it is
plan-register shorthand, expand it into chapter prose.

This is a track C ticket of the combined run (`../00-orchestration.md`, wave
B1). It is sequenced **before** E2-SPEC because both edit `24-data-cells.md`
(cross-track claim rule 4). No other B1 ticket touches `doc/spec/**`.

## Problem

The design is decided but lives only in a plan document, and the 96-plan's own
status line says no E-item is committed work until scheduled — plan prose is
below spec text in the authority order. The E1 code tickets (E1.2 `OrMapCell`
core, E1.3 replication) cite "20/24 §Tagged maps" as their **Spec** clause,
and that section does not exist yet. Until it does, the R-ENG replan cannot
ticket the E1 code spine against merged spec, and the four chapter locations
that should point at the decided design either say "wait for replication
pressure" (24), omit the tagged map from the mergeable roster (42), or point
only at the plan (91, 95).

## Solution direction

Four spec-file edits, from 96 §E1.1's own **Spec** clause. Spec text only —
no code, no scenarios.

### The content being made normative (summary; 96 §E1.1 is authoritative)

Define `TaggedMapDelta<K, V>(puts: Map<K, Map<Timestamp, V>>,
dels: Map<K, Set<Timestamp>>)` — per-key live **dots** carrying values,
tombstoned observed-remove dots. The four laws, verbatim from 96 §E1.1:

> merge = pointwise union (idempotent because a dot's value is immutable);
> presence = any live dot (add-wins); value = LWW **by dot order**
> `(counter, sourceId)`, never wall clock; `remove(k)` = reset-remove
> (tombstone all observed dots; concurrent puts survive as their unobserved
> dots).

Plus the decided points to record (96 §E1.1 + the E1 preamble), each with its
research citation:

1. **One shared causal namespace for the whole map** — per-key contexts
   re-admit stale values on key re-creation; cite Riak
   (`doc/research/incremental-engines/03-lasp-crdt-lattice.md` §4,
   `05-gap-mapping.md` §Gap 2).
2. **Tombstoned dels subsume deferred context ops** — the OR-map follows
   `SetCell`'s tombstoned idiom (dels stored as covered dots), so Riak's
   deferred-operations list is unnecessary: a remove's dots arriving before
   their put simply sit in `dels` and cover the put on arrival, exactly as
   `SetCell.applyRemote` already behaves
   (`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:107-116`).
3. **Embedded values restricted to the idempotent-mergeable
   (`MergeablePayload`) class** — Riak's embedded-counter anomaly is the
   documented counterexample (research 03 §4). Note: research 05 §Gap 2
   phrases the restriction as "the `Replicable` class"; the 96-plan's
   `MergeablePayload` wording is the decided one — transcribe that
   (`kernel/src/main/kotlin/civictech/cell/MergeablePayload.kt` exists).
4. **Dot-metadata bloat is a codec-layer concern from day one** — Riak names
   metadata bloat "a serious issue" (research 03 §4).
5. **The Lasp determinism caveat is normative for downstream adopters** —
   SEC alone does not make value-keyed derivation deterministic; tag-precise
   removes (a remove carries exactly the observed dots) are what keep
   value-keyed derivation deterministic (research 03 §1, "Determinism
   caveat").

And the decided scope boundaries (E1 preamble, `96:52-61`): **additive new
delta type** — `MapDelta` and its single-writer cells untouched,
`KeyedSetCell` untouched (this resolves backlog
`backlog/06-or-map-tagged-map-delta.md`'s open choice); the tombstone-free
(context-only) wire form is deliberately excluded — it needs the
causal-merging condition (research 03 §2) whose delivered-watermark
prerequisite is E3, tracked as 95 §R10.

Cite in the new spec text: `doc/research/incremental-engines/03` §2 and §4,
and `05` §Gap 2 (section anchors verified to exist), per E1.1's **Implement**
clause.

### Edit 1 — `doc/spec/20-dataflow-semantics/24-data-cells.md`

- **New `## Tagged maps` section** — the main deliverable; suggested
  placement between `## Required next steps in the family` (ends `:192`) and
  `## Grouped aggregation (M11.3)` (`:194`). Carry the full content above in
  the chapter's established voice: definition block (mirror the `SetDelta`
  kotlin-style block at `:18-30`), laws, decided points, exclusions with
  their R10 pointer, and an honest "design decided, unbuilt — code is 96
  §E1.2-E1.3" marker in the style the chapter already uses for
  decided-unbuilt material.
- **`## Required next steps in the family` (`:95-192`)** — the stale framing
  is the italic parenthetical at `:86-93` ("stable multi-writer forms wait
  for replication pressure (42)") and the section's silence on the keyed
  deferral now being decided. Update so the map deferral points at the new
  §Tagged maps instead of an open wait. `[24-OP-MAP-01]` **stays true and
  stays put**: `MapDelta` remains single-stream; the tagged map is additive
  beside it, not a change to it.
- **Chapter `> **Status**:` line (`:3`)** — extend to record the tagged-map
  design decided, unbuilt, matching its existing comma-list style. It must
  keep beginning with an allowed status word (`Partial`).

### Edit 2 — `doc/spec/40-distribution/42-replication.md`

`## Design as implemented` (`:48-93`): the mergeable-class roster sentence at
`:65-67` ("The class today: the tagged set family (tag union) and
`PnCounterCell` …") gains the tagged map — worded honestly as the decided
third member whose cell joins the class when 96 §E1.2-E1.3 land (merge =
pointwise dot union, idempotent). One or two sentences; do not restate the
whole design — point at 20/24 §Tagged maps.

### Edit 3 — `doc/spec/90-roadmap/91-gap-analysis.md`

The G-23 row (`:57`) **already carries** a planned-realization pointer
("**Planned realization**: OR-map in 96 §E1 (design decided per research 03
§4; R8 promoted)") — added when the 96-plan was authored. The edit is a
sharpening, not an addition: the pointer now names the normative spec text
(20/24 §Tagged maps, this ticket) with 96 §E1.2-E1.3 as the code path. Keep
it to the row; no new rows.

### Edit 4 — `doc/spec/90-roadmap/95-research-plan.md`

§R8 (`:133-147`) **already carries** the `— PROMOTED (96 §E1, §R17)` heading
marker and an **Actions** paragraph saying direction (1) is decided and
scheduled as 96 §E1. As the briefing anticipated: a small sharpening, not a
rewrite — note that the decided design is now normative spec text at 20/24
§Tagged maps (was: plan-only). One clause or sentence inside the existing
Actions text.

### Requirement-id minting — OPTIONAL

Follow the `[24-SLUG-nn]` EARS conventions the chapter already uses
(templates and the "checkable through the SPI" L0 gate:
`concord/schema/provenance.md` §1). Mint ids **only** for statements that are
genuinely boundary-observable once the cell exists — candidates: merge
commutativity/associativity/idempotence, add-wins presence, LWW-by-dot-order
(never wall clock), reset-remove concurrent-put survival. A fresh slug such
as `TMAP` avoids colliding with existing `24-OP-MAP-nn`. Newly minted ids
will appear as `gap` rows in CONCORDANCE.md until E1 code and scenarios land
— **that is acceptable and honest**. Statements that resist a driver-SPI
check (codec-layer bloat, the namespace-sharing rationale) stay normative
prose without ids. If you mint any id, regenerate CONCORDANCE.md
(`./gradlew :concord:concordance`) and commit the regenerated file — never
hand-edit it.

### docLints traps (both fatal)

- **Package-pointer resolution**: any backticked `cell.<pkg>.<Type>` span
  must resolve to a real declared type under
  `kernel/src/main/kotlin/civictech/cell/<pkg>/`. `OrMapCell` and
  `TaggedMapDelta` **do not exist yet** — never write a backticked,
  `cell.data.`-qualified pointer to either of them (this ticket deliberately
  avoids spelling the offending form, because the lint scans ticket files
  too). Bare backticked names (`` `TaggedMapDelta` ``, `` `OrMapCell` ``)
  are safe (the lint regex requires the dotted `cell.` package prefix), as
  are `kernel/.../cell/data/...` slash paths.
- **Status-header vocabulary**: every edited chapter keeps exactly one
  `**Status**:` line before its first `## `, beginning with
  `Specified|Partial|Implemented|Exploratory|Historical|Living`.

### What NOT to do

- **No code.** No kernel, `gen/`, `wire/`, `testkit/`, or demo edits; no
  `OrMapCell.kt`, no `TaggedMapDelta` type. E1.2 owns those.
- **No `concord/corpus/**` edits** — no scenarios, no DISPUTES entries.
  Concord is single-writer across the run and D-CONCORD holds the pen
  (`../00-orchestration.md` claim rule 3). The only permitted generated
  artifact is the regenerated `doc/spec/CONCORDANCE.md`, and only if ids
  were minted.
- **`96-incremental-engines-plan.md` stays untouched** — it remains the
  plan-of-record; do not mark E1.1 done inside it, do not "fix" its prose.
- **Do not broaden.** The tombstone-free wire form (R10), multi-value
  exposure mechanics, `MapOps` contract details, catch-up/snapshot mechanics,
  and replication wiring are E1.2/E1.3/R10 material — name them as
  excluded-with-pointer where the chapter voice wants it, but do not design
  them.
- Do not close or weaken G-23's remaining deferral (the weighted/bag half —
  96 §E6, 95 §R17) or mark adjacent residuals complete.

## Files expected to touch

- `doc/spec/20-dataflow-semantics/24-data-cells.md` — Status line, §Required
  next steps in the family, new §Tagged maps.
- `doc/spec/40-distribution/42-replication.md` — §Design as implemented
  roster sentence.
- `doc/spec/90-roadmap/91-gap-analysis.md` — G-23 row sharpening only.
- `doc/spec/90-roadmap/95-research-plan.md` — §R8 Actions sharpening only.
- `doc/spec/CONCORDANCE.md` — regenerated, **only if** ids were minted.
- This ticket's `**Status**:` line.

Nothing else.

## Read first

- `doc/spec/90-roadmap/96-incremental-engines-plan.md:44-80` — the E1
  preamble and §E1.1, **the complete content source**. Also `:82-118`
  (§E1.2-E1.3) to see exactly what the code tickets expect the spec section
  to say, so the new text neither under- nor over-specifies.
- `doc/spec/20-dataflow-semantics/24-data-cells.md:1-6` (header), `:14-93`
  (§Established pattern — the `SetDelta` block at `:18-30` is the format
  template; `[24-OP-MAP-01]` at `:90-93` is the limit the new section sits
  beside), `:95-192` (§Required next steps).
- `doc/spec/40-distribution/42-replication.md:48-93` (§Design as
  implemented; the roster sentence `:65-67`, the §Tombstones bullet
  `:69-71` — the idiom the OR-map inherits).
- `doc/spec/90-roadmap/91-gap-analysis.md:57` — the G-23 row as it stands.
- `doc/spec/90-roadmap/95-research-plan.md:133-147` (§R8 as it stands),
  `:168` (§R10 heading — the exclusion pointer target).
- `doc/research/incremental-engines/03-lasp-crdt-lattice.md` — §1
  ("Determinism caveat" paragraph), §2 (delta-state CRDTs; the
  causal-merging condition behind the R10 exclusion), §4 (Riak DT map — the
  design source; shared context, reset-remove, counter anomaly, bloat).
- `doc/research/incremental-engines/05-gap-mapping.md:62-84` — §Gap 2.
- `concord/schema/provenance.md` §1 — EARS id scheme, the five templates,
  the L0 gate.
- `kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:39-45`, `:107-116`
  (`applyRemote` — the tombstone-covers-late-put behavior decided point 2
  cites), `kernel/src/main/kotlin/civictech/cell/MessageContext.kt:16-19`
  (`Timestamp(sourceId, counter)` — the dot).
- `../00-orchestration.md` §Standing rules, cross-track claim rule 4, wave
  B1 table.

Do not modify: `96-incremental-engines-plan.md`, `concord/**` (schema,
corpus, DISPUTES), `kernel/**`, `gen/**`, `wire/**`, `testkit/**`,
`demo/**`, `inspect/**`, `backlog/**`, any `doc/spec/**` file outside the
four named, any 98-plan or 99-plan document other than this ticket's
`**Status**:` line.

## Acceptance criteria

- [ ] `24-data-cells.md` has a §Tagged maps section carrying: the
      `TaggedMapDelta<K, V>` definition (puts as per-key dot→value maps,
      dels as per-key tombstoned dot sets); the four laws exactly as decided
      (pointwise-union merge idempotent via immutable dot values; add-wins
      presence by any live dot; LWW by dot order `(counter, sourceId)`,
      never wall clock; reset-remove with concurrent-put survival); all five
      decided points from the list above; the additive-decision boundary
      (`MapDelta`/`KeyedSetCell` untouched); and the R10 exclusion for the
      tombstone-free wire form.
- [ ] The new section cites research 03 §2, 03 §4, and 05 §Gap 2, and marks
      itself design-decided/unbuilt with the 96 §E1.2-E1.3 code pointer.
- [ ] `24-data-cells.md` §Required next steps no longer frames the keyed
      multi-writer form as waiting on undesigned replication pressure;
      `[24-OP-MAP-01]` is unchanged and still true.
- [ ] `42-replication.md` §Design as implemented names the tagged map in the
      mergeable-class roster, honestly marked decided/unbuilt.
- [ ] 91's G-23 row and 95's §R8 point at 20/24 §Tagged maps as the
      normative home; both edits are sharpenings (no row additions, no R8
      rewrite).
- [ ] Every minted id (if any) follows a provenance.md §1 template, sits on
      a boundary-observable statement, and CONCORDANCE.md was regenerated —
      not hand-edited — with the new ids present as gap rows;
      `./gradlew :concord:check` is green. If no ids were minted,
      CONCORDANCE.md is untouched.
- [ ] No backticked `cell.<pkg>.<Type>` pointer to a not-yet-existing type;
      `./gradlew :concord:docLints` is clean.
- [ ] `96-incremental-engines-plan.md` byte-identical; no code, no
      `concord/` (beyond nothing), no generated/build output in the diff.
- [ ] `git status` shows only the files in the claim list.

## Verify

```bash
./gradlew :concord:docLints
git status --porcelain          # only the claimed files
```

If ids were minted, additionally:

```bash
./gradlew :concord:concordance  # regenerates doc/spec/CONCORDANCE.md
./gradlew :concord:check
```

## Report on completion

- Where §Tagged maps landed and its final heading, plus a one-line summary
  of each of the other three file edits.
- The ids minted (full list with their EARS template), or "none minted" and
  why the statements stayed prose.
- Any point where 96 §E1.1, the research docs, and the existing chapters
  disagreed and which authority you followed (e.g. the
  `MergeablePayload`-vs-`Replicable` wording split).
- Confirmation that the E1.2/E1.3 **Spec** clauses ("20/24 §Tagged maps",
  "40/42 §Design as implemented") now resolve to real sections saying what
  those tickets need.
- Anything specified here you could not do, and why.
