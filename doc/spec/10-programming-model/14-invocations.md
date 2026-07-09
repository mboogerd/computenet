# 14 — Invocations, Proxies, and Delegation (Link-and-Lease)

> **Status**: Specified (this is the most implemented part of the system)
> **Sources**: ADR 3, ADR — Task Connectivity; supersedes the lambda-first `Use.use` design
> **Implementation**: `germ.proxy.Invocation`, `Proxy`, `HostedCellProxy`, `HostProxy`, `HostedPortInvocation`, `Buffering`, `Broadcast`, `NoOp`, `Throwing`, `Callback`; `germ.port.Serve/Use/Subscribe/Invalidating`

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
   serialized and carried by the transport (40/41). ⚠ GAP (G-15): no network
   transport exists yet; the serialized form (stable method ids, argument
   encoding) is not yet pinned down. KSP-generated proxies/serializers are the
   intended mechanism (the `gen` module's `SerializerProcessor` is the seed).

`HostedPortInvocation.Type` distinguishes `PORT_API` (data path) from
`PORT_MANAGEMENT` (operations on the port object itself) — keep this split; it
is the wire-level reflection of the data/management contract split (12).

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
  of re-definitions, not the number of messages (P2).
- Chains of delegation MUST flatten on resolution (resolve to the final
  implementation, not a chain of forwarders).

The Traffic-Light cell demonstrates the idiom: `setRed()` → serve a
`Buffering` proxy (park invocations); `setGreen()` → replay buffer, then
`delegate(dataOutlet)` so subsequent traffic flows with **zero** added
indirection. This same idiom is the suspension primitive (30/33).

### Standard proxy behaviors (implemented)

`Buffering` (park invocations), `Broadcast` (fan-out to many uses), `NoOp`,
`Throwing` (fail fast when unlinked), `Callback`. These compose with
serve/delegate to express boundary behaviors without touching cell logic.

## Context propagation

Task Connectivity requires every invocation to carry a `MessageContext`
(logical timestamp + source port) — transparently flowing inlet → outlet when
a cell emits in response to receiving (20/22 defines the semantics).

⚠ GAP (G-4): germ `Invocation` has **no context field** and germ `Use` has no
context parameter. Without it, glitch-freedom (20/22) and several membrane
behaviors are unimplementable.
*Proposal*: add `context: MessageContext` to `Invocation`; hosts stamp it at
capture time; within a host, an implicit "current context" (host-thread-local,
set while executing an invocation) provides inlet→outlet flow without
polluting contract signatures. Note the ADR's own caveat: delegation is
*not* a context no-op — even an unchanged implementation must present the new
source port in context.

## Reflection budget

⚠ CONFLICT (C-5): `Invocation.of(method, args)` currently holds a
`java.lang.reflect.Method`, `Proxy.fromClass` uses JDK dynamic proxies, and
`ManagedHost.findPort` resolves ports reflectively. ADR 3 forbids reflection
artifacts **in the serialized form** and prefers KSP/Poet generation for KMP
compatibility and speed.
**Resolution**: reflection is acceptable *inside a JVM process* as the interim
mechanism; the serialized form MUST use stable method ids (name + signature
hash or ordinal from generated tables). KSP-generated proxies (extending the
`gen` module) replace dynamic proxies where profiling or KMP demands it.
Port discovery should move from reflection to the delegate registry created by
`by input()/by output()` (10/15).
