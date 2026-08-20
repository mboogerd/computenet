# 11 — Cells

> **Status**: Partial (core specified and implemented; membrane/policy and identity-level design decided in 93, unimplemented)
> **Sources**: ADR — Anatomy of Cellular Programs, ADR — Computelet Kernel, ADR — Cellular Software Development Process, ADR 1 (§3, §14)
> **Implementation**: `civictech.cell.Cell`, `CellRef`, `CellContext`; data cells in `civictech.cell.data`

## Definition

A **Cell** is the unit of state, computation, and identity:

- a container of state, owned exclusively by the cell;
- a locus of logic and incremental computation;
- a participant in the graph via explicit, named ports;
- conceptually single-threaded: state transitions are serializable (in the
  ordering sense) and, per P9, serializable (in the encoding sense).

External mutation of a cell's state is **impossible except via its ports**.

## Kernel interface (normative, as implemented)

```kotlin
interface Cell {
    val ref: CellRef                       // stable identity (UUID today)
    fun onActivate(ctx: CellContext) {}    // hosted-lifecycle hook, see 10/15
}
```

The cell *is its own specification*: the same object describes the cell (cold)
and provides its behavior (hot). See 10/15 for the lifecycle and the rationale
(rejected alternatives: separate Def/Instance hierarchies, codegen-first).

Ports are declared as properties — either via delegates
(`val inlet by input<Consumer<A>>()`) or as explicit registered instances
(`val inlet = registerPort("inlet", FanInlet.create<SetOps<E>>())`). Both are
accepted and both feed the per-cell port registry (C-6 resolved; see 10/15);
delegates are preferred for hosted cells.

`CellContext` is intentionally minimal today (empty interface). Planned
additions (host ref, port resolution, context propagation — see 20/22) MUST
come through this interface rather than through globals.

## Identity and persistence

A cell's identity is its **boundary contract + invariants + behavioral
continuity**, not its implementation. `CellRef` (a UUID) is the runtime handle
for that identity. This is what makes hot upgrades and evolutionary deployment
(50/53) coherent: the implementation behind a ref may be replaced while links
and invariants persist.

*(G-8 resolved, M7.1)*: `CellRef(id, instanceId)` — `id` is the logical
identity, `instanceId` distinguishes live instances of it: replicas (42, one
instance each) and candidate versions (53). Instance ids are minted
collision-free without coordination (random; caller-chosen in deterministic
tests). Links and registries address full refs; "links bound to logicalId"
as an automatic indirection is realized per use-case (replication links the
mesh explicitly, M7.3; promotion re-links in the swap window, M9).

**Three identity levels** (decided in
[93 I-2](../90-roadmap/93-feature-interactions.md)) refine G-8 into an
addressing/grouping/continuity model:

- **Addressing (hot path)** — links, `PortRef`s, wire frames, registry
  resolution, park/replay, supervision, and cardinality MUST key on the full
  `CellRef(id, instanceId)`; one full ref resolves to one location and one
  instance.
- **Grouping (rare path)** — `logicalId` is only an index key:
  `instancesOf(id)` returns the currently-announced full refs sharing `id`,
  used exclusively by rare orchestrators (the replication mesh linker, 42;
  promotion, 53; admin/query). It MUST NOT appear on the data path and never
  resolves to a location by itself.
- **Continuity (semantic)** — `logicalId` is what "the cell" denotes to
  users, invariants, and topology intent; preserved across restart/migration
  by keeping `instanceId` stable, and across promotion by explicitly
  relinking the candidate onto the incumbent's links in the buffered swap
  window.

**InstanceId lifecycle rule** (decided in 93 I-2): a new `instanceId` is
minted ONLY for a replica spawned on another host (42) or a candidate version
spawned under evolution (53). Migration, RESTART supervision, and
suspend/resume MUST preserve the `instanceId` — the same instance moves or
recovers in place, so its links are not rebinds and re-run no handshake.
Promotion IS a rebind (new `instanceId`) and MUST re-run the full link
handshake against the candidate.

**Port sameness across instances** (decided in 93 I-2) is structural: a
port's identity is `(portName, contractId)` (40/41). "The same port" on
another instance means name + contract equality — used for migration mapping,
catch-up, and promotion relink, never as an address. `CellRef` carries no
contract hash; the compatibility check lives on the *ports*:
migration/promotion MUST reject a relink whose target lacks a matching
`(portName, contractId)`. This is the membrane's evolution surface (below).

*(G-48 resolved, W1.6)*: `LocationRegistry.topology` maintains inbound and
outbound links per full ref from successful handshake and idempotent unlink
events. The same registry-announcement channel mirrors topology additions and
retractions across peers without forwarding second-hand edges. Rare-path
orchestrators enumerate a promotion candidate's complete incident link set via
`TopologyIndex.swapSet(ref)` rather than scanning cells or ports.

⚠ GAP (G-57): a client holding only a `logicalId` has no defined
instance-selection policy (nearest replica for reads, leader for writes,
active candidate during promotion), and `instanceId` minting has no stated
collision discipline across hosts or in deterministic tests. *Proposal*: a
logical rendezvous policy over `instancesOf` keyed by operation class; the
port-compatibility rule on relink (reject vs adapt on `(portName,
contractId)` mismatch) confirmed to live on ports rather than refs; and a
stated birthday-bound argument for random `instanceId` minting with
caller-chosen ids in deterministic tests, covering construction-time
`NewInstanceOf` minting across hosts (93 I-2/I-21).

## Mutability classes

Per ADR 1 §14, a cell declares its mutation stance:

- **Immutable / source / sink / invariant** cells — no internal mutation.
- **Single-writer** — mutations serialized through one inlet.
- **Concurrent / mergeable** — accepts concurrent (decentralized) updates and
  resolves them with declared merge semantics (see 20/24, e.g. `SetDelta`
  add-wins / del-wins).

## Hierarchy: organelles

Cells MAY contain cells. Each layer defines its own ports, keeps local
invariants, and chooses which internal ports to hide or expose:

```
Cell
 └── Organelle Cell
       └── Organelle Cell
```

The **Host** (30/31) is itself a cell that hosts cells — hierarchy is not a
special mechanism, it is the same mechanism. Exposing an internal port
externally is done by delegation (10/14), which keeps the fast path flat
(no per-message re-wrapping).

⚠ GAP (G-9, design decided in 93 I-10/I-21, unimplemented): the "hide or
selectively expose organelle ports" operation has no API yet; today all ports
of a spawned cell are reachable by name through the host. *Proposal*: an
`exposes` declaration on the containing cell; ManagedHost `findPort` resolves
only exposed ports for external callers — subsumed by the decided membrane
exposure map (next section), which generalizes `exposes` with surface modes.

Decided (93 I-21): organelle nesting surfaces in graph construction as a
`parent` parameter on the spawn step — lowering to the parent-aware spawn the
host already records (G-28/M8.1) — never a new step type. Exposure stays a
property of the container's *type* (correctness-by-construction, P5), so it
is preserved by reconstruction: replaying the same factory rebuilds the same
cell with the same exposure set; graph specs need no `expose` step. An
external connect to a non-exposed organelle port MUST be `Rejected` at the
link handshake (10/13) — the membrane veto is the loud-failure surface for
hierarchy violations.

## Membranes and policies (conceptual layer)

The **membrane** is the cell's boundary treated as a first-class concern:

- **Boundary as authority** — permissions and capability checks live at the
  boundary, not in business logic.
- **Boundary as causal scope** — stronger consistency may be assumed inside
  than across.
- **Boundary as evolution surface** — internals change; the membrane preserves
  compatibility.
- **Boundary as observability layer** — tracing and replay are defined at
  crossings.

Beyond per-port rules, membranes enable **cross-port** coordination: coupled
flows (symport-like: accept on port A only with port B), exchange flows
(antiport-like: ingress/egress tied by backpressure or conservation), atomic
multi-port transitions, boundary-wide accounting.

**Decision (dual perspective)**: developers reason with the *anatomical* model
(membrane is part of the cell); the runtime treats the membrane as *separable*
(a wrapper layer) where useful. Both ADR models are thereby retained.

**Decided model (93 I-10, unimplemented)**: a membrane is a declarative,
multi-port generalization of the Traffic-Light cell (30/33) — declared as
part of the cell (anatomical), realized as a separable proxy layer only where
mediation is requested. Flattening is not universal; it is the green light. A
membrane declares an **exposure map** plus **couplings**:

- Each `Exposure` names an `externalName` (the ONLY name external callers may
  resolve), the organelle port it re-presents, a surface mode — **Flatten**
  (green: the exposure delegates, off the per-message path; authority is
  link-time `onLink` policies only; cardinality/SPSC counted once, at the
  organelle port — an exposed link is the organelle outlet's own subscriber,
  not a second consumer) or **Mediate** (red-with-logic: a served proxy on
  the path that captures each Invocation, evaluates flow-time policies, and
  applies coupling gates via Buffering; its budget is counted at the proxy's
  external face) — and a wave scope: **Preserve** (transparent, waves flow
  through per 20/22) or **Remint** (Mediate only: the proxy MUST mint fresh
  waves from its own counter, making the membrane a single external wave
  source and a glitch-free frontier, 20/22), plus an optional observe/trace
  outlet.
- **Hidden by default (G-9)**: a cell that declares a membrane publishes to
  external resolvers ONLY its exposures' external names; host
  lookup/connect/findPort initiated from outside MUST resolve only those
  names and refuse non-exposed organelle refs/ports as unknown-port (30/31).
  Enforcement piggybacks on the shipped G-28 parent/child containment record
  (M8.1); cells with no membrane keep today's flat resolution. Organelles
  stay addressable internally via the direct references the container holds
  from spawning them — hiding costs nothing on the internal path.
- **Couplings** make the cross-port rules above concrete: `Symport` (accept
  on A only with B — hold each coupled invocation until all coupled ports
  have delivered for the wave, release together) and `Antiport` (gate egress
  on ingress credit). Both are realized in the Mediate proxy's Buffering,
  local to the container's host — no global barrier, no global clock (P4).

⚠ GAP (G-53): cross-port couplings (Symport/Antiport) can wait forever when
one coupled port never fires for a wave, and the fate of a half-completed
coupled transaction caught in a promotion-swap buffering window is undefined.
*Proposal*: a timeout/veto/abort policy for stalled couplings that composes
with no-message-loss and the drain protocol, plus ordering semantics for
coupled transactions across a buffered swap window (93 I-10/I-11).

**Boundary policy (decided in 93 I-28, landed W4.1 — G-54 core)**: an
`Exposure` carries a `BoundaryPolicy` — identity-keyed predicates (admission,
link authority, per-protocol authority, disclosure, integrity) evaluated at
the three seams the boundary already owns: the peering hello, the link
handshake, and flow time. Declaring any flow-time predicate (protocol
authority, disclosure, or integrity) MUST force the exposure to Mediate; the
Mediate proxy is the sole flow-time enforcement point. Absent a
`BoundaryPolicy`, every predicate defaults open and the exposure Flattens —
today's behavior, byte-for-byte. Realized as `civictech.cell.membrane.
BoundaryPolicy`/`CompositeCell.mediate`/`mediateOutlet`/`MediateProxy`
(`kernel`); `AuthLevel.Authenticated` is reachable (DSC1, `computenet-ssa.3`) —
a socket crossing whose hello verifies, or a `Peering.loopback` direction
with credentials on both sides, promotes past `AuthLevel.TransportVouched` —
and the hand-written proxy (not yet KSP-generated, G-52) is the
outlet-direction Mediate realization — see `BoundaryPolicyTest`.

**Frontier queries at the boundary (decided in 93 I-23)**: a membrane answers
an upstream frontier traversal per its surface mode — Flatten is transparent
(the resolved link lands on the organelle port; the delegating cell
contributes no frontier node); Mediate+Preserve propagates the query inward
and emits a re-based summary on its own exposed edge; Mediate+Remint or an
authority-opaque membrane answers as a terminal/opaque source with its own
sourceId and does not propagate the query inward. Interior edge/port refs
MUST never cross the boundary — reports are re-based at the exposed edge, so
frontier knowledge and addressing capability stay decoupled (hiding holds by
construction).

⚠ GAP (G-10, design decided in 93 I-10, unimplemented): membranes have no
code representation at all. This is acceptable — they are the *narrow/why*
layer above ports and policies — but the spec commits to realizing them
incrementally as: (1) link handshake hooks (10/13), (2) per-port policies,
(3) a membrane object owning cross-port rules. The Traffic-Light cell (30/33)
is the first membrane-like mechanism in code: boundary-level flow control
implemented purely with serve/delegate/buffer.

⚠ GAP (G-52): the adopted membrane design (exposure map + Flatten/Mediate
surface modes over the TrafficLight idiom) is undesigned at its edges: DSL
lowering and proxy generation, exposed-name resolution across the wire,
nested/transitive exposure, wave re-mint interplay, and leaf-cell membranes.
*Proposal*: KSP-generate the Mediate proxy (Invocation capture +
policy/coupling/re-mint) from the membrane declaration and lower the DSL to
spawn/serve/delegate/connect; announcements carry exposed aliases only,
resolved bridge-side to organelle full-ref ports without leaking the
interior; define exposure re-composition (wave-scope/mode) across nesting
levels with cardinality accounting; pin Remint interaction with attention
propagation and late-join catch-up; and specify the minimal non-composite
(leaf) membrane form (93 I-10).

## Rejected alternatives (binding)

- **Actor model**: implicit mailboxes hide topology; contracts too weak (P3).
- **Pure dataflow**: no ownership boundaries or authority (P5, P7).
- **Ports-only, no membrane concept**: cannot express cross-port invariants;
  scatters boundary logic.
