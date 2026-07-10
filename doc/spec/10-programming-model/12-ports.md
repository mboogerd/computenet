# 12 — Ports

> **Status**: Specified (core); multiplex and cardinality enforcement partial
> **Sources**: ADR — Computelet Kernel, ADR — Task Connectivity, ADR — Anatomy of Cellular Programs, ADR 3
> **Implementation**: `civictech.cell.port.*` (Port, Inlet, Outlet, FanInlet, FanOutlet, OneToOnePort, Serve, Use, Subscribe, LinkTo, LinkFrom, delegates)

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
*(G-11 resolved, M5.1 + M9.1)*: `@Contract(management = true/false)` marks
every port contract, the flag rides the generated `ContractDescriptor`, and
the KSP processor **fails compilation** when a data contract (management =
false) declares a non-Unit return — push-only is enforced, not advised.
Effect classification for shadow mode is the `Effectful` cell marker (52,
G-32), orthogonal to the contract flag.

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

*(G-12 phase 1: the handshake protocol of 10/13 is implemented —
`linkTo(LinkFrom)` returns `LinkResult`, point-to-point ports reject at
capacity instead of throwing, and per-port `onLink`/policies can reject.
Ownership-based cardinality landed in M5.6: `FanOutlet` reads the generated
exclusive bit and refuses a second subscriber on `Owned`/`Leased`-carrying
contracts — rule 2 above is enforced, 20/23.)*

## Multiplexed ports and generic protocols

The kernel ADR requires ports that carry **several protocols at once** —
generic protocols (attention propagation 30/34, time/consistency requests
20/22, link management 10/13) stacked beside the cell-specific contract, with
composable handlers.

⚠ GAP (G-13, minimal form landed M6.1): `cell.port.ProtocolSupport` gives any
port sub-channels keyed by well-known `ProtocolId`s, sharing the port's
existing links (which now carry in-process endpoint objects); one map lookup
per delivery (P2), handlers outside cell logic. Attention (34) and suspension
notices ride it. Remaining: full multiplex *data* sub-ports (several data
protocols on one link/queue slot), the state-request protocol (21, G-18
residual), and wire transport for generic protocols (bridged links have no
endpoint objects).

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
