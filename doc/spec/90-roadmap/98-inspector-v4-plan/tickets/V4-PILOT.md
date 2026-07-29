# V4-PILOT — The first genuine same-logical-id replicated pilot across a real socket, with an inspector on each side, and a written account of what the inspector gets right and wrong

**Status**: Specified — not-started. (`:concord:docLints` accepts only
`Specified|Partial|Implemented|Exploratory|Historical|Living` as the first word
of this line; the ticket's own lifecycle word follows it. Move to
`Partial — in-progress` while working, `Implemented — merged` once merged.)
**Model:** `claude-opus-5` (effort xhigh) · **Escalate to:** `claude-opus-5`,
fresh session; if that fails, stop and re-split the ticket.
**Wave:** 10 · **Branches:** `ticket/v4-pilot`

## Context

This is an **evidence ticket**. It ships code — a pilot and a multi-JVM test —
but its equally-important second deliverable is a written findings section that
feeds the next replan checkpoint. The plan already has precedent for that
register: `V1C-DESIGN` was doc-only, `V1C-BENCH` is measurement-only. This one
is both: build the thing, then report honestly on what it revealed.

Read `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` in full
first, especially §"Binding constraints" (all ten) and §"Standing file split" —
they govern this ticket absolutely, and §"Standing file split" is why your file
claim is as narrow as it is.

### The open item this ticket exists to close

`doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1116-1123`, verbatim:

> **Graph identity (MRB-156)**: the min-uuid heuristic held through M5, and the
> instrument fix removed its worst artifact (self-inflicted renames). Still
> emergent and still unnamed for peers: a component spanning JVMs has one id
> per JVM-side view, and genuine same-logical-id replicas across a peer
> boundary remain undriven end to end (`ComponentIndex` now admits mirrored
> refs as vertices — M4-EVAL's line was revisited as predicted — **but no
> replicated pilot exercises it**). Membranes as naming boundary remain the
> real answer.

M4-EVAL's original line, which that paragraph says was revisited, is
`90-progress-log.md:889-893`:

> **Component identity vs replicas.** The id uses the logical `CellRef.id`, so
> two instances of one logical cell cannot flip it, but no genuinely replicated
> graph has been driven through the inspector. `ComponentIndex.sweep` requires
> *both* endpoints to be locally published for a link to connect them — that is
> the line M5-NET will need to revisit for mirrored/announced refs.

Nobody has driven that path. This ticket drives it.

### The state of replication in this repo — verified, not assumed

**No demo's production code uses replication.** `demo/shopping` peers two JVMs
with two *role-distinct* union cells addressed by role-derived refs
(`demo/shopping/src/main/kotlin/civictech/demo/Main.kt:57-65` derives
`myRole`/`peerRole` and mints `unionRef(name, role)`; `:164-170` chains each
union into its counterpart and installs `Peering.chainOnReannounce`). Those two
unions have **different logical ids** — they are counterparts, not replicas.
`demo/exchange`'s "mesh-replicated inputs"
(`demo/exchange/src/main/kotlin/civictech/demo/exchange/Main.kt:41-45`, `:95`)
is the same union-fold pattern under a different name. Neither is a replica.

**In-process replication is extensively tested — always over
`Peering.loopback`, under `SimulationController`, never over a socket.** The
canonical shape is `kernel/src/test/kotlin/civictech/cell/replication/ReplicationTest.kt`:
its `Peer` harness (`:33-46`) holds a `LocationRegistry`, a `ManagedHost`, a
bridge host, a `Peering.Side` and a `Replication(registry)`, and mints
`SetCell<String>(CellRef(logicalId, instanceId))` per peer (`:40-41`); the first
test bridges with `Peering.loopback(p.side, q.side)` (`:53`) and asserts
`onP.ref.sameLogical(onQ.ref) shouldBe true` while `onP.ref shouldNotBe onQ.ref`
(`:60-61`). The same shape recurs in `ReplicatedSessionTest.kt:60-66`,
`DeliveredWatermarkTest.kt:49,:67-69`, `GossipLinkIdempotenceTest.kt:50,:74`,
`GlitchFreeReplicaFrontierTest.kt:93-97`, `ShardedReplicaFrontierTest.kt:116-119`,
`UnknownJoinerFenceTest.kt:145-154`, and `kernel/src/test/kotlin/civictech/cell/app/DistributedCollaborativeAppTest.kt`.

**Nothing under `wire/src/test/` uses `Replication`.** The only mention is a
deferral: `wire/src/test/kotlin/civictech/wire/WsReconnectSmokeTest.kt:151`
says recovering pre-crash state is "the journal/replication story, kernel
CrashRecoveryTest territory."

So: no test and no demo anywhere exercises genuine same-logical-id replicas
across a real socket. That gap is the whole ticket.

**One correction to the framing above, which you should know before you
design.** `demo/exchange`'s *test* suite does use `Replication` with genuine
same-logical-id replicas —
`demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt:108`
constructs `Replication(registry)` and `:181-182`, `:245-246`, `:372-373`,
`:594-596` mint `CellRef(ordersId, 0)` / `(ordersId, 1)` / `(logicalId, i)`
pairs. But every one of them runs in-process over `Peering.loopback` under
`SimulationController` (`:174-178`, `:236-243`, `:294-295`, `:365-369`,
`:479-483`). It is a strong *shape* precedent and worth reading; it is not a
socket. The claim that survives is the narrow one: **no replicated graph has
ever crossed a real socket in this repository.**

### What already exists that you should build on, not rebuild

- **A two-JVM peering launcher with an inspector per side.**
  `demo/shopping`'s `main` (`Main.kt:309-335`) reads `--inspect-port <port>`
  (or env `INSPECT_PORT`) at `:310-311`, `--net-name <name>` at `:312`
  (defaulting to `"local"` at `:331`), `--listen <wsPort>` at `:319`,
  `--peer <ws-uri>` at `:320`, and `--journal <dir>` at `:321`. `stripPairs`
  (`:316`, defined `:338-345`) drops the inspector's `--flag value` pairs
  before `demoPort` reads the first non-`--` argument, so flag order is free.
  `demoPort` itself (`demo/shell/src/main/kotlin/civictech/demo/shell/DemoShell.kt:128-130`)
  skips **any** token starting with `--`, so a bare boolean flag needs no
  `stripPairs` entry. `Array<String>.value(name)` (`DemoShell.kt:148-151`) is
  the `--flag value` lookup.
- **Inspector wiring, already opt-in and already naming things.**
  `DemoApp.startInspector` (`Main.kt:267-299`) builds a `cellNames` map,
  registers the app host as `"shopping"` and the peering bridge host as
  `"shopping-bridge"` (`:283-286`), names the graph `"shopping"` anchored on
  `itemsUnion.ref` (`:293`), and — because the cross-JVM view chain is a
  `streamTo` that no kernel index records — *declares* the two cross-boundary
  streams with `InspectorServer.declareLink` (`:295-296`;
  `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:668-676`, whose
  KDoc at `:650-667` states the "reported, never inferred" rule). The
  `:inspect` dependency is already declared at
  `demo/shopping/build.gradle.kts:13`, and `libs.kotlinx.serialization` is
  already on the test compile classpath at `:20` for decoding inspector DTOs.
- **A two-JVM-plus-two-inspector harness with a runbook.** `V1-DEMO` (wave 3)
  shipped `scripts/demo-shopping-two-inspectors.sh` (85 lines, executable:
  builds `:demo:shopping:installDist`, `npm ci` in `inspect/ui` when needed,
  launches A on `18191 --listen 19201 --inspect-port 17091 --net-name jvm-a`
  and B on `18192 --peer ws://localhost:19201 --inspect-port 17092 --net-name
  jvm-b`, polls both `/api/inspect/topology`, starts two Vite dev servers on
  `5191`/`5192` with distinct `INSPECT_BACKEND`, and traps cleanup) plus
  `doc/demo-shopping-inspector.md` (125 lines: runbook, port table, narration,
  troubleshooting). Your script and runbook are siblings of these, not
  replacements — and must not reuse their ports.
- **Multi-JVM test scaffolding.** `testkit/src/main/kotlin/civictech/testkit/JvmPeer.kt`
  gives `freePort()` (`:17`), `launch(mainClass, vararg args)` (`:20-25`) and
  `destroy(vararg processes)` (`:30-34`); `awaitUntil(what, timeoutMs = 30_000)
  { … }` is `testkit/src/main/kotlin/civictech/testkit/AwaitUntil.kt:11-17`
  (polls every 100 ms, fails loudly, never hangs). Existing users, all
  `@Tag("multi-jvm")`: `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt:43`,
  `CrashRestartConvergenceTest.kt:62`, `TwoJvmInspectorTest.kt:26` (class-level;
  its launch pair is `:42-49`, its `TopologySnapshot` decode is `:29-32`), and
  `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeScaffoldTest.kt:94`
  and `:128`.
- **Named, reconnect-stable peer labels.** Wave 9's `V4-PEERID` threads `PeerId`
  to the registry, so the peer-hull label no longer flips on reconnect
  (`90-progress-log.md:1138-1141` is the problem it solves; `Peers.kt:57-83`
  is the label derivation, whose `peer-` prefix is `Peers.PREFIX` at `:67`).
  Build on it: your reconnect assertions may name the peer label, and your
  findings should say whether it held across the disconnect. If wave 9's shape
  differs from what you expect, adapt and say so — do not re-derive it.
- **The wire codec already carries what a replica mesh emits.**
  `kernel/src/main/kotlin/civictech/cell/wire/WireCodec.kt:125-146` registers
  `SetDelta` (`:142`) and `WatermarkDelta` (`:140`) as polymorphic values, so
  the gossip mesh and its delivered-watermark companion both serialize over the
  socket with **no wire change**. Verified for this ticket; if it turns out to
  be wrong at runtime, that is a finding, not an invitation to edit `wire/`.

### What replication is, in this codebase

`civictech.cell.replication.Replication` (`kernel/src/main/kotlin/civictech/cell/replication/Replication.kt`):
a symmetric gossip-mesh linker driven purely by registry announcements. Its
`init` (`:142-158`) installs `registry.onPublish { linkOut(it) }` and an
`onUnpublish` reconciliation, so **it must be constructed before peer
announcements arrive and must live for the process lifetime**. `replicate(cell,
host)` (`:166-207`) records the local replica, spawns it via
`host.managementInlet.call.spawn(cell)` (`:204` — so the caller must **not**
also spawn it), links it to every already-known replica of its logical id
(`:205`), and calls `trackDeliveries` (`:220-241`). `trackDeliveries` mints and
*also replicates* a `WatermarkCell` companion whose ref is
`CellRef(nameUUIDFromBytes("watermark:${dataRef.id}"), dataRef.instanceId)`
(`:98-99`) — so **each replicated data cell brings a second replicated cell with
it**, and the inspector will show both, on both sides.

The gossip link itself is `local.outlet.streamTo(sink, at = gossipRef(local,
other))` (`:442`), targeting a `HostedCellProxy` for the remote replica's
`deltaInlet` (`:431-432`). That is **not** `ManagedHost.connect`, so **no
`TopologyLink` records it** and the inspector cannot infer the mesh — exactly
the situation `startInspector`'s `declareLink` already handles for the union
chain. Anti-entropy on re-announce is `maybeLink`'s `fireLinked` at `:417-428`.

`SetCell` is `Replicable<SetDelta<E>>` and `DeliveryTracking`
(`kernel/src/main/kotlin/civictech/cell/data/SetCell.kt:30-31`), registers
`deltaInlet` at `:37`, exposes `outlet: Subscribe<Propagate<SetDelta<E>>>`
(`:27`) and `membership()` (`:84`). `UnionSetCell.inlet` is
`Serve<Propagate<SetDelta<E>>>` (`kernel/src/main/kotlin/civictech/cell/data/op/UnionSetCell.kt:19`),
so a `SetCell` outlet links into a union inlet with the same call shape the demo
already uses at `Main.kt:130`.

`CellRef.sameLogical(other) = id == other.id`
(`kernel/src/main/kotlin/civictech/cell/CellRef.kt:21`); `instanceId` defaults to
`0` (`:19`). `LocationRegistry.instancesOf(logicalId)` /
`replicasOf(logicalId)` are `kernel/src/main/kotlin/civictech/cell/host/LocationRegistry.kt:210-219`;
`setInterest`/`interestOf` are `:64-70`.

## Problem

1. **The inspector's graph-identity heuristic has never met a replica across a
   peer boundary.** `ComponentIndex`'s id is
   `g-<lexicographically-min cell uuid in the component>`
   (`inspect/src/main/kotlin/civictech/inspect/Graphs.kt:22-36`,
   `idOf` at `:179-180`), deliberately computed over the **logical** uuid so
   two instances of one logical cell cannot flip it (`:34-36`). Mirrored refs
   became vertices (`:63` is the vertex set; `addCell` `:78-90` is fed from
   `InspectorModel.mirroredPublish` at `InspectorModel.kt:633-636` and from the
   catch-up sweep's `registry.remoteRefs()` pass at `InspectorModel.kt:157-162`),
   and mirrored edges become edges (`addLink` `:102-108`, fed from
   `InspectorModel.linked` at `:594-602`). Nothing has ever exercised the
   combination. Two specific mechanisms are unexamined and must be observed,
   not reasoned about:
   - `sweep()` (`Graphs.kt:149-174`) drops any link whose endpoints are not
     both in `cells` (`:152`), so *which* vertices exist decides *whether* the
     two sides connect at all;
   - `components()` (`:124-132`) groups by the id **string**, so two genuinely
     disconnected components that happen to share a minimum uuid would be
     reported as one graph. With same-logical-id replicas, sharing a minimum
     uuid across JVM-side views is precisely what is at stake.
2. **No pilot exists to look at.** The two open questions MRB-156 asks — does
   one replicated component get one id or two across a peer boundary, and does
   a mirrored vertex genuinely join the component or merely appear beside it —
   are answerable only by running it. The v3 closing report says so in as many
   words.
3. **Nobody knows whether the replica mesh survives a real socket at all.**
   Every convergence, partition/heal and late-join property is proved over
   `Peering.loopback` under a deterministic scheduler. The real path adds frame
   serialization, a reconnect/re-hello handshake, park/replay across a genuinely
   dead connection, and OS-level process restart. All of it *should* work. None
   of it has been run.

## Solution direction

Three parts. Part 1 builds the pilot, part 2 proves it, part 3 is half the
point of the ticket.

---

### Part 1 — A replicated pilot that crosses a real socket

**The decision is made here, not left open: extend `demo/shopping` behind a
mode flag defaulted off. Do not create a new demo module.**

The trade-off, stated so the C-replan checkpoint can revisit it with your
report in hand:

- *For extending shopping*: everything the pilot must exercise already exists
  there and nowhere else — real `--listen`/`--peer` peering over `:wire`, the
  `:inspect` dependency, `--inspect-port`/`--net-name`, graph naming, cross-JVM
  `declareLink`, three multi-JVM tests, and a two-inspector script with a
  runbook. A new module would have to reconstruct all of it, and the
  reconstruction — not the replication — would be where the risk and the time
  went. It would also produce a *second* two-JVM launcher to keep working.
- *Against*: `demo/shopping`'s identity story is deliberately role-distinct
  counterparts (`unionRef(name, role)`), and adding same-logical-id replicas
  puts two different distribution models in one app. That is a legibility cost,
  and it is the reason the mode is opt-in and off by default. Whether it should
  stay there long-term is an explicit question in your completion report.

#### The shape

- **Flag**: a bare `--replicate` (presence-tested with `"--replicate" in args`,
  not `args.value`). `demoPort` skips `--`-prefixed tokens
  (`DemoShell.kt:128-130`), so it needs **no** `stripPairs` entry — but say so
  in a comment, because the neighbouring flags all do need one.
- **Logical id**: one deterministic constant, identical in both JVMs —
  `UUID.nameUUIDFromBytes("demo-replica:shared".toByteArray())` — mirroring
  `unionRef`'s derivation idiom (`Main.kt:64-65`). This constant *is* the
  ticket: same logical id on both sides is what has never been done over a
  socket.
- **Instance id from role**, so no discovery protocol and no extra flag: the
  listener (and solo) take `0`, the dialer takes `1`. `myRole`/`peerRole`
  already exist at `Main.kt:57-62`. Each side therefore knows the peer's replica
  ref deterministically — the same trick `startInspector` already relies on for
  the union counterparts (`Main.kt:261-265`).
- **Wiring**: hold `Replication(registry)` as a `DemoApp` field, constructed
  when the mode is on and **before** peering is established (its `init` installs
  the registry hooks — `Replication.kt:142-158`). Call
  `replication.replicate(sharedCell, host)`; do **not** also `manage.spawn` it
  (`Replication.kt:204` already does).
- **Join the visible graph**: `manage.link(sharedCell.outlet, itemsUnion.inlet)`
  — a real `ManagedHost.connect`, so it lands in the topology index and the
  shared replica belongs to the `"shopping"` component instead of floating as a
  singleton. Same call shape as `Main.kt:130`. Consequence to accept and to
  narrate in the runbook: in replicate mode, shared items appear in the items
  list. That is what makes the demo tellable.
- **Write path**: extend `/op` (`Main.kt:205-229`) with `action=share`, routed
  through the hosted inlet — `host.lookup(TypedRef<SetApi<String>>(sharedCell.ref))!!.inlet.call`,
  the idiom `writerFor` uses at `Main.kt:196-197` — never by calling the cell
  object directly, so the invocation is routed like every other. With the mode
  off, `action=share` returns 400 through the existing `else` branch.
- **State JSON**: add a `"shared":[…]` field **only** when the mode is on, so
  the default payload from `stateJson()` (`Main.kt:237-241`) is byte-identical
  and every existing test passes unmodified. Observe the replica with
  `host.observe(sharedCell.ref, View.set<String>())` in the same style as
  `Main.kt:132-134`.
- **Inspector naming and declaration**, inside `startInspector`
  (`Main.kt:267-299`), all gated on the mode:
  - name `sharedCell.ref` → `"shared"` and the peer's
    `CellRef(SHARED_ID, peerInstance)` → `"shared@$peerRole"`;
  - name both watermark companions too — `"shared-watermark"` and
    `"shared-watermark@$peerRole"`. `Replication.watermarkRef` is `internal` to
    `:kernel`, so the demo must **recompute** the derivation
    (`nameUUIDFromBytes("watermark:$SHARED_ID")`, instance id shared with the
    data replica) and must carry a comment citing `Replication.kt:98-99`,
    because a silent divergence would mislabel a node rather than fail;
  - `declareLink(sharedCell.ref, "outlet", peerSharedRef, "deltaInlet")` — the
    gossip subscription is a `streamTo` (`Replication.kt:442`), not a
    `connect`, so no `TopologyLink` exists for it and the inspector would
    otherwise never draw the mesh. This is the same opt-in annotation as
    `Main.kt:295-296`.
- **Solo mode**: `--replicate` without `--listen`/`--peer` is legal (a lone
  replica is a legal replica). Print one line saying the mesh has no peer, and
  carry on.

#### Non-negotiable: existing behaviour is untouched with the mode off

With `--replicate` absent there must be no `Replication`, no extra cells, no
extra links, no extra JSON field, no extra names, no extra declared edges — a
diff that changes only code reachable behind the flag. Every existing
`demo/shopping` test must pass **unmodified**. If you find yourself editing
`TwoJvmConvergenceTest`, `CrashRestartConvergenceTest` or `TwoJvmInspectorTest`
to keep them green, stop: that is the signal that the mode leaked.

---

### Part 2 — A multi-JVM test

New file `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmReplicaPilotTest.kt`,
`@Tag("multi-jvm")` at class level (`TwoJvmInspectorTest.kt:26`'s shape — the
class holds only multi-JVM tests). Built on `JvmPeer`, `awaitUntil` and
`HttpProbe`, beside the existing three.

It must assert, with bounded waits only:

1. **Convergence across the socket.** Launch A with
   `--listen <ws> --replicate --inspect-port <a> --net-name jvm-a` and B with
   `--peer ws://localhost:<ws> --replicate --inspect-port <b> --net-name jvm-b`,
   all ports from `JvmPeer.freePort()`. Post `action=share` at A, await the item
   in B's `"shared"` array; post at B, await at A. This is
   `doc/spec/40-distribution/42-replication.md` **[42-REPL-04]** — "replicas of
   one logical cell SHALL converge to equal folds at quiescence regardless of
   which replica accepted each write" — over a real socket, for the first time.
2. **One disconnect/reconnect cycle.** Kill the dialer with
   `destroyForcibly()`, await it down, write at A while it is gone (the write
   parks), relaunch B with the same arguments, await it up, await the parked
   write visible in B's `"shared"`, then write at B and await it at A. Use
   `CrashRestartConvergenceTest.kt:25-31`'s local `launch` helper shape (a
   per-process log file rather than `INHERIT`) — a killed peer's own log is the
   first diagnostic you will want — and its generous `timeoutMs = 45_000`
   awaits (`:72-103`).
   **Assert what the mechanism actually promises**: without `--journal`, B does
   not recover its own pre-crash writes from disk; what must hold is that after
   re-announce, anti-entropy catch-up (`Replication.kt:417-428`) leaves B
   holding everything A holds. If the observed behaviour is stronger or weaker
   than that, **report it** — do not add `--journal` to make a convenient
   assertion pass, and do not weaken the assertion to whatever happened to run.
3. **The inspector-visible facts, from both inspectors.** Decode
   `GET /api/inspect/topology` as `TopologySnapshot` exactly as
   `TwoJvmInspectorTest.kt:29-32` does. Assert:
   - each side's topology contains a node for the **peer's** mirrored shared
     replica — `host == null` is the remote discriminator
     (`TwoJvmInspectorTest.kt:57,66`) — whose encoded ref carries the shared
     logical uuid with the peer's instance id. `Node.ref` is
     `"<uuid>:<instanceId>"` (`inspect/src/main/kotlin/civictech/inspect/Dto.kt:33`,
     minted by `InspectorServer.encodeRef` at `InspectorServer.kt:910`), so
     "two instances of one logical id" is decidable from the payload alone;
   - both instances of the shared logical id are present in one side's view and
     are recognizable as instances of that one id.
   - **Capture, do not assert, the graph ids.** Fetch `GET /api/inspect/graphs`
     on both sides and record both bodies verbatim for the report. Do **not**
     assert equality or inequality of the two ids: that is the measurement this
     ticket exists to take, and an assertion written from a guess would either
     fail the ticket or freeze an undecided behaviour into a regression test.
4. **Discipline**: `awaitUntil` only — no `Thread.sleep`, no wall-clock
   scheduling assumptions, no assertions on scheduler timing. Destroy every
   launched process in a `finally` (`JvmPeer.destroy(...)`).

---

### Part 3 — The findings, which are half the point

Two artifacts: a runbook, and a findings section in your completion report.

#### 3a. The runbook and its script

- `doc/demo-shopping-replica-pilot.md` — top-level `doc/`, matching
  `doc/demo-shopping-inspector.md`'s register and naming convention. No
  `**Status**:` header is needed (`:concord:docLints` scans only
  `doc/spec/**`).
- `scripts/demo-shopping-replica-pilot.sh` — executable, modelled on
  `scripts/demo-shopping-two-inspectors.sh` (`#!/bin/bash`, `set -euo
  pipefail`, a `PIDS` array, a `trap cleanup EXIT INT TERM`, a `wait_for` poll
  loop, two Vite dev servers with distinct `INSPECT_BACKEND`), adding
  `--replicate` to both peers.

**Fresh ports.** Concurrent sessions squat ports, and both the `V1-DEMO`
recipe (`18191`/`18192`, `19201`, `17091`/`17092`, `5191`/`5192`) and
`inspect/ui/README.md`'s own recipe (`18081`/`18082`, `19101`,
`17071`/`17072`) must be runnable at the same time as yours. Use:

| purpose | peer A | peer B |
|---|---|---|
| shopping HTTP | `18291` | `18292` |
| `:wire` websocket | `19301` (shared — A listens, B dials) | |
| inspector | `17191` | `17192` |
| inspector UI (Vite dev) | `5291` | `5292` |

None of these collides with `7071` (`InspectorServer.DEFAULT_PORT`,
`InspectorServer.kt:819`), `8080`/`8081`, `5173`, or either existing recipe.
Reproduce this table in the runbook. If a port is genuinely occupied when you
run it, renumber consistently across script and runbook and say so in the
report.

The runbook must cover: what this demonstrates (two JVMs, one *logical* cell,
two instances, gossip over the real socket — and how that differs from the M5
role-distinct union chain the sibling runbook describes); prerequisites (JDK 21,
Node `^22.0.0` per `inspect/ui/package.json`'s `engines`, `curl`); the
one-liner; the port table; the manual four-command walkthrough; the narration
(open both inspector UIs, enter the `shopping` graph, select `shared` on both,
post an item on A, watch it land on B); an explicit note that replicate mode
adds **four** nodes per side — the local data replica, its watermark companion,
and the peer's mirrored two — so a reader is not surprised; cleanup; and
troubleshooting including the port-collision case.

It must also carry a **"What we observed"** section: the captured evidence
behind each finding below, with the commit sha and the date of the run.

#### 3b. The five questions — answered from observation, not from reading code

Each answer needs evidence: a captured response body, a described screenshot,
or a test assertion. "By inspection of `Graphs.kt` it should…" is not an answer
and will fail this ticket.

1. **Does the `g-<min-logical-uuid>` heuristic (`Graphs.kt:22-36`, `:179-180`)
   give the *same* graph id on both JVM-side views of one replicated component,
   or two different ids?** This is MRB-156's central unanswered question and the
   reason this pilot exists. Paste both `GET /api/inspect/graphs` bodies.
2. **How does the canvas render a logical id with two instances — one node or
   two? Is that right?** Describe what you see, then say which you think is
   correct and why. (`Node.ref` disambiguates by instance id; whether the human
   should see one thing or two is a design question the report should have an
   opinion on.)
3. **Do `ComponentIndex`'s mirrored-ref vertices actually connect the two sides
   into one component, or do they stay two components that merely mention each
   other?** Distinguish these two cases explicitly from the payload — they are
   *not* the same thing and can look identical from `GET /graphs` alone:
   (a) genuinely one component, joined by an edge whose endpoints are both in
   the vertex set (`sweep`'s `:152` test); (b) two disconnected components that
   `components()` (`:124-132`) merged into one map entry because they share a
   minimum uuid. Say which holds, and how you told them apart. Also say whether
   the answer changes with and without your `declareLink` — the declared gossip
   edge and the mirrored vertices are two independent mechanisms and the report
   must not conflate them.
4. **What does the inspector say about a replica whose peer is partitioned, and
   what does it say after the peer returns?** Use the reconnect cycle from part
   2 as the partition. Record what happens to the peer's mirrored nodes, to the
   declared edge, to the graph card, and to the error lane's `parked` rows
   during and after.
5. **Does anything in the error/wave-health lane raise a false positive against
   replicated traffic?** `inspect/src/main/kotlin/civictech/inspect/WaveHealth.kt`'s
   guard 7 (`:102-110`) promises that no row is ever derived from one source's
   silence relative to another's, and that `activityTick` (`:157-166`,
   consumed at `:330-332`) can only ever *suppress* a row. Replicas mint their
   own sources, and a gossip-fed fold advances on deltas from a source the
   local tap never saw. Run the pilot with an inspector attached and check
   whether `GET /api/inspect/errors`'s `waveHealth` stays empty. If a row
   appears, capture it in full.

#### 3c. Add these, which the pilot makes cheap to answer

6. **Node-count legibility**: how many nodes does each side's topology carry in
   replicate mode versus with the mode off? Is the watermark companion mesh
   comprehensible to a human looking at the canvas, or noise?
7. **Remote state reads**: select the *mirrored* replica in the inspector and
   record the exact `GET /api/inspect/cell/{ref}/state` response.
   `serveState` answers `CellState.UNAVAILABLE` for a cell with no local
   observation and no snapshot source (`InspectorServer.kt:552-568`), and
   remote state reads are deferred by the C-replan checkpoint on disclosure
   grounds. **Report the boundary as observed; do not work around it.**
8. **Flow on the declared edge**: does `GET /api/inspect/flow` show traffic on
   the declared gossip edge, and does it decay correctly when the peer is gone?

---

## Explicitly out of scope — each with its reason

- **Fixing anything the pilot reveals.** This ticket produces evidence; a fix
  is a later ticket, sized from the evidence. If the pilot reveals a defect it
  must be **reported precisely, not patched** — patching from inside a pilot
  ticket would mix an unreviewed inspector change into a demo diff, and would
  destroy the very measurement the C-replan checkpoint is waiting for. Report
  each defect precisely enough to be ticketed without re-running the pilot.
- **Any edit to `inspect/src/**` or `inspect/ui/**`.** `V1C-BE` owns
  `inspect/src/**` and `V1C-FE` owns `inspect/ui/**` in this same wave. Hard
  boundary — file claims must be disjoint for parallel tickets
  (`10-design-notes.md` §"Standing file split").
- **Any kernel or wire change.** Everything needed exists: `Replication`,
  `LocationRegistry.instancesOf`, `HostedCellProxy`, `Peering`, `WsTransport`,
  and a wire codec that already registers `SetDelta` and `WatermarkDelta`
  (`WireCodec.kt:140,:142`). If you conclude something genuinely requires new
  kernel surface, **SKIP it and flag it in the report**.
- **`concord/**`** — binding constraint 7; `:concord:check` stays green
  untouched.
- **Descriptors over the wire, and remote state reads.** Both deferred by the
  C-replan checkpoint on disclosure grounds. The pilot will make their absence
  visible (a remote cell's state answers `unavailable`) and must **say so**
  rather than working around it — see finding 7.
- **Editing `doc/spec/90-roadmap/97-inspector-plan/20-api-contract.md`** —
  orchestrator-owned (constraint 8). Flag contract observations in the report.
- **Editing any plan document** other than this ticket's own `**Status**:`
  line.

## Files expected to touch

- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt` — the `--replicate`
  flag, the `Replication` field, the shared replica and its link, the `/op`
  `share` action, the conditional `"shared"` JSON field, and the inspector
  names + `declareLink`.
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmReplicaPilotTest.kt` —
  new.
- `demo/shopping/README.md` — a short section for the new mode, beside "Two
  machines, one graph (M5)" (`README.md:17-40`), pointing at the runbook.
  Optional but preferred.
- `scripts/demo-shopping-replica-pilot.sh` — new, executable.
- `doc/demo-shopping-replica-pilot.md` — new.
- This ticket's `**Status**:` line.

**Do not modify**: anything under `inspect/`, `kernel/`, `wire/`, `nature/`,
`gen/`, `testkit/`, `concord/`; any other demo module; any plan document.
`demo/shopping/build.gradle.kts` should need no change (`:inspect` at `:13`,
`libs.kotlinx.serialization` at `:20`, `:testkit` at `:15`) — if you find it
does, that is a report line, not a silent edit.

## Read first

- `doc/spec/90-roadmap/98-inspector-v4-plan/10-design-notes.md` §"Binding
  constraints" (all ten) and §"Standing file split".
- `doc/spec/90-roadmap/97-inspector-plan/90-progress-log.md:1114-1141` — the
  open items this ticket feeds, including the peer-identity item `V4-PEERID`
  closed in wave 9; and `:889-893` — M4-EVAL's original component-identity line.
- `doc/spec/40-distribution/42-replication.md` — in full, but at minimum
  §"What replication must mean here" and **[42-REPL-04]** (what convergence is
  actually promised), the §Anti-entropy discussion, and §"Delivered watermarks"
  (why a companion cell exists at all). Its header records that the mergeable
  set family is the implemented case.
- `kernel/src/main/kotlin/civictech/cell/replication/Replication.kt` — whole
  file. Especially the class KDoc (`:23-42`), `init` (`:142-158`), `replicate`
  (`:166-207`), `trackDeliveries` (`:220-241`), `evict` (`:284-311`), `linkOut`
  (`:391-404`), `maybeLink` (`:406-443`) and `gossipRef` (`:445-476`).
- `kernel/src/main/kotlin/civictech/cell/replication/InstanceSet.kt` — the
  interest/assignment vocabulary the mesh shares with partitioning.
- `kernel/src/test/kotlin/civictech/cell/replication/ReplicationTest.kt` — the
  canonical in-process shape you are lifting to a socket; `:33-46` and `:48-76`
  in particular.
- `demo/exchange/src/test/kotlin/civictech/demo/exchange/ExchangeCompositionExitTest.kt:100-190`
  — the most sophisticated existing replica harness, still loopback-only.
- `demo/shopping/src/main/kotlin/civictech/demo/Main.kt` — whole file.
- `demo/shopping/src/test/kotlin/civictech/demo/TwoJvmConvergenceTest.kt`,
  `CrashRestartConvergenceTest.kt`, `TwoJvmInspectorTest.kt` — the three
  precedents; the second is your reconnect recipe, the third your inspector
  assertion recipe.
- `testkit/src/main/kotlin/civictech/testkit/JvmPeer.kt`,
  `AwaitUntil.kt`, `HttpProbe.kt`.
- `inspect/src/main/kotlin/civictech/inspect/Graphs.kt` — whole file;
  `ComponentIndex` is the subject of findings 1 and 3.
- `inspect/src/main/kotlin/civictech/inspect/InspectorModel.kt:146-175`
  (`sync`), `:594-607` (`linked`), `:633-645` (`mirroredPublish`).
- `inspect/src/main/kotlin/civictech/inspect/WaveHealth.kt:90-170` — the guards,
  for finding 5.
- `inspect/src/main/kotlin/civictech/inspect/InspectorServer.kt:640-680`
  (`nameGraph`, `declareLink`) and `:545-570` (`serveState`'s `unavailable`).
- `inspect/src/main/kotlin/civictech/inspect/Peers.kt:50-90` — peer label
  derivation, as changed by `V4-PEERID`.
- `scripts/demo-shopping-two-inspectors.sh` and
  `doc/demo-shopping-inspector.md` — the sibling script and runbook whose
  register yours matches and whose ports yours avoids.
- `buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:29-53` — how the `multi-jvm`
  tag is gated, and the 5-minute per-method timeout your test runs under.

## Acceptance criteria

- [ ] `demo/shopping` gains a `--replicate` mode that spawns a genuine
      same-logical-id replica per JVM — `CellRef(SHARED_ID, instanceId)` with a
      deterministic shared `SHARED_ID` and role-derived instance ids — wired
      through `civictech.cell.replication.Replication(registry)`.
- [ ] The two replicas gossip over a real `--listen`/`--peer` WebSocket, **not**
      `Peering.loopback`, and both converge at quiescence in both directions.
- [ ] The shared replica is linked into the visible `"shopping"` component with
      a real `manage.link`, and the cross-JVM gossip subscription is reported to
      the inspector with `declareLink`.
- [ ] With `--replicate` absent, the diff changes nothing observable: no extra
      cells, links, JSON fields, names or declared edges. **Every existing
      `demo/shopping` test passes unmodified** — no test file edited to
      accommodate the mode.
- [ ] `TwoJvmReplicaPilotTest` exists, is `@Tag("multi-jvm")`, and asserts
      bidirectional convergence across the socket ([42-REPL-04]).
- [ ] The same test survives one disconnect/reconnect cycle: a killed dialer,
      a write that parks at the listener while it is down, a relaunch, the
      parked write arriving, and a fresh write in the reverse direction.
- [ ] The test asserts the inspector-visible facts on **both** sides: each
      side's topology contains the peer's mirrored shared replica (`host ==
      null`), and both instances of the one logical id are recognizable as such
      from the encoded `"<uuid>:<instanceId>"` refs.
- [ ] The test **captures** both sides' `GET /api/inspect/graphs` bodies for the
      report and asserts nothing about the equality of the two graph ids.
- [ ] No `Thread.sleep`, no scheduler-timing assertion; `awaitUntil` only, and
      every launched process destroyed in a `finally`.
- [ ] `scripts/demo-shopping-replica-pilot.sh` exists, is executable, launches
      both peers with `--replicate` plus an inspector each and two Vite dev
      servers, and cleans up on Ctrl+C with no leftover `java`/`vite`
      processes.
- [ ] `doc/demo-shopping-replica-pilot.md` exists, covers everything listed
      under §3a, and reproduces the port table above — no port colliding with
      `scripts/demo-shopping-two-inspectors.sh`, `inspect/ui/README.md`'s
      recipe, `7071`, `8080`/`8081` or `5173`.
- [ ] The runbook carries a **"What we observed"** section with the captured
      evidence, the commit sha, and the run date.
- [ ] Findings 1–5 are each answered **from observation**, with evidence
      (a captured response body, a described screenshot, or a test assertion).
      Findings 6–8 are answered too.
- [ ] Every defect the pilot revealed is reported and **none is patched**.
- [ ] No edits under `inspect/`, `kernel/`, `wire/`, `nature/`, `gen/`,
      `testkit/`, `concord/`, or any other demo module. No generated/build
      output. No unrelated files.

## Verify

The `multi-jvm` tag is gated by project property
(`buildSrc/src/main/kotlin/kotlin-jvm.gradle.kts:37-40`): a plain `test` runs
**everything including** the tagged tests; `-PmultiJvmOnly` runs *only* them;
`-PexcludeMultiJvm` runs everything else. So:

```bash
# the new pilot alone (fastest loop) — the tag is included by default
./gradlew :demo:shopping:test --tests 'civictech.demo.TwoJvmReplicaPilotTest'

# only the multi-JVM lane in this module
./gradlew :demo:shopping:test -PmultiJvmOnly

# the fast lane, proving nothing outside the tag regressed
./gradlew :demo:shopping:test -PexcludeMultiJvm

# the module gate (runs both lanes)
./gradlew :demo:shopping:test

# nothing the inspector serves may regress
./gradlew :inspect:test

# the repository gate
./gradlew test
```

Then run the script end to end at least once and drive the narration manually —
that run is where findings 2, 4, 6 and 7 come from; they cannot be answered
from the test alone.

Any live server you start must bind an ephemeral or explicitly chosen
non-default port. Concurrent sessions squat `7071`/`8080`
(`00-orchestration.md` §Sandbox), and other sessions may be running the
`V1-DEMO` script on its ports at the same time.

## Report on completion

- Checks run and their results, including which of the three tag lanes you ran
  and the exact invocations.
- **The five findings, answered**, each with its evidence attached — plus
  findings 6–8.
- **Whether the min-uuid component heuristic survives replication across a peer
  boundary: a yes/no with evidence.** This is MRB-156's gate and the single most
  important line in your report. If the answer is "yes but for the wrong
  reason" (e.g. two disconnected components sharing an id string), say exactly
  that — it is a different answer from "yes".
- **Every defect observed but deliberately not fixed**, each stated precisely
  enough to be ticketed without re-running the pilot: what you did, what you
  expected, what happened, which file and line you believe is responsible, and
  what evidence you captured.
- **Whether a replicated pilot belongs in `demo/shopping` long-term or wants
  its own module** — now that you have built one and know what it cost. Name the
  concrete costs you paid (branching in `Main.kt`, extra nodes on the canvas,
  the two distribution models sitting side by side) and what a separate module
  would have cost instead.
- Whether the reconnect behaviour matched what `Replication.kt:417-428`'s
  anti-entropy promises, and anything that differed.
- Whether `V4-PEERID`'s named, reconnect-stable peer labels held across your
  disconnect/reconnect cycle — the first real consumer test of that wave-9
  change.
- **Flag to the orchestrator** (contract observations for
  `20-api-contract.md` — do not edit it yourself): anything the replicated case
  makes ambiguous in the contract's current wording, in particular whether
  `Node.ref`'s instance id and `GraphSummary.id`'s stability are documented
  well enough for a client to render two instances of one logical cell.
- **Flag separately, as input to the C-replan checkpoint**: what a *correct*
  answer to graph identity across a peer boundary would need. The progress log
  says "membranes as naming boundary remain the real answer" — after building
  this, say concretely what that would have to provide, and what the min-uuid
  heuristic's specific failure mode is under replication. This is the most
  valuable output of the ticket.
- Files actually touched, and any not in the claim above.
- Anything specified here you could not do, and why — in particular anything
  you SKIPPED rather than reaching into `kernel/`, `wire/` or `inspect/`.
