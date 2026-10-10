# Composed cells: the composition grammar, and composites as obligation holders

Status: design proposal. Pinned to commit `8d71ea64`. At this pin, `doc/spec`, `kernel/src/main`
and `DISPUTES.md` are byte-identical to `27e57964` and `5a644938`. This note edits no spec and
assigns no requirement ids.

**Kinds of claim.**
- **Decided**: this design makes the call, inside its own scope.
- **Conditional on M*n***: the call revises a spec decision or settles an open dispute; only the
  spec owner can make it. These are collected in §7, each with options, a recommendation and a
  fallback. The body assumes the recommendation and says so where it relies on it.
- **Open**: left for later work, with the reason.
- **Limitation**: a declared ceiling or refusal that the design accepts on purpose.

Proposed requirements use EARS form. **[inference]** marks a conclusion reasoned to rather than
read in the code or spec.

**Citation keys.** `MH` = `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt`; `HD` =
`.../host/HostDurability.kt`; `PSS` = `.../partition/PartitionedShardSet.kt`; `93` =
`doc/spec/90-roadmap/93-feature-interactions.md`; `22`, `23`, `24` =
`doc/spec/20-dataflow-semantics/2x-*.md`; `31`, `33`, `34` = `doc/spec/30-execution-model/3x-*.md`;
`42` = `doc/spec/40-distribution/42-replication.md`; `53` =
`doc/spec/50-development-process/53-evolution.md`; `91` = `doc/spec/90-roadmap/91-gap-analysis.md`;
`DIS` = `concord/corpus/DISPUTES.md`. Other kernel paths are relative to
`kernel/src/main/kotlin/civictech/cell/`. **PLP** = the companion note `per-link-positions.md` in
this directory.

**Division of ownership between the two notes.** This note owns, and PLP refers to: the layers
and their order, the formation rules (F1-F12), the composites, the flip and relocation
*transactions* (records, prepare/commit, crash recovery), and the host contract (H1-H9, E1-E3).
PLP owns, and this note refers to: delivery positions, lanes (including replica-set lanes), the
receiver cursors, acknowledgement levels, and `O`'s retention rules (stable-before-transmit and
the unlink/death table). Maintainer decisions use one numbering across both notes, **M1-M16**:
M1-M11 are argued in §7 here, M12-M16 in PLP §10.

---

## 0. The design on one page

1. **The unit is the *term*.** A term is a leaf cell, a *layer* around exactly one term, or a
   *composite* of several terms. Every term behaves as a cell: port surface, descriptor, `control`
   inlet, transition capsule. The host spawns, routes, schedules, stores, re-creates and relocates
   terms, and never looks inside one.
2. **One new primitive, the `Term` seam.** A layer re-exposes its inner term's whole data-port
   surface and interposes on accept, emit, lifecycle, control and capture. A composite is the same
   seam over N children plus a ledger of the obligations that span them.
3. **The capsule** is what a continuation carries, assembled by nesting, opaque to the host, and
   transferred with `prepare / commit / abort` under a durable transfer id.
4. **Acceptance is a synchronous call into the term's outermost layer.** It returns an
   *acknowledgement level* (PLP §5.8). Until the level the sender's link requires is reached, the sender
   keeps custody (93:4911).
5. **Signals flow inward, faults outward.** A management system decides suspend, migrate, promote,
   repartition and designate. The host executes only physical acts.
6. **Canonical layer order**, outermost first: `Durable ∘ Outbox ∘ Suspendable ∘ Fence ∘ Align ∘
   EffectDedup ∘ Supervised ∘ leaf`, derived from formation rules over layer *properties* (§2.6).
   What a restart must not roll back sits outside `Supervised`; what must survive a crash sits
   inside `Durable`.
7. **Each multi-cell obligation has exactly one composite holder**: replica set (authority, fence,
   disposed-frontier publication, input retention, departure settlement); partition set (a durable
   flip transaction); glitch-free region (atomic suspension, contagious veto); promotion (a transient
   `Swap` above the leaf, with an effect-identity check); coupled membrane (partial units). Sender
   custody before acceptance is the sender's `Outbox` or client stub. A death is reported by
   survivors.
8. **The host contract has nine primitives** (§5) and two honest exceptions: exclusive-payload
   handling in the dead-letter store, and protocol band and direction read from the descriptor;
   plus one transitional exception, host intake coalescing, until it moves into `P` (§5.3).

---

## 1. Vocabulary

| Term | Meaning |
|---|---|
| **term** | A leaf cell, a layer around one term, or a composite of terms. Every term *is* a `Cell`. |
| **layer** | A term with exactly one inner term. It re-exposes the inner term's entire data-port surface under the same names, contracts, direction, cardinality and link identity, and adds behaviour by interposing. Its `control` inlet is a management surface, not a data port. |
| **composite** | A term with several children. A *data composite* owns data ports and exposes a declared subset of its children's (as `PartitionedCell` does). A *scope composite* owns no data ports; it holds control-plane obligations over members that keep their own stacks, and may span hosts (replica set, region). |
| **acceptance point** | Where custody passes from sender to receiver: the `accept` call on the receiving term's outermost layer. Before it, the "Owner-of-record … is the SENDER's link" (93:4911). |
| **capsule** | Everything a continuation must carry (§2.3). |
| **storage stream** | A logical append-only stream per `Durable` scope, with checkpoint and truncation; streams share one physical log per host (H1). |
| **management system** | Whoever decides mode changes (operator, placement/economic layer, graph applier). A client of `control` inlets; neither the host nor part of a term. |

**Delivery positions, cursors and acknowledgement levels are defined in PLP** and used here
unchanged:
- a **delivery position** `(epoch, lane, seq)` names one frame on one link; it is not a wave id
  and not content identity (PLP §0, §5.1). A replica set's inbound lanes are keyed by the set's
  logical address (PLP §3.2);
- the receiver cursors `received`, `applied`, `disposed`, `acted`, `pullDischarged` and, during
  a flip, `scoped` (PLP §5.1). Dedup is against `disposed` (at `X`) or `applied` (inner). A
  follower's authority-suppressed delivery advances `received` only (PLP §2);
- the acknowledgement levels `HELD < APPENDED < STABLE < DISPOSED`, the per-link required
  level, stable-before-transmit and the unlink/death table (PLP §5.8);
- the frontier **scopes** `stable` and `R` kept while a repartition is open, starting the `R`
  scope at the router's recorded `p_begin` and folded at `FlipDrained` (PLP §5.6).

**Transitions.** *Continuation*: fence admission, settle accepted work, capture state and identity,
release held work under its original context, adopt the identity. *Succession*: invalidate transient
work with an accounted disposition, mint a fresh epoch, announce `ReBaseline(supersedes,
supersede = true)`, rebuild by catch-up — whether a successor also re-runs the inbound handshake is conditional on M14; sound only for mergeable state (93:11480-11511). Only a
`supersede = true` notice retires an epoch; a pull-merge notice (`supersede = false`,
`MessageContext.kt:75-86`) retires nothing and fences nothing. *Selector*: continue only when the
successor can prove it is the one exclusive, complete continuation.

**Primitives.** accept, transfer/slice, discharge, report, announce+fence, refuse, veto; the host adds
persist and re-create-then-report-the-gap.

---

## 2. The composition grammar

### 2.1 Terms

```
term      ::= leaf | layer(term) | composite(term+ ; wiring ; ledger)
layer     ::= Durable | Outbox | Suspendable | Fence | Align | EffectDedup | Supervised
            | Coupling(couplings)              -- only directly inside a Membrane's Durable scope
            | Swap(incumbent, candidate)       -- transient two-leaf composite; only during promotion
composite ::= Membrane(exposures, couplings)   -- data composite (CompositeCell today)
            | PartitionSet(router, shards)     -- data composite
            | ReplicaSet(instances)            -- scope composite
            | Region(join, members)            -- scope composite
```

**Decided.** A new concern joins as a new layer that declares the properties of §2.5. The formation
rules are stated over properties, not class names.

### 2.2 What exists, and what each piece lacks

- **`CompositeCell`** (`membrane/CompositeCell.kt:95`) re-exposes organelle ports one at a time
  through a hidden-by-default exposure map (G-9). `flatten` (`:146-160`) re-registers the organelle's
  port object; `mediate` (`:194-226`) installs a `MediateProxy` inlet. Organelles are "never spawned"
  (`:80-83`). It forwards no lifecycle, state, held invocations or control; `PartitionedCell` relies
  on that ("its organelles simply become unreachable", `partition/PartitionedCell.kt:62-67`).
- **`TrafficLightCell`** (`membrane/TrafficLightCell.kt:39-68`) is a one-port layer with a control
  inlet and a private `ParkQueue` (`:48`); going green it removes itself from the path (`:32-33`). It
  exposes nothing to capture, and the host's checkpoint carry enumerates only the holding places it
  knows (HD:651-660), so a red buffer is invisible to a checkpoint **[inference, no test]**.
- **The `InletPolicy` chain** (`port/InletPolicy.kt:34-42`) has a fixed tier order `ADMIT < ALIGN <
  ACTIVATE` and a holding seam, `InletFrontier.checkpointPending()` (`port/FanInlet.kt:37-47`). It is
  the per-port precedent for ordered interposition and for exposing held work to an outer capture.
- **I-10** (93:4183) decided that a membrane is a "declarative, multi-port generalization of the
  TrafficLightCell"; the code form is unbuilt (G-10, 91:29; G-52, 91:38).
- **G-17** (MH:2260-2272) checks at spawn that every declared port is registered under its property
  name.

### 2.3 The `Term` seam (decided; a term-owned `Durable` layer is conditional on M3/M4)

```kotlin
interface Term : Cell {
    val control: Use<ControlApi>                       // management band, one per term
    fun accept(frame: HostedFrame): Ack                // synchronous; THE acceptance point; returns a level
    fun capture(purpose: CapturePurpose): Capsule      // throws Veto(reason) if not capturable now
    fun prepare(tx: TransferId, c: Capsule): Prepared  // receiver side: validate, stage durably; idempotent per tx
    fun commit(tx: TransferId); fun abort(tx: TransferId)  // idempotent per tx; unknown tx on abort = no-op
    val manifest: TermManifest                         // static: layer stack, children, holders, effect identity
}
abstract class Layer(val inner: Term) : Term           // re-exposes inner's ports by name; Mediate by default
abstract class Composite(val children: List<Term>) : Term
```

**Surface.** A layer registers every data port of `inner` under the same name and descriptor. The
formation check verifies each layer's re-exposed manifest equals its inner manifest, and that the
outermost descriptor is the leaf's ports plus `control`, so G-17 holds for the composed term without
reflection. Inlets are **Mediate** (the layer's `accept` runs on the path) unless F7 permits
**Flatten**. Outlets are flattened unless a layer interposes on emission: only `Outbox` (retention)
and `Supervised` (epoch stamping) do.

**Lifecycle.** `onActivate` cascades inward, `onDeactivate` outward. **No layer may discard held work
in `onDeactivate`**: it must already be captured, or the deactivation refused.
`GlitchFreeCell.onDeactivate → frontier.reset()` breaks this today (`consistency/GlitchFree.kt:136-138`;
computenet-5jhg3).

**The capsule.**

```kotlin
data class Capsule(
    val ref: CellRef, val descriptorVersion: String, val locationVersion: Long,
    val ports: PortManifest,                          // exposed ports and incident links by endpoint address
    val state: Serializable?,                         // the leaf's Stateful snapshot
    val held: List<Held>,                             // accepted, undelivered, in acceptance order: original
                                                      //   context, delivery position, ownership disposition, holder layer
    val identity: IdentityTier?,                      // outlet epochs and lane counters, generation, dead epochs
    val frontiers: Map<PortName, DeliveryFrontier>?,  // X, per inlet: keyed (epoch, lane), plus scope during a flip (PLP §5.1)
    val fence: Fence?,                                // F: (epoch, writer)
    val cursors: Map<String, Serializable>,           // durable-input cursors
    val outbox: Map<RetentionKey, List<Held>>,        // O: retained frames per (epoch, lane, recipient, scope), PLP §5.8
    val charges: Map<ChargeId, ChargeState>,          // open budget charges (§3.7)
    val layerPrivate: Map<LayerId, Serializable>,
    val children: Map<CellRef, Capsule>,              // composites only
    val ledger: Serializable?,                        // composites only: the coordination record
)
```

Each layer contributes its part and nests its inner capsule. `Stateful` (`Stateful.kt:11-14`)
becomes the leaf's contribution and `checkpointPending()` becomes `Align`'s; neither changes meaning.
A capsule is one holder's atomic capture of its own accepted work, not a global snapshot;
independent captures give convergence, not a cut (93:2849-2854). A capsule lacking an accepted child
or a frontier for an incident lane cannot prove an exclusive continuation, and `prepare` refuses it.
Frontiers and retention are never keyed by `Link.id`: it is random per `Link` instance
(`link/Link.kt:116`) and re-minted by the recovery re-handshake (PLP §3.1).

**Proposed requirements.**
- *When a term is captured, every layer that holds an accepted frame shall include it in the capsule,
  in acceptance order, with its original message context and delivery position.*
- *When a term is deactivated, it shall not drop an accepted frame that was neither captured nor
  dead-lettered with a report.*
- *When a capsule is transferred, the sending term shall not discharge any obligation in it until
  the receiver has committed under the same transfer id.*

### 2.4 Control

| Signal | Handled by | Kind |
|---|---|---|
| `SUSPEND` / `RESUME` | `Suspendable` | continuation, in place |
| `PASSIVATE` / `ACTIVATE` | `Suspendable`, then `Durable` captures; the host destroys the instance | continuation via storage |
| `DRAIN` | `Suspendable`: fence admission, settle accepted work | first step of every continuation |
| `CAPTURE(purpose)`, `PREPARE`/`COMMIT`/`ABORT(tx)` | every layer | continuation (migrate, T0/T1, repartition) |
| `RESTART` | `Durable` if present and M11 allows, else `Supervised` | see below |
| `PROMOTE(candidate, tier)` | `Supervised`, which installs `Swap` | §3.4 |
| `REPARTITION(interests)` | the `PartitionSet` router | §3.2 |
| `DESIGNATE(leader, epoch)` | each `ReplicaSet` member's instance part | §3.1 |

**Routing (decided).** A signal enters at the outermost layer; each layer handles it (possibly
forwarding a derived signal inward) or forwards it unchanged; a signal unhandled at the leaf is
refused with `UnhandledControl(signal, termStack)`. Signals are management-band frames, as
`SuspensionProtocol` (`control/Suspension.kt`) already is: "host control is just ports" (31:443-445).

**Faults travel outward (decided; `S` as a layer that travels with the term is conditional on M5).** `Supervised` catches a leaf fault and applies its policy. For
RESTART it sends `RESTART` to its own term's `control` inlet, so the outermost restorer handles it.

- **Without `Durable`, RESTART is a succession**, as MH:1997-2022 performs it: bump the generation,
  reset the leaf, mint fresh epochs, announce `ReBaseline(supersedes, supersede = true)`, emit
  `Stall(RESTARTING)` then `Resume`. The failing frame and its exclusive payload are dead-lettered
  and discharged, as today. Generation, epochs and dead epochs are `Supervised`'s own state, so the
  reset cannot roll them back (today the host map `generations`, MH:600, :2002-2004). `X`'s
  `disposed`/`acted` survive (it is outside `S`).
- **With `Durable`, RESTART is conditional on M11.** Spec 31 and 93 R9 require a durable RESTART to
  restore the latest checkpoint and replay the journal tail (31:127-130; 93:2867-2870); 23 and 93
  I-22 R6 require that RESTART "never re-drives the invocations that produced" state, so an `Owned`
  or `Leased` payload is never re-consumed (23:209-213; 93:8416; MH:1998-2000). Checkpoint-plus-tail
  replay re-drives exactly those invocations. The recommended rule (M11 (c)): a durable RESTART
  is a continuation **only for a term no inlet of which can accept an `Owned` or `Leased` payload
  and which declares no `LeaseHolding` (F10)**, read from the manifest. Its tail holds no exclusive
  to re-consume, so R6's purpose holds while R9 replays. The tail is replayed with its original
  positions (downstream dedup by position absorbs re-emissions; F9 keeps them identical) and the
  failing frame is skipped by a logged `Skipped(position)`. A second fault within the policy
  window escalates to succession. A term that can consume an exclusive keeps durable RESTART as a
  succession: a checkpoint taken after the consuming delivery leaves a window in which the tail
  still holds it, and nothing makes consumption and its durable disposition one recoverable step
  (**limitation**, §6). Until M11 is decided, every durable RESTART is a succession like the
  non-durable one, which keeps R6 and loses the tail's state (§6).

**Who sends signals (decided).** The management system sends `SUSPEND`, `DRAIN`, `CAPTURE`,
`PROMOTE`, `REPARTITION` and `DESIGNATE` (the spec names "the placement/economic layer (or an
operator)" as the designator, 93:9748-9751). The host instantiates, destroys, re-creates and
relocates capsule bytes and delivers frames. Attention parking stays a host *scheduling* decision:
the host may decline to run a term, but the inbox is the term's custody.

### 2.5 Layer properties

| Layer | Holds custody | Monotone state (restart must not reset) | Acts at delivery | Acts at emission |
|---|---|---|---|---|
| `Durable` (D) | log, checkpoint | yes | no | no |
| `Outbox` (O) | retained outbound frames | yes | no | yes |
| `Suspendable` (P) | park queue, inbox (incl. coalescing) | yes | no | no |
| `Fence` (F) | – | epoch fence | yes | no |
| `Align` (A) | partial waves | yes | yes | no |
| `EffectDedup` (X) | – | `disposed`, `acted`, `pullDischarged`, `scoped` (PLP §5.1) | yes; advances after the handler | no |
| `Supervised` (S) | – | identity tier, generation, dead epochs, policy | wraps the handler | stamps the epoch |
| `Coupling` (C) | partial coupled units | yes | yes | no |

`P < A` mirrors the `ADMIT < ALIGN` tier order (`port/InletPolicy.kt:34`): the canonical stack is the
per-port chain lifted to the term.

### 2.6 Formation rules (decided unless a rule says otherwise; where a rule places `D` per term, conditional on M3/M4 — under their fallback `D` adapts `journalFor(cellRef)` and the rules still apply)

A formation check runs over `TermManifest` at instantiate, at link time and at every stack edit. It
refuses with `RefusedComposition(rule, stack)`, records which layer holds each obligation, and
rejects missing and duplicate holders.

- **F1. Persistence outermost.** If `D` is present, no layer outside it holds custody or monotone
  state. Anything accepted outside `D` is lost by a crash while the log claims durability (the
  `TrafficLightCell` red buffer today).
- **F2. Restart innermost.** (`S` travelling with the term on relocation is conditional on M5.) `S` directly wraps the leaf (or the `Swap` during a promotion); no layer
  with custody or monotone state sits inside `S`. RESTART resets what is inside `S`. Hence
  `Durable(Supervised(Suspendable(cell)))` is refused as written; its valid form is
  `Durable(Suspendable(Supervised(cell)))`.
- **F3. Dedup adjacent to delivery.** (Checking delivery positions rather than wave positions at `Effectful` inlets is conditional on M16; until adopted, `X` keeps today's wave-keyed check with the computenet-wlwjw limitation.) `X` is immediately outside `S`. It checks the position at
  delivery time (MH:1889 runs at delivery today) and advances `disposed` after the handler returns,
  in the same `D` record as the delivery outcome: the at-least-once window of 24:1488-1522. A
  holding layer between `X` and the leaf would let a frame pass the check and wait while its
  duplicate also passes. `X` outside `D` is refused (F1).
- **F4. Admission before alignment.** `P` is outside `A`, so a suspended term parks whole frames
  before they join a partial wave.
- **F5. Fence before alignment and dedup.** `F` is outside `A` and `X`: a stale-epoch unit is inert
  before it contributes to a wave or advances a frontier ("the fence stays in the CELL",
  `replication/SingleWriterReplication.kt:138-145`).
- **F6. One holder per acceptance.** A term inside a `D` scope carries no `D` of its own; each layer
  appears at most once on any root-to-leaf path. Two durable custodians of one frame double-append
  (computenet-4fpyy). A composite whose children carry `D` persists its ledger with a `D` around its
  coordinator part only (§3.0).
- **F7. No bypass of the acceptance point.** (Mandatory mediation through the membrane is conditional on M10.) A term whose stack contains `D`, `X`, `C` or a flow-time
  boundary policy exposes the affected inlets as Mediate at that layer. Inside a `D` scope, inner
  layers may flatten only where the holder has accepted the input and can deterministically
  re-derive the interior transition. A raw link targeting a port *inside* a term is refused. This is
  the "no bypass" condition; the silent no-journal path through a raw `linkTo` was found only by a
  zero-length WAL (DIS:1387-1391).
- **F8. Effects must cross a port.** A leaf that is both `Effectful` and `Stateful` is refused under
  `D`, pending M1. The admitted form reifies the effect as an outlet to a separate `Effectful` leaf.
  `X` can suppress only what crosses a port (DIS:1232-1268).
- **F9. Non-deterministic durable leaves log their outputs.** A leaf under `D` that does not declare
  replay-determinism has an `Outbox` that logs its emissions; replay that re-issues a position for
  different content corrupts every downstream dedup (I-7 R8, 93:2855-2866). The declaration exempts
  an output only if replaying its determining input re-derives it **through the whole composed
  path** (PLP §5.8). Where a layer suppresses that replay — `X` at an `Effectful` inlet drops an
  already-disposed input (PLP §5.3) — the outputs caused through that inlet are logged by `O`, or
  the term is refused (model finding F9-X).
- **F10. Exclusive payloads need a holder on every path.** A leaf that can hold a `Leased` value
  acquired outside its ports declares `LeaseHolding`; a path on which an `Owned`/`Leased` payload
  could be accepted with no layer able to capture or discharge it is refused. Dynamically,
  `CAPTURE(relocate)` vetoes with `HELD_LEASE` while `P` counts an unreleased delivered lease. The
  vocabulary 93:3639 records as landed does not exist in `kernel/src/main` (computenet-wkopk).
- **F11. Effect identity is declared.** An `Effectful` leaf declares an *effect identity* in its
  manifest: the external destination and the act a position stands for. It is what promotion (§3.4),
  a witness (§3.1) and frontier transfer compare. An `Effectful` leaf without one is admitted but its
  `acted` history is non-transferable.
- **F12. Enforcing inlets carry `X`.** An inlet that enforces dedup (PLP §5.3: every `Effectful`
  inlet, and every inlet of a durable term not declared idempotent) keeps its cursor as `disposed`
  in `X`, outside `S`. `applied` sits inside `S` and a succession RESTART resets it (PLP §7), so a
  term deduplicating on `applied` alone takes a retained duplicate again after one (model finding
  CELL-1). A durable non-idempotent term without `X` is refused unless every RESTART it can take is
  a continuation (M11 (c)); until M11 is decided, that is every such term.

**Canonical stack**: `D ∘ O ∘ P ∘ F ∘ A ∘ X ∘ S ∘ leaf`. Every layer except `S` is optional; `S` is
implicit, since every hosted cell has a supervision policy (PROPAGATE by default, 31:197-201). `O` is
fixed directly inside `D` so the manifest is deterministic. A *designated* reset target instead of
F2's positional rule was considered; it is equal in power but makes the reset domain a declaration
the check must trust.

**Refusals carried over unchanged:** `Effectful` with overlapping replica interest and no declared
authority (`replication/SingleWriterReplication.kt:1188-1203`; 31:412-419); T2 for a
`NonIdempotentCatchUp` candidate (`evolve/Evolution.kt:207-215`; 53:201); T2 for a replicated cell
(`evolve/Evolution.kt:302-346`).

### 2.7 Markers: how each concern finds its holder

Today the host branches on concrete types, marker interfaces and port types about 40 times (e.g.
MH:926, 1030, 1868, 1889, 1945, 2011, 2018, 2021, 2207-2212, 2239, 2249, 2293, 2424; HD:716, 867,
883; `host/TopologyWalks.kt:63-75`). **Decided:** three channels replace them.

| Channel | Carries | Read by |
|---|---|---|
| **Descriptor manifest** (static `TermManifest`) | port names and contracts; `@Contract(effect = true)` and effect identity; replay-determinism; color; layer stack; children; obligation holders | the formation check. The host reads only port names, color, protocol band and direction, resource needs. |
| **Exported capabilities** (runtime, on `control`) | `canSuspend()` with a veto reason (`HELD_LEASE`, `MGMT_ACTIVITY`, attended, non-suspendable); `isLeader`; `heldCount`; `observe` | the enclosing composite and the management system |
| **Nothing** | snapshotting, frontier checks, epoch minting, park queues, coalescing | the layer that owns the concern |

The per-responsibility destinations are in §4.

---

## 3. Composites as obligation holders

### 3.0 Common shape

Each composite is specified by its obligations, acceptance point, persistence, what children expose,
and what it does at its own and its children's mode changes.

**Two persistence forms (decided).**
- **Ledger form.** A `D` around the composite's *coordinator part* (e.g. the partition router)
  persists the coordination record; children keep their own stacks and `D`. On one host the streams
  share the physical log; across hosts they exchange positions and acknowledgements. No global
  checkpoint.
- **Scoped form.** `D(Composite)`: one `D` and one stream around a whole data composite whose
  children carry no `D` (F6). Recovery is exact inside the scope: the "opt-in coordinated checkpoint
  for tightly-coupled subgraphs (never global)" of 93:3021-3023.

**Every cross-term transfer is a durable transaction (decided).** A coordinator that moves an
obligation between holders mints a `TransferId`, logs it before the first side effect, and drives
idempotent `PREPARE(tx)` / `COMMIT(tx)` / `ABORT(tx)` on each side, logging its decision before
sending `COMMIT`. Participants log `Prepared(tx)` and `Committed(tx)` and answer repeats from the
record. A participant that has prepared does not act on the staged obligation until it sees the
decision (it is *in doubt*, and blocks only that obligation). Recovery of the coordinator resumes
from its last record: before the decision it may abort; after it, it only rolls forward. §3.2 and H4
are the two instances.

### 3.1 Replica set (scope composite, spans hosts)

**Realisation (decided).** No coordinator cell. Each instance carries an *instance part*: its `F`,
plus set state inside its `D`. Coordination state is a fold every member holds: `LeaderMark` epochs
(93:9636-9642) and watermark rows (42:416-458). Leadership is folded from announcements, "not a
vote, quorum, or barrier" (93:9755-9756).

**Precondition on positions (decided in PLP §3.2, "Replica-set lanes").** A replica set's inbound
lanes are keyed by the set's logical address, and the delivery to each covering instance is a
*forwarding* fan-out of one position, so every instance names the same logical delivery with the
same position. Without this, instances' lanes differ and no frontier can be compared across them.

| Obligation | Holder | Mechanism |
|---|---|---|
| Single-writer authority | the instance whose `F` holds the maximal `(epoch, writer)` | `LeaderMark` fold; every recipient fences stale units in its `F` (`Stamped.applyTo`, `SingleWriterReplication.kt:123-145`; 93:9758-9764) |
| Effect authority (PN-17; 31:398-410) | the leader | below |
| Input retention for takeover | every follower | below |
| Departed-member settlement | survivors' watermark fold | `[42-WM-08]` (42:418-424): clean leave *closes* the row; unreachable *suspends* it; unclean departure *freezes* it and the freeze is reported; never released by timeout |
| Completeness across replicas | – | mergeable watermark; a frozen row blocks completeness and is reported |

**Effect authority: only proven dispositions transfer (decided).**
- Followers deliver the effect inlet suppressed (31:401-406), which keeps them warm. A suppressed
  delivery advances the follower's **received** cursor only. It never advances `disposed`, because
  nothing was done to the world.
- The leader's `X` publishes its **disposed** frontier (with `acted` where an act occurred) on the
  set's watermark channel after the disposition is `STABLE` in its `D`. Followers fold it (pointwise
  max per logical lane).
- Each follower **retains** every suppressed frame above the folded disposed frontier: its `D` does
  not truncate them, and its capsule carries them. Retention is released as the leader's published
  frontier passes them.
- **Takeover.** A member may be designated, or have its claim admitted, only if it covers the
  effect inlet's whole interest (Total), so every set lane is dense at it (PLP §3.2), and its
  retention is gapless from the folded disposed frontier upward on every logical lane (PLP §5.3);
  otherwise it first fetches the missing frames from a peer's retention, or the set reports and
  waits. **Under partial overlap takeover is refused**: a follower that saw seq 5 and 8 cannot
  tell whether 6-7 lay outside its interest or were lost, and no coverage certificate exists
  (**open**). The new leader acts, in lane order, on every retained frame above the folded frontier.

**Conditional on M9.** For a Total-coverage successor this yields no omission: a position the old
leader did not provably dispose of is acted by the successor. It does not yield exactly-once: a position the old leader acted on but had not yet
published is acted again. **Limitation:** at most one duplicate per unpublished position per
failover, the same direction 24:1503-1510 decides (a duplicate is loud and bounded; a suppression is
silent). PN-17's "exactly once per logical delta across a handoff" (31:408-410) is therefore not met
by the epoch fence alone **[inference]**; M9.

**Retention bound (limitation).** Retention grows while the leader is not publishing (partitioned or
slow). It is bounded by the follower's storage; on exhaustion the follower reports and stalls the
set's intake rather than dropping. Never silent.

**Exclusive authority against a live, partitioned peer.** Storage and re-creation cannot stop two
live instances each believing it leads. *State* needs nothing more: recipients fence the lower
`(epoch, writer)`. *External effects* do: a deposed leader that has not learned of its deposition can
still act, and no in-system fence reaches the world (the divergent-write buffer is documented as
over- and under-inclusive, `SingleWriterReplication.kt:368-382`).

**Posture (decided)**, building on the spec default "no automatic claim" (93:9748-9751):
1. **Default: no automatic failover.** Designation is a management act; a designator that declares
   the old leader dead takes on that layer-4 assumption explicitly.
2. **Opt-in automatic claim** (`EpochClaim`, 93:9751-9757) on a set with an `Effectful` member is
   admitted only if every effect destination is one of:
   - **(a) an exact witness**: the destination receives `(effect identity, logical position, epoch,
     writer)` with each act and, atomically, rejects a stale `(epoch, writer)` **and** rejects a
     logical position already applied under *any* epoch, answering `AlreadyApplied` (which the new
     leader records as a proven act). Then each logical position is applied at most once at that
     destination, and with retention above, at least once: exactly-once at that destination.
   - **(b) a fence-only witness** (rejects stale tokens, no position dedup): it stops a partitioned
     stale leader from acting after the higher mark reaches the destination, but the duplicate
     ceiling above remains.
   - **(c) a declared ceiling**: stale-leader acts are possible until the deposed leader sees the
     higher mark, bounded only by failure detection and synchrony (layer 4; G-44, 42:910-914;
     DIS:3615-3618).

   A set with none of these is refused. Mutual-exclusion leases were not adopted (clock assumptions).

**Acceptance points.** Writes: the leader's `D` intake. Peer deltas: each member's `D` intake (`F`
before `A`/`X`). Authority: a `DESIGNATE` or folded claim is accepted (logged) by each member's `D`,
so a re-created member cannot regress its fence.

| Mode change | Disposition |
|---|---|
| Member SUSPEND, migrate, durable RESTART (M11) | continuation: `instanceId`, leadership, fence, frontiers and retention travel in the capsule (93:9744-9745); the old execution is fenced before the new one is released (H4) |
| Member RESTART without `D` | succession of data, rebuilt by catch-up from the most advanced reachable peer (42:873-880); a leader's unshipped writes are lost (42:878-880). Its disposed frontier is re-learned from the fold; its retention is gone, so it is not eligible for takeover until re-filled. |
| Member dies | survivors report: the row freezes, a recoverable `Stall` is raised at link ends (§3.8), the set waits for `DESIGNATE` or an admitted claim |
| Member joins | adopts the fold, starts retaining from its first received position, announces a fresh lane; eligible for takeover once it covers the whole interest and its retention is gapless above the folded frontier |
| Set-wide promotion | rolling, one instance at a time, each with its own `Swap` (§3.4); T2 refused (§2.6) |

**Routing to the leader.** The fold publishes a versioned route entry (logical id → instance ref, at
an epoch); the host's route primitive keeps the highest version (H6) without interpreting it. The
`LeaderMark` fold in `host/LocationRegistry.kt` (`:223` onward) and `host/InstanceIndex.kt` moves into
the set.

### 3.2 Partition set (data composite, ledger form) — conditional on M6

**Realisation.** The router (`PartitionedShardSet` or `PartitionedCell`) is the coordinator part and
carries its own `D`. Each shard is a child term with its own stack; a replicated shard is a
`ReplicaSet`, giving `PartitionSet(ReplicaSet(…)…)`, and the router routes to the shard set's
versioned leader entry.

**Obligations.**

| Obligation | How |
|---|---|
| Routing epoch, never reused | the router's `D` checkpoint (today in memory, PSS:106-108) |
| The flip transaction (computenet-d2lue) | the records below; today `flipping`, `flipBuffer`, `movingInterest` are volatile (PSS:110-118) |
| Parked moving-range frames | the router's park, inside its `D`, each stored **with its context and position** (today bare `SetDelta`s, PSS:117, 159) |
| Assignment delivery | always a journaled, ref-addressed emission into `assignInlet` (PN-6, 42:1006-1012); the direct path (`journaledAssign = false`, the default, PSS:53-64) is refused under F7 |
| The moved range's dedup state (computenet-8g7kg) | `p_begin` and range-scoped frontiers, below |

**Delivery identity during an open flip (owned by PLP §5.6).** A boundary frame whose keys span a
stable and a moving range is split: its stable part is routed now and its moving part is parked, and
both may later reach the *same* shard inlet with the same position. While a flip is open, each
affected shard therefore keeps its frontier per `(lane, scope)` with scopes `stable` and `R`: at the
gainer B the `R` scope starts at `p_begin` at `COMMIT`; at the loser A it is frozen at `p_begin` at
the fence and is used again only if the flip aborts. Each scope is FIFO per lane (the router parks
and releases in order), so a high-water per scope is exact. The records and steps below are the
transaction that carries those cursors; PLP §5.6 gives the exactness argument.

**Records** (all in the router's `D` unless noted; `tx` is the flip's `TransferId`):

| # | Record | Written when |
|---|---|---|
| R1 | `FlipBegin(tx, routingEpoch, old, new, R, {lane → p_begin})` | in the router turn that opens the flip, before the first moving-range frame is parked. `p_begin` is the last boundary `seq` routed on each lane. |
| R2 | `Parked(tx, frame)` | each parked moving-range part, as part of the router's acceptance of that frame |
| R3 | `Settled(tx)` | when the losing shard A has answered `FlipSettled(tx)` (A logs `SettledFor(tx)` in its own `D` first) |
| R4 | `Prepared(tx)` (in B's `D`) | the gaining shard B has staged the range-handoff record |
| R5 | `FlipDecision(tx, COMMIT \| ABORT)` | the commit point; persisted before any `COMMIT` or `SHED` is sent |
| R6 | `Released(tx, n)` | the release cursor: advanced to `n` only when the receiving shard has acknowledged every released frame through `n` at `STABLE` for its `(lane, shard, R)` key (PLP §5.8), not when a frame is sent |
| R7 | `FlipEnd(tx)` | when both shards have acknowledged `FlipDrained(tx)` (each logged `DrainedFor(tx)`); only then are the R2 records truncatable |

**The flip, step by step.**
1. **PRECHECK.** Veto if A or B is suspended or mid-transfer, if A is unreachable, or (for an
   `Effectful` range) if only an aggregate state-as-delta is available (`partition/PartitionedCell.kt:143`).
2. **Begin.** Log R1; widen B's guard; park moving-range parts (R2) while non-moving ranges flow.
3. **Settle.** Send `SETTLE(tx, R, {lane → p_begin})` to A **in band**, as the `FlipFence(tx)`
   marker on each router-to-A path: FIFO places it behind every R-delivery A will ever receive.
   (It cannot travel on the management band, which preempts data, and A cannot settle from
   `p_begin` alone, because its boundary lanes are filtered and non-dense; PLP §5.6 step 2.) The
   fence is a barrier over dispositions, scheduled as PLP §5.8 "In-band markers" defines: A
   processes it only once everything accepted ahead of it on the lane has a `STABLE` terminal
   disposition in A's `D` (acted, absorbed, refused or dead-lettered); then it freezes its `R`
   scope at `p_begin`, logs `SettledFor(tx)` and answers `FlipSettled(tx)`. Idempotent: a repeat
   is answered from `SettledFor(tx)`. Log R3.
4. **Prepare.** Send `PREPARE(tx, handoff)` to B, with `handoff = (R, state slice, {lane →
   p_begin}, routingEpoch)`, on B's control plane (`assignInlet`), never as a catch-up into a data
   inlet. B logs `Prepared(tx)` and stages; it does not act. For a replicated B, PREPARE needs the
   epoch-confirmed leader.
5. **Decide.** Log R5. Before R5, the router may abort; after it, it only rolls forward.
6. **Commit.** Send `COMMIT(tx)` to B: B logs `Committed(tx)`, installs its `R` scope at `p_begin` per
   lane (its existing cursor becomes the `stable` scope), and merges the state slice **without acting on it** (it continues A's acts; it
   is not new input). Send `SHED(tx)` to A: A logs it and narrows its interest. Both idempotent.
7. **Flip and release.** Once B has acknowledged `Committed(tx)` — so its `R` scope exists before any
   R-slice reaches it — flip the table (derived from R1+R5), then release parked frames in order to
   B through the router's `O`, before new traffic on each lane; B checks each against its `R`
   scope; R6 advances on B's `STABLE` acknowledgement. Then send `FlipDrained(tx)` on each
   router-to-shard path. It is a barrier like the fence: each shard processes it only after every
   frame accepted ahead of it is stably disposed, folds its scopes into the per-lane frontier as
   `max` (exact by PLP §5.6 step 6), logs `DrainedFor(tx)` and acknowledges. Log R7 when both have.
8. **Abort** (decision ABORT, only before R5): `ABORT(tx)` to B (no-op if unknown), `UNSETTLE(tx)` to
   A. Release to A waits for R3, as step 7 waits for `Committed`: if A has not yet settled, the
   router (re-)sends `FlipFence(tx)` in band and waits for `FlipSettled(tx)`, so A's `R` scope
   exists before any R-slice reaches it (model finding FLIP-1: released against A's single cursor,
   which stable slices have already moved past `p_begin`, the R-slice is dropped). Then release
   parked frames in order to A under the old table, A checking them against its `R` scope (frozen
   at `p_begin`), with R6 and `FlipDrained(tx)` acknowledged as in step 7, then R7. If A cannot
   settle, step 9 applies.
9. **Failure while undecided.** If A cannot settle or B cannot prepare, the range stays parked and a
   stall is reported for that range; unrelated ranges flow. Management chooses abort or wait.

**Recovery at every step** (router `D` replays to its last record; shards replay their own streams):

| Crash after | Router resumes by | Shard side |
|---|---|---|
| nothing (before R1) | no flip exists | – |
| R1/R2 | re-sending the in-band `SETTLE` as `FlipFence(tx)` (idempotent); parked frames recovered from R2 | A answers from `SettledFor(tx)` if present |
| R3 | re-sending `PREPARE` | B answers from `Prepared(tx)` if present |
| R4, before R5 | deciding afresh (commit or abort), then R5 | B is in doubt: holds the staged handoff, acts on nothing in R |
| R5 | re-sending `COMMIT`/`SHED` (or `ABORT`/`UNSETTLE`, and `FlipFence(tx)` if R3 is absent), then, once `Committed` (on abort: `FlipSettled`) is acknowledged, releasing from the R6 cursor | each answers from its record; a re-released frame is a duplicate in its scope and is dropped where enforced, re-absorbed where idempotent |
| R6(n) | releasing from n+1 (frames sent but not yet stably acknowledged are re-sent) | as above; a frame B lost in its own unsynced tail is received again |
| release complete, before R7 | re-sending `FlipDrained(tx)` | a shard that logged `DrainedFor(tx)` answers from it; R2 is still held |
| R7 | nothing | – |

**Mode changes.** Composite migrate: one capture (router capsule with its ledger, plus children's
capsules). Shard RESTART, suspend or migrate: the child's own business; frames for a suspended shard
park in its `P` (but PRECHECK vetoes a flip touching it). Shard dies: the router holds custody only
of undelivered frames; delivered frames are in the shard's `D`, or lost with a volatile shard
(limitation); survivors report (§3.8). `rebuildFrom` becomes a consistency *check* against the
router's log.

For non-`Effectful`, idempotent shards, the handoff state is merged as ordinary state; range-scoped
dedup still applies but cannot suppress anything those shards need.

**Proposed requirements.**
- *When a repartition begins, the partition set shall durably record the flip and, per boundary lane,
  the last routed position, before it parks the first moving-range frame.*
- *The partition set shall not decide to commit a range transfer until the losing shard has reported
  a stable disposition of every delivery in the range at or below the recorded position.*
- *When a shard gains a key range, it shall not act on the transferred state, and shall dedup that
  range's deliveries against a frontier starting at the recorded position.*
- *If a range transfer is undecided, the partition set shall keep that range parked and report it,
  and shall continue routing every other range.*

### 3.3 Glitch-free region (scope composite)

**Obligations.** (1) Partial-wave custody: per-cell, so it stays in the join's `A` and travels in the
join's capsule (the fix for computenet-5jhg3). (2) Region-atomic suspension (34:163-169). (3) The
contagious veto (34:169-171). The region holds (2), (3) and its membership.

**Realisation (conditional on M7).** The graph applier declares the region at formation: the join plus its
transitive upstream contributors, bounded by further glitch-free joins (34:164-166). The coordinator
runs on the join's host; a topology edit that changes the cone re-forms the region in the same
management turn. This replaces `suspensionRegionOf` and its `hasFrontierPolicy`/`is NonSuspendable`
checks (`host/TopologyWalks.kt:28-75`); M7.

**SUSPEND as one management act.** Query `canSuspend()` on every member; any `false` vetoes the whole
region (attended, non-suspendable, `HELD_LEASE`). Otherwise deliver `SUSPEND` to every local
member's `P` within one management-band turn; the band preempts data, so local members see no
interleaved delivery (the same argument as the swap's atomicity, 93:4584-4590). Resume stays emergent
(34:172-174).

**Limitations.** Members on other hosts cannot join the atomic act; their arms use WAIT (default,
unbounded latency) or an explicitly chosen DEGRADE (a reduced frontier, not a complete wave)
(34:174-185). DEGRADE/WAIT stays a parameter of `A` (`consistency/GlitchFree.kt:69-78`). With
per-member `D`, replayed frames are baselines and bypass wave reconstruction (DIS:1288-1312), so the
join converges but does not replay waves exactly; only the scoped form `D(Region)` on one host is
wave-exact.

**Child mode changes.** Member migrate is a continuation: partial waves and parked frames travel
intact before any member resumes, then the region re-forms. Member RESTART without `D` is a
succession: its `S` emits `Stall(RESTARTING)` then `Resume` on its own outlets (the host does at
MH:2000, 2022 today); a partial wave depending on its invalidated output is reported by that stall
and re-established by frontier discovery and catch-up.

### 3.4 Promotion swap (`Swap`, a transient two-leaf composite)

**Realisation (decided; logging the swap window is conditional on M8). Promotion replaces only the leaf.** `PROMOTE(candidate, tier)` is handled by
`S`, which inserts `Swap(incumbent, candidate)` between itself and the leaf. `D`'s stream continues,
`F` keeps its epoch, `P` provides the gate (PRECHECK vetoes a term without `P`), and the term's
ports do not change, so there is no relink
(today's `rebind` loop, `evolve/Evolution.kt:252-255`, disappears). For a `PartitionedCell`, each
organelle gets its own `Swap` (`[53-STATE-04]`, 53:230-232). This replaces `Promotion.promote`'s
external orchestration and its `TrafficLightApi` gate.

**Effect history is transferred only between equal effect identities (conditional on M8).** `X`'s state has
two parts with different fates:
- **`applied`** (what the leaf has absorbed, PLP §5.1) belongs to the leaf: under T0/T1 it
  continues with the captured state; under T2 the candidate's state is rebuilt, so it starts
  fresh.
- **The acted history** (`disposed` and `acted` in `X`) is a fact about the world. It applies to the
  candidate only if the candidate's declared effect identity (F11) equals the incumbent's **and**
  the candidate's version carries an *effect-compatibility assertion* naming the incumbent's
  descriptor version. Equal identity strings alone do not show that revised code performs the
  same act; the assertion makes the author state it, per version.

PRECHECK compares effect identities:

| Incumbent | Candidate | Disposition |
|---|---|---|
| same effect identity, with the compatibility assertion | same | `acted` and `disposed` stay in `X`; nothing is re-acted, nothing omitted. Without the assertion: as "different identity" |
| `Effectful` | different identity | **veto**, unless the request declares `effectFrom = COMMIT`: the new effect applies only to deliveries after the commit, and `X` is re-initialised to the term's current `disposed` frontier, labelled with the new identity, so no historical input is re-acted |
| not `Effectful` | `Effectful` (adds `X`) | the incumbent has no `X`, so no `disposed` exists. PRECHECK requires an explicit starting frontier: the incumbent's `applied` cursor (PLP §5.1; every positioned inlet tracks it) for every incident lane, captured after the drain of PREPARE and logged in the COMMIT record; the new `X` starts there, with `effectFrom = COMMIT` implied. **Veto** if any incident lane has no captured entry — an empty `X` would re-act replayed history, a guessed one would suppress unproved work |
| `Effectful` | not `Effectful` (removes `X`) | allowed only if the candidate declares no effect; `X`'s record is retired with a logged `Discharged` |
| either side undeclared | – | **veto** for an `Effectful` side |

**Phases** (`[53-SWAP-01..04]`, 53:115-134), logged by `D` when present:
1. **PRECHECK**, side-effect free: port-surface equality, the candidate's layers pass formation, the
   effect-identity table, a disposition for every held obligation, and the existing refusals
   (`Evolution.kt:207-215`).
2. **PREPARE.** `P` parks inbound frames (custody inside `D`, outside `X`) and the incumbent
   finishes the frame in hand. `Swap` holds no frame: a buffer below `X` would let a frame pass `X`
   and wait while its retransmitted duplicate also passes (F3; model finding SWAP-1).
3. **COMMIT**, logged before green; it does not veto.
   - **T0/T1 (continuation).** The incumbent's capture is prepared and committed into the candidate.
     `S` owns the identity tier, so the lane continues; no `adoptWaveState`, no `ReBaseline`.
   - **T2 (succession of data only).** `S` mints a fresh epoch and, before releasing the buffer,
     announces `ReBaseline(supersedes = {the term's current lanes}, supersede = true)`: the
     *incumbent's* lanes, which are `S`'s own. This fixes computenet-lzfr0 by construction (today
     `to.mintFreshEpoch()` runs on the candidate's outlet, `Evolution.kt:246-248`). Obligations do
     not succeed: fence, park and (per the table) effect history stay in their layers.
4. **Green and RETIRE.** `P` resumes and releases its park through `X` to the candidate, `S` removes
   `Swap`, the incumbent is destroyed; its export is retained.

**Rollback.** Before COMMIT: drop the candidate, remove `Swap`, `P` resumes toward the incumbent.
From COMMIT on, rollback is a new swap with the incumbent re-instantiated from the retained export
(`[53-ROLLBACK-02]`, 53:227-230), subject to the same effect-identity table. COMMIT is the
transaction's decision, after which it only rolls forward (§3.0), and a T2 COMMIT has already
announced the incumbent's lanes superseded, which every receiver fences for good; resuming the
incumbent on them loses its output (model finding SWAP-2). The new swap mints and announces its
own epoch, so no separate rule is needed.

**Crash inside the window.** `D` replays: without a `COMMIT` record it rolls back; with one it rolls
forward. Today the window is unlogged ("needs no journal", `Evolution.kt:268-269`), which holds only
without crashes. Removing any other obligation-holding layer at COMMIT is refused unless its
obligations are discharged first.

### 3.5 Multi-port transactions (membrane couplings, G-53)

**Realisation (decided).** A `Coupling` layer sits directly inside a `Membrane` composite's `D` and
outside the organelles. Each part of a coupled unit is accepted individually at `D` with its position
and any exclusive payload. `Coupling` holds partial units (Symport groups, Antiport credit) in the
capsule. Completion is logged as `CouplingRelease(unit)` before the parts are released to the
organelles in one turn, as one group; a downstream `Swap` or `P` parks the group as one entry. On
replay a released group is re-applied idempotently by position.

**What this settles from G-53** (91:39): a half-completed coupled transaction cannot be caught in a
swap window, because partial units never enter a `Swap`, and a capture carries them whole across
suspend, migrate and crash.

**Decided at formation.** A coupling declares a disposition for a unit that never completes: a
bounded abort rule or an operator-visible WAIT. An abort is a **report**: each part is dead-lettered
and any `Owned` it carries discharged. **Open** (95 §R3): which abort rules compose with no-loss and
drain. **Refused:** couplings whose ports live on different hosts (cross-peer atomic commit was
rejected, 93:4469-4472).

### 3.6 Sender custody before acceptance: `Outbox` and client stubs

**Analysis.** A frame parked because its target is not located, refused by a closed or saturated
intake, or in flight on an asynchronous link has not been accepted to the level its link requires.
Its owner of record is the sender's link (93:4911), and retry is "link/proxy internals — never cell
logic" (33:42). So its holder is the **sender**: not the host, not the future target.

**Decided: for a term, the `Outbox` layer (O); for an external caller, its client stub.**
- O keeps a FIFO per outbound lane and full target ref; each entry keeps its context, position,
  exclusive custody and required acknowledgement level. It re-resolves the same full ref and
  replays retained frames before later sends.
- An external `PORT_API` caller's stub (today the `InvocationSink` behind its proxy) holds the same
  FIFO, volatile. The host's route park (`LocationRegistry.parked`, `host/LocationRegistry.kt:71`,
  used at `:463-480`) becomes non-custodial: lookup plus a `Located(ref)` notice; it may hold nothing,
  or a cache it may drop.

**What O does with an entry is PLP §5.8's, not restated here:** the acknowledgement levels
`HELD < APPENDED < STABLE < DISPOSED` and the per-link required level (a durable sender requires
`STABLE` by default; a volatile receiver is admitted only with a declared per-link ceiling, the
`ALL` gap of §3.8); synchronous acknowledgement on in-process links; **stable before transmit**
(the transport sees a frame only after its `Retained` record — or, for an F9 leaf, the input that
re-derives it — is `STABLE`; a `BATCHED` sender waits for its group sync); acknowledgement and
retention keyed by `(epoch, lane, recipient, scope)`, cumulative only on lanes dense for that
recipient; and
the **disposition table at unlink, peer death, sender relocation, retirement, `discard` and storage
exhaustion**. In short: closing a link or a peer-death report never releases a retained frame;
only an acknowledgement at the required level does, or a reported dead-letter (with discharge
through E1) when the target ref is retired or management unlinks with `discard`.

**Proposed requirement** (PLP §9 states the retention requirements). *While a frame awaits a target
that is not located, the sender's Outbox or client stub shall hold it, and the host's route shall
hold no frame.*

### 3.7 Budget charges

`BudgetLedger` is one `charge(claim)` call returning `Admitted(undo)` or `Refused` (`Budget.kt:85-130`);
its optional key dedups only "within a bounded recent-key window per bucket" (`Budget.kt:69-75`), and
`undo` is a closure that cannot be persisted. A charge made in flow time is an obligation outside the
holder's merge domain, so it needs a holder and a crash protocol.

**Decided.**
- **Holder.** The term layer that charges (a boundary policy at its acceptance seam, as
  `CompositeCell` charges today, `CompositeCell.kt:97`). The host hands it a ledger handle at
  instantiate (H2) and nothing else.
- **Charge id.** `ChargeId = (term ref, delivery position, claim site)`, where the claim site is a
  static id the charging layer declares in its manifest and charges at most once per delivery. The
  layer charges at acceptance, before the leaf runs, so no replay-dependent branch can change which
  sites charge; an ordinal "index within the handling" was rejected for that reason. It is
  replay-stable because positions are (PLP P2), and it is the claim's `key`. A frame without a
  position (PLP §5.2 rule 8) has no stable id: it is charged unkeyed under the ceiling below.
- **Order** (conditional on the keyed ledger below). `D` appends the frame (`APPENDED`); the layer logs `ChargeIntent(id)`; calls `charge`;
  logs `ChargeOutcome(id, Admitted | Refused)` in the same record as the delivery's outcome.
- **Replay.** `ChargeOutcome` present: reuse it, never re-charge. `ChargeIntent` without outcome:
  re-charge with the same key. The ledger dedups if its window covers the term's replay horizon
  (the interval between checkpoints); otherwise the debit may happen twice.
- **Refused charge.** The frame is refused at acceptance with a boundary-denial report, and any
  exclusive payload discharged; the refusal is the frame's disposition.
- **Refund.** If an admitted charge's delivery is later refused or dead-lettered by the term, the
  layer logs `RefundOwed(id)` and calls a keyed `refund(id)`, then `Refunded(id)`; replay retries
  `RefundOwed` without `Refunded`. Both are idempotent by key.
- **Transfer.** Open charges (intent without outcome, refund owed) travel in the capsule.

**Limitation until the ledger offers keyed durable idempotency and `refund(id)`:** at most one
duplicate debit per crash per in-flight charge, and no automatic refund (the `undo` closure dies with
the process); each such case is reported, not silent. Adding `refund(id)` and a declared dedup
horizon to `BudgetLedger` is a kernel API change (ECO1) and is **open**.

### 3.8 Reporting a death

A cell cannot announce its own death; something that survives it reports.

| Event | Reporter |
|---|---|
| A leaf throws | `S`, inside the term: `Stall(DEAD_LETTERED)`, `Stall(RESTARTING)` or `Resume` on the term's outlets (the host emits these today, MH:1993-2022) |
| A term is unrunnable while its host lives | `S`, escalating to a terminal policy |
| A term is re-created | the host hands it `Recreated(gap)`; its outermost layer announces the outcome (below) |
| A host process dies | survivors at each link end: the transport delivers link-down, and the consumer raises a recoverable `Stall` in its own region (WAIT/DEGRADE). *Declaring* the party dead needs a failure detector (layer 4; G-44, 42:910-914); without one, survivors wait |

Today a surviving peer "has no notification path for another peer's crash at all" (DIS:2236-2237);
the link-end report closes that with transport events, not cell semantics.

**"Re-create and report the gap" (decided).** On restart the host reads its residency manifest,
re-instantiates each resident recipe (skipping entries fenced by an outgoing relocation, H4), and
hands back the stream with a `Gap`:
- `NONE`: intact through the last `STABLE` record, and no acknowledged append was lost;
- `TAIL(stableThrough)`: records acknowledged `APPENDED` after `stableThrough` may be lost
  (`BATCHED`);
- `UNKNOWN`: the host cannot bound what was lost;
- `ALL`: the term is volatile or its stream is gone.

`D` with `NONE` continues (replay, then `Resume`). Anything else is a succession: `S` mints a fresh
epoch and announces `ReBaseline(supersede = true)`, so no position from the lost tail is re-issued
under its old epoch. With `UNKNOWN` continuation is refused. After `ALL`, a volatile producer cannot
list its dead lanes, so downstream fencing degrades to convergence-only (93:11507-11511). The host
reports only "term *r* re-created with gap *g*" and link-down; it never translates a gap into a
frontier, an effect decision or a replica closure.

---

## 4. Where the host's cell-semantic duties move

| Responsibility today | Moves to |
|---|---|
| Journal tee at intake under `dataLock` (`journalTee`, MH:1238, 1279, 1304) | `D.accept`, on an H1 stream |
| Per-cell/per-port journal selector and its uniqueness (MH:2273 onward) | `D`, one stream per scope; the per-port split becomes F6 |
| Checkpoint carry across host-known holding places (HD:651-665) | `D.checkpoint` = `capture()` of its inner term |
| `recoverFrom` and its ordering | per-term `D` recovery from H3; upstream re-derivation is admitted only after the term's own tail (PLP I-P2) |
| Effect frontier, discharged baselines (HD:401, 418); `is Effectful` refuse/suppress/advance (MH:1868, 1889, 1945) | `X` |
| `is Stateful` snapshot/restore (MH:1030, 2018, 2293, 2424; HD:716, 867) | the capsule's `state` |
| `as? FanInlet` `checkpointParked` (MH:926); `drainParked` then dead-letter at despawn (MH:983-991) | the capsule's `held`, from `A` and `P` |
| Mergeable-payload coalescing in the host queue (`host/IntakeControl.kt:73-92`) | `P`'s inbox, under PLP §5.3's rules (per-lane ranges of merged positions, no lane reordering, never at an enforcing inlet); the host keeps it as transitional exception E3 until migration step 7 |
| Durable-input cursors (HD:421) | `D` |
| Supervision maps and RESTART (MH:467-476, 600, 962-992, 1985-2037); `Stall`/`Resume` on behalf of cells | `S`, and `D` for a durable restart (M11) |
| Ref-derived durable epochs (HD:630-633); outlet wave record (HD:883) | `S`'s identity tier, persisted in `D`'s stream |
| `suspendedCells` (MH:476); attention-park custody (`control/AttentionScheduler.kt:68`) | the `P` inbox; whether to run a term stays with H8 |
| Region walk and veto (`TopologyWalks.kt:28-75`) | the `Region` composite |
| Leader fold (`LocationRegistry`, `InstanceIndex`) | the `ReplicaSet` fold, plus a versioned route entry (H6) |
| Route park as custody (`LocationRegistry.kt:71, 463-480`) | the sender's `O` or client stub (§3.6) |
| Promotion orchestration (`evolve/Evolution.kt`) | `S` with `Swap` |
| Repartition flip state (PSS:106-118) | the `PartitionSet` router's `D` |
| Drain/migrate/despawn teardown (MH:962-992, 2414-2429); `snapshots` (MH:464) | `DRAIN`, `CAPTURE` and H4/H5 |
| Flow-time charges and boundary-denial accounting (`is BoundaryDenialAccounting` MH:2239, `is BudgetCharging` MH:2249) | the charging layer (§3.7), with handles from H2 |
| Color, G-17, quota and spawn budget (MH:2180-2213, 2260-2272); `is FeedbackPort` barrier (MH:2257) | **stay**: structural and resource admission |
| `DeadLetters` `is Owned`/`is Leased` (`host/DeadLetters.kt:302-316`) | **stays**: exception E1 |
| Retention and acknowledgement of async output | the sender's `O`, per PLP §5.8 |

---

## 5. The host contract

### 5.1 Primitives (decided; H1's per-scope streams conditional on M3/M4, H4's per-term relocation on M2)

| # | Primitive | Exact behaviour |
|---|---|---|
| H1 | **Storage** | `open(streamId, class)` returns a stream: `append(bytes) → Pos` acknowledges *ordered acceptance* (`APPENDED`), not stability; `sync()` and `stableThrough(): Pos` report stability; `checkpoint(bytes)` truncates the tail; `read()` returns checkpoint plus tail, a prefix of the appends. Class `SYNCHRONOUS`: `append` returns after sync, so `APPENDED = STABLE`. Class `BATCHED`: `append` returns before sync (`durability/BatchedFileJournal.kt:13-16`); under a process kill, at most `syncEvery − 1` records are unsynced, but **no physical loss bound is claimed**, since page-cache loss on power failure is outside the class's control (`BatchedFileJournal.kt:37-42, 69-73`, `[KBLK-26]`). One physical log per host, logical streams per `D` scope. A **residency manifest** maps ref → (recipe, streamId, fence); a synced residency record may carry opaque capsule bytes for a volatile term's relocation (H4). |
| H2 | **Instantiate** | `instantiate(recipe, stream?)`: structural admission only (G-17 names, color, quota, spawn budget); hands the term a ledger handle and report sink; activates it; publishes its location. |
| H3 | **Re-create** | on host restart, or for a term the host can no longer run: `recreate(ref)` from the manifest, handing back the stream and `Recreated(gap)`. A fenced entry is not re-created as a live term (H4). |
| H4 | **Relocate** | the transfer protocol below. |
| H5 | **Destroy** | `destroy(ref)`, only after the term has captured or refused. |
| H6 | **Route** | deliver a frame to (ref, port) by location, local or remote, FIFO per link, by calling `accept` synchronously and returning its `Ack`. Keep versioned route entries (highest wins; republishing a version is a no-op). Refuse retired refs (`LocationRegistry.kt:794-801`) and closed or saturated intakes, leaving custody with the sender. Run structural link admission (cycle-head barrier, `FeedbackPort`). Hold no frame. |
| H7 | **Deliver control** | deliver management-band frames to `control`, uninterpreted. |
| H8 | **Schedule** | run terms with a non-empty inbox, on bands (management > router > data, 34:66-68) and by color; attention may decline to run a term, whose inbox stays its own. In-band protocol markers (`FlipFence`, `FlipDrained`, `EdgeClose`, …) are not management band: they are scheduled in their lane's order with its data, and the flip markers are barriers over prior dispositions (PLP §5.8, "In-band markers"). |
| H9 | **Report** | `Recreated(gap)` to the term; link-down to link ends. |

**H4, relocation (conditional on M2; fallback: the same protocol moves a host's terms as one batch).** `[33-MOVE-01]` requires no loss, no duplication and per-link FIFO
(33:85-88). Two live holders must be impossible across a crash of either host at any point. The
source host coordinates; `tx` is a `TransferId` and `v` the term's next location version.

1. Management has sent `DRAIN` and `CAPTURE(relocate, tx)`; the term's admission is fenced and the
   capsule (with `v`) is `STABLE` in its stream. Senders' frames stay in their `O`/stubs.
2. **Source fence.** The source host marks the residency entry `Departing(tx, target)` and syncs it.
   From here a re-created source is a *fenced stub*: it answers transfer queries and refuses
   admission; it is never live.
3. **Prepare.** The target instantiates from the capsule bytes *without activating or publishing*,
   logs `Prepared(tx)` (synced) and acknowledges. Idempotent per `tx`.
4. **Decide.** The source host logs `Departed(tx)` (synced): the commit point. Before it, the source
   may abort: it sends `ABORT(tx)`, clears the fence and resumes the term in place (the target, if
   prepared, discards on `ABORT` and never activates without `COMMIT`).
5. **Commit and release.** `COMMIT(tx)` to the target: it logs `Committed(tx)`, activates, and
   publishes route version `v`. Only now is it live.
6. **Retire.** On the target's acknowledgement, the source destroys its instance and stub and removes
   the residency entry and stream.

| Crash of | after | Recovery |
|---|---|---|
| source | 1, before 2 | no transfer; the term re-creates normally; a prepared target never activates (it asks; an unknown `tx` at the source means abort) |
| source | 2 or 3, before 4 | re-created as a fenced stub; it queries the target and aborts (resume in place) or proceeds to 4 |
| source | 4 or 5 | fenced stub; re-sends `COMMIT(tx)` (idempotent), then retires |
| target | 3, before `Committed` | re-created in `Prepared` state, not live; awaits the decision |
| target | 5 | resumes as the live holder; re-publishing `v` is a no-op |
| either | 6 | retirement and route publication are idempotent; a stale route to the source hits a closed intake, and senders re-resolve to `v` |
| source, volatile term | 2 or 3, before 4 | fenced stub with the staged capsule; aborts by resuming from it, or proceeds to 4 — never `ALL` |

**A volatile term (no `D`)** has no stream for step 1, so its capsule is staged in the host's
residency storage: step 2's synced `Departing(tx, target, capsule)` record carries the capsule bytes,
opaque to the host, and the target's synced `Prepared(tx)` carries them too. A source crash before
that record is an ordinary volatile crash (`ALL`, nothing departed). After it, the fenced stub holds
the capsule: on abort it resumes the term in place *from the staged capsule* (admission was fenced
since capture, so the capsule is complete), clearing fence and bytes in one synced record before admitting, otherwise it proceeds to step 4. The target deletes
its staged copy, synced, before admitting its first frame — a copy that outlived admission would
resurrect a stale state under live epochs — so a target crash after that is an ordinary volatile
crash (`ALL`, succession). The source deletes its copy at step 6. This adds one host duty to H1: a
synced residency record may carry opaque bytes.

### 5.2 Proposed requirements

- *The host shall not branch on any cell type, marker interface or descriptor field other than port
  names, color, protocol band and direction, and resource admission.*
- *When the host re-creates a term, it shall hand back the term's stream and gap, and shall not
  replay, filter or interpret the stream.*
- *The host shall hold no accepted frame, and no frame awaiting acceptance.*
- *When a term is relocated, the host shall durably fence the source before the target is activated,
  and shall not re-create a fenced source as a live term.*

### 5.3 Honest exceptions to "the host knows nothing"

- **E1. Exclusive-payload handling in the dead-letter store.** `DeadLetters` checks `is Owned`/`is
  Leased` to freeze or redact (`host/DeadLetters.kt:302-316`). Terms hand records to this host
  service, and an exclusive payload must never be dropped silently. It is ownership vocabulary carried
  on the frame, not cell semantics.
- **E2. Protocol band and direction, read from the descriptor.** The host cannot schedule or wire a
  protocol edge without them; structural metadata of the same kind as port names.
- **E3 (transitional). Host intake coalescing.** The host merges a saturated intake's queued
  mergeable frames (`host/IntakeControl.kt:73-91`; the default policy, `host/IntakeSaturation.kt:14`),
  which is a cell-semantic act. It is kept, bounded by PLP §5.3 (never at an enforcing inlet,
  never reordering a lane, carrying every merged position), until migration step 7 moves it into
  `P`; then E3 is withdrawn.

The earlier route-park exception is withdrawn: sender custody lives in `O` or the client stub (§3.6).
A caller that truly cannot own a stub would need it back; none is known at the pin **[inference]**.

---

## 6. Limits

**Refused at formation or link time.** Violations of F1-F12 (`X` outside `D`; nested `D`; custody
inside `S`; a bypass link; a durable `Effectful` + `Stateful` leaf; a non-deterministic durable leaf
without `O`, or an emitting `Effectful` term whose `X` suppresses replay without `O` logging; an
exclusive path with no holder; a durable non-idempotent term without `X` while its RESTART is a
succession). Unjournaled shard assignment. Cross-host couplings and
couplings without a disposition. T2 for `NonIdempotentCatchUp` candidates and replicated cells.
`Effectful` with overlapping interest and no authority. An automatic-claim `Effectful` replica set
whose destinations have no witness or declared ceiling. Promotion across differing or undeclared
effect identities without `effectFrom = COMMIT`. Removing an obligation-holding layer without
discharge. A non-idempotent durable term downstream of a volatile term rebuilt by catch-up (a
baseline is a state, not an event sequence). An `APPENDED`-transmit link into an enforcing inlet
(PLP §5.8).

**Vetoed dynamically.** Relocation while a lease is held. Region suspension while a member cannot
suspend. Repartition while A or B is suspended, mid-transfer or unreachable, or for an `Effectful`
range with only aggregate state. Takeover by a member whose retention has a gap, or that does not
cover the effect inlet's whole interest (§3.1). Promotion that adds `X` without a captured starting
frontier for every incident lane, or keeps acted history without the candidate's
effect-compatibility assertion (§3.4). Continuation after
`Recreated(UNKNOWN)`.

**Declared ceilings.**

| Ceiling | Bound |
|---|---|
| Write-ahead window (24:1488-1522) | one effect re-fire per crash per in-flight position, unless the destination deduplicates |
| `BATCHED` class | after a process kill, at most `syncEvery − 1` acknowledged-unsynced records; after power loss, no bound is claimed (`[KBLK-26]`). Either case is reported as `TAIL` and handled by succession. |
| Discharged pull-id eviction (HD:39 onward) | an evicted id re-fires once |
| Replica failover | one duplicate act per position acted but unpublished by the old leader; none at an exact witness |
| Stale-leader effects | unbounded in time without a witness (layer 4); opt-in only (§3.1) |
| Follower retention | bounded by storage; exhaustion stalls, never drops |
| Volatile-acknowledged links | frames acknowledged `HELD` are lost if the receiver crashes before a surviving disposition |
| Volatile custody at a host crash | client-stub frames and unsnapshotted `Owned` are lost at most once (G-46, 31:276-280) |
| Budget charges, until the ledger is keyed and durable; unpositioned frames always | one duplicate debit per crash per in-flight charge; refunds reported, not automatic |
| Durable RESTART until M11, and after it for terms that can accept an exclusive payload | succession: the post-checkpoint tail's state is lost and rebuilt by catch-up |
| Volatile producer's dead lanes | convergence-only (93:11507-11511) |
| Independent per-term recovery | convergence, not a global cut (93:2849-2854) |
| Cross-host region | WAIT (unbounded latency) or DEGRADE (reduced frontier) |
| Region with per-member `D` | baseline convergence, not wave-exact |
| Async primary-backup | a leader's unshipped writes are lost on RESTART without `D` (42:878-880) |

**Open.**
- Coupling abort rules that compose with no-loss and drain (95 §R3).
- Who the management system is (placement and economics, layer 3).
- Retiring dead epochs and retention under long partitions (same bounding problem as HD:39).
- Re-forming a region while it is suspended.
- Storage failure semantics for a failed group commit after `APPENDED` was returned.
- `BudgetLedger` keyed refund and dedup horizon (§3.7).
- A coverage certificate that would let a partial-overlap replica take over effect authority (§3.1).
- Making exclusive consumption and its durable disposition one recoverable step (§2.4, M11).
- **REPLAY-1 (found by the executable model).** `P` releasing a parked frame is not a `D` record,
  so a RESTART journaled after a live release replays *before* that release on recovery; the frame
  is then re-derived under the fresh epoch and taken twice downstream. Candidate fixes: `D` logs
  each custody hand-on (`P` release, and by the same argument every custody layer's internal
  hand-on), or `RESUME` drains the park within its own turn. The first holds in the model
  (`model-results.md` §4); adopting it makes "every custody hand-on is a `D` record" a general rule,
  which is not yet reviewed against F1-F12 and §3.
- **Cost.** One `accept` per mediating layer per frame is the per-message tax 93:4151-4157 warned
  against. A layer with no per-message claim should compile to a delegate inside its `D` scope (the
  I-10 green-light rule, per layer).

---

## 7. Decisions this design asks the maintainer to make

Each lists options, a recommendation, the reason and the fallback. One numbering serves both
notes: **M1-M11** are argued here, **M12-M16** in PLP §10, and no decision appears in both.
PLP's earlier "decision 1" (repartition) is M6 below; its "decision 5" (host coalescing) is no
longer a maintainer decision but transitional exception E3 (§5.3).

**Argued in PLP §10, listed for completeness.**
- **M12.** When an onLinked catch-up is owed (computenet-n2jwi). Recommended: keep catch-up on
  every link install, as pull baselines with fresh ids.
- **M13.** A legacy wire sender feeding an `Effectful` inlet. Recommended: admit it degraded and
  reported during a rolling upgrade, refuse afterwards.
- **M14.** Whether a non-durable RESTART (and a durable one while M11 is undecided) re-runs the
  inbound handshake. Recommended: yes, as a pull-baseline catch-up. §1's "rebuild by catch-up" and
  §2.4 assume this answer.
- **M15.** Who declares idempotency, and the carrier of the replay-determinism declaration F9
  reads. Recommended: a nature axis.
- **M16.** Re-key `[24-DUR-05]`/`[24-DUR-08]` on delivery positions. Recommended: re-key; §2.6 F3
  and `X` assume it.

**M1. `Effectful` × `Stateful` journal-tail state loss** (DIS:1232-1268, undecided). Options: (a)
replay the state transition and suppress only the act; (b) forbid the combination or require a
checkpoint per effect; (c) declare the loss. **Recommend (b) by decomposition**: the effect becomes
an outlet to a separate `Effectful` leaf with its own `X`/`D`. Reason: `X` suppresses only what
crosses a port; (a) needs that separation without a port; (c) silently corrupts state. Interim: F8
refuses durable formation of the combined leaf.

**M2. 33-mobility "the host is the unit of mobility" (33:18-32).** Options: (a) keep it; (b) the term
is the unit, a host move is a batch of term moves. **Recommend (b).** Replacement text: *"The term is
the unit of mobility. When a term migrates, its source shall be durably fenced before its destination
is activated, the complete capsule including held and retained work shall be transferred, and the
term shall resume under its original contexts and epochs only after the destination commits."*
`[33-MOVE-01]` is kept per term. Reason: per-cell moves were rejected as race-prone (33:20-22)
because the host held the cell's custody; once the term holds it and H4 fences the source, the host
protocol applies to one term. It also lets a suspended cell migrate (computenet-g5tr6). Fallback:
relocation stays host-granular; H4 moves batches.

**M3. 31-hosts "Durability is a hosting decision, not a cell concern" (31:98; 93 I-7 R1,
93:2790-2792).** **Recommend** a `Durable` layer on a term or composite, the host supplying only
storage: *"A term or composite declares its durable acceptance boundary with a Durable layer. The
host supplies storage streams and re-creation, and does not choose what to journal."* Reason: the
architect's target; per-cell selectors (24:1251-1255) already make durability per-cell in all but
name; the host-intake boundary allows the silent bypass of DIS:1387-1391. Fallback: `D` is an adapter
over `journalFor(cellRef)`, and F1-F7 still apply.

**M4. I-7's locus and log shape (93:2793-2808 R2/R3; 93:2881-2888).** Options: (a) one
host-sequence-ordered log at the host intake; (b) the boundary at the outermost `Durable` layer's
intake, one acceptance-ordered stream per scope, sharing one physical log. **Recommend (b).** Reason:
a cell needs its own receive order, not a host order; R3's reconstruction argument does not hold for
replayed baselines (DIS:1288-1312) and the scoped form gives exactness where wanted; group commit is
kept; R7's "no consistent cut" (93:2849) is kept.

**M5. Supervision is per-host and does not migrate (31:202-203).** **Recommend** supervision as the
`S` layer, travelling in the capsule, so a moved suspended term carries its parked traffic instead of
dead-lettering it (`clearSupervision`, MH:962-992). Consequence of M2.

**M6. The repartition protocol: `[24-PART-04]` (24:951-962) and `[24-SHARD-03]` (24:1624-1631).**
PART-04 requires replaying R's state-as-delta into the new owner "as one catch-up", an atomic flip,
and parked replay; SHARD-03 says the router holds no durable state. §3.2 changes both: the router is
a durable term with a flip transaction (R1-R7); the moved state is a range-handoff record on the
control plane that the gainer does not act on; the loser settles through `p_begin` before commit.
Options: (a) keep both rules, and refuse repartition of `Effectful` ranges (and declare d2lue's
crash loss for others); (b) adopt §3.2 for every partition set; (c) adopt §3.2 only for sets with an
`Effectful` or non-idempotent durable shard, keeping PART-04's catch-up for mergeable ones.
PLP §5.6 (scoped cursors) and PLP §5.8 ("Router segments") depend on this decision.
**Recommend (c)**, with replacement text for SHARD-03: *"A partition router whose shards include an
Effectful or non-idempotent durable shard is a Durable term: it logs its routing epoch and every open
flip, and rebuildFrom becomes a consistency check."* Reason: d2lue and 8g7kg cannot be fixed with a
volatile router or an acting catch-up; mergeable shards do not need the cost. Fallback (a): §3.2 is
not built, and both defects stay declared. The shed sentence at 24:1631-1633 still describes the
pre-PN-6 unjournaled path that 42:1006-1012 says is fixed (editorial).

**M7. Spec 34's host-resolved region (34:163-185).** **Recommend:** *"The suspension region is a
declared composite; the veto is the members' exported `canSuspend()`."* The host must not walk cell
semantics.

**M8. The swap window is unlogged (`[53-SWAP-05]`, 53:152; 93:4584-4590).** **Recommend:** *"The swap
is a `Swap` composite under the term's `Supervised` layer; its phases are logged by the term's Durable
layer, if present; effect history transfers only between equal declared effect identities that the
candidate asserts compatible."*
`[53-SWAP-05]`'s atomicity argument is kept.

**M9. PN-17's "exactly once per logical delta across a handoff" (31:408-410).** Options: (a) keep the
claim; (b) restate as: no omission for a Total-coverage successor (followers retain; takeover acts
above the leader's published disposed frontier), takeover refused under partial overlap, at most
one duplicate per unpublished position per failover, and exactly-once
only at an exact witness. **Recommend (b).** Reason: neither the epoch fence nor a fence-only witness
stops re-acting a position the old leader acted on but had not published **[inference]**.

**M10. I-10 membranes (93:4183 onward).** **Recommend:** keep the exposure map and Flatten/Mediate;
add lifecycle containment, capsules, and mandatory mediation for any claimed per-message obligation
(F7). A layer re-exposes all data ports; a composite hides its children's unless exposed.

**M11. Durable RESTART: 31:127-130 and 93 R9 (93:2867-2870) versus 23 R6 and 93 I-22 R6 (23:209-213,
93:8416).** R9 replays the post-checkpoint tail; R6 forbids re-driving the invocations that produced
state, so that an `Owned`/`Leased` is never re-consumed. They collide whenever the tail holds an
exclusive payload that was consumed. Options:
- (a) R9 wins: replay the whole tail, and accept re-consumption of exclusive payloads.
- (b) R6 wins: a durable RESTART is a succession (restore the checkpoint, no tail replay, catch-up).
- (c) **Split by exclusivity**: (a)'s replay, with original positions and the failing frame
  skipped by `Skipped(position)`, for a term that can accept no exclusive payload; (b) for every
  other durable term.

**Recommend (c).** Reason: it keeps R6's stated purpose — no `Owned`/`Leased` is re-consumed
(23:209-213) — while relaxing its letter (no re-drive at all) only where no exclusive can be in the
tail, and keeps R9 for every other durable cell. An earlier draft placed a checkpoint after each
exclusive-consuming delivery; a fault or crash between the consumption and that checkpoint still
re-drives it, so the barrier was withdrawn. PLP §7's durable-RESTART row follows this
decision (continuation under (c), succession until decided). (a) breaks SPSC exactly-once; (b) loses the
tail's state for every durable cell. Fallback until decided: (b). Required tests:
`durableRestartWithoutExclusiveInletsReplaysTail`, `durableRestartOfExclusiveConsumingTermIsSuccession`.

**Factual spec corrections** (not decisions): 93:3639 records `HELD_LEASE`, `CONSTRUCTION_LEASE`,
`MGMT_ACTIVITY` as landed, but none exists in `kernel/src/main` (computenet-wkopk); the 24:1631-1633
shed text (M6).

---

## 8. Migration path

Each step lands with its tests and keeps the old host path behind an adapter for terms that have not
adopted it. Steps 0-2 change no behaviour apart from defect fixes. Exact per-link positions (PLP) are
a prerequisite for steps 4, 8, 10 and 12.

| Step | What lands | Fixes or enables |
|---|---|---|
| 0 | `TagState.snapshot` includes `deadSources` (computenet-kxdjx, `data/delta/TagState.kt:235-239`); `GlitchFreeCell` captures instead of resetting (5jhg3); T2 supersedes the incumbent's lane (lzfr0); `TrafficLightCell` implements the pending-capture seam | bead closures |
| 1 | `TermManifest` and a pure `FormationCheck` (re-export equality, F1-F12, unique holders), report-only against today's spawns | the grammar is testable |
| 2 | `Capsule`/`Capturable` generalising `Stateful` and `checkpointPending`; HD's carry consumes it | removes HD:651-660's list |
| 3 | H1 with acknowledgement levels and `stableThrough`; `Recreated(gap)` with `TAIL` driving succession | honest `BATCHED` semantics |
| 4 | `X` with `disposed`/`acted`; records to the term's stream via an H1 adapter over `journalFor(cellRef)`; MH:1868/1889/1945 skipped for terms with `X` | 8g7kg (migrate); wlwjw with positions |
| 5 | `S`: generations, checkpoints, policies and RESTART, with a journaled epoch rotation; durable RESTART as succession (M11 fallback) | RESTART-then-crash epoch reuse |
| 6 | `D` on per-term streams; `accept` replaces `journalTee`; F8 enforced | per-term recovery |
| 7 | `P` (inbox, park, coalescing under PLP §5.3's rules; E3 withdrawn); H4 transfer protocol with residency fences; migrate = capture + H4 | g5tr6 (with M2); no dead-lettered parks on migrate |
| 8 | `PartitionSet` (M6): router under `D`, R1-R7, in-band `FlipFence`/`FlipDrained`, scoped frontiers at both shards (with PLP migration step 10), forced journaled assignment | d2lue, 8g7kg (repartition) |
| 9 | `Swap` replaces `Promotion.promote`'s body; effect identity in the manifest (F11) | crash-safe window; lzfr0 |
| 10 | `ReplicaSet`: `F` from `Stamped.applyTo`, set-keyed inbound lanes (PLP §3.2, stamped from PLP migration step 2), disposed-frontier publication, follower retention, takeover eligibility, claim-formation rule | PN-17 handoff gap (M9) |
| 11 | `Region` replaces `suspensionRegionOf`; `NonSuspendable` becomes `canSuspend()` | host walk removed |
| 12 | `O` with PLP §5.8's acknowledgement levels, required-level negotiation, stable-before-transmit and disposition table (with PLP migration step 9); client stubs; route park non-custodial | async send→log handoff |
| 13 | Budget charge protocol (§3.7); keyed ledger refund if ECO1 accepts it | charge obligations |
| 14 | `Coupling` (after G-52's proxy generation) | G-53 swap half |
| 15 | M11 option (c) if decided | durable RESTART as continuation for terms with no exclusive inlet |
| 16 | Remove remaining semantic branches; an architecture test fails on `is <cell marker>` under `host/`, allow-listing E1/E2 (E3 is gone after step 7); spec revisions per §7 in a separate documentation ticket | the host contract |

---

## 9. Test plan

**(F)** marks a failure or recovery case. Run each narrow test with `--rerun`, then the affected
modules, `:concord:test` (with `dur` and `dist`), and `./gradlew test`; from step 4, re-run
`ExchangeCompositionExitTest` at every step.

- **`FormationCheckTest`**: `acceptsCanonicalStack`, `refusesDedupOutsideDurable`,
  `refusesCustodyInsideSupervised` (including the literal `Durable(Supervised(Suspendable(leaf)))`),
  `refusesNestedDurable`, `refusesFlattenedInletAtDurable`, `refusesDurableEffectfulStatefulLeaf`,
  `acceptsEffectReifiedAsSeparateLeaf`, `refusesNondeterministicDurableLeafWithoutOutbox`,
  `refusesExclusivePathWithoutHolder`, `refusesDuplicateObligationHolder`,
  `refusesCouplingWithoutDisposition`, `volatileReceiverLinkRecordsCeiling`.
- **`LayerSurfaceTest`**: `composedTermReexposesLeafPortNames`, `g17CheckPassesOnComposedTerm`,
  `mismatchedReexposedManifestRefused`, `rawLinkIntoInnerPortRefused`.
- **`ControlRoutingTest`**: `unhandledSignalRefusedWithStack`, `restartRequestTravelsOutward`,
  `restartWithoutDurableIsSuccessionWithReBaseline`, `restartKeepsDisposedFrontier` **(F)**,
  `durableRestartIsSuccessionUntilM11` **(F)**, `durableRestartWithoutExclusiveInletsReplaysTail`
  **(F)** (M11 (c)), `durableRestartOfExclusiveConsumingTermIsSuccession` **(F)** (the `Owned` is
  never re-consumed),
  `repeatedRestartFaultEscalatesToSuccession` **(F)**.
- **Capsule**: `CapsuleTest.capturesParkAlignAndOutboxInAcceptanceOrder`,
  `.ownedPayloadSurvivesRoundTrip`, `.incompleteCapsuleRefusedAtPrepare` **(F)**;
  `TrafficLightCaptureTest.redBufferSurvivesCheckpoint` **(F)**;
  `GlitchFreeDrainTest.partialWaveSurvivesMigrate` **(F)**; `TagStateSnapshotTest.deadSourcesRoundTrip`
  **(F)**.
- **`StorageStreamTest`**: `batchedAppendAcknowledgedBeforeStable`,
  `stableThroughAdvancesOnSync`, `killInUnsyncedTailReportsTailGap` **(F)**,
  `tailGapForcesSuccessionAndFreshEpoch` **(F)**, `unknownGapRefusesContinuation` **(F)**,
  `perTermStreamsShareGroupCommit`.
- **`DurableTermTest`**: `crashRecoveryReplaysOwnStreamOnly` **(F)**,
  `crashBeforeAndAfterAppendAndDelivery` **(F)**, `restartThenCrashDoesNotReissueEpoch` **(F)**.
- **`EffectDedupLayerTest`**: `secondSameWaveFrameAtOnePortDelivered` (wlwjw),
  `frontierMovesWithMigratedTerm` **(F)**, `crashBetweenEffectAndDisposedRefiresOnce` **(F)**,
  `refusedFrameAdvancesDisposedNotActed`.
- **`RelocationTest`** (H4), crash injected at each step on each side **(F)**:
  `sourceCrashBeforeDecisionAbortsAndResumesInPlace`, `sourceCrashAfterDecisionRetiresNeverResumes`,
  `targetCrashWhilePreparedStaysInactive`, `targetCrashAfterCommitResumesAsSoleHolder`,
  `volatileSourceCrashAfterDepartingResumesFromStagedCapsule`,
  `volatileTargetDeletesStagedCopyBeforeFirstAdmission`,
  `neverTwoLiveHolders` (property, crash points × seeds), `routePublicationIdempotent`,
  `suspendedTermMigratesWithParkInOrder` (g5tr6), `relocationVetoedWhileLeaseHeld`,
  `volatileTermFencedByResidencyEntry`.
- **`PartitionSetFlipRecoveryTest`**: `crashAfterEachRecordResumesOrAborts` **(F)** (R1-R7, both
  sides), `releaseResumesFromCursorWithoutDoubleTake` **(F)**,
  `releaseCursorWaitsForStableShardAck` **(F)** (router and B crash after send, before B syncs),
  `fenceProcessedAfterQueuedRSliceDisposed` **(F)**, `drainedProcessedAfterQueuedReleasedSlice`
  **(F)**, `parkedRecordsKeptUntilBothShardsAckDrain` **(F)**,
  `boundaryFrameSplitAcrossRangesBothPartsTaken` **(F)**, `parkedFrameBelowOldOwnerHighWaterActs`
  **(F)** (`p_begin`, not A's high-water), `gainerDoesNotActOnHandoffState` **(F)** (8g7kg),
  `commitWaitsForOldOwnerSettlement` **(F)**, `settleFenceTravelsInBandBehindRoutedFrames` **(F)**, `preparedGainerInDoubtActsOnNothing` **(F)**,
  `abortReleasesParkedToOldOwner` **(F)**, `abortBeforeFenceWaitsForSettled` **(F)**, `uncommittedRangeStaysParkedOthersFlow` **(F)**,
  `directAssignRefused`, `repartitionVetoedWhileShardSuspended`,
  `replicatedShardFlipWaitsForConfirmedLeader`.
- **`SwapLayerTest`**: `t1PromotionContinuesLaneWithoutReBaseline`,
  `t2AnnouncesIncumbentLaneSuperseded` (lzfr0), `crashBeforeCommitRollsBack` **(F)**,
  `crashAfterCommitRollsForward` **(F)**, `sameEffectIdentityKeepsActedHistory` **(F)**,
  `differentEffectIdentityVetoed` **(F)**, `sameIdentityWithoutCompatibilityAssertionVetoed` **(F)**,
  `addedDedupStartsAtCapturedAppliedFrontier` **(F)**, `addedDedupWithoutCapturedFrontierVetoed` **(F)**,
  `rollbackAfterRetireIsNewSwapFromExport` **(F)**, `duplicateInSwapWindowParkedInPActsOnce` **(F)**,
  `rollbackAfterCommitIsNewSwap` **(F)**.
- **`ReplicaSetAuthorityTest`**: `staleLeaderDeltasFencedAtEveryRecipient` **(F)**,
  `followerSuppressionDoesNotAdvanceDisposed` **(F)**, `positionOnlyFollowerSawIsActedAfterTakeover`
  **(F)** (no omission, Total coverage), `unpublishedActedPositionDuplicatedAtMostOnce` **(F)**,
  `exactWitnessDedupsAcrossEpochs` **(F)**, `fenceOnlyWitnessKeepsDuplicateCeiling` **(F)**,
  `takeoverRefusedWithRetentionGap` **(F)**, `takeoverRefusedUnderPartialOverlap` **(F)**, `retentionExhaustionStallsNotDrops` **(F)**,
  `effectfulAutoClaimWithoutWitnessOrCeilingRefused`.
- **`ReplicaDepartureSettlementTest`**: `cleanLeaveClosesRow`, `unreachableSuspendsRow` **(F)**,
  `uncleanDeathFreezesRowAndReports` **(F)**.
- **`RegionCompositeTest`**: `suspendIsAtomicAcrossLocalMembers`, `oneNonSuspendableMemberVetoesRegion`,
  `heldLeaseVetoesRegion`, `crossHostArmFallsBackToWait`, `crossHostArmDegradesWhenOptedIn`,
  `memberMigrateCarriesPartialWave` **(F)**.
- **`CouplingLayerTest`**: `partialUnitSurvivesSwapWindow` **(F)**, `partialUnitSurvivesCrash`
  **(F)**, `releasedGroupReplaysOnce` **(F)**, `abortedPartialUnitDeadLettersAndDischargesOwned`
  **(F)**, `crossHostCouplingRefused`.
- **`OutboxTest`** (custody only; acknowledgement, stable-before-transmit and the disposition
  table are PLP's `DeliveryAckRetentionTest`): `frameToUnlocatedTargetRetainedUntilRequiredLevel`
  **(F)**, `outboxTravelsInRelocatedSenderCapsule` **(F)**, `externalStubHoldsUnlocatedFrames`,
  `hostRouteHoldsNoFrame`.
- **`BudgetChargeTest`**: `crashAfterDebitBeforeOutcomeRechargesWithSameKey` **(F)**,
  `crashAfterOutcomeNeverRecharges` **(F)**, `crashBeforeDebitChargesOnce` **(F)**,
  `refusedChargeRefusesFrameAndDischarges` **(F)**, `refundOwedRetriedAfterCrash` **(F)**,
  `chargeIdStableAcrossReplay`, `chargeIdUnchangedWhenReplayTakesOtherBranch` **(F)**.
- **`DeathReportingTest`**: `linkEndRaisesRecoverableStallOnPeerLoss` **(F)**,
  `recreatedTermAnnouncesResumeOrReBaseline` **(F)**.
- **`HostContractArchTest`**: `noCellMarkerChecksUnderHostPackage` (allow-list E1, E2; E3 until step 7),
  `hostHoldsNoAcceptedFrame`.

---

Reconciliation with earlier drafts and the review response:
[`reconciliation/composite-obligation-holders.md`](reconciliation/composite-obligation-holders.md).
