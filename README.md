# ComputeNet

An experimental Kotlin/JVM dataflow runtime. You build programs as graphs of
**cells** — small stateful components with typed **ports** — connected by
explicit **links**. Data flows as incremental deltas: sets, maps, and counters
propagate changes, not snapshots, and operators (joins, group-by, quorum sets,
filters) update their outputs incrementally. The same graph runs in one
process, across hosts, or across JVMs over a WebSocket wire, with the same
observable semantics — including deterministic, seedable simulation for tests.

Status: research project. The design is specification-led (`doc/spec/` is
normative) and there is no published library artifact yet — clone, run the
demos, and build flows in-tree.

## Prerequisites

- Nothing beyond a JDK for Gradle itself: the build pins a **Java 21
  toolchain** and auto-provisions it (Foojay resolver). Always use the wrapper
  (`./gradlew`).
- Node.js/npm only if you want the Agora web frontend.

## Build and test

```bash
./gradlew build     # compile everything
./gradlew test      # whole-repo test gate
./gradlew check     # tests + concordance gate
```

There is no root `run` task — each demo is its own application module, so
always use qualified paths (`:demo:agora:run`, not `run`).

## Try a demo

The quickest way to see the runtime work:

```bash
./gradlew :demo:slotfinder:run --args="8091"
```

then open <http://localhost:8091>. Three participants toggle meeting slots;
every panel (intersection, near-miss, business-hours filter, per-day counts)
is a live incremental view over the same fan-in.

Every demo serves HTTP + SSE; the port is the first argument (default 8080, or
`$PORT`).

| Demo | Run | Shows |
|---|---|---|
| slotfinder | `./gradlew :demo:slotfinder:run --args="8091"` | Quorum sets: one fan-in read at two thresholds, filter, group-by |
| shopping | `./gradlew :demo:shopping:run` | Collaborative shopping list; open two tabs, edits converge (OR-set) |
| exchange | see [multi-node](#multi-node-two-jvms) | Two peers, partitioned + replicated + durable + glitch-free order board |
| agora | `./gradlew :demo:agora:run` | Argumentation graph; every edge is itself a claim, cycles quiesce |
| skillmatch | `./gradlew :demo:skillmatch:run --args="8092"` | Relational operators: equi-join, negated semijoin, lookup join, combine-latest |
| tiering | `./gradlew :demo:tiering:run --args="8093"` | Score fusion: valuations + pairwise preferences → one tier board |
| backlog-triage | `./gradlew :demo:backlog-triage:run --args="8094"` | Collective ranking with pluggable engines (elo, Bradley–Terry, TrueSkill…), JSON agent API |

Durability: `shopping`, `agora`, and `exchange` accept `--journal <dir>` and
become `kill -9` safe — restart with the same flag and state recovers from the
write-ahead log.

The Agora demo has a real web UI (SolidJS + Vite):

```bash
./gradlew :demo:agora:run          # backend on :8080
cd demo/agora/ui && npm install && npm run dev   # UI on :5173
```

To run demos without the Gradle daemon: `./gradlew :demo:X:installDist`, then
`demo/X/build/install/X/bin/X <port> [flags]` (`scripts/stage-preview.sh`
automates this for all demos).

## Multi-node: two JVMs

Peers connect over WebSocket; edits cross the wire as serialized frames and
converge on both sides. Start a listener and a dialer:

```bash
./gradlew :demo:shopping:run --args="8080 --listen 9090"
./gradlew :demo:shopping:run --args="8081 --peer ws://localhost:9090"
```

Open a tab on each HTTP port and edit — both sides converge. A late-starting
or reconnecting peer replays history in order and catches up automatically.
The same flags work on `:demo:exchange`.

## Building a data flow

A graph is built with the `graph { }` DSL against a host, then driven through
typed refs. Minimal complete example (adapted from a kernel test):

```kotlin
val controller = SimulationController(seed = 12)     // deterministic; use the default VirtualThreadScheduler in real apps
val host = ManagedHost(scheduler = controller.scheduler())

lateinit var items: TypedRef<SetApi<String>>
lateinit var count: TypedRef<CountSetApi<String>>
graph(host.managementInlet) {
    val a = spawn("items") { ref -> SetCell<String>(ref = ref) }
    val c = spawn("count") { ref -> CountCell<String>(ref = ref) }
    link(a.cell.outlet, c.cell.inlet)                // typed: mismatch = compile error
    items = a.refAs()
    count = c.refAs()
}

host.lookup(items)!!.inlet.call.add("x")
host.lookup(items)!!.inlet.call.add("y")
controller.runToIdle()
```

Observe outputs through the observation API — `current()` gives a consistent
snapshot from any thread, `onChange` includes late-join catch-up:

```kotlin
val view = host.observeAll {
    set("items", items.ref)
    count("count", count.ref)
}
view.onChange { broadcast(view.current()) }
```

Combinator sugar keeps pipelines short: `items.filter("inStock") { ... }`,
`a.intersect("both", b)`, `a.union("all", b)`, `set.count("n")`, and
relational `leftJoin`/`rightJoin`/`fullJoin`.

### The vocabulary

- **Source cells** (`civictech.cell.data`): `SetCell` (observed-remove set —
  concurrent edits converge), `MapCell`, `ListCell`, `CounterCell`,
  `PnCounterCell` (replicable), `KeyedSetCell` (keyed upsert; re-put ships
  retract+add atomically).
- **Operators** (`civictech.cell.data.op`): `UnionSetCell`, `IntersectSetCell`,
  `QuorumSetCell(threshold)` — one lambda covers the family: union `{ 1 }`,
  intersection `{ n -> n }`, majority `{ n -> n/2 + 1 }` — `FilterCell`,
  `FlatMapSetCell`, `CountCell`, `PresenceCountCell`, `GroupByCell(keyFn,
  aggregator)` with `Aggregators.count/sumOf/avgOf/minOf/maxOf/topK`, and the
  joins: `JoinSetCell` (equi-join), `SemiJoinCell` (semi/anti-join),
  `CombineLatestCell` (keyed outer combine), `LookupJoinCell` (foreign-key,
  reactive on both sides).
- **Views** (`civictech.cell.data.view` / `civictech.cell.observe`):
  `SetView`/`MapView`/`CountView` fold deltas into read models and report
  whether membership *effectively* changed, so you can gate broadcasts.

### Defining your own cell

Declare an Api interface with `@CellBase`; KSP generates a base class with
ports registered and inlets bound. Every kernel data cell/operator
(`SetCell`, `MapCell`, `CounterCell`, `CountCell`, `GroupByCell`, ...) is
written this way; `demo/backlog-triage`'s `RatingCell` is the app-level
example (`demo/backlog-triage/.../RankingCells.kt`):

```kotlin
@CellBase
interface RatingApi {
    val inlet: Serve<Propagate<SetDelta<Pref>>>
    val outlet: Subscribe<Propagate<MapDelta<String, Double>>>
}

class RatingCell(
    private val engine: RatingEngine,
    ref: CellRef = CellRef(UUID.randomUUID()),
) : RatingCellBase(ref) {
    override fun onInlet(value: SetDelta<Pref>) {
        // fold the delta into `engine`, then outlet.call.propagate(...)
    }
}
```

Requires the KSP plugin plus `ksp(project(":gen"))` — copy the setup from
`demo/backlog-triage/build.gradle.kts` (or `kernel/build.gradle.kts` — every
cell-authoring module applies `buildsrc.convention.ksp-cell`). Cells that
should survive restarts implement `Stateful` (`snapshot()`/`restore()`).

## Project layout

```
kernel/    the cell model and runtime (civictech.cell.*)
nature/    descriptor vocabulary shared by codegen and runtime
gen/       KSP processors (@Contract, @CellBase) — generated code is authoritative metadata
wire/      WebSocket transport (kernel itself is transport-free)
concord/   executable specification: YAML conformance corpus against doc/spec
testkit/   test helpers (SimWorld, awaitUntil, JvmPeer)
demo/      the demo applications above
doc/spec/  the normative specification
```

Deeper dives: [doc/ARCHITECTURE.md](doc/ARCHITECTURE.md) (module graph, kernel
package map, runtime lifecycle), [doc/spec/README.md](doc/spec/README.md) (the
specification), [AGENTS.md](AGENTS.md) (contributor/agent conventions).

## Troubleshooting

- `:kernel` fails to compile after touching `gen/`: `:kernel:compileKotlin`
  depends on `:gen:test`, so a generator regression surfaces there first.
- `./gradlew check` fails in `:concord` with a concordance lint: a scenario's
  `covers:` id and the spec drifted; regenerate the matrix with
  `./gradlew :concord:concordance` and fix the dangling id — never hand-edit
  `doc/spec/CONCORDANCE.md`.
- Never edit `build/generated/` — change `gen/` and rebuild.
- Port already in use: another demo defaults to 8080; pass a port argument.
