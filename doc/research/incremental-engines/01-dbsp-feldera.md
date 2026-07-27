# DBSP / Feldera — primitives and what they mean for ComputeNet

Research date: 2026-07-23. Method: multi-agent deep research; every claim below
survived 3-vote adversarial verification against the primary sources unless
marked otherwise. Archived copies in `doc/archive/frontend/references/raw/`.

Primary sources:

- [S1] DBSP paper, VLDB 2023 (Best Research Paper) — `dbsp-vldb2023-budiu.pdf`
  (https://www.vldb.org/pvldb/vol16/p1601-budiu.pdf)
- [S2] Extended VLDB Journal 2025 version — `dbsp-vldbj2025-budiu-extended.pdf`
  (https://mihaibudiu.github.io/work/budiu-vldb25.pdf)
- [S3] Feldera README — `feldera-readme.md` (https://github.com/feldera/feldera)
- [S4] Feldera blog: lateness — `feldera-blog-lateness.html`
- [S5] Feldera blog: time series — `feldera-blog-time-series.html`

## 1. The incrementalization theorem (verified 3-0, ×3)

DBSP gives a compositional, heuristic-free recipe for incrementalizing any
stream operator:

- **Q^Δ = D ∘ Q ∘ I** — the incremental version of operator `Q` is: integrate
  the input delta stream (`I`), run the original operator, differentiate the
  output (`D`). [S1 Def 3.1, S2 Def 12]
- **Chain rule: (Q1 ∘ Q2)^Δ = Q1^Δ ∘ Q2^Δ** — incrementalization distributes
  over composition, so it is *syntax-directed and per-operator*. [S1, with proof]
- A mechanical 5-step algorithm (translate query plan to circuit, eliminate
  `distinct`, lift, bracket with I/D, apply the chain rule) converts any
  relational query plan into an incremental circuit; the paper calls it "a
  simple, syntax-directed, deterministic recipe for computing the incremental
  version of an arbitrarily complex query" [S2 Algorithm 1, rewrites confluent].
- The theory is formally verified in Lean; Feldera is the reference
  implementation (paper authors Budiu/Ryzhyk are Feldera founders). [S1, S3]

**ComputeNet relevance**: this is the template for deriving delta-operators
mechanically instead of hand-writing each cell's incremental logic (today every
cell in `civictech.cell.data` hand-implements its delta transfer function). A
`kernel` analog would be: define each cell's *batch* semantics on integrated
state, and derive the delta behavior via a shared I/D harness — or at least use
Q^Δ = D∘Q∘I as the correctness oracle in generative tests.

## 2. Z-sets: the abelian-group substrate (verified 3-0, ×2)

- The I and D operators are defined on **abelian groups**; relational data is
  lifted into one via **Z-sets**: finite-support functions `A → Z` — each row
  carries a possibly-negative integer weight. [S1 §2]
- Z-sets generalize sets (all weights 1) and bags (positive weights); negative
  weights encode deletions. Set difference becomes `a \ b = dist(a − b)`;
  retraction is "free". [S1]
- **`dist`** restores set semantics. Its incremental form `(↑dist)^Δ` keeps
  O(|DB|) integrated state but does O(|Δd|) work per step via sign-change
  detection (the H function). [S1 Prop; S2 Prop 11]
- **Indexed Z-sets** (`K → Z[A]`) implement GROUP BY; building an indexed Z-set
  from a Z-set "is linear for any key function! It follows that group-by is
  incremental." [S1 §4]

**The tension for ComputeNet** (analysis, not a sourced claim): Z-set addition
is pointwise integer addition — **not idempotent**. Retraction is weight −1,
not tag removal. Z-sets therefore cannot ride ComputeNet's gossip replication,
which admits only idempotent-mergeable deltas. The viable position: Z-sets as
*per-replica operator-internal* representation, with OR-set tags remaining the
replication-boundary representation (tag-count ↔ weight at the boundary). See
`05-gap-mapping.md` gap 1.

## 3. Operator cost taxonomy (verified 3-0; broader variant REFUTED 1-2)

- **Linear time-invariant (LTI) operators are their own incremental versions**:
  `Q^Δ = Q` for LTI `Q` [S1 Thm 3.3]. Filter, projection, and sum-like
  aggregation are linear — zero persistent state.
- **Bilinear operators** (equi-join) incrementalize to the classic delta-join
  form `Δ(a×b) = Δa×Δb + a×Δb + Δa×b` — O(|DB|·|ΔDB|) time, O(|DB|)
  integrated state per join input. [S1 Thm 3.4]
- ⚠️ **Refuted variant** (1-2): a broader claim that grouping, +, −, I, D, z⁻¹
  are all stateless O(|ΔDB|) operators is wrong — the paper's own complexity
  table says "The space complexity of operators such as (↑distinct)^Δ, (↑⋈)^Δ,
  I, and D is O(|DB[t]|)." [S1 §4.4]
- MIN is *not* linear (matches ComputeNet's existing observation that min/max
  need a support multiset). [S1]

**ComputeNet relevance**: a principled answer to "which cells need state".
ComputeNet's `FilterCell`/`FlatMapSetCell` being stateless and
`JoinSetCell`/`Aggregator(min/max)` being stateful is exactly the LTI/bilinear
split — the taxonomy predicts state needs for any future operator before it is
built.

## 4. Incremental recursion via nested time (verified 3-0, ×2)

- Fixpoint iteration runs in an **independent inner time dimension**, insulated
  by "bracketing... between a δ0 and an ∫ operator". [S1/S2]
- **Cycle rule**: `(λs. fix α. T(s, z⁻¹(α)))^Δ = λs. fix α. T^Δ(s, z⁻¹(α))` —
  the incremental version of a feedback loop is the loop around the
  incrementalized body. [S1 Prop 3.2]
- "We have just proven the correctness of **semi-naive evaluation** as an
  immediate consequence of the cycle rule!" [S1]
- Fully incremental recursion (input updates to an already-computed fixpoint)
  uses two-dimensional time `(t0, t1)`: nested streams `N×N → A`; each input
  update yields a stream of adjustments to the fixpoint. Termination: iterate
  until the differentiated change stream is empty. Guaranteed only for
  stratified queries over finite domains. [S1 Thm 5.4]

**ComputeNet relevance**: the decided-but-unbuilt cycle-head model (gap 5) has
a proven semantics to target: a cycle-head cell = δ0/∫ bracket pair, the wave
domain inside a cycle = (outer wave, iteration counter), termination = empty
effective delta (which ComputeNet's effective-only emission rule already
detects for free). See `05-gap-mapping.md` gap 5.

## 5. Lateness → waterline → GC (verified 3-0, ×4; one variant REFUTED 0-3)

- **LATENESS** is a per-column annotation on timestamp columns
  (`ts TIMESTAMP NOT NULL LATENESS INTERVAL 1 HOURS`), "essentially a
  deadline": at time T+L all data with timestamps ≤ T is promised to have
  arrived. [S4]
- The compiler **propagates lateness bounds across all views** "to determine
  when it's safe to discard state". [S4]
- The derived **waterline** (oldest data that can still be updated) is computed
  *inside the circuit model*: MAX over incoming changes minus lateness, fed
  through a z⁻¹-delayed max so it is monotone non-decreasing and takes effect
  one step later. [S5]
- The waterline drives GC that shrinks internal indexes: "the waterline is
  crucial to keep the computation running... without running out of memory". [S5]
- Feldera deliberately coins "waterline" instead of Flink-style "watermark",
  arguing the latter lacks a precise agreed definition. [S5]
- ⚠️ **Refuted** (0-3): "lateness GC is a pure optimization that never changes
  outputs and never delays computation." Correctness-preservation is
  *conditional* on waterline derivability from monotone/near-monotone columns —
  check per query shape, don't assume.

**ComputeNet relevance**: the waterline is derived from event-time
monotonicity, not wall clocks or coordination — so a per-source waterline could
ride ComputeNet's existing per-source `Timestamp` waves. This is the worked
design for gap 6 (tag/tombstone compaction, window eviction), *for
frontier-reachable state*; CRDT tag GC additionally needs causal stability (see
`03-lasp-crdt-lattice.md`).

## 6. Feldera's consistency claims (verified 3-0 ×2, confidence medium)

- Feldera evaluates "full SQL syntax and semantics completely incrementally" —
  joins, aggregates, group by, correlated subqueries, window functions,
  time-series operators, UDFs, recursive queries. [S3]
- "Feldera is strongly consistent... the state of the views always corresponds
  to what you'd get if you ran the queries in a batch system." [S3] This is
  *internal/batch-prefix consistency of one logical pipeline* achieved by
  totally ordered single-pipeline evaluation — **not** linearizability across
  replicas, and it does not transfer to coordination-free multi-writer
  replication.
- The "only engine in existence" exclusivity is vendor marketing and
  contestable: Materialize also incrementally maintains recursive SQL
  (`WITH MUTUALLY RECURSIVE`, `materialize-blog-recursion.html`).

**ComputeNet relevance**: batch-prefix equivalence is the right *shape* of
guarantee for gap 4 (consistent multi-view snapshots), but ComputeNet can at
best get **per-source-prefix batch-equivalence per replica** — the global total
order that makes Feldera's guarantee cheap is exactly what ComputeNet's
replication model refuses.
