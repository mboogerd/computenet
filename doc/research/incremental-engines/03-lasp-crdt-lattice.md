# LASP and the CRDT/lattice lineage — primitives and what they mean for ComputeNet

Research date: 2026-07-23. Method: research agent fetched primary sources and
extracted verbatim-quoted facts (single-agent verification against fetched
text; the initial workflow's adversarial-vote budget did not reach this area).
Archived copies in `doc/research/incremental-engines/raw/`.

Sources:

- [S1] Lasp (Meiklejohn & Van Roy, PPDP 2015) — `lasp-ppdp2015-meiklejohn.pdf`
- [S2] Delta State Replicated Data Types (Almeida, Shoker, Baquero) —
  `delta-state-crdts-almeida-1603.01529.pdf`
- [S3] Pure Operation-Based RDTs (Baquero, Almeida, Shoker) —
  `pure-op-crdts-baquero-1710.04469.pdf`
- [S4] Riak DT map — `riak-dt-map.pdf` is only a one-page PaPEC 2014 abstract;
  technical detail below is quoted from the `riak_dt_map.erl` module docs
  (basho/riak_dt, develop branch)
- [S5] Keeping CALM (Hellerstein & Alvaro) — arXiv 1901.01930 preprint of the
  CACM 2020 article — `keeping-calm-hellerstein-alvaro-1901.01930.pdf`
- [S6] Bauwens & Gonzalez Boix, MPLR 2020 — `causality-to-stability-mplr2020.pdf`

Provenance caveats: "join-decompositions" as a formal minimal-delta device is
from Enes et al. ICDE 2019 (arXiv 1803.02750), *not* [S2] — not fetched. LVars
is covered only via Lasp §7.5's critique.

## 1. Lasp: functional transformations over CRDT *metadata* (S1)

The system closest in spirit to ComputeNet's data-cell suite. Central claim:
applications "support programming with data structures whose values appear
nonmonotonic externally, while computing internally with the objects'
monotonic metadata" [§1]. The OR-Set is `(v, a, r)` triples; "It is important
to distinguish between the external representation of the set (... nonmonotonic)
and the internal representation (... monotonic)" [§2.2].

Why naive map breaks [§2.3, Fig 3]: applying `f` to the *query value* discards
causality — "Without properly mapping the metadata, the convergence property
does not hold... we can not determine if the incoming state has been previously
observed or not." Hence every transformation is a **metadata morphism**
(deterministic, per fn 7):

- **map** [Def 3.3]: output `w = f(v)` gets add-set `⋃{a}` and remove-set
  `⋃{r}` over *all preimages* of `w` — tag sets union across colliding
  preimages, so removes flow through for free. (ComputeNet's `FlatMapSetCell`
  does exactly this; Lasp is the citable prior art, including the
  colliding-preimage union that ComputeNet calls distinct-projection.)
- **filter** [Def 3.4]: failing values are *not deleted* — "removed by a
  metadata computation, to ensure that the filter is a monotonic process":
  `{(v,a,r) | p(v)} ∪ {(v,a,a∪r) | ¬p(v)}` — filter-out = self-remove.
- **product** [Def 3.6]: `((v,v′), a×a′, a×r′ ∪ r×a′)` — pair tags are the
  cross product; removing either side kills all pairs containing it. Cost:
  |a|·|a′| tag blowup. (ComputeNet's `JoinSetCell` `MintedTags` is the same
  idea with minted rather than paired tags — minting avoids the blowup but
  loses derivability of output tags from input tags; see gap-mapping.)
  **intersection** [Def 3.8] reuses the pairing; **union** [Def 3.7] unions
  tag sets pointwise.
- **fold** [Def 3.5]: apply `op(v)` once per add-tag and the *inverse*
  `op′(v)` once per remove-tag: "This solution assumes that op is associative,
  commutative, and has an inverse... correct but inefficient... we are actively
  working on more efficient solutions." Requires a group, not a monoid —
  matches ComputeNet's invertible-vs-support-multiset aggregator split.

**Strict-inflation stream processing** [Defs 2.3-2.4, 3.1-3.2, §5.1]:
execution is a stream of lattice states; `read(x, v)` blocks until state ≥ v;
operations use `strict_read` ("waits until the value of x is strictly greater
than v") so processes "prevent duplication of already computed work" —
Lasp's analog of effective-only emission, on the read side rather than the
emit side.

**Determinism caveat** [§4.3, Fig 5]: SEC alone does not make OR-set pipelines
functionally deterministic — whether a remove at replica A cancels a
concurrent add at B depends on the merge schedule. Lasp's sufficient
conditions: remove(v) only after a local add(v); no concurrent add(v) of the
same value at two replicas. ComputeNet's tag-precise removes (a remove carries
exactly the observed tags) are strictly stronger than Lasp's value-level
formalization here, but the theorem is a warning for any *value-keyed*
operator (e.g. a future OR-map): confluence of state ≠ determinism of derived
streams.

**vs LVars** [§7.5]: threshold reads need "a priori knowledge of the internal
state... and that the queryable value of a CRDT is monotone" — both fail for
OR-Sets (fresh tags unpredictable; query nonmonotonic). LVars-style threshold
reads are *not* a fit for ComputeNet's data plane.

**vs Bloom^L** [§7.4]: Bloom^L lattices "lack causal information, which places
the requirement on monotone functions to, once satisfied, freeze, or seal,
their values," whereas "Lasp can detect these scenarios using metadata in the
form of logical clocks." Retraction in Bloom^L "is nonmonotonic, and therefore
not confluent," while Lasp's OR-set composition makes retraction "monotonic
and confluent... but can not guarantee when the update might be visible."
ComputeNet sits on Lasp's side of this line already.

## 2. Delta-state CRDTs: the shippable-increment discipline (S2)

- **Delta-mutator** [Defs 1-3]: `m^δ(X)` returns a state in the same
  join-semilattice; transition is always `X′ = X ⊔ m^δ(X)`. "A delta is just a
  state, that can be joined possibly several times without requiring
  exactly-once delivery" [§4.2]. Minimality [§4.1]: "minimal delta-mutators do
  not leak into the deltas they produce any redundant information that is
  already present in X."
- **Basic anti-entropy** [Alg 1]: buffer deltas, periodically ship delta-group
  or full state; convergence needs only that each delta-mutation "is joined...
  at least once with every other replica" [Prop 1].
- **Delta-intervals + causal merging** [Defs 4, 6]: join `Δⱼ^{a,b}` into `Xᵢ`
  only if `Xᵢ ⊒ Xⱼᵃ`; any algorithm satisfying this produces exactly the
  reachable states of the state-based CRDT ⇒ per-object causal consistency
  [Prop 2, Cor 1]. Causal anti-entropy [Alg 2]: durable per-node sequence
  counter ("otherwise... violating the delta-merging condition"), ack map,
  ship the unacked interval, fall back to full state if GC'd.
- **Causal δ-CRDTs** [§7.4]: state = `DotStore × CausalContext`; "a dot present
  in a causal context but not in the corresponding dot store means... removed
  meanwhile. Therefore, the causal context can track operations with remove
  semantics, while avoiding the need for individual tombstones." Under causal
  delivery the context compresses to a **version vector**. DotSet join:
  `(s∩s′) ∪ (s\c′) ∪ (s′\c)` with `c∪c′`.
- **Delta AWSet** [Fig 15]: `add^δ` ships new dot + old dots-in-context (join
  erases them); `remove^δ(e) = ({}, m(e))` — **removal ships only observed
  dots in the context, no element payload, no tombstone**.

**ComputeNet relevance**: ComputeNet's `SetDelta` keeps del-tag tombstones
forever; the dot-store/causal-context factoring shows removal can be *context
only*, with tombstone-freedom bought by the causal-merging condition. That
condition maps directly onto machinery ComputeNet has: per-source contiguous
counters + baseline `StateRequest(since)` = delta-intervals + full-state
fallback. This is the concrete design for compacting `dels` (gap 6) and for
the OR-map (gap 2). The cost: replica-to-replica links must enforce the
causal-merging condition (per-peer interval tracking), which the current
gossip full mesh does not.

## 3. Pure op-based CRDTs: causal stability = the GC trigger (S3, S6)

- **PO-Log** [§6]: state is a partially ordered log `T ↪ O`; effect = set
  union (commutative). Full logs are "not realistic."
- **Causal redundancy** [§7.1]: datatype-specific relations discard operations
  at delivery. Compact AWSet [Fig 8]: removes/clears are never stored; an add
  is discarded when causally dominated — "a PO-Log that is deprived of any rmv
  or clear operations." Remove executes by deleting dominated adds, using the
  middleware's causal order. **Lossless tombstone-freedom.**
- **Causal stability** [Def 5.1]: "A timestamp τ... is causally stable at node
  i when all messages subsequently delivered at i will have timestamp t > τ"
  — stronger than "received by all nodes" (no further *concurrent* delivery
  possible); per-node, not global. Oracle: track last-delivered vector-clock
  entry per peer; τ is stable when every other node has delivered past it.
- **Stability-based compaction** [§7.2]: a stable timestamp "can be replaced
  by ⊥"; the stable part detaches into "a plain set of all stable operations...
  e.g., a bitmap for dense sets of integers" — the CRDT **degenerates
  losslessly into a sequential data structure plus a small unstable frontier**.
- **Infrastructure cost** [§5, §8.2]: tagged causal broadcast (TCSB) with
  `tcstable` callbacks, exactly-once delivery, and **membership knowledge**
  (the oracle enumerates all peers). The delta paper's own comparison: pure
  op-based "requires more systems guarantees than δ-CRDT do."
- **Practical caveats** [S6]: "if one single node does not issue any updates
  for some time, no causal stability can be determined at any replica during
  that period" — the idle-source problem again, in CRDT clothing. RCB
  buffering also delays unrelated concurrent ops. Their fix: derive stability
  from RCB *acks* plus periodic stability broadcasts, with the stability
  message's clock bumped past known concurrents.

**ComputeNet relevance**: causal stability is the replication-plane analog of
reader-frontier compaction (doc 02 §4) and the correct trigger for tag/
tombstone GC (gap 6). ComputeNet's `Timestamp(sourceId, counter)` tags are
already dot-shaped; the missing ingredients are (a) a per-replica-set
membership view (which `LocationRegistry.replicasOf` approximates, eventually
consistently — the gap is knowing the set is *complete*), and (b) per-peer
delivered-watermark exchange (PN-counter-shaped, pointwise max — gossipable).
The idle-replica liveness caveat [S6] must be answered with heartbeat/ack
traffic, exactly as Bauwens does.

## 4. Riak DT map: the convergent multi-writer OR-map (S4)

The design ComputeNet's gap 2 asks for:

- **Composition**: "a multi CRDT holder... Uses the same tombstone-less,
  Observed Remove semantics as riak_dt_orswot." Embedded CRDTs "share the
  causal context of the map, even when fields are removed, and subsequently
  re-added"; embeddable types "must support embedding: that is a shared,
  dot-based, causal context, and a reset-remove semantic." [S2 Fig 17 agrees:
  one common causal context for the whole map, "never reset that single
  context" — per-key contexts "would introduce anomalies when recreating
  keys."]
- **Reset-remove**: "if a field is removed, and concurrently that same field
  is updated, the field is _in_ the Map (only observed updates are removed)
  but those removes propagate, so only the concurrent update survives" —
  remove-field ≡ remove-all-observed-content + remove-field.
- **Counter anomaly**: embedded counters can't get full reset-remove without
  a dot per increment ("very costly"); concurrent update + remove leaves the
  updated counter ("much like appending to a file").
- **Deferred operations**: context-carrying removes for state the local
  replica hasn't seen yet are parked "in a list of deferred operations" and
  executed "when, eventually... the Map's clock descends the context." Only
  actorless (remove) ops may defer. Known bug: a field remove can drop a
  deferred op stored inside that field.
- **Size cost**: concurrent dot→field entries are kept unmerged; "the
  repetition of actor information... is a serious issue with regard to
  size/bloat."

**ComputeNet relevance**: the OR-map is buildable — keys behave like
`KeyedSetCell` entries (observed-remove), values are embedded mergeable cells
sharing the map's causal context. Adopt: shared context, reset-remove,
deferred context ops (which ComputeNet's park/replay machinery resembles).
Avoid: embedded non-idempotent counters (Riak's own anomaly) — restrict
embedded values to ComputeNet's `Replicable` class; and dedupe actor metadata
at the codec layer from day one (Riak's bloat lesson).

## 5. CALM: the boundary condition (S5)

- **Theorem 1**: "A program has a consistent, coordination-free distributed
  implementation if and only if it is monotonic." Consistency = confluence
  ("the same set of outputs for any non-deterministic ordering and batching of
  a set of inputs"); coordination = messages required "under all possible
  partitionings — including partitionings that co-locate all data at a single
  machine." Monotone programs are exactly those needing no knowledge of
  membership ("they do not query All").
- **Monotone**: selection, projection, intersection, join, transitive closure.
  **Not**: set-difference, universal quantifiers/¬∃, updates and deletions in
  general. Anchor: "A deadlock is identified by the existence of a (cyclic)
  path. Garbage is identified by the non-existence of a path" — GC is
  inherently coordination-bearing.
- Mitigations named: tombstoning (monotone undefined → defined → tombstoned),
  lazy background coordination for deletion ("even tombstone GC ultimately
  needs agreement"), compensation/apologies.
- Could NOT verify in this text: the oft-cited sharper claim "reads of CRDT
  state are not confluent" — treat that phrasing as secondary-source
  paraphrase. The paper's actual position: CALM static analysis can "certify
  when programs provide the state-based convergence properties provided by
  CRDTs, and when those properties are preserved across compositions."

**ComputeNet relevance**: CALM is the *classifier* for the whole operator
suite. ComputeNet's tagged retraction keeps deletion confluent (the Lasp §7.4
trick) — but any operator whose *output value* requires knowing absence or
completeness (antijoin emission, fold over current value, threshold/bucket
cells, "group death") is non-monotone and needs sealing machinery: a wave
frontier locally, causal stability across replicas. This cleanly predicts
which ComputeNet cells can stay coordination-free (union, intersect, filter,
map, join, semijoin-presence) and which need frontier gating (antijoin,
aggregations observed as values, bucket thresholds, multi-view snapshots).
