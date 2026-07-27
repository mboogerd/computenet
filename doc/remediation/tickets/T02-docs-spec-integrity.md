# T02 — Documentation & specification integrity

**Phase 0 · parallel with T01/T03 · fresh session · Sonnet 5**
**Write scope**: `doc/**` (markdown), `AGENTS.md`, `concord/build.gradle.kts` +
`concord/src/**` (lint code only), `concord/corpus/DISPUTES.md`,
`doc/spec/90-roadmap/91-gap-analysis.md`.
**Do not touch**: kernel/wire/demo/gen sources; `doc/spec/CONCORDANCE.md` by
hand (regenerate only); `doc/remediation/`.

## Problem

Audit findings (docs-concordance + conceptual-integrity + modularity + SRP
audits, verified 2026-07-27 at `742f7ca`):

1. **Spec headers that lie.**
   - `doc/spec/90-roadmap/94-implementation-plan.md` header says
     "**Implementation**: none — this document *is* the work list"; all its
     waves merged long ago, and `AGENTS.md:20-21` has to carry a counter-
     disclaimer. A reader arriving by grep never sees the correction.
   - `doc/spec/90-roadmap/92-way-forward.md` carries **two conflicting**
     `**Status**:` lines.
   - `doc/spec/30-execution-model/34-scheduling.md` header claims
     `Implemented` and cites `cell.attention.*` — **a package that does not
     exist** (actual: `cell/control/Attention*.kt`, `cell/host/AttentionPolicy.kt`),
     plus `cell.data.Magnitude` (actual `cell/control/Magnitude.kt`) and
     `cell.port.Protocols` (actual `cell/protocol/Protocols.kt`). `AGENTS.md:47`
     carries the inline correction — drift patched downstream, not at source.
2. **The concordance denominator silently excludes 46% of the normative
   spec.** 98 requirement ids exist; 52 live in one chapter
   (`24-data-cells.md`); **13 of 22 normative chapters carry zero ids** —
   including `23-ownership`, `34-scheduling`, `32-concurrency-colors`,
   `51-construction`, `53-evolution` — i.e. the chapters holding the
   invariants `AGENTS.md` calls core. The gate (`concordanceGate`) only fires
   on dangling/orphan ids, so a zero-id chapter passes silently, and
   `CONCORDANCE.md`'s "52 covered / 46 gap" reads as ~53% conformance when
   real normative coverage is roughly half that.
3. **`93-feature-interactions.md` (1,029,586 bytes, ~257k tokens — 43% of
   `doc/` by bytes)** is ranked #2 in the authority order by `AGENTS.md`, yet
   its own header says "Exploratory — … design proposals, not normative text",
   while 25 normative-chapter citations treat its I-* resolutions as decided.
   At its size it cannot be read into context, so citations degrade to blind
   greps.
4. **27% of `doc/` (81 files, ~641KB) is explicitly-disclaimed stale material
   co-located with live docs**: historical run records (`COMPOSITION-*`,
   `PERNODE-*`, `CHANGELOG-*`, `RESTRUCTURE-PLAN`, `ORCHESTRATION`, `92`,
   `94`), the agora-UI/frontend-research cluster (22 of 25 sampled source
   paths in `agora-ui-implementation-plan.md` do not exist), and the
   pre-spec `doc/adr/` record. The disclaimers live upstream (6 correction
   clauses in `AGENTS.md`, ~20 of 39 lines of `ARCHITECTURE.md` §7), taxing
   every session, while grep hits from the stale files carry no in-band
   warning.
5. **Glossary drift** (`doc/spec/00-foundations/03-glossary.md`):
   - "Frontier" names **eight** distinct code concepts (`TagFrontier`,
     `WaveFrontier`, `ReplicaFrontier`, `AttentionFrontier`,
     `DeliveredFrontier`, `InletFrontier`, `RetainedFrontiers`,
     `FrontierRecord`) and the glossary's own definition ("nearest upstream
     set of glitch-free cells") matches none of them (the code calls that a
     *region*, `host/TopologyWalks.kt:43`).
   - `Invalidate` is listed as one of "the five primitive port operations"
     and specified in `14-invocations.md:77` — it does not exist in
     `kernel/src/main`.
   - `Organelle` — core hierarchy term, no type.
   - One row fuses `Attention / Interest`, which in code are two unrelated
     types (`link.Interest` key-space predicate vs `control.AttentionSupport`
     scheduling band); the workaround comment at `control/Attention.kt:196-198`
     even cites the wrong package for `Interest`.
   - `Generation` is already correctly marked "(unimplemented)" — that is the
     model to follow.
6. **Structural divergences with no marker** in `91-gap-analysis.md`:
   - All 20 non-leaf kernel packages form **one strongly-connected
     component**; `doc/spec/00-foundations/02-design-principles.md:83` claims
     "P1 + P2 together produce the layering: kernel → host → distribution".
     No `G-*`/`C-*` records the divergence.
   - `LocationRegistry` carries three off-mandate distribution lanes
     (`interests` interest-assignment table :51-68, `byLogicalId` membership
     index :33-49, repartition `hold`/`release` :217-243) — an undocumented
     accretion four subsystems edit blind.
   - The `ProtocolSupport` JVM-global retention (reason for `forkEvery 80`)
     is acknowledged only in a build-script comment, tracked nowhere.
   - `91-gap-analysis.md:73` marks G-26 ("Error handling = printStackTrace")
     **Resolved**, but four print-and-continue sites survive
     (`VirtualThreadScheduler.kt:33`, `CoroutineScheduler.kt:47`,
     `LocationRegistry.kt:134-137`, `:268-274`) — the row overstates.

## Solution

### A. Fix the lying headers (and delete the compensating disclaimers)

1. `94-implementation-plan.md` header →
   `**Status**: historical — all waves merged; not the work list (see 96-incremental-engines-plan.md for the forward queue)`.
   Delete the now-redundant counter-sentence from `AGENTS.md`.
2. `92-way-forward.md`: collapse to a single `**Status**:` line
   (`historical record of M1–M11; stale about M12+`).
3. `34-scheduling.md` header: fix the three package references to
   `cell.control.AttentionSupport`/`AttentionBand`/`AttentionScheduler`,
   `cell.host.AttentionPolicy`, `cell.control.Magnitude`,
   `cell.protocol.Protocols`. Delete the inline correction from `AGENTS.md:47`.
4. `93-feature-interactions.md`: rewrite the header to state that I-*
   resolutions **cited by normative chapters are decided**, exploratory status
   applies only to the un-consumed pair matrix. Add an index table at the top:
   `I-n | one-line title | status (decided/proposed) | line number`. Mark
   "decided" exactly those I-ns cited from `doc/spec/` chapters (grep
   `decided in 93` to enumerate). Do **not** split the file.

### B. Archive the stale strata

1. Create `doc/archive/{runs,frontend,adr}/` each with a 5-line `README.md`
   ("historical record; paths predate the restructure; not guidance").
2. `git mv` into them: run records (`doc/COMPOSITION-*.md`,
   `doc/PERNODE-*.md` **except** `doc/PERNODE-FOLLOWUP-TICKETS.md` which has
   open FU items — leave it, `doc/CHANGELOG-*.md`, `doc/RESTRUCTURE-PLAN.md`,
   `doc/ORCHESTRATION.md`) → `runs/`; the agora-UI/frontend cluster
   (`doc/gui-design-guide.md`, `doc/agora-ui-*.md`, `doc/references/`,
   `doc/frontend-research/`) → `frontend/`; `doc/adr/*` **except** the live
   `ADR - Adapter Synthesis.md` → `adr/`.
3. Sweep inbound links from live docs (`AGENTS.md`, `doc/ARCHITECTURE.md`,
   `doc/spec/README.md`) and update paths. Collapse `ARCHITECTURE.md` §7's
   stale-doc taxonomy and `AGENTS.md`'s remaining disclaimers to one line each
   pointing at `doc/archive/`.

### C. Doc lints wired into `:concord:check`

Add three small checks beside the existing `concordanceGate` (same idiom —
plain Gradle/Kotlin, fail the build with a listing). Implementation can live
in concord's existing lint/scanner source:

1. **Package-pointer resolution**: every backticked `cell.<pkg>.<Type>`
   reference in `doc/spec/**.md` must resolve to an existing
   `kernel/src/main/kotlin/civictech/cell/<pkg>/` directory (type-level check
   optional; directory-level is enough to catch the `cell.attention` class of
   drift).
2. **Requirement-id density**: report ids-per-chapter for
   `doc/spec/{10,20,30,40,50}-*/**.md`; **warn** (loudly, listed in output),
   don't fail, on zero-id chapters — the point is visibility, not forcing
   instant spec work.
3. **Status-header vocabulary**: every `doc/spec/**.md` chapter (excluding
   README/CONCORDANCE) must carry exactly one `**Status**:` line beginning
   with one of `Specified|Partial|Implemented|Exploratory|Historical|Living`.
   Fix the chapters this initially flags (96 and spec README currently have
   none).

### D. Concordance denominator honesty

1. In the concordance generator, emit a preamble section into
   `CONCORDANCE.md`: chapters with ids vs without (count + names), so the
   coverage number is never read bare. Regenerate via
   `./gradlew :concord:concordance`.
2. Add a `DISPUTES.md` entry documenting the structural gap: 13 normative
   chapters (name them) carry no requirement ids, so the matrix cannot
   arbitrate them — filed per the ledger's own "cannot be checked honestly"
   rule.

### E. Glossary repairs

1. Add a disambiguation sub-table fixing one word per sense — *watermark*
   (per-source counter high-water: `TagFrontier`, `DeliveredFrontier`,
   `RetainedFrontiers`), *frontier* (an edge/link-set fold: `WaveFrontier`,
   `AttentionFrontier`), *region* (a cell set: `suspensionRegionOf`) — and
   note `FrontierRecord`/`InletFrontier` under their owners. **No renames.**
2. Split the fused `Attention / Interest` row into two entries naming
   `civictech.cell.control.AttentionSupport` and
   `civictech.cell.link.Interest`; note that `AttentionSupport.scatter`'s
   overlap predicate must stay in sync with `Interest.overlaps`. Fix the
   wrong-package comment at `kernel/.../control/Attention.kt:196-198`
   (`replication.Interest` → `link.Interest`) — this one-line source comment
   fix is in scope.
3. Mark `Invalidate` and `Organelle` "(specified, unimplemented)" in the
   glossary, following the existing `Generation` style; add the same note to
   `14-invocations.md` §Invalidating.

### F. New gap/consistency markers in `91-gap-analysis.md`

Following the file's existing format, add:

1. `G-` (new): kernel package graph forms a single 20-package SCC; spec
   `02-design-principles.md:83` claims a physical layering that does not
   exist. Note the two cheap first cuts (move `AttentionPolicy` to
   `.control`; invert `host → wire` via a codec interface) as the remediation
   sketch. Downgrade the sentence in `02-design-principles.md` from physical
   claim to conceptual layering with a pointer to the marker.
2. `C-` (new): `LocationRegistry` co-locates three distribution lanes
   (interest table, `byLogicalId`, repartition hold set) for
   reference-convenience; extraction target `InstanceIndex`/`DeliveryHold`
   named but deferred.
3. `G-` (new): `ProtocolSupport`/`PortRegistry` JVM-global retention forces
   `forkEvery 80`; end state is instance-scoped registries; cite the
   build-script comment as the current mitigation.
4. Amend the **G-26** row: status `Resolved (residual)`, listing the four
   surviving stderr-only sites.

## Verification

```bash
./gradlew :concord:concordance          # regenerates with new preamble
./gradlew :concord:check                # new lints pass on the fixed tree
grep -rn "cell.attention" doc/spec/     # zero hits
git status --porcelain -uall            # only intended moves/edits
```

Sanity: pick 3 moved files and confirm no live doc still links to their old
paths (`grep -rn "COMPOSITION-" AGENTS.md doc/ARCHITECTURE.md doc/spec/`).

## Report

List every header fixed, files moved per archive bucket, lint hit-counts on
first run, the markers added, and any pre-existing lint violations you fixed.
