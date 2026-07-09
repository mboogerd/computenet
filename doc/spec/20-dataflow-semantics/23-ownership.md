# 23 — Payload Ownership Contracts and the SPSC Rule

> **Status**: Specified (semantics clear); unimplemented
> **Sources**: ADR — SPSC link requirement
> **Implementation**: none (no ownership types, no link-time validation)

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

## Proposal for implementation (G-21)

Phase 1 (types only): marker wrappers + conventions, documented; no
enforcement. Cheap, immediately usable by data cells.
Phase 2: link-handshake inspection once G-12 lands (KSP can emit the
"contract carries exclusive types" bit into generated port metadata, avoiding
runtime reflection).
Phase 3: pooling (`Leased`) with host-integrated release-on-drain, only when a
performance-critical use case exists — pools before profiling would violate
"complexity only where paid for".
