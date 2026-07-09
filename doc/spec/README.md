# ComputeNet Specification (Draft)

This is the draft specification for **ComputeNet**: a unified, decentralized dataflow
graph framework built from Cells connected through explicit Ports and Links.

It consolidates the ADRs under `doc/adr/` into a single coherent structure, ordered
from **broad (why)** → **what** → **narrow (how)**. Where the ADRs are inspirational
or mutually inconsistent, this spec says so explicitly and proposes a way forward
compatible with the established vision and constraints.

## How to read this spec

Read in numerical order for the full picture; each file is also self-contained
enough to be read on its own. Every document carries a header block:

| Field | Meaning |
|---|---|
| **Status** | `Specified` (implementable as written) · `Partial` (core specified, edges open) · `Exploratory` (direction set, not yet implementable) |
| **Sources** | The ADRs this document consolidates |
| **Implementation** | Where (if anywhere) the current code realizes this, as of the `germ` iteration |

Normative language: **MUST** / **SHOULD** / **MAY** are used in the RFC-2119 sense.
Open issues are marked inline as `⚠ GAP` (missing decision) or `⚠ CONFLICT`
(ADRs or code disagree), each with a proposed resolution. All gaps and conflicts
are consolidated in [90-roadmap/91-gap-analysis.md](90-roadmap/91-gap-analysis.md).

## Structure

### 00 — Foundations (broad / why)
- [01-vision.md](00-foundations/01-vision.md) — the problem, the niche, and what success looks like
- [02-design-principles.md](00-foundations/02-design-principles.md) — the invariant constraints every design decision must satisfy
- [03-glossary.md](00-foundations/03-glossary.md) — canonical terminology (resolves Task/Computelet/Cell and Runner/Host drift)

### 10 — Programming model (what)
- [11-cells.md](10-programming-model/11-cells.md) — Cells: state, logic, identity, hierarchy, membranes, policies
- [12-ports.md](10-programming-model/12-ports.md) — Ports: contracts, direction, cardinality, Inlet/Outlet duality
- [13-links.md](10-programming-model/13-links.md) — Links: handshakes, cardinality enforcement, policy hooks
- [14-invocations.md](10-programming-model/14-invocations.md) — Invocations, proxies, and delegation (the Link-and-Lease model)
- [15-lifecycle.md](10-programming-model/15-lifecycle.md) — Cold/Hot phases, activation, spawning

### 20 — Dataflow semantics (what, precisely)
- [21-propagation.md](20-dataflow-semantics/21-propagation.md) — push/pull, incremental/complete, delta algebra
- [22-consistency.md](20-dataflow-semantics/22-consistency.md) — MessageContext, local glitch-freedom, topology versioning
- [23-ownership.md](20-dataflow-semantics/23-ownership.md) — Borrowed/Owned/Leased/Frozen payload contracts and the SPSC rule
- [24-data-cells.md](20-dataflow-semantics/24-data-cells.md) — standard stateful cells, merge semantics, partitioning

### 30 — Execution model (how, locally)
- [31-hosts.md](30-execution-model/31-hosts.md) — Hosts: single-consumer queues, priorities, the host protocol
- [32-concurrency-colors.md](30-execution-model/32-concurrency-colors.md) — Pure/Blocking/Suspending, coercion, bridges
- [33-mobility.md](30-execution-model/33-mobility.md) — suspension and migration as link manipulation
- [34-scheduling.md](30-execution-model/34-scheduling.md) — attention-driven execution and cycle throttling

### 40 — Distribution (how, globally)
- [41-location-transparency.md](40-distribution/41-location-transparency.md) — serializable invocations across JVMs and machines
- [42-replication.md](40-distribution/42-replication.md) — interest-driven replication and topology formation
- [43-security.md](40-distribution/43-security.md) — membranes as authority, isolation, policies

### 50 — Development process (why again, at scale)
- [51-construction.md](50-development-process/51-construction.md) — building cellular programs; DSL and codegen
- [52-verification.md](50-development-process/52-verification.md) — invariant-driven testing, live invariants
- [53-evolution.md](50-development-process/53-evolution.md) — deployment as evolutionary selection

### 90 — Roadmap
- [91-gap-analysis.md](90-roadmap/91-gap-analysis.md) — every `⚠ GAP` / `⚠ CONFLICT`, consolidated with proposals
- [92-way-forward.md](90-roadmap/92-way-forward.md) — proposed milestones from current code to the vision

## ADR → spec map

| ADR | Consolidated primarily into |
|---|---|
| ADR 0 — Principles and motivations | 00/01, 00/02 |
| ADR 1 — Collaborative dataflow graph abstraction | 00/01, 20/21, 30/34, 40/42 |
| ADR 2 — Coroutines and Virtual Threads | 30/32 |
| ADR 3 — Lightweight and serializable port invocations | 10/14, 40/41 |
| ADR — Anatomy of Cellular Programs | 10/11, 10/12, 40/43 |
| ADR — Cellular Software Development Process | 50/51–53 |
| ADR — Computelet Kernel | 10/11–15, 30/31 |
| ADR — Computelet Mobility | 30/33 |
| ADR — Blocking Mailbox *(superseded)* | 30/33 (history only) |
| ADR — Glitch Freedom | 20/22 |
| ADR — SPSC link requirement | 20/23 |
| ADR — Task Connectivity | 10/12–14, 20/22 |
| ADR — Task Definitions | 10/15 |

## Relationship to the code

The codebase contains two generations:

1. **Legacy** (`civictech.kernel.computelet|port|link|host|protocol`,
   `civictech.runtime.blocking|suspending`) — implements the ADR 2 color model and
   the earlier port/link kernel. Treated here as historical evidence, not as the
   normative base.
2. **Current** (`civictech.cell`, `civictech.cell.data`) — the living
   kernel: `Cell`/`onActivate`, port delegates, Fan ports, `Invocation`-based
   routing, `ManagedHost`, cross-host proxies, delegation, buffering
   (traffic-light) cells, delta-propagating data cells.

This spec is written against the **germ** iteration and states per section what
exists, what must be migrated from legacy, and what is unbuilt.
