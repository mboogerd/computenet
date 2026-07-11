# 41 — Location Transparency

> **Status**: Implemented (M5) — in-process transparency and the wire layer; generated boundary proxies (point 2) remain interim JDK proxies; wire forms for generic protocols, EdgeEvents, and remote construction are decided design (93), unimplemented
> **Sources**: ADR 3, ADR — Task Connectivity (§4)
> **Implementation**: `cell.proxy.HostedCellProxy`, `HostProxy`, `Invocation`, host routing; `gen.wire.ContractProcessor` + `ContractRegistry`; `cell.wire` (WireCodec, bridge cells, Peering); `:wire` (WebSocket transport)

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

## Wire layer (G-15 — resolved in M5; commitments annotated below)

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
   ⚠ GAP (G-52): the adopted membrane design (exposure map + Flatten/Mediate
   surface modes over the TrafficLight idiom) is undesigned at its edges —
   DSL lowering and proxy generation, exposed-name resolution across the
   wire, nested/transitive exposure, wave re-mint interplay, and leaf-cell
   membranes. Proposal: KSP-generate the Mediate proxy (Invocation capture +
   policy/coupling/re-mint) from the membrane declaration and lower the DSL
   to spawn/serve/delegate/connect; announcements carry exposed aliases
   only, resolved bridge-side to organelle full-ref ports without leaking
   the interior; define exposure re-composition (wave-scope/mode) across
   nesting levels with cardinality accounting; pin Remint interaction with
   attention propagation and late-join catch-up; and specify the minimal
   non-composite (leaf) membrane form (93 I-10).
3. **Addressing**: `CellRef`/`PortRef` extend to include a resolvable host
   location; the **location registry** is shared with mobility re-resolution
   (33) — one mechanism: "where does this ref live *now*?".
   *(M5.4: done, with a design refinement — refs themselves stay bare
   (G-8 deferred); resolvability lives entirely in the registry:
   `Location = Local(host) | Remote(sink)`. Remote locations are learned via
   `RegistryAnnounce` invocations on a peer's `RegistryMirrorCell` — ordinary
   wire traffic over the same bridge as data (`cell.wire.Peering`). Senders
   are placement-blind: registry proxies reach either side, park while
   unlocated or mid-move, replay in order (`RemoteAddressingTest`, 50-seed
   ordering under mid-stream migration). `host.lookup` returns remote-backed
   proxies for `Remote` refs; local refs on other hosts remain that host's
   business. Remote publishes never re-announce, so mirrored registries
   cannot loop.)*
   ⚠ GAP (G-48): no cross-host index enumerates all links pointing at a
   full ref, so promotion swaps cannot find their relink set, shadow cuts
   cannot compute SCC closure, and logical-level orchestration is
   O(everything). Proposal: a maintained cross-host reverse-topology index
   (inbound and outbound links per full ref, including bridged links) with
   defined maintenance/migration cost, serving the promotion swap set, SCC
   computation for observation-membrane cuts, and instancesOf-based
   orchestration (93 I-2/I-11/I-17).
   ⚠ GAP (G-57): a client holding only a logicalId has no defined
   instance-selection policy (nearest replica for reads, leader for writes,
   active candidate during promotion), and instanceId minting has no stated
   collision discipline across hosts or in deterministic tests. Proposal: a
   logical rendezvous policy over instancesOf keyed by operation class; the
   port-compatibility rule on relink (reject vs adapt on (portName,
   contractId) mismatch) confirmed to live on ports rather than refs; and a
   stated birthday-bound argument for random instanceId minting with
   caller-chosen ids in deterministic tests, covering construction-time
   NewInstanceOf minting across hosts (93 I-2/I-21).
   ⚠ GAP (G-41): the adopted CycleHead/threshold structure leaves admission
   and well-formedness holes — cross-host cycles are invisible to link-time
   checks, multi-head/nested/multi-tier cycles have no stated rule, hop
   bounds are uncalibrated magic numbers, and head behavior under
   RESTART/promotion is unpinned. Proposal: distributed cross-host cycle
   detection (or an explicit hop-guard-only stance) tied to
   peering/announcements; a ≥1-head-per-elementary-cycle well-formedness
   rule with detection over the cycle basis; a multi-tier mix admission
   policy; hop-bound calibration against loop diameter; feedback-join
   consistent-snapshot semantics; membrane-scoped lap quiescence; plus
   generation-bump (RESTART) and swap-on-live-cycle behavior at a head —
   weak-tier fixpoint convergence itself stays open under G-19
   (93 I-5/I-6/I-17/I-22).
4. **Transport**: a network bridge is a pair of boundary cells (egress
   serializer → wire → ingress deserializer) — ordinary cells + links, so
   policies/membranes apply to network crossings with no special casing
   (40/43). Transport choice (TCP/QUIC/WebSocket/WebRTC) stays pluggable
   behind the bridge cell.
   *(M5.3: `cell.wire.BridgeEgressCell` (an `InvocationSink` — proxies aim at
   it and every send becomes a frame on an ordinary outlet) +
   `BridgeIngressCell` (frame inlet → decode → the receiving registry's
   `deliver`). Between them only bytes travel; the loopback form is an
   in-process link on SimulationController hosts, so the full wire format
   runs under the 100-seed generative harness (`BridgedGraphTest`: view
   pipeline split at a random cut across two registries; control runs prove
   dropped frames diverge detectably and corrupt frames dead-letter on the
   receiving host and traffic continues). This is the wire's P1 proof.)*
   *(M5.5: transport chosen — **WebSocket** (`:wire` module, `WsTransport`;
   `org.java-websocket` for the server since the JDK ships only a client):
   framed and bidirectional out of the box, lives in the demo's HTTP world,
   and leaves browser-tabs-as-peers open for M6+ without a second transport.
   The dependency lives in `:wire`, keeping `:kernel` dependency-free —
   another transport is another small module behind the same bridge cells.
   Frames go out as binary messages; a text hello exchanges mirror refs, then
   `Peering` wires announcements. IO threads only enqueue — decoding happens
   on the bridge host. Disconnect ⇒ `unpublishRemotes(sink)` ⇒ senders park.
   **M10.3–M10.4**: clients reconnect with capped backoff (`shutdown()` is
   the only permanent close); the re-hello re-runs the announcement catch-up
   and parked traffic replays. The crash *window* is covered too: a send on
   a dying socket raises `IntakeClosedException`, which the registry treats
   exactly like a closed local intake — park, never drop — and bytes the
   dying socket already swallowed are recovered end-to-end by catch-up
   re-firing on every (re)announce (42).)*
   ⚠ GAP (G-37): on-demand pull (the G-18 residual) has a decided shape but
   no concrete design — descriptor, reply routing to a specific requester,
   buffer-survival detection, pull storms on mesh heal, and pull-safety for
   non-idempotent/effectful cells are unspecified. Proposal:
   `RequestState(replyTo, since)` on the metadata plane answered by an
   ordinary state-as-delta single wave, issued by the subscriber exactly
   when a link goes live history-incomplete (fresh link → pull;
   parked-and-replayed → none; dropped-and-re-resolved → incremental pull
   with a TagFrontier under a stated per-source-monotonic tag invariant);
   add the per-link liveness epoch for park-vs-drop detection, a
   mesh-reconnect coalescing/debounce policy, and a pull-serves-copy-only
   rule for non-idempotent cells (93 I-16/I-1).
   Generic protocols cross the bridge the same way — decided design,
   unimplemented (decided in
   [93 I-1](../90-roadmap/93-feature-interactions.md)): `PORT_PROTOCOL`
   (14) joins the existing `WireFrame` `type` field, with the reserved
   version byte bumped only if the enum's encoding is not additively
   compatible; the bridge cell pair becomes protocol-aware — egress MUST
   emit a `PORT_PROTOCOL` frame and ingress MUST deliver it to
   `ProtocolSupport` on the receiving side; and a counter-directional
   (upstream) protocol MUST travel the reverse bridge path the link already
   maintains for re-resolution. No new bridge type — this closes 12's
   "bridged links have no endpoint objects" note by decision.
   ⚠ GAP (G-35): generic protocols (PORT_PROTOCOL) cannot cross the wire
   and peers cannot negotiate or version each other's protocol capability
   sets — attention, saturation, state-request, and taps all stop at a
   bridge. Proposal: bridge egress/ingress gain a PORT_PROTOCOL frame path
   (one WireFrame type variant, direction tag, reverse-channel realization
   for upstream protocols over the reverse bridge path a cross-host link
   already maintains); the link's negotiated protocolCapabilities
   generalizes to cross-peer negotiation with a versioned
   ProtocolId↔contractId mapping and downstream-only capability sets for
   shadow taps; verified by a generative frame-reorder/duplication harness
   (cross-host attention convergence as the first case)
   (93 I-1/I-4/I-17/I-9).
   Topology edge events cross the bridge likewise (decided in 93 I-13,
   unimplemented): `EdgeOpen`/`EdgeClose` (20/22) become a `WireFrame`
   event type — ordinary frames on their link's bridge channel that MUST
   inherit per-link FIFO ordering against data frames over the wire; on
   disconnect `unpublishRemotes` parks senders and reconnect replays in
   order, so that ordering survives park/replay — no synchronous handshake
   reply is required (10/13).
   ⚠ GAP (G-39): link/unlink are null-context management ops with no stamp
   in the wave domain — glitch-free consumers cannot know from which wave a
   new/removed edge counts, source-set changes do not propagate downstream,
   and EdgeEvents/floors have no wire form. Proposal: in-band
   EdgeOpen/EdgeClose markers injected into the affected link's own FIFO
   carrying a per-source flushed-high-water floor; design the floor
   representation and retention/compaction horizon, hop-by-hop downstream
   source-set delta propagation with a liveness proof (an upstream cut must
   not strand a waiting join), bridged EdgeEvent frame types ordered
   against data across disconnect/park/replay, the floors×cycles×merge-tag
   interaction, and the explicit topology-serializing coordinator
   (JoinBarrier) cell that doubles as the diamond-over-replica escape hatch
   (93 I-13/I-14).
5. **Failure semantics**: remote sends inherit the closable/fail-fast +
   re-resolve + park contract (33). Request/response-style management calls
   over the wire get `Deferred`/`CompletableFuture` wrapping with timeouts
   (Task Connectivity's noted type-safety cost).
   Remote construction takes exactly this form — decided design,
   unimplemented (decided in 93 I-21): live-cell `spawn(cell)` stays
   local-only; a live cell MUST NOT cross the wire. The wire form is
   `spawnBound(factory, identity, parent)` — the factory is `Serializable`
   and the target host constructs the cell locally — returning
   `Deferred<CellRef>` over the wire (an applier that chose the identity
   binding (`Exact`/`NewInstanceOf`, 50/51) can predict the ref without
   waiting; only fresh minting needs the reply). Per-step rejections
   surface as dead letters on the target host; the optional reply channel
   is a `ConstructionReport` outlet on the target host, subscribed over an
   ordinary dataflow link — the same mechanism `RegistryAnnounce` uses
   (point 3) — never a synchronous cross-wire reply. Loud construction
   failure is synchronous only where construction is co-located with the
   target host; across the wire it MUST degrade to asynchronous rejection
   reporting.
   ⚠ GAP (G-55): the admission (structural, from construction) vs
   activation (behavioral, handler-establishment) split needs its
   enforcement surface — stateful-onLink classification, deferred-admission
   result surfacing including cross-host, Eager verification,
   dropped-protocol observability, and remote-spawn rejection channels.
   Proposal: a per-port structural-only vs stateful onLink declaration with
   defined defer/replay/result-surfacing of admission requests to
   not-yet-hot cells, composing with LinkResult.Deferred and registry
   park/replay across the wire; a KSP-checked Eager capability (handler in
   constructor, pure, allocation-free, host-context-free) from which
   unhosted-linking permission derives; a count/log policy for protocols
   dropped before handler install; and a typed rejection surface for
   wrong-color or invalid remote spawns pinned against G-26/G-12
   (93 I-26/I-15).

## Ordering of work

In-process transparency is done; **do the wire layer only after** invocation
context (G-4) and link handshakes (G-12) land — retrofitting context or
rejection into a shipped wire format is far more expensive than sequencing
correctly now.
