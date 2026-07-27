# 14 — Invocations, Proxies, and Delegation (Link-and-Lease)

> **Status**: Specified (this is the most implemented part of the system)
> **Sources**: ADR 3, ADR — Task Connectivity; supersedes the lambda-first `Use.use` design
> **Implementation**: `cell.proxy.Invocation`, `Proxy`, `HostedCellProxy`, `HostProxy`, `HostedPortInvocation`, `Buffering`, `NoOp`, `Callback`; `cell.port.Serve/Use/Subscribe` (`Invalidating` is specified, unimplemented — see below)

## The core move (ADR 3)

Port interaction is **calling methods on the port's contract**; the framework
captures each call as a serializable **Invocation**:

```kotlin
// preferred call styles
outlet.call.provide(x)          // direct, static, discoverable
outlet.use { provide(x) }       // lease-scoped block (local sugar; see below)
```

An `Invocation` contains stable method identification plus arguments — and
MUST NOT contain lambdas or `java.lang.reflect.Method` in serialized form
(P9). It can be applied to any implementation of the contract:
`invocation.invoke(target)`.

This replaces the earlier lambda-based `Use.use { ... }` as the *primary*
mechanism. Rationale: lambdas don't serialize across JVMs; transactions are
not the framework's core (dataflow is); direct methods are more discoverable;
the assumed runtime savings of lambda-passing never materialized.

### ⚠ CONFLICT (C-3, resolved here): `use { }` vs `call`

Task Connectivity specifies `use(context) { block }` as the lease API; ADR 3
argues to move away from lambda blocks; germ code has both (`outlet.use {}`
in `MapperCell`, `.call` everywhere else).

**Decision**: `.call` (proxy capture) is the canonical mechanism and the only
wire-level one. `use { }` is retained as **local-only sugar** whose value is
(a) lease scoping — discouraging callers from caching stale implementations,
and (b) a future carrier for context binding (20/22). A `use {}` block is
compiled/executed as a sequence of `.call` invocations; it is never
serialized as a lambda.

## Dispatch tiers (normative)

The same invocation takes the cheapest possible path (P2):

1. **Local, same host**: the `Use` resolves to the actual served
   implementation → **zero-cost direct method call** (after lease resolution).
2. **Cross-host, same process**: a proxy captures the call into an
   `Invocation`, wrapped as `HostedPortInvocation(cellRef, portName, type)`
   and enqueued on the target host's queue (`ManagedHost.enqueueHostedInvocation`).
3. **Cross-process / cross-machine**: same capture, but the invocation is
   serialized and carried by the transport (40/41). ⚠ GAP (G-15, narrowing):
   the serialized form is pinned (M5.2): `cell.wire.WireFrame` — versioned,
   ids-only (`contractId`/`methodId` from the generated tables), arguments as
   `@SerialName`-discriminated polymorphic kotlinx values, context riding
   along. Transport is M5.3–M5.5.

`HostedPortInvocation.Type` distinguishes `PORT_API` (data path) from
`PORT_MANAGEMENT` (operations on the port object itself) — keep this split; it
is the wire-level reflection of the data/management contract split (12).

A third dispatch class is decided design, unimplemented (decided in
[93 I-1](../90-roadmap/93-feature-interactions.md)): `Type` MUST gain
`PORT_PROTOCOL` for framework-owned generic protocols (attention,
state-request, link-management) riding an established link as a metadata
plane, and dispatch becomes a three-way branch — `PORT_API` → the data path
(unchanged), `PORT_MANAGEMENT` → the port's management API, `PORT_PROTOCOL` →
`ProtocolSupport.deliver(protocolId, link, msg)`, one map lookup beside the
existing type branch. Protocol invocations are management-class: null
`MessageContext`, own ordering lane, never the data FIFO. The shipped enum
remains the two variants above.

## The Link-and-Lease model

Five primitive operations define delegation and cache-invalidation semantics:

```kotlin
fun interface Invalidating { fun invalidate() }

interface Use<Api> {
    val call: Api                          // leased proxy or direct impl
    fun <R> use(block: Api.() -> R): R     // scoped lease (local sugar)
    fun attach(observer: Invalidating)     // subscribe to re-resolution events
    fun detach(observer: Invalidating)
}

interface Serve<Api> : Invalidating {
    fun serve(provider: Api)               // define the implementation
    fun delegate(upstream: Use<Api>)       // O(1) rebind: bypass this cell
}
```

Semantics (normative):

- **serve** installs the implementation an inlet presents. Serving anew
  *invalidates* downstream leases.
- **delegate** rebinds an inlet directly to another outlet's `Use`, collapsing
  the indirection: after delegation, callers' next lease resolves through to
  the delegate target — the delegating cell is *not* on the per-message path.
- **invalidate** is O(1): it marks leases stale; **re-resolution is lazy**,
  performed on next use. Cost of behavior change is proportional to the number
  of re-definitions, not the number of messages (P2). **(specified,
  unimplemented)** — no `Invalidating` type or `invalidate()` method exists in
  `kernel/src/main`; today's `serve` re-registration is the only invalidation
  path (see the glossary's `Invalidate` row, 00/03).
- Chains of delegation MUST flatten on resolution (resolve to the final
  implementation, not a chain of forwarders). This rule governs `delegate`
  chains only: a membrane's Mediate crossing (10/11) is `serve(proxy)` — a
  real cell on the per-message path, identical in kind to the red
  Traffic-Light below — so mediation leaves the flatten rule untouched
  (decided in 93 I-10).

The Traffic-Light cell demonstrates the idiom: `setRed()` → serve a
`Buffering` proxy (park invocations); `setGreen()` → replay buffer, then
`delegate(dataOutlet)` so subsequent traffic flows with **zero** added
indirection. This same idiom is the suspension primitive (30/33).

### Standard proxy behaviors (implemented)

`Buffering` (park invocations), `NoOp`, `Callback`. These compose with
serve/delegate to express boundary behaviors without touching cell logic.
(`Broadcast` and `Throwing` were removed as dead alternatives — remediation
T03 — superseded by `FanOutlet`'s live fan-out and `FanInlet`'s cold-park
model respectively; see `91-gap-analysis.md`.)

## Context propagation

Task Connectivity requires every invocation to carry a `MessageContext`
(logical timestamp + source port) — transparently flowing inlet → outlet when
a cell emits in response to receiving (20/22 defines the semantics).

*(G-4 resolved: `Invocation.context` carries `MessageContext`; outlets stamp
at emission (fresh wave when spontaneous, same-timestamp/rewritten-sourcePort
when reactive); `CurrentContext` is the implicit thread-local, and
`Invocation.invoke` is the single restore point — delivery and buffered replay
run under the invocation's own context, management calls under none. Contract
signatures stay unpolluted. The ADR's caveat holds: every outlet hop rewrites
the source port, so delegation presents the correct new port.)*

## Reflection budget

**Resolved (C-5, W4.6)**: `Proxy.fromClass` used JDK dynamic proxies. ADR 3
forbids reflection artifacts **in the serialized form** and prefers KSP/Poet
generation for KMP compatibility and speed.
**Resolution**: the serialized form MUST use stable method ids (name +
signature hash or ordinal from generated tables). Every `@Contract` interface
now gets a KSP-generated proxy class (extending the `gen` module's
`ContractProcessor`), so in-process cell API dispatch no longer needs
`java.lang.reflect.Proxy.newProxyInstance` — `civictech.gen.wire.ProxyRegistry`
resolves the ahead-of-time-compiled proxy class for a `@Contract` interface,
falling back to a runtime dynamic proxy only for interfaces outside the
`@Contract` surface (the cross-host structural navigation proxies
`HostedCellProxy`/`HostProxy` walk arbitrary `Cell`/`Port` resource types —
tier 2/3 dispatch, not a fixed method-dispatch contract).

*(M5.1: the stable ids exist. `@Contract`-annotated port interfaces get
KSP-generated `ContractDescriptor` tables — `contractId = hash(FQN)`,
`methodId = hash(FQN#name + erased JVM signature)`, FNV-1a 64 — collected via
ServiceLoader into `gen.wire.ContractRegistry`. `Invocation.of` resolves and
carries `contractId`/`methodId` at capture; the M5.2 wire form serializes only
those ids. W4.6: each `@Contract` interface also gets a generated proxy class
dispatching through the same `InvocationHandler` shape the existing proxy
behaviors (`Buffering`, `NoOp`, `Callback`, ...) already used, registered via
`gen.wire.ProxyRegistry` — in-process dispatch
for the `@Contract` surface is reflection-free at proxy *construction* time;
`Invocation.invoke`'s per-call dispatch remains a reflective `Method.invoke`.)*
