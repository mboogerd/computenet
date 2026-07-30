# 22 — Consistency: Context, Glitch-Freedom, Topology Versioning

> **Status**: Specified; context machinery and the opt-in glitch-freedom wrapper implemented (static frontier); the catch-up-baseline rule below is implemented (W2.2); the source-epoch, cycle-head, edge-marker, and watermark rules below are decided design (93), unimplemented; the bridged frontier below is location-transparent — `EdgeOpen`/`EdgeClose` cross the wire today (W3.2), and `Progress` absorb-acks are implemented both in-process (`cell.control.absorbAck`, CP-A3) and across a bridge, over the handshake-routed bridged open (CP-A2)
> **Sources**: ADR — Glitch Freedom, ADR — Task Connectivity (§2, MessageContext), 93 (feature-interaction resolutions I-1/4/5/11/13/14/18/23/24)
> **Implementation**: `cell.MessageContext`/`Timestamp`/`CurrentContext`, `cell.proxy.Invocation.context`, stamping in `cell.port.FanOutlet`, `cell.consistency.GlitchFreeCell`

## MessageContext (normative, as implemented)

Every data-path invocation carries:

```kotlin
data class MessageContext(
    val timestamp: Timestamp,  // logical time / propagation-wave id
    val sourcePort: PortRef    // identity of the emitting port
)
data class Timestamp(val sourceId: UUID, val counter: Long)  // per-source waves (G-20 decision)
```

Rules:

1. **Origination**: an external event entering the graph (a source cell
   emitting spontaneously) mints a fresh timestamp (wave id) from the emitting
   outlet's own monotonic counter.
2. **Transparent flow**: when a cell emits in response to an inlet invocation,
   the outlet invocation carries the *same* timestamp; `sourcePort` is
   rewritten to the emitting port. Framework responsibility — cell logic never
   touches context. The single stated exception to transparent flow is
   re-origination at a declared cycle head (decided in
   [93 I-5](../90-roadmap/93-feature-interactions.md); see Cycles below).
   *(Implemented: outlets stamp at emission; `Invocation`
   carries `context`; `Invocation.invoke` is the single restore point, so
   delivery and buffered replay both run under the invocation's own context;
   management invocations have null context and clear any stale wave.)*
3. **Fan-out** duplicates context; **fan-in** delivers each input's own
   context (that is the point — see buffering below).
4. Delegation is not a context no-op: a delegated path still presents the
   correct (new) source port per hop (10/14) — every outlet hop rewrites
   `sourcePort`.
5. **Generic-protocol traffic is outside the wave domain** (decided in 93
   I-1, I-4): metadata-plane messages — attention (the exemplar),
   state-request, frontier discovery, link management — carry null
   `MessageContext` and never mint, carry, or clear waves; `sourcePort` has
   no meaning for their upstream travel (the receiver keys on the link the
   message arrived on). True of the shipped synchronous `ProtocolSupport`
   delivery (M6.1), recorded here as normative for every carrier. A protocol
   payload MAY carry its own monotonic discriminator — attention's
   per-emitter LWW `version` — a third use of the clock shape beside waves
   and merge tags (see Interaction below), never a wave.

Two decided field extensions to `MessageContext` (93, unimplemented):
`hop: Int = 0` — incremented per transparent-flow hop, reset to 0 by
cycle-head re-origination, a pure divergence guard that is never part of the
wave join key (93 I-5) — and `baseline: TagFrontier?` — non-null marks a
delta as a catch-up baseline, excluded from every wave-completeness set
(93 I-24; see Pull/late-join below).

⚠ GAP (G-36): all metadata-plane notices are single-hop (M6) — attention
retraction, upstream disinterest, Stall/Progress, and state-request pulls do
not propagate through absorbing or stateless intermediaries to distant joins
or remote producers. Proposal: one hop-by-hop re-emission rule for
metadata-plane notices with loop prevention and the band-quantization
interaction pinned per protocol — attention levels and transitive retraction
across damped hops, disinterest quiescing remote producer cones,
stall/progress watermarks reaching deep joins, and requestState forwarding
through stateless cells (93 I-1/I-4/I-9/I-16/I-18).

*(G-4 resolved. Context is captured into invocations at the cross-host proxy
(`HostedCellProxy`) and the `Buffering` recorder; wire bridges are M5.)*

### Source identity: emission epochs (decided in 93 I-14, unimplemented)

A "source" is one outlet during one **emission epoch** of one instance —
never the logical cell, and finer than the instance (each emitting outlet
has its own counter). An outlet MUST mint a fresh collision-free `sourceId`
with `counter = 0` at each epoch start, and MAY preserve `(sourceId,
counter)` across an epoch boundary only when the framework restores a
provable counter high-water for that outlet (Rules S1/S2). Fresh epochs:
cold start, supervision RESTART (no proven high-water), new-instance spawn
(replicas, shadow candidates), promotion without state transfer. Preserved
epochs: suspend/resume, migration (the high-water rides the migration
payload), and a promotion whose state transfer carries each outlet's
`(sourceId, highWater)` inside the buffered swap window. Thus `(sourceId,
counter)` is never reused, and distinct instances or epochs mint disjoint
source ids.

- **Origination at merge (Rule S4)**: a `Replicable` cell re-emitting an
  effective delta after a CRDT merge is a wave origination point — it MUST
  mint a fresh wave from its own outlet. Gossip convergence is carried by
  merge tags, not wave alignment, so no source id circulates around the
  gossip mesh.

- **The frontier quantifies over physical sources (Rule S5)**: glitch-free
  completeness buffers per `(sourceId, counter)` and quantifies over
  outlet-epoch sources, never logical cells. A logical upstream with several
  instances is several sources: the consumer gets convergence, not the
  diamond guarantee — recovering it takes subscribing to a single instance
  or an explicit coordinator cell (G-20's escape hatch).

- **Wave visibility of instance swaps (decided in 93 I-11)**: a
  preserved-epoch adoption — the candidate adopts the incumbent's
  `(sourceId, highWater)` inside the drained swap window — is invisible to
  downstream completeness: no EdgeEvent, the same source lane continues
  monotonically. A fresh-epoch succession of a live lane MUST be announced
  via the `ReBaseline` supersession notice (93 I-22) naming the superseded
  source ids — never silent; glitch-free consumers recompute their
  per-source completeness set on it.

  ⚠ CONFLICT (C-12): landed RESTART restores the spawn-time checkpoint and
  continues emitting under the same outlet sourceId/counter (aliasing),
  contradicting the decided fresh-epoch + ReBaseline supersession rule
  (93 I-22, reconciled).

⚠ GAP (G-42): epoch source-ids and restart generations accrete unboundedly —
OR-set/PN source columns, stale glitch-free partial-wave buffers, and
frontier entries for vanished epochs are never reclaimed, and
counter/generation continuity across migration and host failure is unpinned.
Proposal: safe reclamation of provably-superseded epochs (compaction riding
G-25 checkpoints), frontier GC for orphaned partial waves triggered by
relink-driven recompute, a concrete migration-payload field carrying the
outlet counter high-water, durable-counter batching kept off the emission
hot path, and generation derivation from the journal high-water (fresh high
base on non-durable hosts) so post-restart tags never alias
(93 I-14/I-22/I-3/I-7).

## Local glitch-freedom (opt-in)

A **glitch** is observing an inconsistent intermediate state in fork-join
topologies (diamond: `A → B, A → C, B+C → D`; D must not see B's update for
wave *t* paired with C's for wave *t−1*).

Global propagation locks (SID-UP-style) are **rejected**: they serialize the
whole graph (violates P4). [22-GF-01] WHILE a wave from a single source is
partially delivered across a fork-join, a cell that has declared itself
glitch-free SHALL NOT expose derived state that mixes pre-wave and post-wave
inputs. Instead, a cell MAY declare itself glitch-free, and then:

1. **Dependency tracking** — on init and on topology change, traverse upstream
   to discover which inlets/edges feed it for a given wave.
2. **Version buffering** — buffer per-wave inputs until the input set for that
   wave is complete.
3. **Local evaluation barrier** — evaluate exactly once per wave, with a
   consistent snapshot; no locks beyond the cell.
4. **Topology consistency** — link/unlink events are part of the update stream
   and must be causally ordered with value updates (a wave knows which
   topology version it flows over).

### The glitch-free frontier (composition)

Traversal does not recurse to sources: it stops at the **nearest upstream
glitch-free cells**, whose outputs are already per-wave consistent. Only the
edges between that frontier and the declaring cell need tracking and
buffering. [22-GF-02] Consequently glitch-freedom **composes**: WHILE a wave is partially
delivered across nested or chained fork-joins, every glitch-free cell in the
chain SHALL preserve the no-torn-wave guarantee — chains of glitch-free cells
cost each cell only its local frontier.

Non-declaring cells process eagerly with zero coordination cost (P4, P2).

Operators that fan one input delta into several outputs keep per-delta
atomicity: `GroupByCell` (M11.3) emits all groups touched by one input delta
as a single `MapDelta` under the input's wave id, so a downstream glitch-free
wrap composes normally.

*(Implemented — static-frontier phase: `GlitchFreeCell` wraps a fan-in edge
set with per-wave version buffering; the frontier is the inlet's current link
set, recomputed on every completeness check, so link/unlink adapts the
condition (the first instance of "topology is part of the completeness
condition"). Waves flush in per-source counter order; per-link FIFO (30/31)
makes wave completion monotone per source. Validated by a diamond-topology
invariant test: 200 seeds of randomized cross-host scheduling glitch-free,
with a control run proving the harness produces glitches without the wrapper.
Upstream frontier traversal awaits multiplex ports (G-13) — its traversal
model is decided, see plan item 4; unwaved traffic passes through.)*

### Completeness over silent or stuck edges (decided in 93 I-18; implemented — in-process CP-A3/CP-A4, bridged CP-A2)

[22-LIVE-01] IF an independent source feeding a multi-source join is silent for
a wave, THEN the join SHALL NOT block updates from other sources from becoming
observable at quiescence (completeness is per-source; over-alignment across
independent sources is forbidden).

The shipped wrapper can only observe arrivals; the decided rule makes
non-delivery positive. A glitch-free join keeps a per-inlink, per-source
**watermark** — the highest counter known settled on that edge — advancing
on a real data delta (per-link FIFO settles every lower counter, so an
absorbed wave is retired by the next delta or any later wave), on a
metadata-plane `Progress(thru)` absorb-ack emitted at an upstream's
quiescence boundary, or on a later wave (monotone, `max`). Completeness of
wave (s, t) ⟺ every OPEN inlink with floor(s) < t has watermark(s) ≥ t.
Typed `Stall(reason, recoverable)` markers resolve a stuck edge by per-edge
policy keyed on recoverability: WAIT (park-not-drop, the default) or
DEGRADE for recoverable stalls, RE-SCOPE (abandon the lost contribution
loudly) for terminal ones. The version buffer is transient on supervision
RESTART: the join drops it and re-runs frontier discovery + catch-up
(re-catch-up, not restore) — safe precisely because unflushed buffers were
never observed downstream.

**The absorb-ack rule (96 §E2.1 rule 3).** [22-LIVE-01] makes non-delivery
observable at the join's boundary; this rule is its emitting-side obligation.
An operator cell whose waved input yields no effective output MUST advance
downstream watermarks via a metadata-plane `Progress(sourceId, thru)`
absorb-ack, riding the exact wave it would otherwise have silently swallowed
— the second of the three watermark-advance mechanisms named above (delta,
`Progress`, later wave). Without it, a downstream join can only settle an
absorbing arm from real data arrivals, and a mid-pipeline filter/join/
antijoin that drops the final wave strands the join forever (the G-40
family). `cell.control.absorbAck` is the shared helper (an extension on
`FanOutlet`, CP-A3): it reads the wave off the current
invocation's context, skips baseline and spontaneous emissions — already
excluded from every completeness set above — and fans the ack over the
outlet's real links only, so a topology-blind subscriber pays nothing. The
per-cell call site is the uniform emit-or-absorb-ack shape (`emitOrAbsorb` in
`civictech.cell.data.op`): propagate a non-empty delta, or absorb-ack the
swallowed wave. Adopted across the operator suite — `FilterCell`,
`SemiJoinCell`, `IntersectSetCell`, `JoinCell`, `JoinSetCell`, `GroupByCell`,
`FlatMapSetCell`, `UnionSetCell`, `QuorumSetCell`, `CountCell`, and
`CoalescingCombineCell`, all under `civictech.cell.data.op` — call it at the
end of their waved handler exactly when the wave produced no outlet
emission. *(One absorbing operator does not yet satisfy this MUST:
`cell.data.op.CombineLatestCell` drops an effective-only-silent wave without
an ack. A known divergence, not an exemption — the rule binds it.)* The
consumer is the per-inlet watermark fold this subsection
describes, landed as `cell.consistency.WaveFrontier` (CP-A4, §The
observation frontier below): its `Progress` handler folds the ack into the
same per-edge, per-source watermark map a real delta advances, so an
absorbing arm is retired by whatever it next produces — a delta, an ack, or
a later wave — never left silently stuck. The rule holds identically
in-process and bridged: CP-A2 routed the bridge install through the shared
`handshake()`, and the absorb-ack rides the same `topology-order` frame path
as `EdgeOpen`/`EdgeClose`, so an absorbing **remote** arm settles a far-host
wave exactly as a local one does. *(§Bridged frontier below still labels the
`Progress` crossing and the handshake-routed bridged open unimplemented —
a stale label against this landed code, not a live residual.)*

⚠ GAP (G-40): *(residual narrowed by CP-A2/CP-A3/CP-A4 — the core mechanism is
landed; see §Completeness over silent or stuck edges above)*. Landed:
per-source per-edge watermarks advanced by real deltas, by the `Progress`
absorb-ack rule above (in-process CP-A3, bridged CP-A2), or by later waves
(monotone close); typed
`Stall(reason, recoverable)` markers with per-edge WAIT | DEGRADE | RE-SCOPE
policies keyed on recoverability — both folded by
`cell.consistency.WaveFrontier`. Open: pin the DEGRADE correctness contract
(how downstream distinguishes a degraded emission from a genuine one),
calibrate the backstop deadline against frontier depth, and build the
generative completeness harness proving the mechanism under randomized
absorb/suspend/restart/dead-letter schedules (93 I-18); and bring
`cell.data.op.CombineLatestCell` — the one absorbing operator still missing
its ack — under the rule.

## The observation frontier

A multi-view observation edge — several named outlets folded into one
composite read, `cell.observe.CompositeSink`'s honest point-consistency
problem — needs its own consistency ideal, distinct from a single glitch-free
cell's diamond guarantee above: the target is not "this cell never mixes
pre/post-wave state" but "this composite read never mixes waves across its
contributing views" (backlog `consistent-multiview-snapshot.md`, F-5;
advances G-13's frontier residual and G-40; 96 §E2.1).

### The guarantee: internal consistency by per-source vector-frontier alignment

The observation edge's target guarantee is **internal consistency**: "every
output is the correct output for some subset of the inputs provided so far"
(`doc/research/incremental-engines/04-cross-cutting-watermarks-consistency.md`
§3). [22-OBS-01] The composite observation edge SHALL achieve internal
consistency as **per-source-vector-frontier alignment**: every emitted
composite output SHALL be the correct output for some per-source vector
frontier of this replica's inputs (Ubiquitous).

Materialize's alternative is explicitly rejected: a single scalar virtual
time assigned to every event at ingestion, so every operator's output aligns
at identical timestamps, requires a coordination point — a timestamp oracle —
that ComputeNet's coordination-free replication model refuses (P4;
`04-cross-cutting-watermarks-consistency.md` §4;
`02-timely-differential-materialize.md` §7). A per-source vector frontier is
the coordination-free analog of the same alignment idea: per-source-prefix
batch-equivalence *per replica*, the shape Feldera's strong-consistency claim
names but can only offer globally, under one total order
(`01-dbsp-feldera.md` §6) — ComputeNet trades that global guarantee for a
per-replica one it can get without a coordination point.

### The aligned-composite rule

[22-OBS-02] A composite for wave `(s, t)` SHALL be assembled only after every
contributing view has settled every wave at or below the shared frontier, and
the composite's inlets SHALL be **per-name**, preserving each contributing
view's identity, rather than replaying every view's raw deltas into one
anonymous stream (State-driven). Per-name inlets are what dissolve
`observeAll`'s documented blocker 1: `GlitchFreeCell` replays raw deltas to a
single outlet, erasing which named source each delta came from, so two
`set(...)`-registered views emitting the same delta type become
indistinguishable downstream (`observeAll`'s KDoc, `cell.observe`). A
per-view identity tag on each buffered delta is what lets the
aligned sink fold each named view under its own accumulator and release one
snapshot at completeness, instead of one shared accumulator that cannot tell
its inputs apart.

*(Landed as `cell.observe.AlignedCompositeCell`, behind the
`ManagedHost.observeAligned { … }` builder — 96 §E2.3, E2-ALIGN. One named
inlet per contributing view carries the per-name clause structurally; one
completeness fold spanning **every** inlet's edges — `WaveFrontier`'s
condition mirrored at cell scope, the `CoalescingCombineCell` precedent —
carries the assembly clause, publishing one composite per settled wave in
per-source counter order. It is the WAIT shape: no `DEGRADE` frontier
shrinking, no RE-SCOPE, no replica-fed settlement, and the static-link-set
residual below still applies (an arm that structurally never carries a source
is a phantom expected edge for its waves until an ack, a later wave, or an
`EdgeClose` shrinks the condition). `cell.observe.CompositeSink` remains the
shipped point-consistent fallback — honestly documented as not wave-aligned,
and the right choice when a stalled arm must not delay a read.)*

**What this section already governs.** `cell.consistency.WaveFrontier` — the
per-inlet wave-completeness fold extracted from `GlitchFreeCell` (CP-A4) — is
the landed completeness primitive this guarantee is built from: "every
contributing view has settled every wave ≤ the shared frontier" is exactly
the per-inlet completeness test `WaveFrontier` already computes, one inlet
short of the many-named-inlet aligned composite the rule above describes.
The absorb-ack rule above (§Completeness over silent or stuck edges) is a
prerequisite this guarantee assumes: without it, a `WaveFrontier` spanning an
absorbing view can stall short of the shared frontier indefinitely rather
than ever reach it.

### Acceptance benchmark: the balanced-transfer suite

The named acceptance benchmark for this guarantee is the **balanced-transfer
suite** (`04-cross-cutting-watermarks-consistency.md` §3, the "$1-transfer"
experiment): a generative stream of balanced transfers — every debit paired
with a credit in one wave — driven through a credit/debit join, a grouped
aggregate, and an outer self-join, with the invariant (the running total is
always zero; no observed output corresponds to a partially-applied transfer)
checked at *every* observed output, not only at idle. A point-consistent
composite and an ungated (non-monotone) outer join are the suite's documented
failure controls (04 §3; the outer-join gating rule is 20/24 §Operator
library, below).

## Topology versioning

Structural changes must be causally consistent with value updates:

- Each link/unlink applied by a host is stamped into the same logical-time
  domain as data waves.
- A glitch-free cell treats "the set of edges feeding wave *t*" as part of
  wave *t*'s input completeness condition.
- *(G-20 decided and implemented: wave ids are per-source monotonic counters —
  `Timestamp(sourceId, counter)`, minted by the emitting outlet. No global
  time protocol; glitch-free cells buffer per (source, counter). [22-SRC-01]
  Cross-source joins SHALL get **convergence** — equal folds on all observers at
  quiescence — not simultaneity, unless an explicit coordinator cell is inserted. Cycle wave semantics are decided in 93 I-5 — see Cycles
  below; fixpoint semantics stay open with G-19.)*

In-band edge markers (decided in 93 I-13, unimplemented): the stamp lives on
the *effect*, not the invocation. Link/unlink stays a null-context
management op (§MessageContext); its effect emits a per-link
`EdgeOpen`/`EdgeClose` marker riding the affected link's own FIFO —
`EdgeOpen` ahead of any data, `EdgeClose` after the last. The marker carries
no wave id of its own: its logical time is the per-source **floor** the
consumer computes when it processes the marker (its flushed high-water per
source). Completeness rule: wave (s, t) expects a contribution from every
OPEN inlink with floor(s) < t; arrivals at or below the floor are discarded
as pre-join (idempotent, safe against a lagging upstream); `EdgeClose` drops
the link from all in-flight waves, unblocking any wave waiting on it.
Emission is gated on a downstream having expressed topology-order interest;
eager and mergeable consumers ignore EdgeEvents entirely. Migration, wire
re-resolution, and instance swaps emit NO EdgeEvent — versioning operates on
the logical edge set, which they leave intact — qualified by the epoch
guard: a fresh-epoch succession is still not an edge event but is
wave-observable via `ReBaseline` (§Source identity above; 93 I-14/I-22).

⚠ GAP (G-39): link/unlink are null-context management ops with no stamp in
the wave domain — glitch-free consumers cannot know from which wave a
new/removed edge counts, source-set changes do not propagate downstream.
*(EdgeEvents/floors' wire form is resolved, W3.2: bridged EdgeEvent frame
types — `PORT_PROTOCOL` frames on the `topology-order` protocol — are
ordered against data across disconnect/park/replay by the same per-link
FIFO the bridge already gives ordinary sends; see 41 point 4.)* Proposal: in-band
EdgeOpen/EdgeClose markers injected into the affected link's own FIFO
carrying a per-source flushed-high-water floor; design the floor
representation and retention/compaction horizon, hop-by-hop downstream
source-set delta propagation with a liveness proof (an upstream cut must not
strand a waiting join), the floors×cycles×merge-tag interaction, and the
explicit topology-serializing coordinator (JoinBarrier) cell that doubles as
the diamond-over-replica escape hatch (93 I-13/I-14).

## Bridged frontier (location transparency)

The observation frontier is **location-transparent**: the markers that carry
it — `EdgeOpen`/`EdgeClose` (§Topology versioning) together with the
`Progress(thru)` absorb-acks and `Stall` markers of the watermark rule
(§Completeness over silent or stuck edges) — cross a wire bridge in the same
per-link FIFO frame order as the data they order. [22-GF-03] WHERE a frontier edge is bridged across a host boundary, a glitch-free
join SHALL compute an identical completeness condition and preserve the diamond
guarantee across the cut, exactly as for an in-process edge. A bridged edge is a `WireEdgeLink` (`cell.wire`)
carrying stable per-side link identity (40/41 point 4), so frontier bookkeeping
keyed by link id (`GlitchFreeCell.edges`, per-source watermarks) behaves exactly
as it would for a local link, and the diamond guarantee holds across the cut.

- **EdgeOpen/EdgeClose cross today** *(implemented, W3.2 — resolves the
  wire-crossing sliver of G-39)*: they travel as ordinary `PORT_PROTOCOL`
  frames on the contract-backed `topology-order` protocol, inheriting the
  per-link FIFO the bridge already gives data (40/41 point 4). The consumer
  computes the per-source floor on the marker it receives exactly as in-process
  (§Topology versioning), so the far-host join opens and closes its arms in
  frame order against the data on the same channel.
- **Progress absorb-acks and `Stall` do not cross yet** *(decided design,
  unimplemented — CP-A2, composition plan)*: the watermark rule's
  non-delivery signals — a `Progress(thru)` absorb-ack minted at an upstream
  quiescence boundary, and typed `Stall(reason, recoverable)` — must ride the
  same `topology-order` FIFO across the bridge (as additive `WireFrame`
  fields, no version bump), so a quiescent or stuck **remote** arm settles (or
  loudly resolves) a far-host wave the same way a local one does. Without this,
  a glitch-free consumer on the far side of a bridge can complete a wave only
  from real data arrivals on its bridged arms; an effective-only-silent remote
  arm blocks it forever — the cross-host face of G-40.

⚠ CONFLICT (C-13): the shipped bridged link is installed by
`WireEdgeLink.bridgeTo`/`bridgeFrom` (`cell.wire`) calling `linking.register`
directly and firing `EdgeOpen` from a raw `Protocols.sendDownstream`, never
routing through `handshake()` — so a bridged edge skips the link policies, the
peer allowlist (43), the cardinality/cycle checks, and the negotiated
`EdgeOpen`/`EdgeClose` emission that a local link runs. Local and remote links
thus negotiate differently, contradicting location transparency (40/41
§Requirement, where this is named a bug). Reconciled by routing the bridge
install through `handshake()` and forwarding `Progress` absorb-acks over the
frame path (CP-A2, composition plan; see 40/41 point 4).

## Implementation plan (ordered; 1–3 done)

1. ~~Context on `Invocation` + current-context (G-4).~~ Done.
2. ~~`PortRef` unification.~~ Done (`cell.port.PortRef`).
3. ~~A `GlitchFree` decorator cell implementing version buffering (kernel
   untouched, per P1).~~ Done (`cell.consistency.GlitchFreeCell`).
4. Upstream frontier traversal (decided in 93 I-23, unimplemented): a
   FRONTIER generic protocol on the metadata plane — "describe your
   frontier" rides resolved links upstream as a null-context query with
   per-recompute epoch + visited-edge-set termination (each edge expanded at
   most once; re-encountered edges report CYCLIC and are left to G-19
   fixpoint semantics). Reports speak only wave source ids and guarantee
   kinds (TERMINAL | TRANSPARENT | CYCLIC | OPAQUE), never cell/port refs,
   so hiding is preserved by construction. The static frontier is the
   conservative fallback and the traversal its refinement — two refinement
   levels of one topology-versioned slot; the cell never blocks on flood
   completion.
5. Topology events as in-band edge markers (decided in 93 I-13,
   unimplemented): the link/unlink management invocation stays null-context;
   its effect emits the per-link `EdgeOpen`/`EdgeClose` marker whose logical
   time is a consumer-computed per-source floor (§Topology versioning above;
   depends on Link objects, G-12). The static-frontier wrapper already
   recomputes its edge set from live links.

## Interaction with other parts

- **Suspension/migration (30/33)**: buffered waves survive because buffering
  happens at ports (Buffering proxy) and context rides inside invocations.
- **Pull/late-join (21)**: a catch-up snapshot is *not* stamped with the
  wave it represents — a multi-source fold has no representable wave.
  *(Implemented, W2.2 — decided in 93 I-24)*: a catch-up state-as-delta is a
  **baseline** — marked by the nullable `MessageContext.baseline:
  TagFrontier?` field, a merge-tag frontier carried for dedup and
  incremental pull, never a wave position — causally anchored at the
  link-install topology event. A glitch-free consumer installs a baseline as
  arm state and never admits it to any wave-completeness set
  (`GlitchFreeCell` forwards it immediately, bypassing floors/watermark/
  pending); evaluation over the new arm resumes at the first wave that
  completes over the post-install topology. The install→first-complete-wave
  window gets convergence, not simultaneity (G-20), exactly as any topology
  change does. Tag frontiers are valid only for per-source-monotone
  families (full-state fallback otherwise, `since = null`); the
  quiet-upstream transition-window and multi-arm simultaneous-join
  generative harness extensions named in the original proposal remain
  follow-up work beyond this ticket's `StatePullTest` coverage.
- **Causal merge tags (24)**: observed-remove set tags reuse the `Timestamp`
  type but are minted cell-locally, *not* taken from the current wave —
  OR-set correctness needs a tag unique per add instance, and a wave id
  repeats across every cell the wave touches. The wave context still rides
  delta invocations unchanged; tags and waves are separate uses of one clock
  shape — attention's per-emitter LWW `version` (93 I-4) is a third: a
  payload discriminator, never a wave.
- **Cycles (21)**: decided in 93 I-5, unimplemented. Every cycle declares at
  least one **cycle head**; the head's feedback→emission transition mints a
  fresh wave instead of preserving the incoming one — the single stated
  exception to transparent flow (§MessageContext rule 2). Feedback inlets
  absorb into loop state and never join wave completeness, so the incoming
  wave terminates at the head and each loop iteration is a fresh wave. Wave
  uniqueness follows: no `(sourceId, counter)` traverses any cell twice, so
  per-wave buffering applies to loops unchanged — same-wave re-entry is
  structurally prevented, not detected. `MessageContext.hop` (incremented
  per transparent-flow hop, reset by head re-origination) is a liveness
  guard that dead-letters undeclared cross-host cycles past a
  host-configured bound; it is never part of the wave join key. Quiescence
  below the G-19 magnitude threshold terminates the wave sequence: the
  fixpoint is the state after the last emitted wave settles.

## Recovery is a baseline, not a wave (PN-2)

> **Status**: Implemented (PN-2). Journal replay re-enters the intake as a
> catch-up **baseline**, closing matrix cell A–D (durable + glitch-free).

Glitch-freedom is a property of the live *wave* plane; durability is a property
of the *state* plane. [22-REC-01] WHEN a cell recovers state by journal replay,
every replayed emission SHALL be installed as a baseline and produce no wave —
and therefore no glitch (the same rule already decided for `StateRequest`
replies, §Pull/late-join) — so observers converge to the pre-crash fold without
seeing a torn wave. Before PN-2, `ManagedHost.recoverFrom` re-injected each
journaled frame as an ordinary live wave. Post-PN-1 (replay-stable
`sourcePort`) those waves match their edge again, but a frame replayed into one
arm of a fork-join diamond then makes the join *expect* the sibling arm for that
wave — and a **volatile** sibling arm never replays it, so the join stalls
(transient floors make every sibling arm expected while the volatile arm never
advances). Pre-PN-1 the same frames dropped silently on a `sourcePort` mismatch.

**Rule of recovery.** Every emission in the replayed cone carries
`MessageContext.baseline` (a `TagFrontier`), taking the frontier's existing
baseline-exclusion path (§Pull/late-join): released immediately, installed as
arm state, admitted to no wave-completeness set. Mechanism:

- `recoverFrom` runs its whole replay inside a thread-local `ReplayScope`
  (the exact analogue of `PendingReBaseline`) holding the baseline frontier.
- A replayed frame that already carries a wave context — a **mid-graph** cell's
  frame, reactive from an upstream wave — is stamped `baseline` up front. A
  reactive re-emission then inherits it through the ordinary `MessageContext`
  copy, so the mark rides the entire cone across the asynchronous dispatch that
  follows the synchronous re-injection. A cell that *originates* mid-replay
  reads the frontier from `ReplayScope` at its spontaneous-emission point.
- A **root** cell's externally-driven frame carries no wave context and is
  replayed verbatim — byte-for-byte behavior for graphs that do not opt in, and
  the reason the mid-graph case (non-null context frames) is the one this rule
  newly covers.

`Effectful` processed-frontier consultation is unchanged: replay still dedups
an already-applied `(sourceId, counter)` against it, so an effect fires once
across crash + replay + resume. The invariant `released + suppressed ==
journal.replay().size` holds — a silent drop lowers `released` and fails loudly.

## Interest-scoped settlement (PN-7)

> **Status**: Implemented (PN-7). Resolves the latent liveness conflict F2
> between disjoint-`Interest` instance sets (PN-6) and the cross-replica
> completeness read (E3.4): before PN-7 completeness quantified over *all*
> replica-set members, so a disjoint-interest instance — one that by design never
> delivers waves outside its slice — kept its delivered-watermark row at bottom
> forever, stalling a `WaveMode.WAIT` consumer the moment shards joined the mesh.

**Rule of settlement.** A wave `(s, t)` touching keys `K` is *replica-complete*
iff for every `k ∈ K`, every **live, open member whose `Interest` admits `k`**
has delivered it. The read is `ReplicaFrontier.completeAt(source, counter, key)`:
the quorum is the **covering subset** of `LocationRegistry.instancesOf(logicalId)`
filtered by `interestOf(member).admits(key)`, not every member. One expression
collapses to the three cases:

- **all-`Total`** (every member wants every key) ⇒ the covering subset is every
  member ⇒ *today's behavior verbatim*;
- **disjoint** (partitioning) ⇒ the covering subset is the one owning slice ⇒ no
  phantom cross-slice member stalls the wave (F2 resolved);
- **overlapping partial** (sharded replication) ⇒ the covering subset is the
  slice's replica quorum.

A `null` `key` — a consumer that extracts no origin keys — is *unfiltered*: the
quorum is every member, byte-identical to pre-PN-7. The key is supplied per
buffered payload by the `ReplicaGate.originKeys` extractor (each key the payload
touches → the origin waves attached to it); with no extractor the gate falls
back to the flat `originTags` read with a `null` key. Defaults (empty
`originKeys`) therefore leave every non-opting graph unchanged.

**Membership index.** `LocationRegistry.instancesOf(logicalId)` is served off a
`byLogicalId` index maintained on every publish/removal, so the settlement read
— called once per buffered wave per `recheck` — is O(instances-of-one-id) rather
than a full scan of every published ref.

### R13 creation fence (promoted to blocking in PN-7)

Because filtering *shrinks* the quorum, a joining covering member is the
premature-release hazard: dropping it from the quorum releases a wave it has not
delivered — a mixed frontier — rather than holding conservatively. The fence
(`Replication.replicaFrontier(logicalId, creationFence = true)`, the default) is
the requirement that **every covering member is required**: one with no
delivered-watermark row for `source` reads as bottom and holds the wave until it
catches up, exactly as a lagging member does. With the fence off a member is
admitted to the quorum only once it has some row for `source`, so a freshly
joined covering member is silently skipped — reproducing the premature release.

**Honest boundary.** `instancesOf` membership is itself only eventually
consistent: a covering member the *local* view has not yet learned of at all
cannot be waited on by the quorum read (the read cannot conjure an unknown
member). The DEGRADE quorum-shrink analogue landed in PN-19 (a recoverably
suspended member is dropped, restored on resume); the *unknown-joiner* half is
closed by the converged-membership barrier below. Clean departure remains the
`WatermarkCell.close()` `closed` marker (PN-0c), which the covering read still
honors.

## Converged-membership barrier (FU-2)

The quorum read closes the *known*-covering-member hazard (a rowless member holds
via R13; a suspended member drops via DEGRADE). It cannot close the
*unknown*-joiner hazard on its own: a covering member the settling node has not
learned of *at all* is absent from `instancesOf`, so the wave releases on a quorum
that silently omits it — a torn read for that key, in the window between the
member joining and its existence reaching this node.

**Invariant.** An *unannounced* covering member must cause a **conservative hold**,
never a premature release — the same asymmetry R13 gives a known-but-rowless
member, extended to an unknown one. ⚠ EARS-GAP: this invariant is
boundary-relevant (a premature release is a torn cross-replica read) but is not
deterministically triggerable through the driver SPI — there is no verb to
inject a membership-announcement race — so it resists a checkable scenario as
the SPI stands; its clean observable form (sharded replicas converge without
torn reads) is a chapter-42 replication requirement, not a chapter-22 id.

**Mechanism (reuse, not a new protocol).** Each replica announces its existence
on the **delivered-watermark companion** (`WatermarkCell.announceMember()`): its
`slotId` is added to a grow-only `members` set carried in `WatermarkDelta` and
union-merged over the ordinary gossip mesh. The companion is a *transitively*
gossiped, idempotent CRDT — it merges over the whole mesh and self-heals — so it
converges membership **more completely** than the point-to-point topology
announcements that feed `instancesOf` (each registry mirrors only its *direct*
peers' local publishes; it does not relay second-hand locations). A node can
therefore learn *that a covering member exists* over the companion before, or even
without, learning *where it lives* over the topology.

**Read.** For a keyed wave (`key != null`, i.e. a covering-quorum graph),
`replicaFrontier(membershipBarrier = true)` — the default — computes the slots it
has *accounted for*: the members in its `instancesOf` view, plus `closed`, plus
(under DEGRADE) `suspended`. If the companion's `members()` set names any slot
outside that accounted set, the node holds **every** keyed wave (a deliberately
coarse, conservative hold — it need not resolve *which* range the unknown member
covers). The hold clears the instant `instancesOf` converges (the unaccounted set
empties), so it is a latency bound, not a wedge.

**Boundaries.** An unkeyed wave (`key == null`, no covering quorum) is never held
— the default settlement path is byte-identical. A fully-converged membership
never holds — a covering-quorum graph settles exactly as it did once everyone is
known. The residual is the Byzantine/permanent-partition case: a member that
announces then vanishes without a clean `close()` holds keyed waves until the
WAIT board's latency-unbounded contract (or DEGRADE / a `close`) resolves it —
the same standing property clean departure already relies on, not a new gap.
