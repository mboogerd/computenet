# 03 — Glossary and Canonical Terminology

> **Status**: Specified (contains normative naming decisions)
> **Sources**: all ADRs; `civictech.cell` package
> **Implementation**: partially — code and ADRs currently mix several vocabularies

The ADRs were written over time and use overlapping vocabularies
(*Task* / *Computelet* / *Cell*; *Runner* / *Host*). This document fixes the
canonical terms. Older terms remain valid as historical aliases in ADRs but
MUST NOT be introduced in new code or documents.

## ⚠ CONFLICT (resolved here): Task vs Computelet vs Cell

ADR 1 and the connectivity/definition ADRs say *Task*; the kernel and mobility
ADRs say *Computelet*; the anatomy/process ADRs and the `germ` code say *Cell*.

**Decision**: **Cell** is canonical. *Computelet* and *Task* are historical
synonyms. The biological framing (cells, organelles, membranes) is the one the
newest ADRs and code converged on, and it carries the hierarchy story best.

## ⚠ CONFLICT (resolved here): Runner vs Host

ADR 2 uses *Runner* for the concurrency container; the Computelet Kernel ADR
defines *Runner* as "a specialized cell that hosts other cells".

**Decision (implemented)**: **Host** is canonical for "a Cell that hosts other
Cells and owns their execution". A Host has a **color** (see below)
determining its concurrency machinery. *Runner* survives only as an adjective
for the color machinery ("coroutine-hosted", "virtual-thread-hosted").
`ManagedRunner` is gone; execution strategy is a `HostScheduler`
configuration of the one `ManagedHost` class (30/31, C-2/G-2 resolved).

## Core terms

| Term | Definition | Spec |
|---|---|---|
| **Cell** | The unit of state, logic, and identity. Owns its state exclusively; interacts only via ports. Acts as its own specification (cold) and its own logic provider (hot). | 10/11 |
| **Logical cell** | The placement-independent identity of a cell — what links, views, and users mean by "the cell" (`CellRef.id`). One logical cell may have several live Instances. (G-8, M7.1.) | 10/11, 40/42 |
| **Instance** | One live realization of a logical cell (`CellRef.instanceId`): a replica (42) or a candidate version under evolution (53). *Avoid*: **incarnation** — historical alias. | 10/11, 40/42, 50/53 |
| **Generation** | Per-instance recovery counter, host bookkeeping only — never on the wire, held outside the `Stateful` checkpoint. The host MUST bump it on every RESTART/recovery before reactivation (a checkpoint restore cannot roll it back); its only observable role is seeding `ReBaseline.supersedes`. Distinct from `instanceId` (the replica/candidate axis, unchanged by restarts); the wire `Timestamp(sourceId, counter)` shape is unchanged — a restart mints a fresh per-epoch `sourceId`. Realizes the retired *incarnation* intuition (decided in [93 I-22](../90-roadmap/93-feature-interactions.md), unimplemented). | 30/31, 90/93 |
| **Organelle** | A cell nested inside another cell (hierarchical composition). **(specified, unimplemented)** — no `Organelle` type exists in `kernel/src/main`; hierarchy today is host parent/child bookkeeping only (→ G-9/G-10). | 10/11 |
| **Membrane** | The conceptual boundary of a cell: the locus of cross-port rules, policies, authority, and observability. Part of the cell in the developer model; separable in the runtime model. | 10/11, 40/43 |
| **Port** | A named, typed crossing point on a cell. Has a direction and cardinality. The unit the topology connects. | 10/12 |
| **Inlet** | The receiving side: the cell *serves* an API implementation on it; external parties *use* it. | 10/12 |
| **Outlet** | The sending side: the cell *uses* it; external parties *subscribe*/link it onward. | 10/12 |
| **Contract** | A plain interface of **push-only** methods that a port carries (e.g. `Consumer<T>`, `SetOps<E>`, `Propagate<D>`). Bidirectional exchange = two contracts, one per direction. | 10/12 |
| **Link** | A first-class directional connection between an outlet and an inlet, created by an explicit handshake. | 10/13 |
| **Invocation** | A serializable capture of one method call on a contract (stable method identification + arguments). The unit that crosses boundaries. | 10/14 |
| **Serve / Use / Subscribe / Delegate** | Four of the five primitive port operations of the Link-and-Lease model. | 10/14 |
| **Invalidate** | The fifth primitive port operation: mark leases stale so re-resolution happens lazily, on next use. **(specified, unimplemented)** — no `Invalidating` type or `invalidate()` method exists in `kernel/src/main` (see 10/14 §Invalidating); today's `serve` re-registration is the only invalidation path. | 10/14 |
| **Host** | A Cell that hosts other cells: manages lifecycle (spawn/activate), owns the single-consumer queue, executes invocations, enforces isolation. | 30/31 |
| **Color** | Execution style of logic and hosts: 🟢 Pure, 🔵 Blocking, 🟣 Suspending. | 30/32 |
| **Bridge** | A unidirectional adapter carrying invocations between hosts of different colors. | 30/32 |
| **Delta** | An incremental state transition propagated along links; the default payload style. | 20/21 |
| **Tag hygiene** | The normative rule for tagged delta emitters: never re-emit a tag previously deleted. Pass-through operators may reuse input tags only when every membership flip-ON rides a fresh input add-tag; non-monotone operators mint fresh output tags per entry. | 20/21, 20/24 |
| **Aggregator** | A grouped aggregate as a deterministic function of group membership (never arrival order): `empty/insert/retract/value` over a serializable accumulator. | 20/24 |
| **MessageContext** | Metadata traveling with invocations: logical timestamp / wave id + source port. Required for glitch-freedom. | 20/22 |
| **Glitch-free frontier** | The nearest upstream set of glitch-free cells at which dependency tracking may stop; realized per-inlet as an edge/link-set fold (see the disambiguation table below). | 20/22 |
| **Ownership contract** | Payload sharing semantics: `Borrowed`, `Owned`, `Leased`, `Frozen`. | 20/23 |
| **Suspension** | Isolating a cell/host from the graph by unlinking (buffering at the boundary). | 30/33 |
| **Suspension region** | The atomic unit of attention-driven suspension: a glitch-free join plus its local transitive upstream contributors, bounded by further glitch-free cells. Parks together or not at all. | 30/34 |
| **Non-suspendable** | Per-cell marker vetoing attention suspension; the veto is contagious to the cell's whole suspension region. | 30/34 |
| **Migration** | Unlink → move → relink of a host's cells to another host. | 30/33 |
| **Attention** | A subscription-derived scheduling-priority signal (`civictech.cell.control.AttentionSupport`): downstream demand aggregates upstream into a band that drives suspend/resume and dispatch priority. | 30/34 |
| **Interest** | A key-space admission/demand predicate (`civictech.cell.link.Interest`): decides whether a replication link forms at all and which slice of a source's deltas rides it — replication, partitioning, and sharded replication are three settings of this one knob. | 40/42 |
| **Policy** | A constraint governing port use or linking (authz, rate, schema, quota). | 10/13, 40/43 |
| **Journal** | A host's write-ahead log of accepted invocations, stored as wire frames; replay through the ordinary decode path is recovery. | 20/24, 30/31 |
| **Checkpoint** | A journal compaction point: every Stateful cell's snapshot as one record; recovery = restore + replay the tail. | 20/24, 30/31 |
| **Invariant** | A property that must hold across all valid executions; the primary verification artifact. | 50/52 |

**Attention/Interest sync obligation**: `AttentionSupport.scatter`'s overlap
predicate and `Interest.overlaps` are two independently-callable checks over
the same key-space algebra (attention scatter, `control/Attention.kt`, PN-19;
gossip link formation, `link/Interest.kt` via `Replication.maybeLink`) — a
scatter predicate that drifts from the interest it is meant to mirror would
let attention travel to a shard the gossip linker itself would refuse to
serve. Callers wire them by passing the same `Interest.overlaps` check into
`scatter` (see the `AttentionSupport.scatter` KDoc); there is no shared type
enforcing this, so a future refactor of either must re-check the other.

### Disambiguation: "frontier" / "watermark" / "region"

Eight code types share the `*Frontier` suffix across three genuinely
different senses. This table pins one word per sense — **no renames**, the
code identifiers below stay exactly as they are; this is a reading aid only.

| Sense | Meaning | Code |
|---|---|---|
| **Watermark** | A per-source counter high-water mark — the highest/most-complete position observed, tracked as scalar or per-source state. | `TagFrontier` (merge-tag currency, `MessageContext.kt`); `DeliveredFrontier` (contiguous-prefix delivery watermark, `data/delta/DeliveredFrontier.kt`); `RetainedFrontiers` (per-instance retained `TagFrontier`s, `protocol/StateRequestProtocol.kt`) |
| **Frontier** | An edge/link-set fold — the boundary of tracked links a completeness or attention computation folds over. This is the sense the "Glitch-free frontier" row above realizes. | `WaveFrontier` (per-inlet wave-completeness fold over `EdgeOpen`/`EdgeClose`, `consistency/WaveFrontier.kt`); `AttentionFrontier` (per-link attention-version fold, `control/Attention.kt`); `ReplicaFrontier` (`consistency/WaveFrontier.kt`) is a settlement *predicate* over the replica set's delivered-watermark lattice rather than a stored fold, but answers the same "has this frontier passed" question |
| **Region** | A *set of cells*, not an edge or counter structure — see the "Suspension region" row above. | `suspensionRegionOf` (`host/TopologyWalks.kt`) |

Two more `*Frontier` names are policy/record types owned by a specific host
structure, not additional senses: `InletFrontier` (`port/FanInlet.kt`) is the
policy interface a fan-inlet installs to opt into the wave-completeness fold
above; `FrontierRecord` (`host/HostDurability.kt`) is the durable on-disk
record of an `Effectful` inlet's processed-frontier advance — a watermark-sense
value, persisted.

## Naming conventions (normative)

- New packages use `cell`, `port`, `link`, `host`, `proxy`, `data` — not
  `computelet`, not `task`. The incubation package `germ` has been renamed to
  `civictech.cell` (with `.port`, `.proxy`, `.host`, `.data`, `.consistency`
  sub-packages); the legacy `civictech.kernel.*` / `civictech.runtime.*`
  generation (`:legacy`) was deleted after the G-3 color port (G-1, done M3.1).
- Port properties are nouns describing role (`managementInlet`, `dataOutlet`).
- Contracts are interfaces named for the capability (`SetOps`, `Propagate`,
  `TrafficLightControl`), never `*Message` classes.
