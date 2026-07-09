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
| **Organelle** | A cell nested inside another cell (hierarchical composition). | 10/11 |
| **Membrane** | The conceptual boundary of a cell: the locus of cross-port rules, policies, authority, and observability. Part of the cell in the developer model; separable in the runtime model. | 10/11, 40/43 |
| **Port** | A named, typed crossing point on a cell. Has a direction and cardinality. The unit the topology connects. | 10/12 |
| **Inlet** | The receiving side: the cell *serves* an API implementation on it; external parties *use* it. | 10/12 |
| **Outlet** | The sending side: the cell *uses* it; external parties *subscribe*/link it onward. | 10/12 |
| **Contract** | A plain interface of **push-only** methods that a port carries (e.g. `Consumer<T>`, `SetOps<E>`, `Propagate<D>`). Bidirectional exchange = two contracts, one per direction. | 10/12 |
| **Link** | A first-class directional connection between an outlet and an inlet, created by an explicit handshake. | 10/13 |
| **Invocation** | A serializable capture of one method call on a contract (stable method identification + arguments). The unit that crosses boundaries. | 10/14 |
| **Serve / Use / Subscribe / Delegate / Invalidate** | The five primitive port operations of the Link-and-Lease model. | 10/14 |
| **Host** | A Cell that hosts other cells: manages lifecycle (spawn/activate), owns the single-consumer queue, executes invocations, enforces isolation. | 30/31 |
| **Color** | Execution style of logic and hosts: 🟢 Pure, 🔵 Blocking, 🟣 Suspending. | 30/32 |
| **Bridge** | A unidirectional adapter carrying invocations between hosts of different colors. | 30/32 |
| **Delta** | An incremental state transition propagated along links; the default payload style. | 20/21 |
| **MessageContext** | Metadata traveling with invocations: logical timestamp / wave id + source port. Required for glitch-freedom. | 20/22 |
| **Glitch-free frontier** | The nearest upstream set of glitch-free cells at which dependency tracking may stop. | 20/22 |
| **Ownership contract** | Payload sharing semantics: `Borrowed`, `Owned`, `Leased`, `Frozen`. | 20/23 |
| **Suspension** | Isolating a cell/host from the graph by unlinking (buffering at the boundary). | 30/33 |
| **Migration** | Unlink → move → relink of a host's cells to another host. | 30/33 |
| **Attention / Interest** | A subscription-derived signal that drives scheduling and replication. | 30/34, 40/42 |
| **Policy** | A constraint governing port use or linking (authz, rate, schema, quota). | 10/13, 40/43 |
| **Invariant** | A property that must hold across all valid executions; the primary verification artifact. | 50/52 |

## Naming conventions (normative)

- New packages use `cell`, `port`, `link`, `host`, `proxy`, `data` — not
  `computelet`, not `task`. The incubation package `germ` has been renamed to
  `civictech.cell` (with `.port`, `.proxy`, `.host`, `.data`, `.consistency`
  sub-packages); the legacy `civictech.kernel.computelet|port|link|host|protocol`
  and `civictech.runtime.*` packages are quarantined in the `:legacy` module
  pending the G-3 color port (90/91, G-1).
- Port properties are nouns describing role (`managementInlet`, `dataOutlet`).
- Contracts are interfaces named for the capability (`SetOps`, `Propagate`,
  `TrafficLightControl`), never `*Message` classes.
