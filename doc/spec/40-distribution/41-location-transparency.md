# 41 — Location Transparency

> **Status**: Partial (in-process transparency implemented; wire layer unbuilt)
> **Sources**: ADR 3, ADR — Task Connectivity (§4)
> **Implementation**: `cell.proxy.HostedCellProxy`, `HostProxy`, `Invocation`, host routing; KSP seed in `gen` module

## Requirement

Linking two ports MUST behave identically whether the cells share a thread, a
process, or nothing but a network. Only cost differs (P2 tiers, 10/14):
direct call → queue hop → serialized send.

## What exists (in-process)

- `host.lookup<Api>(ref)` returns a **cell proxy** implementing the cell's API
  interface; its ports are proxy ports usable in `linkTo` exactly like local
  ones (verified: producer in host1 → consumer proxy from host2, both
  directions, including proxy-to-proxy links).
- Calls captured as `Invocation` → `HostedPortInvocation` → an
  `InvocationSink` (14): either a fixed host intake (fail-fast on closure) or
  the **location registry** (`cell.host.LocationRegistry`, M3.2) — one map
  read + enqueue on the fast path, park-and-replay on closure/absence, so a
  proxy survives its target relocating. Senders never know where the target
  runs, nor when it moves.
- The registry is in-process; its interface ("where does this ref live
  *now*?") is the seam remote addressing fills in M5 (point 3 below).

## Wire layer (⚠ GAP G-15 — design commitments)

1. **Serialized invocation format**: stable method identification (contract id
   + method id from KSP-generated tables — never `java.lang.reflect.Method`,
   P9) + arguments encoded via kotlinx.serialization.
   *(M5.1: `@Contract` interfaces get generated `ContractDescriptor` tables
   (`gen.wire.ContractProcessor`), ids hashed FNV-1a 64 from FQN /
   FQN#name+erased-JVM-signature, ServiceLoader-collected into
   `ContractRegistry`; `Invocation` carries `contractId`/`methodId` at
   capture.)*
   *(M5.2: the format is pinned — `cell.wire.WireFrame(version, contractId,
   methodId, cellRef, portName, type, context, args)` as kotlinx JSON with
   array polymorphism; a `version` byte reserves the G-8 retrofit. Arguments
   travel as polymorphic values under `@SerialName`-pinned stable
   discriminators (`SetDelta`, `Timestamp`, …) — no class names, no method
   names in the bytes (test-asserted). Decode recovers the reflective
   in-process dispatch path from the descriptor's name + erased signature.
   Deviation from the original commitment: a uniform polymorphic codec
   replaced per-method generated codec bindings — one codec covers all
   contracts including generic ones; generate bindings only if profiling
   demands.)*
2. **Generated proxies** (KSP/Poet) replace JDK dynamic proxies at boundaries:
   KMP-compatible, reflection-free, and the natural place to emit port
   metadata (contract ids, ownership flags 20/23, color 30/32).
3. **Addressing**: `CellRef`/`PortRef` extend to include a resolvable host
   location; the **location registry** is shared with mobility re-resolution
   (33) — one mechanism: "where does this ref live *now*?".
4. **Transport**: a network bridge is a pair of boundary cells (egress
   serializer → wire → ingress deserializer) — ordinary cells + links, so
   policies/membranes apply to network crossings with no special casing
   (40/43). Transport choice (TCP/QUIC/WebSocket/WebRTC) stays pluggable
   behind the bridge cell.
5. **Failure semantics**: remote sends inherit the closable/fail-fast +
   re-resolve + park contract (33). Request/response-style management calls
   over the wire get `Deferred`/`CompletableFuture` wrapping with timeouts
   (Task Connectivity's noted type-safety cost).

## Ordering of work

In-process transparency is done; **do the wire layer only after** invocation
context (G-4) and link handshakes (G-12) land — retrofitting context or
rejection into a shipped wire format is far more expensive than sequencing
correctly now.
