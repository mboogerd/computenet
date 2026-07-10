# 23 — Payload Ownership Contracts and the SPSC Rule

> **Status**: Implemented through phase 2 (M5.6); pooling (phase 3) deliberately unbuilt
> **Sources**: ADR — SPSC link requirement
> **Implementation**: `cell.Ownership` (Borrowed/Owned/Leased/Frozen); exclusive bit in generated `MethodDescriptor`s; `FanOutlet` link/subscribe enforcement; `Broadcast` refusal; `BridgeEgressCell` boundary rules

## Motivation

On hot paths (HFT-inspired, P2), copying and defensive immutability are too
expensive; in-place mutation of pooled or transferred buffers is required.
That is only safe if **sharing is impossible by construction** (P5).

## The four contracts

Encoded at the type level on payloads:

| Contract | Semantics | Fan-out safe? |
|---|---|---|
| `Borrowed<T>` | Read-only, temporary snapshot view; valid only during the invocation | ✅ |
| `Owned<T>` | Ownership transfer; consumed exactly once; receiver may mutate/retain | ❌ |
| `Leased<T>` | Exclusive mutable access from a shared pool; must be released | ❌ |
| `Frozen<T>` | Immutable form of a previously owned value | ✅ |

Typical flows: `Owned` → mutate in place along a pipeline; `Owned` →
`freeze()` → `Frozen` fan-out to many readers; `Leased` from a buffer pool →
fill → hand off → release.

## The SPSC link rule (normative)

A link whose contract carries `Owned` or `Leased` arguments MUST have exactly
one downstream consumer (unary edge). Enforcement points:

1. **Link time** (primary): the handshake (10/13) inspects the contract's
   payload types; a second link to an `Owned`/`Leased`-carrying outlet is
   `Rejected`. `OneToOnePort` is the natural carrier.
2. **Graph validation** (secondary): whole-graph check before/at activation.
3. The rule constrains the graph-building API and scheduler only — core
   port/link interfaces are unchanged.

Corollaries:

- `Owned`/`Leased` payloads MUST NOT cross into a `Broadcast` proxy; the
  Buffering proxy MAY hold them (buffering preserves exclusivity).
- Suspension/migration must preserve exactly-once: a parked `Owned` message is
  still owned by the link (30/33 drain rules).
- Cross-machine transfer of `Owned` degenerates to move-by-serialize
  (serialize + drop sender's reference); `Leased` MUST NOT cross machine
  boundaries (a lease on a remote pool is meaningless) — freeze or copy first.

## Implementation (G-21, M5.6)

Phase 1 — done: `cell.Ownership` marker wrappers. `Owned.take()` is
consume-once (use-after-move throws); `Owned.freeze()` is the fan-out path.
Phase 2 — done: the KSP contract processor scans parameter types (recursively
through type arguments) for `Owned`/`Leased` and emits the exclusive bit into
the generated `MethodDescriptor` — enforcement reads metadata, never
reflection. Enforcement points, one funnel each:
- **`FanOutlet.subscribe`** — where every attach path converges (handshake
  installs, `Use.fixed` links, cross-host and bridge links): a second
  subscriber on an exclusive contract fails; the handshake wrapper returns
  `Rejected` rather than throwing (source-side, mirroring `Outlet`'s
  cardinality style). "Rejectable everywhere" holds by construction.
- **`Broadcast` proxy** — refuses `Owned`/`Leased` payloads when fanning to
  more than one target.
- **`BridgeEgressCell`** — `Leased` is refused at the machine boundary;
  `Owned` crosses as move-by-serialize (the sender's wrapper is consumed as
  the frame is encoded; `Owned`/`Frozen`/`Borrowed` are wire-registered
  payloads). Boundary rules are send-time checks — the boundary is where the
  knowledge lives.
Phase 3 — pooling (`Leased`) with host-integrated release-on-drain stays
unbuilt until a performance-critical use case exists — pools before profiling
would violate "complexity only where paid for".
