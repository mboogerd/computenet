# Incremental analytics engines — deep research (2026-07-23)

What ComputeNet can borrow from Feldera/DBSP, Differential/Timely Dataflow,
Materialize, and LASP + the CRDT/lattice lineage. Raw source documents are
archived in `doc/archive/frontend/references/raw/` (see its README for the file↔URL manifest).

## Method and provenance levels

- Phase 1: multi-agent deep-research workflow (5 search angles, 24 sources
  fetched, 119 claims extracted, top 25 adversarially verified by 3-vote
  panels: 23 confirmed, 2 refuted). Docs 01 and 02 carry these
  vote-verified claims; refuted variants are flagged inline with ⚠️.
- Phase 2: the workflow's verification budget did not reach the LASP/CRDT and
  watermark angles, so two follow-up agents fetched those primary sources and
  extracted verbatim-quoted facts (quote-checked, not vote-verified). Docs 03
  and 04 carry these.
- Doc 05 is synthesis; statements beyond the sourced facts are marked
  *(analysis)*.

## Contents

| Doc | Covers |
|---|---|
| [01-dbsp-feldera.md](01-dbsp-feldera.md) | Z-sets, Q^Δ = D∘Q∘I + chain rule, LTI/bilinear taxonomy, nested-time recursion, lateness→waterline GC, Feldera consistency claims |
| [02-timely-differential-materialize.md](02-timely-differential-materialize.md) | Multidimensional timestamps, loop contexts, progress tracking, Naiad's distributed progress protocol, arrangements + reader-frontier compaction, delta joins, single-writer boundary |
| [03-lasp-crdt-lattice.md](03-lasp-crdt-lattice.md) | Lasp metadata morphisms (map/filter/product/fold over OR-sets), delta-state CRDTs (delta-intervals, dot-store/causal-context), pure-op CRDTs + causal stability, Riak OR-map, CALM |
| [04-cross-cutting-watermarks-consistency.md](04-cross-cutting-watermarks-consistency.md) | Flink watermarks/lateness/eviction, Dataflow Model accumulation modes + retractions, internal consistency (Brandon), Materialize virtual time |
| [05-gap-mapping.md](05-gap-mapping.md) | Per-gap borrow/adapt/reject mapping for ComputeNet's 7 gaps, non-transferring ideas, open questions, suggested priority |

## Headline conclusions

- The borrowing rule: **weights, arrangements, and total orders stay inside a
  replica; tags, lattices, and vector frontiers cross replicas.**
- Five of seven gaps have worked, verified designs to adapt (Z-sets, OR-map,
  delta joins, nested-time fixpoints, lateness/waterline + causal-stability
  GC). Gap 4 (consistent multi-view snapshot) is a local vector-frontier
  construction ComputeNet can build coordination-free. Gap 7's frontier
  coordination and gap 6's tag GC are one primitive (per-peer delivered
  watermarks) read at two freshness levels.
- Non-transfers, with reasons, are consolidated in doc 05.
