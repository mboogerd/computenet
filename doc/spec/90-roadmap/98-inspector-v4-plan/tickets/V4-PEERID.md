# V4-PEERID — a peer's chosen name reaches the registry, so its hull keeps one identity across a reconnect

**Status**: Specified — not-started
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 9 · **Branches:** `ticket/v4-peerid`

## Context

ComputeNet is a Kotlin/JVM dataflow runtime: cells with typed ports, explicit
links, hosted execution. `LocationRegistry`
(`kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt`) says where
each `CellRef` lives; a location is either `Local(host)` or `Remote(sink)`
(`LocationRegistry.kt:30`). Peers announce their local refs to each other over
an ordinary port invocation (`kernel/src/main/kotlin/civictech/cell/wire/Peering.kt`),
and `:wire` carries the same shape over a WebSocket
(`wire/src/main/kotlin/civictech/wire/WsTransport.kt`).

`:inspect` is a read-only HTTP/SSE view of a live host process
(`doc/spec/90-roadmap/97-inspector-plan/`, milestones M0–M5 all merged). Its
`Node.net` field answers "which network host does this cell live on": the
launcher's `--net-name` for a local ref, a derived `peer-<id>` label for a
peer-announced one (`inspect/src/main/kotlin/civictech/inspect/Peers.kt:57-61`).

The v3 delivery's closing report lists the entire cumulative kernel diff that
the whole inspector required — five read-only accessors, nothing else
(`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088`):

| File | Addition | Why it was acceptable |
|---|---|---|
| `LocationRegistry.kt` | `describe(ref)` + weak `descriptions` map + defaulted `publish` param (`LocationRegistry.kt:227-241`, `:363-368`) | captured on the rare publish path, never reflected at read time |
| `ManagedHost.kt` | `outletAt(PortRef)` | tap-seam resolution, ~6 lines |
| `ManagedHost.kt` | `snapshotOf(ref)` | host-routed `Stateful.snapshot()`, caller-owned deadline |
| `ManagedHost.kt` | `isSuspended(ref)` (`ManagedHost.kt:220-231`) | tell a cone is parked *without touching it* |
| `ManagedHost.kt` | `isDrained` + `@Volatile state` (`ManagedHost.kt:181-204`) | distinguish DRAINED from DRAINING |

That table is the bar. Every line this ticket adds to `kernel/**` is judged
against it: small, rare-path, read-mostly, transport-neutral, threaded through
structures that already exist, each with a focused kernel test. A wave-9
checkpoint audits the resulting diff in exactly that format before anything
merges.

Note on the file split: `../10-design-notes.md` §"Standing file split" says
`V2-KERNEL` "is the only ticket allowed to touch `kernel/**`". That sentence was
written before this ticket was scheduled and `V2-KERNEL` merged in wave 4. You
are the **second and last** kernel-touching ticket in this plan; your kernel
claim (`LocationRegistry.kt`, `wire/Peering.kt`) is disjoint from `V2-KERNEL`'s
(`host/ManagedHost.kt`) and from `V1C-KERNEL`'s (`ManagedHost.kt`,
`BoundedRead.kt`, wave 8, merged before you branch). Do **not** edit
`10-design-notes.md` to reconcile the sentence — flag it in your report.

### The open item you are closing

`90-progress-log.md:1150-1154`, verbatim:

> **Peer identity across reconnects**: `PeerId` reaches only the transport
> ingress; a reconnect relabels the peer's hull (observed live:
> `peer-0ae324f9` → `peer-804f5917`). Stable cross-reconnect identity needs
> `PeerId` threaded to the registry — a peering-protocol change, now with a
> concrete consumer.

## Problem

### 1. `PeerId` exists, is exchanged, and dead-ends at the ingress

`PeerId` is a one-field name (`kernel/src/main/kotlin/civictech/cell/link/Identity.kt:6-15`),
and its KDoc is explicit that it identifies *the connection*, which the
transport vouches for — authentication is future work (spec 43).

The only production mint site is the transport hello:
`WsTransport.kt:147` parses `HELLO <mirrorRefUuid> <peerName>`; the local side
writes its own name at `WsTransport.kt:142` (`fun hello()`), sourced from
`Peering.Side.peer` (`Peering.kt:70-79`).

From there it propagates exactly one hop:
`WsTransport.Session.onText` → `Peering.hostIngress(side, fromPeer = peer)`
(`Peering.kt:119-125`) → `BridgeIngressCell(..., peer = fromPeer, ...)`
(`BridgeCells.kt:51-73`), whose inlet handler stamps every decoded frame
`decoded.copy(peer = peer)` (`BridgeCells.kt:81-87` — line 83 for the
`PORT_PROTOCOL` branch, line 85 for everything else). The stamp lands on
`HostedPortInvocation.peer` (`kernel/src/main/kotlin/civictech/cell/proxy/HostedPortInvocation.kt:20-25`,
whose KDoc states it is never serialized into frames — the receiving transport
knows its own peer).

Consumers of the stamp today are the ambient `CurrentPeer`
(`Identity.kt:21-35`, a `ThreadLocal<PeerId?>`), re-installed by `ManagedHost`
**only on the management branch** (`ManagedHost.kt:757-761`,
`PORT_MANAGEMENT -> CurrentPeer.with(hostedInvocation.peer) { … }`), and read by
`Handshake.kt:146`, `Handshake.kt:225` and `BoundaryPolicy.kt:36-37`
(`currentPrincipal()` → `Principal.Peer(it, AuthLevel.TransportVouched)`).

The `PORT_API` branch (`ManagedHost.kt:770` onwards) does **not** install the
ambient. And `PeerId` never reaches `LocationRegistry` at all:
`publish(ref, sink)` (`LocationRegistry.kt:371-374`) takes only the sink, and
`Remote` (`LocationRegistry.kt:30`) is `data class Remote(val sink: InvocationSink)`.

So no production `Peering.Side` is even named today: `demo/shopping`'s
`Peering.Side(registry, bridgeHost!!)` (`demo/shopping/src/main/kotlin/civictech/demo/Main.kt:151`)
and `demo/exchange`'s `Peering.Side(registry, bridgeHost)`
(`demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt:192`) both omit
`peer`, so every real hello carries a bare `HELLO <uuid>` and `PeerId` is
exercised only in kernel tests (`kernel/src/test/kotlin/civictech/cell/wire/TrustBoundaryTest.kt`,
`kernel/src/test/kotlin/civictech/cell/membrane/BoundaryPolicyTest.kt`,
`kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeBridgedDiamondTest.kt`,
`kernel/src/test/kotlin/civictech/cell/membrane/MediateProxyIntegrityTest.kt`).

### 2. So the inspector's peer hull is renamed by every reconnect

`Peers.labelOf` (`Peers.kt:81-84`) derives the label from the egress cell the
mirrored ref routes through: `is BridgeEgressCell -> labelFor(PREFIX, sink.ref.id)`
(`PREFIX = "peer-"`, `Peers.kt:67`; `labelFor` is
`inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:31`), else the
sink's identity hash. `Peers.kt:24-41` states why in its own KDoc, and ends
with the sentence this ticket retires: a stable cross-reconnect identity
"needs `PeerId` to reach the registry".

Why the label flips: each `WsTransport.Session` constructs a fresh
`BridgeEgressCell` (`WsTransport.kt:106`), so `sink.ref.id` is a new UUID; and
the **listener** side builds a brand-new `Session` on every `onOpen`
(`WsTransport.kt:187-191`), so a reconnect always produces a new egress on at
least one side. `InspectorModel.mirroredPublish` (`InspectorModel.kt:621-644`)
is written as an upsert precisely to absorb the relabel, and `nodeOf`
(`InspectorModel.kt:721-748`) reads the label at `:738`.

The instability is pinned by the contract
(`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md:55-57` — *"Remote
cells: a `"peer-<id>"` label, NOT stable across a peer reconnect (M5)"*) and by
two tests (`inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt:126-129`,
`demo/shopping/src/test/kotlin/civictech/demo/TwoJvmInspectorTest.kt:68-78`).

## Solution direction

The prize is that **the announcement already arrives with the `PeerId`
attached**. `BridgeCells.kt:83`/`:85` stamp every decoded invocation, including
the `RegistryAnnounce` calls. What is missing is only that the receiving mirror
cell cannot see the stamp and the registry has nowhere to put it. So a stable
name is reachable with **no wire frame change, no `WireCodec.VERSION` bump, and
no `RegistryAnnounce` method-signature change**.

### The mechanism: capture the peer where the connection is built, not where a message arrives

**Rejected — widening `CurrentPeer` to the `PORT_API` branch of `ManagedHost`
(`ManagedHost.kt:770`+).** That is the per-message data path. A `ThreadLocal`
set-and-restore on every API delivery is exactly what binding constraint 1
(P2, `doc/spec/00-foundations/02-design-principles.md:21-28`) forbids: rare
operations may be arbitrarily expensive, folds and per-message dispatch may
not. Do not do this. If you believe you can demonstrate it is genuinely free,
you must still prefer the alternative below and record the measurement in your
report rather than acting on it.

**Preferred — a per-connection mirror cell that knows its peer.**
`RegistryMirrorCell` (`Peering.kt:44-59`) is constructed once per peer
connection (`Peering.spawnMirror`, `Peering.kt:127-132`; call sites
`Peering.kt:112-113` for loopback and `WsTransport.kt:107` for the socket).
Its `init` block serves the announcements straight into the registry
(`Peering.kt:52-57`), so if the cell holds a `PeerId?` it can pass it to
`registry.publish(ref, sink, peer)` with no ambient read and no touch of the
message path at all.

**Verified limitation you must design around — read this before coding.** The
per-connection claim holds, but *constructor injection alone does not*, on the
socket path:

- In `Peering.loopback` (`Peering.kt:109-117`) both sides' names are known when
  the mirrors are spawned. `spawnMirror(b, toPeer = bToA)` builds the mirror on
  **B** that receives **A**'s announcements, so its peer is `a.peer` (and
  symmetrically `mirrorOnA`'s peer is `b.peer`). Pure constructor value. ✅
- In `WsTransport.Session`, the mirror is spawned in the constructor
  (`WsTransport.kt:107`) because `hello()` (`:142`) must send `mirrorRef`
  before any peer name is known. The remote peer's name only arrives later, in
  `onText` (`:147`). So on the socket path the mirror is created **before** its
  peer is knowable. ❌

The ordering that makes a late bind safe — verify it and state it in a KDoc:

1. Our hello (carrying our mirror ref) is sent from `onOpen`
   (`WsTransport.kt:190` listener, `:230` client).
2. The peer can only address our mirror after receiving that hello, so its
   `announceTo` (`Peering.kt:134-149`) cannot run earlier.
3. The peer's hello is sent from *its* `onOpen`, i.e. before it processes ours,
   and a WebSocket preserves per-connection message order — so our
   `onText` runs before any announcement frame from that peer.
4. Independently, `Session.onFrame` drops every binary frame that arrives
   before `ingress` is set in `onText` (`WsTransport.kt:158-165`,
   `preHelloDropCount`).

So assigning the mirror's peer inside `onText`, **before** the
`Peering.announceTo` call at `:155`, happens-before every announcement that
mirror will ever serve.

Decided shape (deviate only with a justification in the report):

- `RegistryMirrorCell` gains a peer the transport can set: a constructor
  parameter defaulted to `null` (used as-is by `loopback`) **plus** the ability
  to (re)assign it once per hello. A `@Volatile var` is the house discipline
  here — `WsTransport.Session` already holds `ingress` and `announcement` as
  `@Volatile` (`WsTransport.kt:109-110`, `:123-124`) because `onText` runs on
  the WebSocket IO thread while the mirror's `serve` body runs on the bridge
  host's scheduler thread. A `() -> PeerId?` supplier is an acceptable
  alternative if you argue it is strictly better; either way the read happens
  on the announce path (rare), never on the data path.
- It must be **re-assignable, not set-once**: the client side keeps one
  `Session` across reconnects (`WsTransport.kt:217`) and re-runs `onText` on
  every re-hello (`:154-155` already replaces the announcer for exactly this
  reason). Re-assignment with the same name must be a no-op in effect.
- `Peering.spawnMirror` gains a defaulted `peer: PeerId?` parameter so
  `loopback`'s call sites pass a constructor value. Keep its `CellRef` return
  type if you can; if you need a richer handle for the transport's late bind,
  say so and keep `WsTransport.kt:142`'s use of the ref working.

### The registry side

- `LocationRegistry.Remote` gains an optional `peer: PeerId?`.
- `LocationRegistry.publish(ref, sink)` (`:371-374`) gains a **defaulted**
  `peer` parameter, so every existing call site stays source-compatible. Read
  the local overload at `:363-368` first — it is the precedent (the defaulted
  `cell` parameter that captures `describe`'s class), including its KDoc
  convention of explaining what omitting the argument preserves.
- `unpublishRemotes(via)` (`:486-497`) matches on `sink === via` and is
  unaffected; do not change its batching or its notification ordering, both of
  which are load-bearing (its KDoc explains the re-entrancy).
- Check that nothing depends on `Remote`'s arity through `data class`-generated
  `equals`/`hashCode`/`copy`. The only production construction is
  `LocationRegistry.kt:372`; the only production consumers are
  `RoutedInlet.kt:127`, `ManagedHost.kt:882`, `Peers.kt:57-61` and
  `RemoteAddressingTest.kt:88`, all `is`-checks. Confirm this rather than
  assume it.
- `host -> link` is already in the architecture ratchet baseline
  (`kernel/src/test/resources/architecture/package-edges.txt:82`), so importing
  `PeerId` into `civictech.cell.host` adds no new package edge. Do **not** edit
  that baseline.

### The consumers

- **`inspect/.../Peers.kt`**: `netOf` prefers the registry-stored peer name and
  **falls back to today's derived label when the peer is anonymous**. An
  unnamed peer must not regress to a crash, a blank, or `"local"` — it keeps
  the old, honest, unstable label. Rewrite the KDoc at `Peers.kt:24-41`: the
  "why the label is derived" and "consequence, deliberately not papered over"
  paragraphs become wrong for named peers and stay right for anonymous ones.
  `civictech.cell.link` is already on `:inspect`'s allowed import surface
  (`DemoSurfaceAllowlistTest.kt:70-79`), so no allowlist edit is needed — do
  not make one.
- **`demo/shopping`** must name itself, or nothing changes observably.
  `--net-name` is already parsed in `main` (`Main.kt:312`, defaulted to
  `"local"` at `:331`) but is currently consumed only by `startInspector`
  (`:267-269`, `:331`). `DemoApp`'s constructor (`Main.kt:39`) does not take it,
  so plumbing the name to `Peering.Side(..., peer = PeerId(netName))` at
  `Main.kt:151` means threading it through the constructor. Keep the default
  behavior identical when the flag is absent — decide and state whether an
  absent `--net-name` yields an anonymous peer (no name in the hello) or the
  literal `PeerId("local")`; "every unnamed peer is called `local`" would make
  two anonymous peers indistinguishable, so prefer anonymity, and test it.
- **`demo/exchange`** has no `--net-name` flag at all (`Main.kt:276-280` parses
  only `--listen`/`--peer`/`--journal`). Add the smallest flag + plumbing that
  matches shopping's shape, or, if you judge a new launcher flag out of
  proportion for this demo, name the side from something it already has and
  justify it. `demo/exchange` is the repo's toughest property gate
  (`ExchangeCompositionExitTest`); a change there must be behaviour-neutral.
- `civictech.cell.link` is already on the demo allowed surface too
  (`DemoSurfaceAllowlistTest.kt:57-68`).

### The label itself

Use the peer's raw name as `Node.net`, in the same register as `localNet` — so
peer A's inspector shows B's cells under `jvm-b`, which is the whole point of
the two-JVM demo criterion below. Two consequences you must handle rather than
discover:

- **Collision.** A peer that names itself the same as `localNet` renders inside
  the local hull. Decide what happens, test it, and report it. Rendering as-is
  and documenting the hazard is acceptable; disambiguating is acceptable if you
  argue for it — but it changes what the demo shows, so it must be a stated
  decision, not a silent one.
- **It is asserted, not proven.** `PeerId` is transport-vouched by design
  (`Identity.kt:6-12`). A stable label is *not* an authenticated one. Nothing
  in the inspector or the demo may present it as verified identity.

## Explicitly out of scope

- **Any change to a `RegistryAnnounce` method signature, or a new announce
  method** (`Peering.kt:20-38`). `methodId = StableHash.of("$fqn#$name$descriptor")`
  (`gen/src/main/kotlin/civictech/gen/wire/ContractProcessor.kt:385`), so
  changing a signature repoints the id and `WireCodec.decode` throws on an
  unresolvable method (`kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:278-280`);
  adding a method leaves an older receiver unable to resolve it, and there is
  no capability negotiation for announcements — the only negotiated set is
  `BridgeIngressCell.protocolCapabilities`, which is `PORT_PROTOCOL` only
  (`BridgeCells.kt:71-72`). `AGENTS.md`: preserve wire compatibility unless the
  cited spec requires a change. **Nothing here requires one.**
- **Sending cell descriptors / type FQNs / port names across the wire.**
  Deferred by the C-replan checkpoint: it is a wire break or needs negotiation,
  its benefit is classified as cosmetic in the v3 closing report
  (`90-progress-log.md`, "Cosmetics"), and deciding what one peer may learn
  about another's cells is a **disclosure** question
  (`doc/spec/40-distribution/43-security.md` seam 3; `93-feature-interactions.md`
  I-28 "filtered, not forked"; `doc/spec/20-dataflow-semantics/21-propagation.md:72-76`),
  not plumbing. Not this ticket.
- **Authentication.** See above: this ticket makes an existing claim *visible*,
  not *trustworthy*.
- **`concord/**`** (binding constraint 7), `kernel/.../cell/BoundedRead.kt`,
  `ManagedHost.readState`, and everything the V1C chain owns:
  `cell/data/{MapCell,KeyedSetCell,ListCell,Watermark}.kt`,
  `cell/replication/InstanceSet.kt`, `cell/partition/ShardCell.kt` (V1C-CELLS,
  parallel), `cell/data/op/**` (V1C-OPS, parallel).
- The un-despawned mirror/egress cells a listener-side reconnect leaves behind
  (`WsTransport.Session.onClose`, `:167-174`, closes the announcer and
  unpublishes but despawns nothing). If you confirm it, report it as an
  observation; do not fix it here.
- Any edit to `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`
  (orchestrator-owned, binding constraint 8) or to `10-design-notes.md`.

## Files expected to touch

- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt` — `Remote`
  gains `peer`; the remote `publish` overload gains a defaulted parameter.
- `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt` —
  `RegistryMirrorCell` learns its peer; `spawnMirror` gains a defaulted
  parameter; `loopback` passes the opposite side's name.
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt` — prefer the stored
  name, fall back to the derived label; KDoc rewrite.
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt` — name the side.
- `demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt` — name the side.
- Tests: `kernel/src/test/kotlin/civictech/cell/wire/**` (new focused test,
  suggested `PeerIdentityTest.kt`),
  `inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt`,
  `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmInspectorTest.kt`, and a
  reconnect test in `wire/src/test/kotlin/civictech/wire/**` if the socket-path
  stability cannot be asserted from `:demo:shopping` alone.

**Expected to be unchanged, but inside your claim** — so that if your analysis
says one must change, it is a justified edit rather than a surprise:

- `wire/src/main/kotlin/civictech/wire/WsTransport.kt` — the analysis in
  §"Solution direction" says this one **probably must change** by two or three
  lines (bind the mirror's peer inside `onText`, before `:155`). That is
  expected; justify the exact delta in your report.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt` — expected
  untouched. Any edit here needs the P2 argument quantified (see Acceptance).

Touching anything outside this list: note it in the completion report rather
than expanding silently.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` — the whole
  file; §"Binding constraints" 1 (P2), 5 (kernel stays transport-neutral;
  small, explicitly listed accessors), 7 (no `concord/` edits), 8
  (`20-api-contract.md` is orchestrator-owned) and §"Standing file split" (see
  the Context note about its now-stale sentence).
- `doc/spec/90-roadmap/98-inspector-v4-plan/tickets/V2-KERNEL.md` — the closest
  analogue: the only other kernel-touching ticket in this plan, and the rigor
  exemplar for the diff table and the "verify what already landed rather than
  re-implementing it" discipline.
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1076-1088` (the
  cumulative kernel diff table — your bar) and `:1137-1141` (the open item you
  close).
- `kernel/src/main/kotlin/civictech/cell/wire/Peering.kt` — all of it:
  `RegistryAnnounce` `:20-38`, `RegistryMirrorCell` `:44-59`, `Side` `:70-79`,
  `Loopback.partition/heal` `:96-107`, `loopback` `:109-117`, `hostIngress`
  `:119-125`, `spawnMirror` `:127-132`, `announceTo` `:134-149`.
- `wire/src/main/kotlin/civictech/wire/WsTransport.kt` — all of it, especially
  `Session` construction order `:101-142`, `onText` `:144-156`, `onFrame`
  `:158-165`, `onClose` `:167-174`, listener `onOpen` `:187-191`, client
  session/reconnect `:211-259`.
- `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt:30`,
  `:363-374` (both `publish` overloads), `:376-388` (hook-failure containment),
  `:434` (`install`), `:486-497` (`unpublishRemotes`).
- `kernel/src/main/kotlin/civictech/cell/wire/BridgeCells.kt:25-40` (egress) and
  `:51-90` (ingress + the peer stamp).
- `kernel/src/main/kotlin/civictech/cell/link/Identity.kt` — all 35 lines.
- `kernel/src/main/kotlin/civictech/cell/host/ManagedHost.kt:757-761` and the
  `PORT_API` branch from `:770` — the two dispatch branches, and why only one
  installs the ambient.
- `kernel/src/main/kotlin/civictech/cell/host/HostedCellProxy.kt:74-96`
  (`PORT_MANAGEMENT`) and `:98-111` (`PORT_API`) — the reason an announcement
  travels as `PORT_API` *despite* `@Contract(management = true)`: the type is
  chosen by the proxy path, and the `management` flag is read only at codegen
  time (`ContractProcessor.kt:101`, `:153`, `:368`), never in runtime dispatch.
  Verify this yourself; it is the fact that makes the ambient-widening
  temptation look plausible.
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt` — all 86 lines;
  `InspectorModel.kt:31` (`labelFor`), `:621-644` (`mirroredPublish` upsert),
  `:721-748` (`nodeOf`, label read at `:738`).
- `inspect/src/test/kotlin/civictech/inspect/InspectorNetTest.kt` — the whole
  file: its `peer()` helper (`:61-62`) builds anonymous loopback `Side`s today,
  and `:126-129` pins the `peer-` label.
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmInspectorTest.kt:68-78` —
  the multi-JVM pin, with a KDoc comment that becomes wrong.
- `kernel/src/test/kotlin/civictech/cell/architecture/DemoSurfaceAllowlistTest.kt:57-79`
  and `ArchitectureRatchetTest.kt` + `kernel/src/test/resources/architecture/package-edges.txt`
  — the two guardrails a careless import would trip. Both already permit what
  this ticket needs; confirm, do not edit.
- `wire/src/test/kotlin/civictech/wire/WsReconnectSmokeTest.kt` — the existing
  reconnect harness (listener dies and returns on the same port). Reuse it
  rather than inventing one.
- `kernel/src/test/kotlin/civictech/cell/wire/TrustBoundaryTest.kt` — how a
  seeded harness drives named peers and allowlists today.
- `doc/demo-shopping-inspector.md` and `scripts/demo-shopping-two-inspectors.sh`
  — the two-inspector runbook (`--net-name jvm-a` / `jvm-b`, ports
  17091/17092, ws 19201).
- `AGENTS.md` §"Core invariants to protect" and §"Verification".

Do not modify: anything under `concord/**`, `gen/**`, `nature/**`,
`testkit/**`, `inspect/ui/**`, any other module's sources, any plan document
other than this ticket's `**Status**:` line, and
`doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`.

## Acceptance criteria

- [ ] A **named** peer's cells report a **stable** `Node.net` across a real
      disconnect/reconnect cycle — asserted over an actual reconnect (socket
      close and re-hello, or `Loopback.partition()` + `heal()` where that is
      the honest analogue), not asserted by construction from a single connect.
- [ ] The stable value is the peer's own configured name (`--net-name` on the
      other JVM), not a locally derived one.
- [ ] An **anonymous** peer (a `Peering.Side` with no `peer`) still gets
      today's derived `peer-<id>` label, nothing throws, and the existing
      anonymous assertions still hold. Keep at least one test asserting the
      anonymous case verbatim.
- [ ] `LocationRegistry.publish`'s new parameter is defaulted and **no existing
      call site is changed**. The 20 `publish(...)` call sites across
      `kernel/src/test` and the single production remote-publish caller
      (`Peering.kt:53`) compile untouched except where you deliberately pass
      the new argument.
- [ ] **No wire change.** `WireFrame`, `WireCodec.VERSION` (`WireCodec.kt:116`,
      still `2`), the `RegistryAnnounce` interface (`Peering.kt:20-38`) and
      every generated `methodId` are byte-identical. State this as a checked
      claim, naming how you checked it (e.g. `git diff --stat` over the three
      files plus a `:gen:test` run).
- [ ] **No per-message cost.** Name every call site you added and show that
      none is on the data path: announcement handling
      (`RegistryMirrorCell.inlet.serve`) fires on publish/unpublish/link/unlink,
      the hello binding fires once per connection. `ManagedHost.kt`'s
      `PORT_API` branch is unchanged — or, if changed, the P2 argument is
      quantified as a count of reads/writes/branches added per message, in the
      register `V3-BE`'s report used.
- [ ] A repeated hello (reconnect) re-binds the mirror's peer without leaking a
      stale name, and a mirror bound after its `Session` was constructed serves
      no announcement before the bind. Assert the ordering, do not merely argue
      it.
- [ ] The peer-name-equals-local-net collision case is decided, tested and
      reported.
- [ ] `Peers.netOf` never returns `null`-collapsed-to-`localNet` for a remote
      ref that has a peer name; `isRemote` semantics are unchanged.
- [ ] Two-JVM verification: run `scripts/demo-shopping-two-inspectors.sh`
      (documented in `doc/demo-shopping-inspector.md`) and confirm each side's
      peer hull is labelled with the *other* side's `--net-name`
      (`jvm-a`/`jvm-b`), before and after killing and restarting one peer.
      Paste the two `Node.net` values observed on each side.
- [ ] `TrustBoundaryTest`, `BoundaryPolicyTest`, `GlitchFreeBridgedDiamondTest`,
      `MediateProxyIntegrityTest` and the five `wire` smoke/reconnect tests stay
      green **unmodified** (note: three of those four live outside
      `civictech.cell.wire` — `membrane` and `consistency` — so the narrow
      `--tests 'civictech.cell.wire.*'` run does not cover them; run the full
      `:kernel:test`).
- [ ] `ArchitectureRatchetTest` and `DemoSurfaceAllowlistTest` pass with
      `package-edges.txt` and the allowlists **unedited**.
- [ ] Every added or changed public member carries KDoc naming this ticket
      (`V4-PEERID`) and the reason it exists, matching the register of
      `describe` (`LocationRegistry.kt:227-241`) and `remoteRefs`
      (`LocationRegistry.kt:252-261`). The mirror's late-bind KDoc must contain
      the happens-before argument.
- [ ] No `concord/` edits, no contract-document edits, no `10-design-notes.md`
      edit, no generated/build output in the diff, no `G-*`/`C-*` markers
      removed, nothing under `inspect/ui/`.

## Verify

```bash
./gradlew :kernel:test --tests 'civictech.cell.wire.*'
./gradlew :kernel:test
./gradlew :wire:test
./gradlew :inspect:test
./gradlew :demo:shopping:test
./gradlew :demo:exchange:test
./gradlew test
```

`:kernel:compileKotlin` depends on `:gen:test`, so a generator regression
surfaces here as a kernel compile failure (`AGENTS.md` §Repository map).

Multi-JVM tests carry `@Tag("multi-jvm")` (`TwoJvmInspectorTest.kt:26`,
`TwoJvmConvergenceTest.kt:43`, `CrashRestartConvergenceTest.kt:62`,
`ExchangeScaffoldTest.kt:94`/`:128`). The tag is gated by project property in
`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:29-41`: a plain
`./gradlew :demo:shopping:test` runs **everything including** the tagged tests;
`-PmultiJvmOnly` runs only them; `-PexcludeMultiJvm` runs everything else.
Confirm this yourself and report the exact command you used, e.g.:

```bash
./gradlew :demo:shopping:test -PmultiJvmOnly --tests 'civictech.demo.TwoJvmInspectorTest'
```

Any live server you start by hand must bind an ephemeral port (`0`) or an
explicitly non-default one; `scripts/demo-shopping-two-inspectors.sh` already
uses 18191/18192/19201/17091/17092/5191/5192 and must not be edited to squat
8080.

## Report on completion

- Checks run and their results, including the exact multi-JVM invocation.
- **The exact kernel diff**, in the format of `90-progress-log.md:1076-1088`'s
  table: file, addition, line count, justification. A wave-9 checkpoint audits
  it for P2/P6, read-only-ness and transport-neutrality before merge. Include
  `wire/WsTransport.kt` in the table if you changed it, with the two- or
  three-line delta spelled out.
- **The checked claim that no wire shape changed**, and how you checked it.
- The exact call sites you added, and the thread each runs on — the P2 claim
  must be checkable, not asserted.
- **Whether the "per-connection mirror cell" assumption held**, in your own
  words: it is per-connection, but on the socket path it is constructed before
  the peer name is known. Confirm or correct the happens-before argument in
  §"Solution direction", and say exactly where the bind ended up.
- **The proposed replacement wording for `20-api-contract.md:55-57`** —
  covering both the named case (stable, the peer's own `--net-name`) and the
  anonymous case (today's unstable derived label) — as a quotable block for the
  orchestrator. Do **not** edit that file.
- **What a stable-but-unauthenticated label does and does not entitle a UI to
  say.** One short paragraph, precise enough to become UI copy guidance: it is
  a transport-vouched claim, spoofable by a peer that reaches the socket, and
  it says "the same connection identity as before", not "the same principal".
- The collision decision (peer name == `localNet`) and its test.
- **Whether anything here makes the deferred descriptor-over-the-wire work
  easier or harder** — input for the next replan. In particular: does a named
  peer make the "remote endpoints show raw port uuids" cosmetic gap more or
  less pressing, and does the mirror now being peer-aware create a natural
  place to hang negotiated metadata, or a place where a later negotiation would
  now have to be threaded twice?
- The stale sentence in `10-design-notes.md` §"Standing file split", and any
  other stale claim you hit in the plan documents or `90-progress-log.md`.
- Anything specified here you could not do, and why.
