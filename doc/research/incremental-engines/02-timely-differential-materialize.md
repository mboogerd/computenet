# Timely / Differential Dataflow / Materialize — primitives and what they mean for ComputeNet

Research date: 2026-07-23. Method: multi-agent deep research; every claim below
survived 3-vote adversarial verification against the primary sources unless
marked otherwise. Archived copies in `doc/research/incremental-engines/raw/`.

Primary sources:

- [S1] Naiad: A Timely Dataflow System, SOSP 2013 — `naiad-sosp2013-murray.pdf`
  (https://sigops.org/s/conferences/sosp/2013/papers/p439-murray.pdf)
- [S2] Shared Arrangements, VLDB 2020 — `shared-arrangements-vldb2020.pdf`
  (https://arxiv.org/pdf/1812.02639)
- [S3] Materialize blog: delta joins — `materialize-blog-delta-joins.html`
- [S4] Materialize blog: virtual time — `materialize-blog-virtual-time.html`
- [S5] differential-dataflow repo (dogsdogsdogs module) —
  https://github.com/TimelyDataflow/differential-dataflow

## 1. Multidimensional timestamps and structured cycles (verified 3-0, ×3)

- Timely timestamps are **multidimensional**: an epoch plus one loop counter
  per enclosing loop context, compared by product/lexicographic partial order.
  [S1 §2] (Modern Rust timely generalizes to arbitrary partially ordered
  `Product` timestamps — the Naiad description here remains accurate for the
  2013 system.)
- Cycles are **structured into loop contexts** with three system vertices that
  mechanically transform timestamps: *ingress* appends a `0` counter, *feedback*
  increments the innermost counter, *egress* removes it. Every cycle must pass
  through a feedback vertex. [S1 p.440-441]
- This is the direct ancestor of Differential Dataflow's partially ordered
  times and DBSP's nested time domains.

**ComputeNet relevance**: the wave id `Timestamp(sourceId, counter)` is
one-dimensional. Admitting cycles (gap 5) means waves inside a cycle need an
iteration dimension — Naiad's ingress/feedback/egress triple is the concrete
graph-structural recipe (cycle-head admission ≙ ingress; the backlog item
`agora-dynamic-cycle-head-admission` is this shape).

## 2. Progress tracking (verified 3-0)

- Each worker maintains, per active **pointstamp** (location, timestamp), an
  *occurrence count* and a *precursor count* under a could-result-in relation
  evaluated via precomputed minimal **path summaries** Ψ[l1,l2]. A pointstamp
  whose precursor count reaches zero is **in the frontier**; its notifications
  are deliverable. [S1 §3.2]
- This generalizes ComputeNet's per-wave EdgeOpen/EdgeClose + per-edge
  watermark frontier machinery from a single consumer's diamond to arbitrary
  graphs, including cyclic ones.

## 3. Naiad's distributed progress protocol (verified 3-0)

The closest existing design to cross-replica frontier coordination (gap 7)
that matches ComputeNet's no-consensus philosophy:

- Updates are `(p ∈ Pointstamp, d ∈ Z)` occurrence-count deltas **broadcast to
  all workers including self**; "The broadcasts from a given worker to another
  must be delivered in FIFO order, but there is no constraint on ordering
  between two workers' broadcasts." — **FIFO broadcast only, no consensus, no
  total order across workers**. [S1 §3.3]
- Safety property: "no local frontier ever moves ahead of the global frontier"
  — locally delivered notifications are always safe. [S1 §3.3]
- Mechanically verified independently (TLA+ by Abadi et al.; Isabelle/HOL,
  ITP 2021).
- **Assumptions that don't hold for ComputeNet**: fixed, fully-known worker
  set; reliable FIFO channels (fault tolerance is checkpoint/restore, not
  membership change). The signed-delta accumulation is **not idempotent**, so a
  gossip port would need exactly-once/FIFO per pair (ComputeNet's per-source
  counters could provide this) — or reformulation as a join-semilattice of
  per-source low-watermarks merged by pointwise max, the same shape as
  ComputeNet's PN-counter. (Reformulation = our analysis, not a sourced fact.)

## 4. Arrangements: shared, compactable operator state (verified 3-0, ×3)

- An arrangement's **trace** is a list of immutable, indexed **batches** of
  `(data, time, diff)` triples forming a multiversioned index. New batches are
  sealed **only when the input frontier advances**; amortized merging keeps
  logarithmically many batches. [S2 §4-4.2]
- **Compaction is driven by reader frontiers**: each trace handle carries a
  frontier; once all readers pass certain times, updates at indistinguishable
  older times are coalesced. Correctness and optimality are proven via the
  lattice representative `rep_F(t) = ∨_{f∈F}(t ∧ f)`. [S2 Appendix A,
  Thms 1-2] (Notation caveat: the paper's ∧/∨ usage is idiosyncratic; rep_F is
  semantically a meet-of-joins, matching `Lattice::advance_by`.)
- **Post-hoc sharing**: unlike deployment-time multi-query optimization
  (CJoin/SharedDB/DBToaster), a newly installed query imports an existing
  arrangement's consolidated historical batches and immediately produces
  correct outputs reflecting all prior events. [S2 §4.3, Related Work]

**ComputeNet relevance**: (a) reader-frontier compaction is the formal analog
of what tag/tombstone GC (gap 6) needs — the missing piece is that ComputeNet
has no reader frontiers, only per-source waves; the CRDT-side analog is causal
stability (`03-lasp-crdt-lattice.md`). (b) Post-hoc sharing is the pattern for
ComputeNet's late-join `StateRequest(since)`/baseline machinery generalized to
*operator state*, not just cell state.

## 5. Single-writer boundary (verified 3-0)

- "Shared arrangements are our design for **single-writer, multiple-reader**,
  shared state in dataflow systems"; they "do not support multiple writers, and
  are not suitable tools to implement a general transaction processor." [S2]
- They depend on data-parallel sharding, worker co-scheduling, and partially
  ordered timestamps with frontier progress statements.

**ComputeNet relevance**: the trace machinery transfers as *per-replica local
state* (each replica is the sole writer of its own operator traces) — not as a
replicated shared index. Materialize's total-order timestamp selection sits on
the same non-transferring side of the line.

## 6. Delta joins / dogsdogsdogs (verified 3-0)

- Materialize's **DeltaQuery** plan "aggressively re-uses arrangements and
  maintains zero intermediate results" — in contrast to binary join trees that
  must arrange every intermediate result. [S3]
- Trade-off: delta joins require arrangements over **all inputs** — state
  shifts to (expected pre-existing, shared) input indexes. Example from the
  post: ~23K records of intermediate state vs 3.4M for the binary plan. [S3]
- Corroborated by the open-source `dogsdogsdogs` module (`delta_query.rs`) and
  Materialize docs (delta join chosen when all required input indexes exist).
  [S5]

**ComputeNet relevance** (analysis): compose n-way joins as n per-input delta
pipelines over shared input views rather than chaining binary `JoinSetCell`s —
avoids minted-tag intermediate sets entirely (gaps 3 and 6). A lookup/FK join
cell (backlog `02-foreign-key-lookup-join-cell`) is the n=2 special case where
one side is an index.

## 7. Materialize virtual time (see also 04-cross-cutting)

Materialize assigns totally ordered timestamps at ingestion (virtual time);
all operators produce output at the same virtual times, giving internal
consistency across views. This **requires a timestamp oracle / coordination
point** and does not transfer to ComputeNet's coordination-free replication —
covered in `04-cross-cutting-watermarks-consistency.md`.
