# Two-JVM Replicated Pilot with Inspector UI (V4-PILOT)

The first genuine **same-logical-id replicated** graph across a real socket in
this repository, with one inspector per side.

## What this demonstrates — and how it differs from the sibling runbook

`doc/demo-shopping-inspector.md` shows the **M5 role-distinct union chain**: two
JVMs, each with its *own* `items` union under its *own* logical id
(`unionRef("items", "listener")` and `unionRef("items", "dialer")` are two
different UUIDs), streaming into each other. Those are **counterparts**, not
replicas — the graph genuinely has two cells, and each JVM owns one.

This runbook shows something the repository had never driven end to end: **one
logical cell in two places**. Both JVMs mint `CellRef(SHARED_ID, n)` for the
same deterministic `SHARED_ID`
(`UUID.nameUUIDFromBytes("demo-replica:shared")`), differing only in
`instanceId` — the listener takes `0`, the dialer takes `1`. They are wired
through `civictech.cell.replication.Replication`, whose symmetric gossip mesh
links each local replica's delta outlet to every other replica's `deltaInlet`
it learns about from registry announcements. Writes accepted at either replica
converge at both — `doc/spec/40-distribution/42-replication.md` **[42-REPL-04]**
— and here that convergence crosses a real WebSocket rather than
`Peering.loopback`.

Every prior proof of replication in this repository (`ReplicationTest`,
`ReplicatedSessionTest`, `GlitchFreeReplicaFrontierTest`,
`ExchangeCompositionExitTest`) runs in-process, over `Peering.loopback`, under a
deterministic `SimulationController`. Nothing under `wire/src/test/` uses
`Replication` at all. This pilot is the socket case.

**The mode is opt-in and off by default.** Without `--replicate`,
`demo/shopping` is byte-identical to what it was: no `Replication`, no extra
cells, no extra links, no `"shared"` field in the state JSON, no extra inspector
names, no extra declared edges.

## Prerequisites

- JDK 21 toolchain (ComputeNet repository standard)
- Node.js `^22.0.0` (`inspect/ui/package.json`'s `engines`)
- `curl`

## One-liner

From the repository root:

```bash
./scripts/demo-shopping-replica-pilot.sh
```

Ctrl+C stops all four processes cleanly (two JVMs, two Vite dev servers).

## Ports

| purpose | peer A | peer B |
|---|---|---|
| shopping HTTP | `18291` | `18292` |
| `:wire` websocket | `19301` (shared — A listens, B dials) | |
| inspector | `17191` | `17192` |
| inspector UI (Vite dev) | `5291` | `5292` |

None of these collides with `scripts/demo-shopping-two-inspectors.sh`
(`18191`/`18192`, `19201`, `17091`/`17092`, `5191`/`5192`), with
`inspect/ui/README.md`'s recipe (`18081`/`18082`, `19101`, `17071`/`17072`),
with `7071` (`InspectorServer.DEFAULT_PORT`), with `8080`/`8081`, or with
`5173` (Vite's own default). All three recipes can run at the same time.

## Manual walkthrough

1. Build the distribution:
   ```bash
   ./gradlew :demo:shopping:installDist
   ```

2. Install the UI dependencies once:
   ```bash
   cd inspect/ui && npm ci && cd ../..
   ```

3. Peer A — the listener, replica instance `0`:
   ```bash
   ./demo/shopping/build/install/shopping/bin/shopping 18291 \
     --listen 19301 --replicate --inspect-port 17191 --net-name jvm-a
   ```

4. Peer B — the dialer, replica instance `1`:
   ```bash
   ./demo/shopping/build/install/shopping/bin/shopping 18292 \
     --peer ws://localhost:19301 --replicate --inspect-port 17192 --net-name jvm-b
   ```

5. Inspector UI for A:
   ```bash
   cd inspect/ui
   INSPECT_BACKEND=http://localhost:17191 npx vite --port 5291 --strictPort
   ```

6. Inspector UI for B:
   ```bash
   cd inspect/ui
   INSPECT_BACKEND=http://localhost:17192 npx vite --port 5292 --strictPort
   ```

`--replicate` is a **bare boolean flag** — no value follows it. It needs no
`stripPairs` entry in `Main.kt` because `demoPort` skips any token starting with
`--` (`DemoShell.kt:128-130`), so it can never be mistaken for the demo's HTTP
port and flag order stays free.

`--replicate` without `--listen`/`--peer` is legal: a lone replica is a legal
replica, and the process says so on stdout (`the replica mesh has no peer to
gossip with`).

## The story to narrate

1. Open both inspector UIs side by side: `http://localhost:5291` and
   `http://localhost:5292`.

2. Enter the `shopping` graph in both. Both sides show the *same* graph card —
   see "What we observed" below for why, and for the caveat.

3. Find the `shared` cell on each side. Each side also carries the peer's
   instance, named `shared@dialer` (on A) / `shared@listener` (on B).

4. Write onto the replica — this is a different action from the shopping list's
   `add`:
   ```bash
   curl -s -X POST http://localhost:18291/op -d 'user=alice&action=share&item=flour'
   curl -s http://localhost:18292/events | head -1     # B: "shared":["flour"]
   ```

5. And back the other way, to show that convergence does not depend on which
   replica accepted the write ([42-REPL-04]):
   ```bash
   curl -s -X POST http://localhost:18292/op -d 'user=bob&action=share&item=yeast'
   curl -s http://localhost:18291/events | head -1     # A: "shared":["flour","yeast"]
   ```

6. Both browser tabs (`http://localhost:18291`, `http://localhost:18292`) also
   show the shared items in the ordinary items list: the shared replica is
   linked into the `items` union with a real `ManagedHost.connect`, on purpose,
   so it belongs to the visible `shopping` component instead of floating as a
   singleton. That link is what makes the pilot narratable on the canvas.

### Six extra nodes per side — expected, not a bug

| node | where | why |
|---|---|---|
| `shared` | local | this JVM's replica of the shared logical cell |
| `shared-watermark` | local | the delivered-watermark companion `Replication.trackDeliveries` mints per replicated cell (`Replication.kt:220-241`) — itself `Replicable`, so it gossips over the same mesh |
| (unnamed `ObserveCell`) | local | the demo's own `host.observe(sharedCell.ref, …)` fold behind the `"shared"` JSON field — the same cost `items`/`votes`/`produce`/`wanted` each already pay |
| the peer's three | mirrored | announced across the bridge |

**Measured** on a live run, both sides identical, with one writing user per JVM
in each configuration:

| | `--replicate` off | `--replicate` on |
|---|---|---|
| topology nodes / edges per side | 24 / 16 | **30 / 21** |
| `shopping` graph card | 16 cells · 2 nets | **20 cells · 2 nets** |
| graph cards total | 9 | **10** |

So: six added nodes per side, of which **four** land inside the named
`shopping` component and **two** — the watermark companions — form the tenth,
edgeless graph card discussed in finding 3 below. (The V4-PILOT ticket
predicted four added nodes; the measured six includes the demo's own
observation cell on each side, which the prediction did not account for.)

## Cleanup

The script traps `EXIT INT TERM` and kills both JVMs and both Vite servers.
Running manually, close each terminal or `kill` each process, then check that
nothing still listens on `18291`, `18292`, `19301`, `17191`, `17192`, `5291`,
`5292`.

## Troubleshooting

**Port already in use.** Concurrent sessions squat ports. Check with
`lsof -nP -iTCP:18291 -sTCP:LISTEN`. If a port is genuinely taken, renumber
consistently across this table and the script (they must agree), and say so in
the run report. Do **not** fall back to `8080`/`8081`/`7071`/`5173`, which the
port choice above exists to avoid.

**Peers not connecting.** B dials A, so A must be up first; the script starts A
first and waits for both inspectors before launching the UIs. If B never
converges, read B's stdout — the launcher prints the shared logical id and this
JVM's instance id in replicate mode, so a mismatched id is visible immediately.

**`action=share` returns 400.** The mode is off. `--replicate` must be passed to
*both* peers; a peer without it has no replica, so there is nothing to gossip
with.

**Inspector UI not loading.** Verify `INSPECT_BACKEND` and that
`/api/inspect/topology` responds on that port.

---

## What we observed

Run date **2026-07-31**, on commit `70f7c42` (the `ticket/v4-pilot` branch
point) plus this ticket's working tree. Evidence came from two independent
sources: `TwoJvmReplicaPilotTest`, which writes response bodies to
`demo/shopping/build/v4-pilot-evidence/`, and a live
`./scripts/demo-shopping-replica-pilot.sh` run driven by hand through both
inspector UIs.

UUIDs below are from specific runs. The **name-derived** ones reproduce exactly
— `shared` = `4f421498-bf2d-3b19-a493-2a126be9b301`, `shared-watermark` =
`98ebe0fa-aff5-3add-9e66-afd614f2e2c4`, the declared gossip edge =
`e714344e-f077-3112-ac0b-0cdaf5d1a267`, the union refs. The DSL-spawned ones
(`produce`, `wanted`, per-user writer cells) are random per process and do not,
which is exactly why the graph ids below move between runs.

### 1. Does the min-uuid heuristic give one graph id or two across the boundary?

**One — the same id on both sides.** Both `GET /api/inspect/graphs` bodies
report the named graph identically:

```json
{"id":"g-0aa35ea4-aa2b-4f15-a697-b0cb7c47197a","name":"shopping","cells":20,
 "hosts":1,"nets":2,"health":{...},"lifecycle":"hot"}
```

A and B returned **the same ten graph ids, in the same order, with the same
`cells`, `nets`, `health` and `lifecycle` on every card** (both bodies 1599
bytes). The bodies are not byte-identical, and the one field that differs is the
correct one: `hosts` counts distinct non-null `Node.host`, and a mirrored cell
has `host: null`, so each side reports `hosts:1` for the singleton cards it owns
and `hosts:0` for the singleton cards the peer owns. **`id` never differs.**

The agreement is structural rather than lucky: each side mirrors every ref the
other announces, so both compute `idOf` over the *same* vertex set, and `idOf`
uses the **logical** uuid (`Graphs.kt:180`), which a mirrored ref preserves.

**The stability caveat, from the reconnect run.** The minimum uuid in that
component belonged to a *randomly minted* cell (peer A's `wanted`,
`0aa35ea4-…`). When the peer disconnected, A's graph became
`g-25f43d46-…` (10 cells, 1 net); when it returned as a fresh process with
fresh random uuids, the graph became `g-1dfe7365-…` (20 cells, 2 nets) — and
`1dfe7365-…` is a **peer-side** cell. So the named graph's id is decided by
whichever JVM happens to own the lexicographically smallest uuid at that moment,
and it changes on every peer connect and disconnect. `Graphs.kt:37-45` documents
merge/split instability as intended; what this run adds is that under peering
the id is *also* not owned by the local process.

### 2. How does the canvas render a logical id with two instances?

**Two nodes.** Each instance is its own vertex — `Node.ref` is
`"<uuid>:<instanceId>"` (`Dto.kt:33`), and `ComponentIndex`'s vertex set is
`CellRef`, which includes `instanceId`. Observed live in the canvas at
`http://localhost:5291`, inside the `shopping` graph with the network-hosts
toggle on: two boxes in two dashed net hulls, `shared` inside `jvm-a` and
`shared@dialer` inside `jvm-b PEER`.

Their two detail panels, verbatim from the live run:

| field | `shared` (local) | `shared@dialer` (mirrored) |
|---|---|---|
| Class | `SetCell` | `<unknown>` |
| Manifests | `D` `R` | — |
| Ports | `deltaInlet` (IN), `inlet` (IN), `outlet` (OUT) | — |
| Process host | `shopping` | `not reported (remote)` |
| Network host | `jvm-a` | `jvm-b` `PEER` |
| Links | in 0 · out 3 · taps 0 | in 1 · out 2 · taps 0 |
| State | live fold: `flour, x1…x6` | `remote — state/flow/errors not available in this milestone` |

Two nodes is the right default: the instances are on different machines, hold
independently-timed folds, and one can be partitioned away while the other keeps
accepting writes — collapsing them into one box would hide exactly what a
distributed-systems debugger opens the inspector to see.

**What is missing is any indication that they are the same logical cell.**
Nothing in either panel names the logical id, the instance id, or the sibling.
The only thing on screen that pairs them is the string `shared` /
`shared@dialer` — and that is a *name this demo supplied*, not something the
inspector derived. Strip the app's `cellNames` map and the two nodes are
indistinguishable from two unrelated cells. The gap is a grouping/"instance N of
M" affordance, not a merged node.

### 3. Do the mirrored-ref vertices actually connect the two sides?

**Both answers occur in one payload — and telling them apart matters.**

The two cases the ticket asks to distinguish are:
(a) genuinely one component, joined by an edge whose endpoints are both in the
vertex set (`sweep`'s `Graphs.kt:152` test); (b) two disconnected components
that `components()` (`Graphs.kt:124-132`) merged into one map entry because they
share a minimum uuid.

Both were measured by re-running `sweep()`'s exact algorithm over the captured
`GET /topology` payloads (30 nodes, 21 edges per side):

- **The data replica pair is case (a).** One flood-fill of 20 members contains
  both `4f421498-…:0` and `4f421498-…:1`. Real edges join them; endpoints are
  both published.
- **The watermark companion pair is case (b) — a false positive.** `sweep()`
  yields **two disconnected singleton components**,
  `98ebe0fa-…:0` and `98ebe0fa-…:1`, with **no edge between them and no edge
  incident to either**. `components()` merges them into one map entry because
  `idOf` maps both to `g-98ebe0fa-…`. `GET /graphs` then reports:

  ```json
  {"id":"g-98ebe0fa-aff5-3add-9e66-afd614f2e2c4","name":null,"cells":2,
   "hosts":1,"nets":2,"health":{...},"lifecycle":"hot"}
  ```

  A card claiming a two-cell graph spanning two network hosts, for two cells
  that are not connected by anything the inspector knows about. `hosts:1` +
  `nets:2` is the only tell, and it is not a reliable one.

  **Confirmed visually.** The navigator's thumbnail for that card shows two
  isolated dots and no line. Opening it shows `shared-watermark` (`WatermarkCell`,
  in the `jvm-a` hull) and `shared-watermark@dialer` (`<unknown>`, in the
  `jvm-b PEER` hull) at opposite ends of an otherwise empty canvas, with no edge
  between them. It is literally "two components that merely mention each other",
  drawn as one graph.

  (Aside, flagged for the FE track, not fixed here: while that card was open the
  header breadcrumb still read `shopping` — the name of the previously-opened
  graph — rather than the id of the graph on screen.)

**With and without `declareLink` — the two mechanisms are independent, and the
answer does not change.** Dropping the declared gossip edge
(`4f421498-…:0 → 4f421498-…:1`) from the swept edge set leaves both replicas in
the *same* 20-member component. What actually joins the sides is the **mirrored
topology edges**: B's own `manage.link(shared.outlet → itemsUnion.inlet)` is a
real `ManagedHost.connect`, so it is indexed, mirrored to A, and joins
`shared@dialer` to `items@dialer`, which A's declared union-chain edge already
reaches. The declared gossip edge is therefore *documentation of the mesh* — it
makes the gossip visible as an edge — not the thing that makes the component
one. The watermark pair proves the converse: mirrored vertices with **no** edge
of either kind stay two components and are merged only by the id string.

### 4. What does the inspector say about a partitioned replica, and after it returns?

From the reconnect cycle in `TwoJvmReplicaPilotTest`:

- **During the partition** every one of the peer's mirrored nodes is retracted
  (`nodes.filter { host == null }` goes empty), including `shared@dialer` and
  `shared-watermark@dialer`. The graph card shrinks to **10 cells / 1 net** and
  its **id changes** (in the test run `g-1dfe7365-…` → `g-25f43d46-…`; in the
  live script run `g-0c0f714e-…` → `g-183abe9f-…`). The declared gossip edge
  survives the peer's departure by design
  (`InspectorServer.declareLink`'s KDoc: the subscription is still there and
  still emitting into the park queue) but is dropped from the *swept* adjacency,
  because `sweep` (`Graphs.kt:152`) requires both endpoints published and the
  target is gone. The `g-98ebe0fa-…` card stays, now honestly one cell.
- **`parked` stays empty.** `GET /errors` reported
  `{"counters":{…,"parked":0,…},"parked":[]}` throughout the partition, in both
  the test run and the live run, even though writes were accepted at A while B
  was down and were demonstrably parked — B received them on return. So the
  error lane's `parked` rows did **not** surface the registry's park queue for
  the replica mesh.
- **After the peer returns** the mirrored nodes come back, the card returns to
  20 cells / 2 nets under a *new* id, and the parked write plus everything else
  A held is on B — anti-entropy catch-up did its job.
- **The peer label held.** `V4-PEERID`'s named, reconnect-stable hull label was
  `jvm-b` on every mirrored node both before the kill and after the relaunch —
  the first real consumer test of that wave-9 change, and it passed.

### 5. Does the error/wave-health lane raise a false positive against replicated traffic?

**No.** `GET /api/inspect/errors` reported `"waveHealth":[]` and
`"counters":{"waveHealth":0}` on both sides at every capture point: after
bidirectional convergence, during the partition, and after the reconnect. The
gossip-fed fold advances on deltas from a source the local tap never saw, which
is exactly the shape guard 7 (`WaveHealth.kt:102-110`) promises never to derive
a row from, and it did not. Full bodies in
`build/v4-pilot-evidence/errors-{A,B}.json`,
`partitioned-errors-A.json`, `reconnect-errors-A.json`.

### 6. Node-count legibility

**30 nodes / 21 edges per side in replicate mode; 26 / 20 with the mode off.**
Four added nodes per side, two of which are watermark companions that carry
**zero edges** — they are unattached boxes on the canvas whose only relationship
to anything is the ghost graph card from finding 3. They are not comprehensible
to a human looking at the canvas: nothing on screen says what a
`shared-watermark` is for, why there are two, or why they connect to nothing.
The demo names them (`shared-watermark`, `shared-watermark@dialer`) precisely
because unnamed they would be worse.

### 7. Remote state reads

Selecting the **mirrored** replica gives exactly the deferred boundary, verbatim:

```json
{"ref":"4f421498-bf2d-3b19-a493-2a126be9b301:1","frontier":null,
 "kind":"unavailable","value":null,"staleMs":0}
```

The local instance answers normally, from the `SnapshotSource` V0 wired:

```json
{"ref":"4f421498-bf2d-3b19-a493-2a126be9b301:0","frontier":null,
 "kind":"snapshot","value":["flour","yeast"],"staleMs":0}
```

Reported as observed, not worked around. Note the asymmetry this creates for a
*replica*: the two nodes hold the same converged fold by construction, and the
inspector shows one of them and refuses the other, with no indication that the
value it just refused is sitting one node to the left.

### 8. Flow on the declared edge

**First: `GET /api/inspect/flow` does not exist.** The route table
(`InspectorServer.kt:820-849`) has `topology`, `events`, `cell`, `errors`,
`graphs`, `activity`, `graph`, `search` — no `flow`. Requesting it falls through
to `serveStatic` and returns `{"reason":"no UI build available"}` with 404, which
is why every `flow-*.json` evidence file in this run contains that body. Flow is
**SSE-only**: the `flow.rates` event (`InspectorModel.kt:475-481`, `FlowBatch` in
`Dto.kt:201-216`).

**Yes, the declared gossip edge carries flow.** Six `action=share` writes at A
inside one second produced exactly one non-empty 1 Hz window on
`GET /api/inspect/events`, and the declared gossip edge is in it:

```json
{"kind":"flow.rates","payload":{"window":1000,"edges":[
  … ,{"id":"e714344e-f077-3112-ac0b-0cdaf5d1a267","rate":6.0,
      "lastWave":{"source":"e0a4a855-…","counter":8},"hop":0}, … ]}}
```

`e714344e-…` is exactly the declared `shared:0.outlet → shared:1.deltaInlet`
edge from the topology payload. Subsequent windows are `"edges":[]`.

**No, it does not decay when the peer is gone — and that is correct but
misleading.** With peer B killed and fully retracted from A's topology (15 nodes,
none mirrored), six more writes at A produced the *same* `rate: 6.0` on
`e714344e-…`. That is honest at the mechanism level: the subscription is still
installed and still emitting, into the registry's park queue, exactly as
`InspectorServer.declareLink`'s KDoc says a declared edge should. But on screen
it is an edge reporting live traffic into a node that no longer exists — and
the edge itself is one of **three dangling declared edges** left in A's
partitioned topology:

```
fc773380-…  items        -> items@dialer      (pre-existing, union chain)
44fb34cb-…  votes        -> votes@dialer      (pre-existing, union chain)
e714344e-…  shared:0     -> shared:1          (this ticket's gossip edge)
```

The replicated case does not introduce this; it makes it louder, because the
gossip edge is the only one of the three that is also *hot* during a partition.

---

## Defects observed, deliberately not fixed

V4-PILOT is an evidence ticket: its `Explicitly out of scope` section forbids
patching what the pilot reveals, because a fix mixed into a demo diff would
destroy the measurement the C-replan checkpoint is waiting for. Each of these is
stated precisely enough to be ticketed without re-running the pilot.

**D1 — `ComponentIndex.components()` merges disconnected same-logical-id
replicas into one graph.**
*Did*: ran two JVMs with `--replicate`; the watermark companions
`98ebe0fa-…:0` and `98ebe0fa-…:1` were published, one per JVM, with no link
between them (their gossip is a `streamTo`, `Replication.kt:442`, which no
`TopologyLink` records, and this demo does not `declareLink` it).
*Expected*: two graph cards of one cell each, or one card explicitly marked
as not connected.
*Got*: one card, `{"id":"g-98ebe0fa-…","cells":2,"hosts":1,"nets":2}`, rendering
as two nodes with no edge.
*Responsible*: `inspect/src/main/kotlin/civictech/inspect/Graphs.kt` — `idOf`
(`:180`) keys on `CellRef.id`, the **logical** uuid, so two coexisting instances
of one logical id compute the same id; `components()` (`:124-132`) then groups by
that id string with no check that the members came from one `sweep()` flood-fill.
`sweep()` itself is correct — it produced two components. The merge happens
afterwards.
*Evidence*: `build/v4-pilot-evidence/graphs-{A,B}.json`, `topology-{A,B}.json`,
plus the re-run of `sweep()`'s algorithm over those payloads described in
finding 3.
*Note*: `idOf`'s use of the logical uuid is deliberate and documented
(`Graphs.kt:34-36`) — it stops the id flipping when a minimum member is
*replaced* by a later instance of itself. What was never considered is two
instances **coexisting**, which is precisely the replicated case.

**D2 — the error lane's `parked` rows never fire for a partitioned replica
mesh.**
*Did*: killed the dialer, waited for full retraction, wrote at the survivor.
*Expected*: `GET /errors` to show a `parked` row for the unreachable replica —
the writes demonstrably parked, since the returning peer received them.
*Got*: `{"counters":{…,"parked":0,…},"parked":[]}` at every capture, in both the
test run and the live run.
*Responsible*: not diagnosed from inside this ticket's file claim. The parked
poll is `inspect/src/main/kotlin/civictech/inspect/Errors.kt` (2 s tick) reading
host-side accounting; the replica mesh's park queue lives in
`LocationRegistry`'s routing, not in a host's supervision accounting, so the
likely cause is that these two are simply different queues. Needs a reader who
owns `inspect/src/**` to confirm before ticketing a fix.
*Evidence*: `partitioned-errors-A.json`, `reconnect-errors-A.json`, and the live
`GET /errors` during the manual partition.

**D3 — the named graph's id is not stable under peer churn, and is not owned by
the local process.**
*Did*: watched `GET /graphs` across a kill and a relaunch.
*Expected*: documented instability across merge/split (`Graphs.kt:37-45`).
*Got*: more than that — the id of the *named* `shopping` graph changed on
every peer connect and disconnect (`g-0aa35ea4-…`, `g-25f43d46-…`,
`g-1dfe7365-…`, `g-0c0f714e-…`, `g-183abe9f-…` across the runs), and in two of
those the deciding minimum uuid belonged to a **randomly minted cell in the
other JVM**. A client that deep-links to a graph id loses the link whenever a
peer restarts.
*Responsible*: `Graphs.kt:180` (`idOf`) by design; the *name* is anchored to a
cell and survives, which is what `nameGraph` exists for. This is evidence for
the "membranes as naming boundary" answer, not a bug to patch in `Graphs.kt`.
*Evidence*: the five ids above, from `graphs-A.json`,
`partitioned-graphs-A.json`, `reconnect-graphs-A.json` and the live run.

**D4 (cosmetic, FE) — the graph header breadcrumb showed the previously-opened
graph's name.** Observed once: with `g-98ebe0fa-…` open, the header read
`shopping`. Owner is `inspect/ui/**`, which this ticket may not touch.

### Not defects, recorded so they are not re-litigated

- A mirrored cell's state answering `"kind":"unavailable"` is the **deferred**
  remote-state boundary, working as decided (finding 7).
- A declared edge surviving its target's departure is `declareLink`'s documented
  contract, not a leak (finding 8).
- `waveHealth` staying empty under replicated traffic is guard 7 working
  (finding 5).
- The wire codec needed no change: `SetDelta` and `WatermarkDelta` are already
  registered (`WireCodec.kt:140,:142`) and both crossed the socket unmodified.
