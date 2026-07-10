# 11 — Cells

> **Status**: Partial (core specified and implemented; membranes/policies exploratory)
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

*(G-8 resolved, M7.1)*: `CellRef(id, incarnation)` — `id` is the logical
identity, `incarnation` distinguishes live instances of it: replicas (42, one
incarnation each) and candidate versions (53). Incarnations are minted
collision-free without coordination (random; caller-chosen in deterministic
tests). Links and registries address full refs; "links bound to logicalId"
as an automatic indirection is realized per use-case (replication links the
mesh explicitly, M7.3; promotion re-links in the swap window, M9).

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

⚠ GAP (G-9): the "hide or selectively expose organelle ports" operation has no
API yet; today all ports of a spawned cell are reachable by name through the
host. *Proposal*: an `exposes` declaration on the containing cell; ManagedHost
`findPort` resolves only exposed ports for external callers.

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

⚠ GAP (G-10): membranes have no code representation at all. This is
acceptable — they are the *narrow/why* layer above ports and policies — but
the spec commits to realizing them incrementally as: (1) link handshake hooks
(10/13), (2) per-port policies, (3) a membrane object owning cross-port rules.
The Traffic-Light cell (30/33) is the first membrane-like mechanism in code:
boundary-level flow control implemented purely with serve/delegate/buffer.

## Rejected alternatives (binding)

- **Actor model**: implicit mailboxes hide topology; contracts too weak (P3).
- **Pure dataflow**: no ownership boundaries or authority (P5, P7).
- **Ports-only, no membrane concept**: cannot express cross-port invariants;
  scatters boundary logic.
