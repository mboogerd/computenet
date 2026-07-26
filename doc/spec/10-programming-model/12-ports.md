# 12 — Ports

> **Status**: Specified (core); multiplex and cardinality enforcement partial (metadata plane, link roles, effect axis, and descriptor bits decided in [93](../90-roadmap/93-feature-interactions.md), unimplemented)
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

## Port identity is replay-stable

A port carries a `PortRef` — the identity links resolve to and the identity an
outlet stamps onto `MessageContext.sourcePort` at emission. For a port owned by
a **hosted cell** this ref is **derived**, not random: at registration time
(the one seam that knows both the owning `CellRef` and the registered port
name) the ref is set to

```
PortRef.of(cellRef, name) = nameUUIDFromBytes("port:$name:${cellRef.id}:${cellRef.instanceId}")
```

— the same M10.1 derivation that gives `SetCell.tagSource`, `MintedTags`, and
the replication watermark ref their restart-stable identity. A cell rebuilt or
recovered with the same `(cellRef, name)` therefore re-mints the exact `PortRef`
the network already observed, so a wave stamped before a restart still matches
its edge in the rebuilt graph (the durable plane keys on `(cellRef, portName)`;
`sourcePort` now keys the same thing). Anonymous ports — `Use.fixed` endpoints,
ad-hoc test scaffolding, and any port not registered on a `Cell` — are never
stamped and keep the fresh random ref minted at construction (PN-1).

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
Effect classification for shadow mode is today the `Effectful` cell marker
(52, G-32), orthogonal to the contract flag. `@Contract(effect = true)` is a
**third classification axis** (decided in 93 I-17, unimplemented): it marks a
world-touching boundary contract (e.g. `DbWriter.persist`), emitted by the
same KSP scan as `management`. Shadow suppression MUST cut at
`effect = true` boundary contracts — never at a cell's data inlets — so
interior cells keep emitting the derived deltas a judge needs; this amends
the landed cell-granularity rule (G-32's NoOp-serve of every fan-in inlet of
an `Effectful` cell as the only suppression mode). The `Effectful` cell
marker is retained as the coarse fallback for opaque, non-portable I/O
inside served logic: such a cell is replaced wholesale in shadow mode and
terminates judgeability downstream of itself.

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
- `FanOutlet<T>` — [12-FANOUT-01] one caller (the owning cell); many subscribers; emission is
  broadcast.
- `OneToOnePort<T>` — [12-CARD-02] exactly one counterpart; required for `Owned`/`Leased`
  payloads (20/23) and safe mutable-state transfer.

Rules (normative):

1. [12-CARD-01] Cardinality MUST be enforced at **structural admission**, never send time
   (P2, P5). "Link time" decomposes into *admission* — structural, reading
   only the port's descriptor (contract, cardinality, exclusive bit,
   policies), binding from construction in any lifecycle phase — and
   *activation*, behavioral, gated on the cell's handler establishment
   (decided in 93 I-26; the refinement matches the shipped descriptor-driven
   enforcement).
2. [12-EXCL-01] Fan-out MUST be rejected at link time when the contract's payload types
   carry exclusive ownership (`Owned`, `Leased`) — see 20/23. This refusal
   binds **Consume** links only (decided in 93 I-20, unimplemented): a
   downstream attachment is either a *Consume* link (receives the declared
   payload form, bears the consume-once/release obligation, counted by the
   exclusive funnel) or an *Observe* link / tap (receives a KSP-generated
   `Borrowed` projection valid only for the emitting invocation, never
   counted). `FanOutlet.tap(observer)` — equivalently a
   `LinkRole { Consume, Observe }` on the 10/13 handshake — is the decided
   surface.
   ⚠ EARS-GAP: the Consume/Observe scoping is decided but unimplemented; the
   currently landed behavior (M5.6) rejects *any* second subscriber on an
   `Owned`/`Leased`-carrying outlet, not only a second Consume link — unclear
   which behavior a scenario should assert against until Observe/tap ships.
3. [12-FANIN-01] Multi-producer inlets are permitted only where the cell declares merge
   semantics (e.g. `UnionSetCell` ref-counting) — "unions may explicitly
   allow multiple producers".
   ⚠ EARS-GAP: the converse isn't stated — whether an inlet on a cell that
   does *not* declare merge semantics rejects a second producer link at
   admission, or merely admits it with unspecified resulting fold semantics.

*(G-12 phase 1: the handshake protocol of 10/13 is implemented —
`linkTo(LinkFrom)` returns `LinkResult`, point-to-point ports reject at
capacity instead of throwing, and per-port `onLink`/policies can reject.
Ownership-based cardinality landed in M5.6: `FanOutlet` reads the generated
exclusive bit and refuses a second subscriber on `Owned`/`Leased`-carrying
contracts — rule 2 above is enforced, 20/23.)*

A NoOp-served shadow inlet whose contract carries `Owned`/`Leased` MUST be a
*discharging* sink — `Owned` → `take()`-and-drop, `Leased` → `release()` —
generated from the same exclusive bit (decided in 93 I-20); the landed
shadow proxy drops such payloads undischarged (conflict C-11, recorded at
50/52 and 20/23).

Cardinality is per-instance and per-link — never a cross-replica-set budget
(decided in 93 I-25, replication machinery unimplemented). For a replicated
single-writer cell, "one writer" is a **role**: exactly one leader instance
serves the write inlet (an ordinary `FanInlet`; the host's single-consumer
queue is the serialization point) while followers serve a command-forward
delegate. Where the payload carries `Owned`/`Leased`, the leader instance
holds the one counterpart — the exclusive bit rejects a second writer link
at admission, on the leader, per instance.

The exclusive bit's KSP scan is decided to widen (decided in 93 I-6 and
I-8, unimplemented). The same contract scan also emits, on
`MethodDescriptor`: `magnitude` (a delta parameter type implements
`Magnitude`) and `idempotentMerge` (the delta type is `Replicable` with an
idempotent merge), read at link time for cycle admission (21, G-19) — cycle
throttling gates re-origination at the head's inbound feedback edge, so a
`FanOutlet` broadcast is never throttled; and `keyIndex` (`@Key` on one
parameter of a data-contract method; -1 = key-less), the routing slot for
partitioned cells (24), with a lint that **fails compilation** on a method
that is both key-less (broadcast) and exclusive — one `Owned` cannot move
to N organelles.

⚠ GAP (G-60): A dozen adopted mechanisms hang on undesigned KSP descriptor
bits and lints: protocol descriptors + registry, the
ownership-free/idempotence protocol lint, color, the effect-boundary flag
with opaque-effect detection, `@Key`, magnitude/idempotentMerge bits,
`size()` well-formedness, Eager, determinism, pull-safety, and
fallback-tier markers. Proposal: One descriptor-generation sweep extending
the M5.6 exclusive-bit scan: emit the per-type/per-contract bits into
`CellDescriptor`/`ContractDescriptor`/`ProtocolDescriptor` with stable
cross-peer ids, and fail compilation on the associated violations
(Owned/Leased in generic-protocol contracts, non-null-context expectations,
broadcast-keyed exclusives, opaque I/O outside effect boundaries, non-eager
constructor handlers, catch-up fallback on non-idempotent cells)
(93 I-1/I-5/I-6/I-7/I-8/I-15/I-16/I-17/I-26/I-27).

## Multiplexed ports and generic protocols

The kernel ADR requires ports that carry **several protocols at once** —
generic protocols (attention propagation 30/34, time/consistency requests
20/22, link management 10/13) stacked beside the cell-specific contract, with
composable handlers.

⚠ GAP (G-13, minimal form landed M6.1): `cell.port.ProtocolSupport` gives any
port sub-channels keyed by well-known `ProtocolId`s, sharing the port's
existing links (which now carry in-process endpoint objects); one map lookup
per delivery (P2), handlers outside cell logic. Attention (34) and suspension
notices ride it. The full shape is recorded (decided in 93 I-1,
unimplemented): the **link is the unit of every data concern** — contract
identity, cardinality, ownership/SPSC, handshake, policy, and the data FIFO
lane. A link carries exactly one primary data contract; a second data
contract between the same two cells is a second link — data sub-ports are
rejected. Generic protocols are a bounded, framework-owned **metadata
plane**: a third dispatch class `PORT_PROTOCOL` beside
`PORT_MANAGEMENT`/`PORT_API`, with a generated `ProtocolDescriptor` per
well-known protocol (direction, priority band, its own per-link ordering
lane, and protocol-intrinsic cardinality — FanInMerge or FanOutBroadcast,
independent of the link's) and a per-link `protocolCapabilities` set (the
intersection of both ports' declared support) negotiated at the ordinary
10/13 handshake. Protocol invocations carry null `MessageContext`, ride the
always-open inlet (bypassing data-path parking, 30/33), MUST be idempotent
and reorder/duplication-tolerant, and MUST NOT declare `Owned`/`Leased`
payloads. Wire transport is designed, not implemented: one `WireFrame` type
variant tagged with direction, upstream protocols riding the reverse bridge
path a cross-host link already maintains (G-35 below).

⚠ GAP (G-35): Generic protocols (`PORT_PROTOCOL`) cannot cross the wire and
peers cannot negotiate or version each other's protocol capability sets —
attention, saturation, state-request, and taps all stop at a bridge.
Proposal: Bridge egress/ingress gain a `PORT_PROTOCOL` frame path (one
`WireFrame` type variant, direction tag, reverse-channel realization for
upstream protocols over the reverse bridge path a cross-host link already
maintains); the link's negotiated `protocolCapabilities` generalizes to
cross-peer negotiation with a versioned ProtocolId↔contractId mapping and
downstream-only capability sets for shadow taps; verified by a generative
frame-reorder/duplication harness (cross-host attention convergence as the
first case) (93 I-1/I-4/I-17/I-9).

⚠ GAP (G-37): On-demand pull (the G-18 residual) has a decided shape but no
concrete design: descriptor, reply routing to a specific requester,
buffer-survival detection, pull storms on mesh heal, and pull-safety for
non-idempotent/effectful cells are unspecified. Proposal:
`RequestState(replyTo, since)` on the metadata plane answered by an
ordinary state-as-delta single wave, issued by the subscriber exactly when
a link goes live history-incomplete (fresh link → pull; parked-and-replayed
→ none; dropped-and-re-resolved → incremental pull with a `TagFrontier`
under a stated per-source-monotonic tag invariant); add the per-link
liveness epoch for park-vs-drop detection, a mesh-reconnect
coalescing/debounce policy, and a pull-serves-copy-only rule for
non-idempotent cells (93 I-16/I-1).

## Directionality of generic protocols

Directionality exists so generic protocols know how to propagate without
inversion: e.g. attention flows **upstream** (consumer → producer) along links
whose data flows downstream. Every link therefore knows its data direction,
and each generic protocol declares whether it travels with or against it.

Upstream protocols share one termination discipline (decided in 93 I-23,
rule R6, unimplemented): a per-traversal epoch plus a visited-edge set
bounds every flood (each edge expands at most once per epoch), and a
per-protocol terminal predicate ends it — FRONTIER terminates at sources,
glitch-free cells, and opaque membranes; STATE_REQUEST (21) at the nearest
stateful cell that can serve state-as-delta; multi-hop ATTENTION (34) at a
source or a band-quantization damper.

⚠ GAP (G-36): All metadata-plane notices are single-hop (M6): attention
retraction, upstream disinterest, Stall/Progress, and state-request pulls
do not propagate through absorbing or stateless intermediaries to distant
joins or remote producers. Proposal: One hop-by-hop re-emission rule for
metadata-plane notices with loop prevention and the band-quantization
interaction pinned per protocol — attention levels and transitive
retraction across damped hops, disinterest quiescing remote producer cones,
stall/progress watermarks reaching deep joins, and requestState forwarding
through stateless cells (93 I-1/I-4/I-9/I-16/I-18).

## Policies

A port's behaviour beyond bare dispatch is a **stack of policies**, not a set of
single slots that overwrite one another (PN-9). Policies are *installed* in any
order; they *run* in a fixed tier order. Install order is never significant —
only tier order is authoritative.

### Inlet policy tiers

An inlet routes each inbound invocation through an ordered chain of tiers:

1. **ADMIT** — may drop an invocation, **never holds** one. A dropping ADMIT
   that sits above an ALIGN tier MUST mint a metadata-plane `Progress`
   absorb-ack for every *waved* invocation it drops (the CP-A3 law): the
   dropped edge otherwise never settles and the downstream frontier stalls
   forever waiting for the contribution. A policy declares whether it satisfies
   this obligation (`mintsProgressAck`).
2. **GATE** — holds invocations FIFO (suspension / backpressure), draining in
   arrival order when released. Never drops, never reorders.
3. **ALIGN** — reorders/buffers for wave completeness (the wave frontier,
   20/22). **At most one ALIGN per inlet** — a second is rejected at install
   time.
4. **ACTIVATE** — cold-park until a handler is installed (10/15 §Admission vs
   activation). This tier is intrinsic to the port (its parked tail); it is the
   terminal of every chain, not an installable policy.

`FanInlet.at(portRef)` — targeted catch-up / pull-reply delivery — is
**policy-exempt** (PN-9 decision): it carries a topology-versioned baseline, not
a wave position, so it bypasses the chain and dispatches straight to the handler.
Routing it through ADMIT/GATE/ALIGN would be wrong — none of drop, hold, or
reorder is admissible for state transfer, and the ALIGN frontier already
releases such deliveries immediately.

### Outlet policies

An outlet composes two families:

- **FILTER** — pure `Delta → Delta` projections applied to every emission
  (broadcast and single-target alike): interest slicing ∪ disclosure
  projection, unified, with **disclosure pinned last** (a trust-scoped replica's
  effective interest = declared interest ∩ disclosure; 40/43 seam 3). Identity
  by default — byte-for-byte today's behaviour.
- **ON-LINK multicast** — late-join catch-up, pull-serve, and infrastructure
  re-announce all register as independent hooks on the link-lifecycle multicast
  (`onLinkedListeners`), so none overwrites another. Before PN-9 these shared a
  single `onLinked` slot: the last writer won, and catch-up was silently lost
  whenever any other on-link behaviour was installed.

Pull-serve (answering a `StateRequest` with a single-wave state-as-delta
baseline) and pull-on-open (issuing a `StateRequest` when an inlink opens) are
themselves installable policies — composed, not welded into the frontier or a
data cell's constructor.

## What ports are not

- Not mailboxes: a port never stores messages in steady state (buffering is a
  suspension-time behavior, 30/33).
- Not RPC endpoints: no synchronous request/response on the data path.
- Not security boundaries by themselves: policies and membranes govern them
  (10/13, 40/43).
