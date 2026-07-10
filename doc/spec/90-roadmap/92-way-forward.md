# 92 — Proposed Way Forward

> **Status**: proposal (the one document meant to be argued with and rewritten)
> Premise: the germ kernel validated the right things first — invocation capture, delegation, cross-host proxies, host queues, boundary buffering. The next work should **harden the four enablers** (context, links, port registry, drain) before widening scope, because every ambition in 40/50 lands on them.

## Milestone 1 — One kernel (consolidation) ✅ DONE

*Goal: a single, coherent codebase that matches spec sections 10 and 31.*

1. ~~Fold `ManagedRunner` into `ManagedHost` (C-2)~~ (`ManagedRunner` had
   already been deleted; the remainder became the `HostScheduler`
   configuration + `SimulationController`); `Thread.sleep` tests replaced
   with deterministic ones (C-8 fix: sequence-number FIFO tiebreaker).
2. Port registry from delegates/factories; retire reflective `findPort`
   (G-17). Explicit-style ports register too (C-6).
3. `onDeactivate` hook (G-16); error handling to a defined dead-letter path
   (G-26 minimal).
4. Rename/move: germ → `civictech.cell` (or chosen name); delete legacy
   packages or quarantine under `legacy/` pending G-3 port (G-1 start).

*Exit criterion — met: kernel tests green with no sleeps, no reflection in
port resolution, one host class; legacy quarantined in `:legacy`.*

## Milestone 2 — Context and links (the semantic spine) ✅ DONE

*Goal: spec sections 13, 14, 22 implementable claims become true.*

1. `MessageContext` on `Invocation`; host-managed current-context;
   transparent inlet→outlet flow (G-4).
2. `Link` objects with handshake (`onLink/onUnlink`, `LinkResult`),
   `unlink()`, cardinality enforcement (G-12); policies as link-time
   functions with an identity slot (G-14 phase 1).
3. Wave ids: per-source `(sourceId, counter)` timestamps (G-20 decision).
4. `GlitchFree` wrapper cell: dependency tracking to the frontier + version
   buffering (22), validated on the diamond topology.

*Exit criterion — met: the diamond invariant test runs 200 seeds of
randomized cross-host scheduling glitch-free, with a control run proving the
harness detects glitches (`GlitchFreeDiamondTest`).*

## Milestone 3 — Colors and mobility (execution completeness) ✅ DONE (core)

*Goal: spec sections 32, 33 real; graphs survive placement changes.*

1. ~~Coroutine ManagedHost + HostColor + color markers + spawn validation
   (G-3, G-27); legacy runtime deleted (G-1 done)~~ — done (M3.1). Bridges
   degenerated to nothing while intakes are unbounded; see 32.
2. ~~Closable intake + fail-fast send + re-resolution via a location registry
   (G-5); Buffering-based park/replay~~ — done (M3.2).
3. ~~Full drain protocol: suspend/resume/migrate on `HostManagementApi`;
   state capture via serializable snapshots (starts G-25)~~ — done (M3.3).
4. ~~Traffic-light generalized: suspension as a standard membrane behavior~~
   — done (M3.4): `cell.membrane.TrafficLightCell`; same `Buffering`
   primitive at port and location granularity (33).

*Exit criterion — met: `SubchainMigrationTest` migrates a running stateful
subchain across colors under load, 100 seeds, zero loss, per-link FIFO, with
a control run proving the harness detects loss. Supervision policies landed
as M3.5 (G-26 resolved, narrowed to error outlets → M4).*

## Milestone 4 — Data + verification (the developer payoff) ✅ DONE (core)

*Goal: the incremental dataflow layer becomes genuinely usable.*

1. ~~Causal tags on deltas (G-23): observed-remove tags on the set family,
   commutative CounterCell, documented limits for map/list~~ — done (M4.1).
   ~~State + late-join catch-up (G-22, G-18 core): post-install `onLinked`
   hook, state-as-delta unicast, Stateful data cells~~ — done (M4.2).
   ~~Operator library: filter/count/intersect/join~~ — done (M4.3).
2. ~~Invariants-as-cells + kotest adapter + per-cell error outlets (G-26
   completed)~~ — done (M4.4). ~~Generative graph harness on the simulated
   host (G-31)~~ — done (M4.6).
3. ~~Graph DSL as thin builder (G-30) — also yields graphs-as-data
   (`GraphSpec`)~~ — done (M4.5).
4. Partitioned cell (G-24) **deferred as planned**: the exit app's sets and
   counters are atomic structures; trigger = the first keyed dataset with
   placement pressure (24).

*Exit criterion — met: `CollaborativeAppTest` runs a three-user
shopping-list-with-votes session built purely from cells (DSL-constructed
views, mixed 🔵/🟣 hosts) with a mid-session joiner catching up, a mid-session
host migration, and an injected failure consumed via an error outlet under
RESTART — invariants asserted through the kotest adapter, 100 seeds, with a
control run proving the harness detects non-convergent views. A demo UI over
the same graph is M4.8 (`:demo`).*

## Milestone 5 — Wire (distribution begins) ✅ DONE

*Goal: two processes, one graph.*

Sequenced (decisions: kotlinx.serialization as codec; WebSocket transport in
a new `:wire` module keeping `:kernel` dependency-free; G-8 deferred behind a
wire-frame version byte; demo splits as symmetric peers):

1. ~~M5.1 — contract identity: KSP method-id tables (`@Contract` →
   `ContractDescriptor`, ids hashed from FQN + erased JVM signature),
   `ContractRegistry`, `Invocation.contractId/methodId` (C-5: stable ids
   exist; in-process dispatch stays reflective)~~ — done.
2. ~~M5.2 — serializers + `WireFrame` envelope (kotlinx.serialization, JSON
   array polymorphism, stable `@SerialName` discriminators; version byte
   reserves G-8; uniform polymorphic codec instead of per-method generated
   bindings)~~ — done.
3. ~~M5.3 — loopback bridge cells: egress/ingress as ordinary cells on
   SimulationController hosts; generative harness with a bridge at a random
   cut, 100 seeds + drop/corrupt control runs (the wire's P1 proof:
   `BridgedGraphTest`)~~ — done.
4. ~~M5.4 — remote addressing: `LocationRegistry` resolves Local|Remote;
   announcements as ordinary wire invocations (`Peering`/`RegistryMirrorCell`);
   `lookup` returns remote-backed proxies~~ — done. Cross-registry `connect`
   request/response frames deliberately skipped: the registry-proxy +
   `Use.fixed` linking pattern covers the exit app; `LinkResult.Deferred`
   keeps its existing contract (handshake runs on the target host, rejections
   dead-letter there). Revisit only if M5.7 proves it necessary.
5. ~~M5.5 — WebSocket transport driver in `:wire` (org.java-websocket
   server); disconnect ⇒ unpublish ⇒ park; localhost smoke test (seeds stay
   on the loopback)~~ — done.
6. ~~M5.6 — ownership enforcement at link time (G-21 phases 1+2) — `Owned`
   fan-out rejectable everywhere (local, cross-host, bridge — one funnel:
   `FanOutlet.subscribe`); `Owned` moves-by-serialize, `Leased` refused at
   the boundary~~ — done.
7. ~~M5.7 — exit: `DistributedCollaborativeAppTest` (loopback split, 100
   seeds + control run) + the demo as two symmetric WebSocket peers
   (`TwoJvmConvergenceTest` drives two OS processes)~~ — done.

*Exit criterion — met: `DistributedCollaborativeAppTest` runs the M4
collaborative session with hosts split across two location registries
connected only by serialized wire frames — same cells, same graph, placement
the only diff — converging under 100 seeds (late joiner arriving on the other
peer, mid-session migration, injected failure through an error outlet), with
the control run proving divergence detection; and the same split runs live
across two OS processes over WebSocket (`:demo` peer mode), browsers on each
side converging both directions.*

## Milestone 6+ — The decentralized horizon (now sequenced)

Formerly unsequenced research tracks; sequenced here (M6–M9) with the same
exit-criteria discipline as M1–M5. Ordering rationale: attention (M6) is the
only track whose enabler (G-13 multiplex) unlocks other residuals (G-18 pull);
replication (M7) needs G-8 and *uses* M6's interest signal for replica
extent; trust (M8) trails the policy substrate per 43's sequencing and gives
replication its untrusting-peers story; evolution (M9) composes everything
(instances from M7, shadow-effect policy from M8's classification work,
invariant gates from M4). The programming environment / visualization track
stays unscheduled — it follows whichever surface stabilizes first.

## Milestone 6 — Attention (interest-driven scheduling) ✅ DONE

*Goal: spec 34 real — unattended subgraphs quiesce, attended ones get
resources; the three open scheduling questions resolved (decisions recorded
in 34: max-aggregation quantized into bands; park-not-drop + service stride
as fairness floors; glitch-free WAIT default with opt-in DEGRADE via
frontier-shrink).*

1. M6.1 — generic-protocol substrate (G-13 minimal): sub-channels on existing
   ports keyed by well-known `ProtocolId`, sharing the port's links and queue
   slot; each protocol declares direction (with or against data flow).
   Attention is the first protocol; state-request (G-18 residual) becomes
   possible but is not built here.
2. M6.2 — attention protocol: `Attention(level: Float)` emitted by sinks;
   per-cell aggregation (max over downstream links), quantization to bands
   (`NONE|LOW|NORMAL|HIGH`), re-emit upstream only on band change. Default
   handling lives in port/link support, not cell logic.
3. M6.3 — host mapping: attention band → data-priority sub-bands in the host
   queue (management > router > data(HIGH>NORMAL>LOW) > drain preserved);
   fairness stride (default 16) services the oldest lower-band task after N
   higher-band dequeues; band NONE sustained for a policy window (counted in
   scheduling steps) → per-cell suspend via the existing park machinery (33);
   band > NONE on a parked cell → resume/replay.
4. M6.4 — glitch-freedom interaction: hosts emit suspended/resumed notices
   downstream for parked cells; `GlitchFree` gains a `WAIT|DEGRADE` policy —
   DEGRADE shrinks the wave frontier (reusing the unlink path) and restores
   on resume with catch-up semantics.
5. M6.5 — exit test.

All five landed (M6.1–M6.5); wire crossing for generic protocols and
transitive suspension notices deliberately deferred (noted in 34).

*Exit criterion — met (`AttentionGenerativeTest`, `GlitchFreeSuspensionTest`,
`AttentionSchedulingTest`): a generative attention harness (SimulationController) where
a randomized fan-in/fan-out graph with two sinks converges under 100 seeds
while (a) dropping one sink's attention quiesces exactly its exclusive
upstream cone (parked, zero loss on re-attention), (b) a low-attention but
live branch still makes progress under sustained high-attention load
(stride floor), and (c) a glitch-free diamond with one suspended branch
holds waves under WAIT and completes degraded waves under DEGRADE — each
with a control run (stride ∞ starves; WAIT-under-drop stalls) proving the
harness detects the failure it guards against.*

## Milestone 7 — Replication (interest-driven, convergent) ✅ DONE

*Goal: spec 42 real for the mergeable class — same logical cell live on
several hosts, converging by delta gossip over ordinary links.*

1. M7.1 — ref model (G-8): `CellRef(logicalId, instanceId)`; links,
   registries, and proxies bind to `logicalId`; wire frames carry both.
2. M7.2 — location sets: `LocationRegistry` from "one location" to a set per
   logicalId; `Peering` announcements generalize to multi-peer fan-out
   (lifting the M5.4 single-peer restriction); deterministic local pick
   (local > first remote) for non-replicated delivery.
3. M7.3 — replica gossip: replicated spawn links each replica's delta outlet
   to the others' inlets over existing bridges — no new sync protocol;
   restricted to cells whose deltas declare merge semantics (the tagged set
   family + counters); single-writer cells refuse replicated spawn (leader +
   followers deferred until a use case).
4. M7.4 — anti-entropy: on link re-establishment after partition/disconnect,
   the late-join catch-up path (state-as-delta, idempotent via tags) doubles
   as recovery — verified, not rebuilt.
5. M7.5 — interest-scoped extent: replica suspension when local attention
   (M6) decays to NONE; eviction stays manual (despawn) — automatic eviction
   deferred until there's memory pressure to justify it.
6. M7.6 — exit test.

All six landed. Notes: replicas are distinct instances of one logical id
(no location sets needed — one location per full ref); SetCell became a full
OR-set (tombstones) because multi-path gossip demands them; counters stay
derived-per-peer (delta addition is not idempotent — G-Counter deferred with
trigger: the first app needing replicated counter *state*).

*Exit criterion — met (`ReplicatedSessionTest`): a three-registry replicated
set session where each peer works against its local replica, a mid-run
partition isolates one peer, the heal converges by park/replay + idempotent
catch-up, and all replicas converge under 100 seeds with zero cross-replica
coordination beyond delta links — control run (no heal) proving the harness
detects divergence. MapCell/ListCell keep their documented single-writer
limits.*

## Milestone 8 — Trust boundaries (hierarchy + identity) ✅ DONE

*Goal: specs 31 (hierarchy) and 43 (posture) get their first mechanisms:
hosts as sandbox units, links that know who is asking.*

1. M8.1 — host hierarchy (G-28): parent/child host relations (a host spawning
   a host records the relation); shutdown cascade (drain children first);
   spawn-placement hook (parent may veto/redirect child spawns); a simple
   cell-count quota as the first resource limit (proof of the enforcement
   point, not a resource model).
2. M8.2 — identity (G-29 phase 1): `PeerId` on `LinkRequest.identity`
   populated end-to-end — local links carry the local peer, bridged links
   carry the transport peer's id (from the WebSocket hello); link policies
   can therefore express allowlists.
3. M8.3 — deny-by-default at the boundary: a bridge/host policy mode where
   unlisted peers' link requests are `Rejected` (not deferred); the demo peers
   run with an allowlist. Encryption stays a transport concern (wss:// is
   configuration, not kernel work); signing/Sybil resistance remain open in
   43.
4. M8.4 — exit test.

Notes: spawn *redirection* deferred (veto via quota exists; redirect when a
placement scheduler wants it); cross-wire link *handshakes* still don't
exist (M5.4 note stands), so identity-bearing LinkRequests are verified on
host-delivered management invocations — the same path a future connect
frame would take.

*Exit criterion — met (`HierarchyTest`, `TrustBoundaryTest`): an untrusted
child host under quota cannot exceed its subtree budget or outlive its
parent (cascade verified under drain), and a two-registry session where the
peer is not on the allowlist has every delivery refused at the boundary and
every identity-bearing link request rejected by policy, refusals observable
as ordinary dead letters — 100 seeds, control run proving open mode would
have delivered/linked.*

## Milestone 9 — Evolution (shadow deployment + promotion) ✅ DONE

*Goal: spec 53's claim made real — deployment as incremental graph
operations: candidate instances run as shadows, are judged by invariants,
and are promoted by link swap.*

1. M9.1 — effect classification (G-11 completion + G-32 marker): KSP lint
   enforcing push-only data contracts; an `Effectful` marker for
   side-effecting sink cells.
2. M9.2 — shadow mode (G-32): spawn a candidate instance (G-8) of a
   subgraph subscribed to production outlets via fan-out; `Effectful` cells'
   inlets are NoOp-served under a shadow policy; invariant cells watch the
   shadow.
3. M9.3 — promotion/rollback: link swap under a traffic-light window
   (buffer → relink → replay, 33/13); rollback is the same swap reversed.
4. M9.4 — state migration across instances (G-33):
   `exportState()/importState(prior)` invoked in the swap's drain window;
   cells that can't transform state fall back to upstream catch-up replay.
5. M9.5 — exit test.

Notes: promotion is orchestration over existing primitives — traffic light
(33), snapshot (G-25), subscribe/unsubscribe (13) — validating 53's central
claim that no new kernel mechanism is needed; rollback is the same call
with the roles exchanged. Continuous *live* shadowing (a long-running
sidecar runtime) remains future operational work, not kernel work.

*Exit criterion — met (`ShadowPromotionTest`): a candidate instance of a
running middle cell (different internal representation) shadows production
traffic without duplicating side effects (control: an unsuppressed
effectful sink double-fires), is judged by an invariant cell, and is
promoted mid-stream via the buffered swap window with state carried across
instances — the post-swap output stream identical to an unswapped
control, 100 seeds, zero loss, per-link FIFO preserved.*

## Milestone 10 — Durability + reconnect ("survive a restart") ✅ DONE

*Goal: nothing is lost to a process death — G-25's journal half lands, and
the WebSocket transport heals itself. A journal is a **bridge to disk**: the
same wire frames, encoded once, replayed through the same decode path.*

1. M10.1 — write-ahead journal (G-25 core): `Journal`
   (`InMemoryJournal` for seeded sims / `FileJournal` — length-prefixed,
   fsync-per-append, torn-tail tolerant) teed at the host intake (the single
   funnel: journal order = acceptance order = per-cell FIFO on replay);
   `recoverFrom` replays frames through the ordinary `WireCodec` decode after
   the graph is rebuilt. **Replay-stable identity** proved necessary: set
   tags and PN source slots derive from the ref (random ids resurrect
   removed elements / double-count on replay), and the tag counter rides
   snapshots.
2. M10.2 — checkpoints: `checkpoint(journal)` captures every `Stateful`
   snapshot as one record and compacts the log (`reset` = atomic tmp+rename);
   recovery = restore + tail replay. Tombstone/source-slot compaction rides
   checkpoints.
3. M10.3 — reconnect (G-15 residual): `WsConnection` retries with capped
   backoff (`shutdown()` = deliberate close); the re-hello re-runs the
   announcement catch-up; announcement hooks are deregistrable and die with
   their session.
4. M10.4 — the crash window hardened end-to-end: dead-socket sends raise
   `IntakeClosedException` so the registry **parks instead of dropping**
   before close detection; catch-up re-fires on every (re)announce
   (`Replication.maybeLink` and the demo's union chaining) — the transport
   cannot save bytes buffered into a dying socket, so recovery is
   end-to-end: journal for what the process accepted, anti-entropy for what
   the wire swallowed; registry publish hooks are failure-isolated
   (notifications, not participants). Demo peer mode gains `--journal`
   (deterministic writer refs, users file, routed writer→union links).

*Exit criterion — met, at both levels like M5: `CrashRecoveryTest` (sim) — a
three-peer replicated session where one peer's host, registry, queues, and
links are discarded mid-run (only its journal survives), rebuilt, recovered
by checkpoint-restore + replay, re-peered, converging under 100 seeds
including a write burst accepted-but-in-flight at the crash; the control run
(no journal) still converges via anti-entropy but LOSES the burst on every
seed — the loss only a write-ahead journal prevents. And
`CrashRestartConvergenceTest` (two OS processes) — a demo peer kill -9'd
mid-session relaunches with the same `--journal` directory, recovers its
state, reconnects, replays parked edits from the surviving peer, and both
browsers converge in both directions.*

## Milestone 11 — Incremental dataflow operations (the relational + aggregation suite) ✅ DONE

The operator library (M4.3) grows into a full suite drawing on the cited
inspirations (Differential Dataflow / DBSP for the relational core; Kafka
Streams / Flink / SQL for aggregation and windowing), mapped onto the OR-tag
algebra rather than importing Z-sets (idempotent merge is what gossip
requires — 42):

1. M11.1 — `FlatMapSetCell`/`mapSet`: tag pass-through with fold-with-union
   on output collision (last-wins remap is the control-tested divergent form).
2. M11.2 — `MintedTags` + `SemiJoinCell`/`differenceSet`: the non-monotone
   core; the tag-hygiene rule becomes normative (21).
3. M11.3 — `Aggregator` + `GroupByCell`/`.global` + count/sumOf/avgOf;
   aggregates are deterministic functions of membership; replication =
   per-peer recompute over replicated inputs.
4. M11.4 — minOf/maxOf/topK/collectToSet on full support multisets
   (bounded-memory top-k rejected as unsound under retraction).
5. M11.5 — `JoinSetCell`/`joinSet`/`crossProduct`: relational equi-join,
   one minted tag per live pair; `JoinCell` reframed as the LWW dictionary
   join.
6. M11.6 — outer joins as GraphDsl compositions; `Windows` assigners —
   windowing is key derivation over event-time-as-data (no wall clock, P1);
   windows never close, watermark eviction deferred with trigger.

Deferred with triggers (recorded in 24/91): OR-map keyed family, gossipable
aggregate outputs, weighted/bag semantics, session windows, watermark
eviction, PartitionedCell sharding (G-24 trigger now armed), atomic
outer-join cell.

*Exit criterion — met (`DataflowSuiteExitTest`): writers → union → mapSet
(many-to-one) → equi-join → antijoin → groupBy(sum) + groupBy(topK)
composed across two hosts equals a batch recompute over final writer state
on every one of 100 seeds, through category flaps (join re-entry),
block-list churn, a mid-run snapshot/restore twin, and a hosted late
joiner served by catch-up — with a retraction-blind control fold that
must diverge.*

## Working agreements (process, immediate)

- **Specs lead code**: changes to semantics update the relevant spec file in
  the same change-set; `⚠` markers are added/removed as facts change. The ADRs
  remain immutable history; this spec is the living surface.
- **Kernel review gate**: any addition to the kernel model must cite the
  principle (00/02) it serves and must survive the P1 test (meaningful in a
  single-threaded simulation).
- **One migration at a time**: C-4/G-1 (two kernels in-tree) is the largest
  source of drift risk; Milestone 1 exists to close it before new semantics
  are added.
