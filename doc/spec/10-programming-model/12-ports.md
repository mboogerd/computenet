# 12 — Ports

> **Status**: Specified (core); multiplex and cardinality enforcement partial
> **Sources**: ADR — Computelet Kernel, ADR — Task Connectivity, ADR — Anatomy of Cellular Programs, ADR 3
> **Implementation**: `civictech.kernel.germ.port.*` (Port, Inlet, Outlet, FanInlet, FanOutlet, OneToOnePort, Serve, Use, Subscribe, LinkTo, LinkFrom, delegates); legacy `civictech.kernel.port.*`

## Definition

A **Port** is a named, typed crossing point on a cell. Everything that enters
or leaves a cell passes through a port. Ports make topology visible,
linkable, checkable, and enforceable.

A port declares:

- a **contract** — the interface it carries;
- a **direction** — inlet (receives / serves) or outlet (sends / uses);
  bidirectional exchange is modeled as *two* ports;
- a **cardinality** — how many links it accepts (fan-in, fan-out, one-to-one);
- optionally, **policies** and link-time validation (see 10/13).

## Contracts are push-only interfaces

A contract is a plain interface whose methods are **push-only** (no return
values on the fast path). Examples from code: `Consumer<T>` (`provide`),
`Propagate<D>` (`propagate`), `SetOps<E>` (`add`/`remove`),
`TrafficLightControl` (`setGreen`/`setRed`), and the host's own
`HostManagementApi` / `HostRoutingApi`.

Rationale (ADR 3): push-only methods have trivially serializable invocations
(10/14) and impose no synchronous coupling. Request/response is expressed as a
pair of push contracts or a completion callback carried in the payload.

Methods that *do* return values (e.g. `spawn(cell): CellRef`) are permitted on
**management** contracts, where the implementation may internally block on a
future (see 30/31) — never on data-path contracts.
⚠ GAP (G-11): the spec needs a typed marker separating management contracts
(returns allowed, may block) from data contracts (push-only). *Proposal*: a
`@DataPath`-checked annotation or a lint rule in the KSP processor.

## The Inlet/Outlet duality

Each port has two faces — what the owning cell does with it, and what the rest
of the graph does with it:

| Port | Cell-internal face | External face |
|---|---|---|
| **Inlet** | `serve(impl)` / `delegate(outlet)` — provide the implementation | `Use` — call `.call.method(...)` or `use { ... }` |
| **Outlet** | `Use` — invoke `.call.method(...)` to emit | `Subscribe` / `LinkFrom` — receive what is emitted; `linkTo(...)` connects onward |

This is the Link-and-Lease model of the Task Connectivity ADR mapped onto the
germ code: `Serve`, `Use`, `Subscribe`, plus `Invalidating` for lease
invalidation (10/14 specifies the semantics precisely).

Declaration styles in code today:

```kotlin
// delegate style — discoverable by hosts via reflection
val inlet by input<Consumer<A>>()
val outlet by output<Consumer<B>>()

// explicit style — used by data cells and API-first cells
override val inlet = FanInlet.create<SetOps<E>>()
override val outlet = FanOutlet.create<Propagate<SetDelta<E>>>()
```

A cell SHOULD also expose an **API interface** naming its ports (e.g.
`SetApi<E>`, `TrafficLightApi<T>`, `Host`), because cross-host proxies are
derived from that interface (`host.lookup<CollectingConsumerInterface>(ref)`),
see 40/41.

## Cardinality

Port implementations encode cardinality:

- `FanInlet<T>` — many users may call; one served implementation.
- `FanOutlet<T>` — one caller (the owning cell); many subscribers; emission is
  broadcast.
- `OneToOnePort<T>` — exactly one counterpart; required for `Owned`/`Leased`
  payloads (20/23) and safe mutable-state transfer.

Rules (normative):

1. Cardinality MUST be enforced at link time, not send time (P2, P5).
2. Fan-out MUST be rejected at link time when the contract's payload types
   carry exclusive ownership (`Owned`, `Leased`) — see 20/23.
3. Multi-producer inlets are permitted only where the cell declares merge
   semantics (e.g. `UnionSetCell` ref-counting) — "unions may explicitly
   allow multiple producers".

⚠ GAP (G-12): germ Fan ports do not currently *enforce* anything at link time
(no handshake, no rejection path — `linkTo` always succeeds). The legacy
package has `PortCardinality` and the Task Connectivity ADR has
`LinkResult.Rejected`. *Proposal*: adopt the handshake protocol of 10/13 in
germ ports; enforcement lives in `linkTo`/`onLink`.

## Multiplexed ports and generic protocols

The kernel ADR requires ports that carry **several protocols at once** —
generic protocols (attention propagation 30/34, time/consistency requests
20/22, link management 10/13) stacked beside the cell-specific contract, with
composable handlers.

⚠ GAP (G-13): not present in germ. The legacy `MultiplexPort` sketch
(`asInlet(protocolId)` / `asOutlet(protocolId)`) exists only as an interface.
*Proposal*: model a multiplex port as a bundle of sub-ports keyed by
`ProtocolId`, sharing one link and one queue slot; generic protocols get
well-known ids. This preserves P2 (no per-message dispatch cost beyond one
table lookup) and keeps composability out of cell logic.

## Directionality of generic protocols

Directionality exists so generic protocols know how to propagate without
inversion: e.g. attention flows **upstream** (consumer → producer) along links
whose data flows downstream. Every link therefore knows its data direction,
and each generic protocol declares whether it travels with or against it.

## What ports are not

- Not mailboxes: a port never stores messages in steady state (buffering is a
  suspension-time behavior, 30/33).
- Not RPC endpoints: no synchronous request/response on the data path.
- Not security boundaries by themselves: policies and membranes govern them
  (10/13, 40/43).
