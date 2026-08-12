package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.PortRef
import civictech.demo.shell.DemoShell
import civictech.demo.shell.beginSse
import civictech.demo.shell.respond
import civictech.demo.shell.sseFrame
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ComputeNet Inspector backend (`doc/spec/90-roadmap/97-inspector-plan`):
 * a **read-only** view of the dataflow graph a host process is running, served
 * beside the application on its own port.
 *
 * M0 serves the topology vertical of `20-api-contract.md`:
 *
 * - `GET /api/inspect/topology` — a [TopologySnapshot] of the live cells and links;
 * - `GET /api/inspect/events` — the SSE delta stream (`topology.node`,
 *   `topology.link`, `lifecycle`, `heartbeat`) that keeps that snapshot current.
 *
 * M1 adds selection and state:
 *
 * - `GET /api/inspect/cell/{ref}` — a [CellDetail];
 * - `POST`/`DELETE /api/inspect/cell/{ref}/observe` — the explicit state
 *   subscription (see [Observations]);
 * - `GET /api/inspect/cell/{ref}/state` — a [CellState], plus the
 *   `state.summary` SSE events an open subscription streams.
 *
 * M2 adds the error lane (see [Errors]):
 *
 * - `GET /api/inspect/errors` — an [ErrorSnapshot];
 * - `error.deadLetter` / `error.parked` / `error.restart` SSE events.
 *
 * M3 adds the flow feed (see [FlowCollector]): the 1 Hz `flow.rates` events.
 *
 * M4 adds the multi-graph navigator (see [ComponentIndex] and [Graphs]):
 *
 * - `GET /api/inspect/graphs` — a [GraphList] of the connected components;
 * - `GET /api/inspect/search?mode=&q=` — a [SearchResult];
 * - `?graph=g-…` scoping on `GET /api/inspect/topology`;
 * - `graphs.changed` SSE events, plus `Node.graph` on every node;
 * - [nameGraph], the opt-in host-side annotation that labels a component.
 *
 * M5 adds content search (see [DataSearch]): `?mode=data` reads hot,
 * locally-hosted cells' state under an explicit cap and deadline and reports
 * the cost it paid; cold graphs (see [Heat] and [Waker]): a component whose
 * cells are all parked lists as `lifecycle: "cold"` from registry metadata
 * alone, and `POST /api/inspect/graph/{id}/wake` is the one explicit,
 * user-initiated act that ends that; and network hosts (see [Peers]):
 * peer-announced cells appear in topology with their `Node.net` set to the
 * connection they arrived through (and `host: null` — a mirrored location
 * carries no process host), a peer disconnect retracts them, and [declareLink]
 * reports a cross-boundary stream the kernel indexes nowhere. Remote cells are
 * topology and placement only: they are not locally hosted, so state, flow and
 * error feeds have nothing to read for them and say so (`GET state` →
 * `unavailable`, `POST observe` → 409).
 *
 * V1C adds the **bounded** state read (see [PagedState]):
 *
 * - `GET /api/inspect/cell/{ref}/state?cursor=&limit=` walks a cell one page at
 *   a time off `ManagedHost.readState` instead of copying it whole, and says
 *   where the bytes came from (`provenance`), how much of the walk to believe
 *   (`page.walkStable`) and — when there is nothing to report — *which* nothing
 *   it is (`unreadable`);
 * - a **suspended** cell and a cell on a **drained** host stop being skipped:
 *   both are readable without waking anything, which also narrows what
 *   [DataSearch] declines to read to held refs alone (see [Heat.isReadable]).
 *
 * V2 adds the activity feed (see [Activity]):
 *
 * - `GET /api/inspect/activity` — an [ActivitySnapshot], the bounded history of
 *   `passivated` / `activated` / `drained` / `woken` / `restarted` per cell;
 * - the matching `activity` SSE event;
 * - and it turns the `lifecycle` event from a 1 Hz sample into a push, off
 *   V2-KERNEL's [ManagedHost.onLifecycle]. `CellDetail.attention` stops being
 *   a hard-coded null in the same wave, off [ManagedHost.attentionOf].
 *
 * ### What it can and cannot see
 *
 * The inspector reads one [LocationRegistry]. **Cells on registry-less hosts
 * (`ManagedHost(registry = null)`) are invisible**: such a host publishes
 * nowhere, so it has no [LocationRegistry.localRefs], no
 * [LocationRegistry.locate], and no [LocationRegistry.describe] — there is
 * nothing to show and nothing to fail on. Passing such a host in [hosts] is
 * harmless; its cells simply never appear.
 *
 * ### Invariants
 *
 * Nothing here sits on the per-message data path (P2): the only feeds are the
 * registry's rare-path publish/unpublish/link/unlink hooks, plus — for a cell a
 * client explicitly asked to observe — one ordinary `ObserveCell` sink folding
 * that cell's outlet, which is a normal graph participant, not a hook. Serving
 * topology and cell detail subscribes to no cell and raises no attention (P6):
 * they read registry metadata and generated descriptors only. State is the one
 * causal read, and it is opt-in per cell and released explicitly (see
 * [Observations]). And the viz never blocks the graph: hook threads and sink
 * dispatch threads hand frames to per-client bounded queues that drop their
 * oldest frames rather than wait (see [SseBroadcaster]).
 */
class InspectorServer internal constructor(
    registry: LocationRegistry,
    /**
     * The process hosts to inspect, by the name their cells report as
     * `Node.host`. A located host that is not named here falls back to a name
     * derived from its ref.
     */
    hosts: Map<String, ManagedHost>,
    port: Int = DEFAULT_PORT,
    /**
     * Optional application-supplied cell names (`Node.name`). The kernel has no
     * name registry — graph-builder handle names live in the `GraphSpec`, not
     * in the runtime — so an app that wants readable node labels passes what it
     * knows. Unnamed cells report `null`, per the contract.
     */
    cellNames: Map<CellRef, String> = emptyMap(),
    /**
     * This JVM's network-host label — the launcher's `--net-name` (M5-NET).
     * Every locally published cell reports it as `Node.net`; peer-announced
     * cells report a label derived from the connection they arrived through
     * (see [Peers]). Defaults to the contract's `"local"`, so an inspector
     * that is not told about a wider network keeps emitting exactly what M0–M4
     * emitted.
     */
    netName: String = Node.LOCAL_NET,
    /**
     * (b) — where `inspect/ui`'s built static assets (Vite's `dist/`) live on
     * disk, or a path that simply does not exist yet. Resolved against the
     * process's working directory, so the default
     * (`inspect/ui/dist`, Vite's own default output directory) only ever
     * finds a real build when this JVM was launched from the repo root — an
     * installed distribution launched from there qualifies
     * (`inspect/ui/README.md`'s "Run" section); `./gradlew :demo:skillmatch:run`
     * does not, since Gradle's `run` task defaults to the subproject's own
     * directory as its working directory, not the repo root. A caller with a
     * different working-directory convention, or one that wants to point at
     * a build copied elsewhere, passes an explicit path; nothing here
     * invokes `npm run build` itself (binding constraint 10).
     */
    uiDist: Path = defaultUiDist(),
    /**
     * Where [shell] comes from — [Shells.Real] for every caller outside this
     * module, and the seam that makes T19's **named**-port half assertable
     * (see [Shells], computenet-lxq).
     *
     * It is a *constructor* parameter, and deliberately not the instance `var`
     * that [inspectorClock], [snapshots] and [reads] are: the shell binds
     * during construction, so a seam reassigned afterwards would come too
     * late. That leaves the two shapes that are early enough — a constructor
     * parameter or a process-wide static — and this is the one that carries no
     * mutable global, no restore obligation on the test that installs a
     * stand-in, and no dependence on `:inspect` never enabling JUnit parallel
     * execution. Deliberately un-defaulted, so a call that omits it cannot
     * resolve here and lands on the public constructor below instead.
     */
    private val shells: Shells,
) : AutoCloseable {

    /**
     * The public form — identical to the primary but for [shells], which only
     * this module's own tests supply. Every parameter is documented on the
     * primary constructor above.
     */
    constructor(
        registry: LocationRegistry,
        hosts: Map<String, ManagedHost>,
        port: Int = DEFAULT_PORT,
        cellNames: Map<CellRef, String> = emptyMap(),
        netName: String = Node.LOCAL_NET,
        uiDist: Path = defaultUiDist(),
    ) : this(registry, hosts, port, cellNames, netName, uiDist, Shells.Real)

    /** Name the hosts by ref — the convenience form when the app has no names of its own. */
    constructor(registry: LocationRegistry, hosts: Set<ManagedHost>, port: Int = DEFAULT_PORT) :
        this(registry, hosts.associateBy { labelFor("host-", it.ref.id) }, port)

    constructor(registry: LocationRegistry, host: ManagedHost, port: Int = DEFAULT_PORT) :
        this(registry, setOf(host), port)

    /**
     * T19: loopback only, not every interface. The inspector serves live
     * topology, cell state and content search — a `?mode=data` hit can read
     * hot cells' values — so it must not be network-reachable by default the
     * way `DemoShell`'s wildcard bind would leave it (see
     * `doc/remediation/AUDIT-2026-07-28.md` §W6 item 1, `doc/architecture-
     * decisions.md` finding B8).
     *
     * The bind address is handed over **explicitly** rather than left to
     * `DemoShell.endpoint`'s own default, and that argument is load-bearing for
     * exactly the case an operator reaches with `--inspect-port`: since
     * computenet-dqy.33 the shell binds loopback by itself for an *ephemeral*
     * port and the wildcard for a *named* one, so dropping it here would
     * publish this read-only instrument on every interface. Built through
     * [shells] so that stays pinned — see [Shells] (computenet-lxq).
     */
    private val shell = shells.open(port, InetAddress.getLoopbackAddress())
    private val broadcaster = SseBroadcaster()

    /**
     * V1C-BE — the one registry, held rather than only captured, so
     * [serveState] can tell "no local host for this ref" from "both read seams
     * were disabled" when neither answered. Everything else reads it through
     * the collaborators built below.
     */
    private val locations: LocationRegistry = registry

    /**
     * (b) — [uiDist] resolved to a real, existing directory, or null when it
     * is not one (not yet built, wrong working directory, or a file rather
     * than a directory). `toRealPath()` both resolves symlinks and fails
     * fast when nothing is there, so a missing/relocated build degrades to
     * "serve API only" here, once, at construction — never a per-request
     * `Files.exists` race, and never a thrown exception a caller has to
     * handle.
     */
    private val uiRoot: Path? = runCatching { uiDist.toRealPath() }.getOrNull()?.takeIf(Files::isDirectory)

    /** M5 — the network-host resolver (see [Peers]). */
    private val peers = Peers(registry, netName)

    // `flow` is declared below and read through the supplier, so the two can
    // reference each other without a construction cycle: the model asks the
    // collector for an edge's `fused`, the collector hands its windows back to
    // the model's `flowRates`. The supplier only runs after construction.
    private val model: InspectorModel =
        InspectorModel(
            registry, hosts, cellNames, broadcaster::publish,
            flow = { flow },
            peers = peers,
            // `observations` is declared below; safe for the same reason as
            // `flow` — the supplier only runs once hooks fire or sync() runs,
            // both after construction completes
            instruments = { ref -> ref in observations.sinkRefs },
        )

    /**
     * The inspector's own wall clock, read through by the collaborators whose
     * behaviour is time-shaped — [Errors]' park ages and restart-cause window,
     * [FlowCollector]'s re-baseline stamp, and [WaveHealth]'s thresholds.
     *
     * Internal and a `var` for exactly the reason [snapshots] is: V3's
     * wave-health conditions are measured in seconds, and a test that waited
     * them out in wall-clock would be both slow and an assertion on scheduler
     * timing, which the ticket forbids. Every consumer captures a read-through
     * lambda rather than today's value, so a test that reassigns this after
     * construction still takes effect. Untouched, this is
     * `System::currentTimeMillis` and nothing behaves differently from before.
     */
    internal var inspectorClock: () -> Long = System::currentTimeMillis

    /** M3 — the flow feed (see [FlowCollector]); attaches taps as edges appear. */
    private val flow: FlowCollector =
        FlowCollector(registry, onBatch = model::flowRates, clock = { inspectorClock() })

    /**
     * The `Stateful.snapshot()` fallback — see [SnapshotSource]'s doc for the
     * seam's history. Wired by default to [ManagedHost.snapshotOf] (V0-BE):
     * `registry.locate(ref)` is null for a cell with no local host (mirrored,
     * remote, or unknown) and this resolves to null without ever reaching a
     * host; [ManagedHost.snapshotOf] itself completes null for a non-
     * `Stateful` cell or a terminated host. The [SNAPSHOT_WAIT_MS] bounded
     * wait mirrors [DataSearch.read]'s pattern for the same accessor — this
     * runs synchronously on the HTTP dispatcher thread inside [serveState],
     * so a wedged cell must cost this one request its timeout, never hang it
     * (binding constraint 6, "viz never blocks").
     *
     * Internal, and still a `var`: wiring the *default* was the orchestrator
     * decision (an app-supplied source would otherwise inherit the host-
     * thread routing obligation this seam exists to honor), but a caller —
     * chiefly tests standing in for a slow or absent kernel accessor — can
     * still install its own after construction; [observations]'s own
     * constructor call reads through this field rather than capturing
     * today's value, so a later reassignment still takes effect.
     */
    internal var snapshots: SnapshotSource = SnapshotSource { ref ->
        registry.locate(ref)?.snapshotOf(ref)?.let { pending ->
            runCatching { pending.get(SNAPSHOT_WAIT_MS, TimeUnit.MILLISECONDS) }
                .onFailure { pending.cancel(false) }
                .getOrNull()
        }
    }

    /**
     * V1C-BE — the **bounded** read seam (see [BoundedReadSource]), wired by
     * default to [ManagedHost.readState]: `registry.locate(ref)` is null for a
     * cell with no local host (mirrored, remote, or unknown) and this resolves
     * to null without ever reaching a host, which is exactly the
     * "this seam did not answer" case [PagedState] falls through to [snapshots]
     * on.
     *
     * The deadline is **not** applied here — [PagedState] owns it, so one
     * request spends exactly one bounded wait and a miss is reported as
     * `unreadable: "unanswered"` rather than degenerating into a second read
     * against the older whole-copy seam.
     *
     * Internal and a `var` for the same reason [snapshots] is: a test standing
     * in for a slow, absent or deliberately disabled kernel accessor installs
     * its own after construction, and [PagedState] reads through this field
     * rather than capturing today's value.
     */
    internal var reads: BoundedReadSource = BoundedReadSource { ref, request ->
        registry.locate(ref)?.readState(ref, request)
    }

    /**
     * V1C-BE — the paged `GET /cell/{ref}/state` (see [PagedState]), and the
     * owner of the cursor table its `?cursor=` ids index.
     */
    private val pagedState = PagedState(
        reads = { reads },
        cursors = CursorTable(clock = { inspectorClock() }),
        waitMs = SNAPSHOT_WAIT_MS,
    )

    private val observations = Observations(
        registry = registry,
        onSummary = model::stateSummary,
        // read through, so a source installed after construction takes effect
        snapshots = SnapshotSource { ref -> snapshots.snapshotOf(ref) },
    )

    /**
     * M5 — content search (see [DataSearch]). Reads through [observations] so a
     * cell a client already observes costs nothing extra, and through the
     * registry for everything else.
     */
    private val dataSearch = DataSearch(
        registry = registry,
        components = model::components,
        observed = observations::reading,
        instruments = { observations.sinkRefs },
    )

    /**
     * M5-COLD — the one causal act in the whole inspector (see [Waker]).
     *
     * V2 — and the one activity kind that is the *user's*: the cells a wake
     * acted on are recorded as `woken` from inside [Waker.wake], before its
     * resume calls go out, so the feed reads "a user asked, then the kernel
     * did" rather than the other way round.
     */
    private val waker = Waker(registry, onWoken = { refs -> activity.woken(refs) })

    /**
     * V2 — the activity feed (see [Activity]). It carries the kernel lifecycle
     * listeners that replaced the `"lifecycleChanged"` poll, so it is also the
     * path by which a transition reaches [InspectorModel.lifecycleChanged].
     */
    private val activity = Activity(
        registry = registry,
        hosts = hosts.values,
        knows = model::knows,
        onEntry = model::activityEvent,
        onLifecycle = model::lifecycleChanged,
    )

    /**
     * V3 — the wave-health heuristic (see [WaveHealth]). Constructed from
     * accessors on collaborators that already exist: it installs no tap, opens
     * no observation and touches no cell, which is the whole reason a diagnostic
     * of this class is admissible at all (P2/P6).
     */
    private val waveHealth = WaveHealth(
        sites = flow::tapReadings,
        observed = { observations.openRefs },
        frontierOf = { ref -> observations.frontierOf(ref) },
        isCold = { ref -> Heat.of(registry, ref).isCold },
        onRow = model::waveHealthEvent,
        clock = { inspectorClock() },
    )

    /** M2 — the error lane (see [Errors]'s doc for the three sources it feeds). */
    private val errors = Errors(
        registry = registry,
        hosts = hosts,
        onDeadLetter = model::deadLetterEvent,
        onParked = model::parkedEvent,
        // V2: a supervision restart is the one activity kind with no push seam,
        // so the feed takes it from the same observed generation increase the
        // error lane already reports (see [Activity.restarted])
        onRestart = { row ->
            model.restartEvent(row)
            activity.restarted(row)
        },
        // V3 — the wave-health rows ride the error snapshot rather than a route
        // of their own, and the re-baseline beat comes off the flow feed's taps
        waveHealthRows = waveHealth::openRows,
        reBaselineAtMsOf = flow::reBaselineAtMsOf,
        clock = { inspectorClock() },
    )

    private val heartbeats = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "inspector-heartbeat").apply { isDaemon = true }
    }

    /**
     * Registry hook registrations — *all* of them, since T21 gave the two
     * any-scope hooks the same `AutoCloseable` contract their `onLocal…`
     * siblings always had. Held so [close] can drop every one: a stopped
     * inspector leaves no listener on a live registry.
     *
     * Four feeds, in the order they are registered:
     *
     * - [LocationRegistry.onLocalPublish] — a cell published on this JVM;
     * - [LocationRegistry.onTopology] — *any* edge, local or mirrored in from a
     *   peer (`mirrorLink`/`mirrorUnlink`), which is what retired M5's 1 Hz set
     *   difference against `LocationRegistry.all()`;
     * - [LocationRegistry.onPublish] — filtered to
     *   [LocationRegistry.Remote] locations, so a local publish is not seen
     *   twice by the two publish feeds;
     * - [LocationRegistry.onUnpublish] — every removal path: a local despawn, a
     *   peer's announced eviction, and (T21) a whole peer dropped by
     *   `unpublishRemotes`. It strictly supersets `onLocalUnpublish`, so the
     *   view subscribes to it alone rather than being told twice.
     *
     * The first entry is V2's and is registered *before* [InspectorModel]'s own
     * publish handler on purpose: a host this inspector has not met yet must be
     * carrying a lifecycle listener before anything it holds can transition
     * (see [Activity.watchLocatedHosts] for why the declared [hosts] map is not
     * the whole set).
     */
    private val hooks: List<AutoCloseable> = listOf(
        registry.onLocalPublish(activity::watchHostOf),
        registry.onLocalPublish(model::published),
        registry.onTopology(model::linked, model::unlinked),
        registry.onPublish { ref -> if (peers.isRemote(ref)) model.mirroredPublish(ref) },
        registry.onUnpublish(model::unpublished),
    )

    val boundPort: Int get() = shell.boundPort

    /** Live SSE subscribers — diagnostics, and the tests' connect barrier. */
    internal val attachedClients: Int get() = broadcaster.clientCount

    init {
        // hooks first (see [hooks]), then the catch-up read: a publish racing
        // construction is seen by the hook and merely re-confirmed by the sync,
        // never lost
        model.sync()
        // V2 — the same catch-up for the lifecycle listeners: hosts already
        // holding published cells at construction never fire the publish hook
        // that would have attached one (idempotent, so a host reached both ways
        // is watched once)
        activity.watchLocatedHosts()

        if (uiRoot == null) {
            // matching the operator-facing startup message convention in the
            // demo mains (e.g. SkillMatchApp's "computenet inspector: ..."):
            // this module has no logging framework dependency and should not
            // gain one for a one-line note
            println("computenet inspector: no UI build at $uiDist — serving API only")
        }

        shell.route(TOPOLOGY_PATH) { exchange ->
            exchange.allowCrossOrigin()
            // M4: `?graph=g-…` scopes the snapshot to one component; absent, the
            // whole process, exactly as M0 through M3 served it
            val graph = exchange.query()[GRAPH_PARAM]?.takeIf { it.isNotBlank() }
            exchange.respond(200, model.snapshotJson(graph), JSON)
        }
        shell.route(ERRORS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.respond(200, inspectorJson.encodeToString(ErrorSnapshot.serializer(), errors.snapshot()), JSON)
        }
        // V2 — the activity feed's catch-up read (see [Activity]). Its path
        // prefixes none of the routes above and none of them prefixes it, so
        // the JDK server's longest-prefix matching needs no ordering care here
        // (unlike the GRAPHS_PATH/GRAPH_PATH pair — see [GRAPH_PATH]).
        shell.route(ACTIVITY_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.respond(200, inspectorJson.encodeToString(ActivitySnapshot.serializer(), activity.snapshot()), JSON)
        }
        shell.route(GRAPHS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            val body = Graphs.list(model.components(), errors.snapshot())
            exchange.respond(200, inspectorJson.encodeToString(GraphList.serializer(), body), JSON)
        }
        // M5-COLD. Registered here, after GRAPHS_PATH's route above — see
        // [GRAPH_PATH]'s own doc for why that order (and its length relative
        // to GRAPHS_PATH) matters.
        shell.route(GRAPH_PATH) { exchange ->
            exchange.allowCrossOrigin()
            runCatching { serveGraph(exchange) }
                .onFailure { failure -> runCatching { exchange.respond(500, problem(failure.toString()), JSON) } }
        }
        shell.route(SEARCH_PATH) { exchange ->
            exchange.allowCrossOrigin()
            runCatching { serveSearch(exchange) }
                .onFailure { failure -> runCatching { exchange.respond(500, problem(failure.toString()), JSON) } }
        }
        shell.route(EVENTS_PATH) { exchange ->
            exchange.allowCrossOrigin()
            exchange.beginSse()
            // the handler returns immediately; frames leave on the client's own
            // pump thread, so a slow reader never occupies the http dispatcher
            broadcaster.attach(
                writer = { frame -> exchange.sseFrame(frame) },
                onDetach = { runCatching { exchange.close() } },
            )
        }
        // one context for the whole /cell subtree: the JDK http server matches
        // contexts by longest path prefix, so {ref}, {ref}/state and
        // {ref}/observe all arrive here and are dispatched on method + tail
        shell.route(CELL_PATH) { exchange ->
            exchange.allowCrossOrigin()
            runCatching { serveCell(exchange) }
                .onFailure { failure -> runCatching { exchange.respond(500, problem(failure.toString()), JSON) } }
        }
        // (b) — the catch-all static route. The JDK http server dispatches by
        // longest matching path prefix (see [GRAPH_PATH]'s doc for the
        // general rule this module already relies on), so "/" only ever
        // serves a request no more specific `/api/inspect/...` context above
        // claims first; registration order relative to those routes does not
        // matter.
        shell.route("/") { exchange -> serveStatic(exchange) }
    }

    /**
     * `GET /api/inspect/search?mode={name|problems|data}&q=`.
     *
     * `name` and `problems` are answered from metadata the inspector already
     * holds — component membership, cell names and types, and M2's error rows
     * — so searching subscribes to nothing and raises no attention (P6).
     * `data` (M5) is the one mode that has to *ask cells*: it reads hot,
     * locally-hosted candidates through the host-routed snapshot seam, under a
     * cap and a deadline, and reports what that cost in [SearchResult.cost] —
     * see [DataSearch] for the whole cost model and why it does not fan
     * `StateRequest` out.
     */
    private fun serveSearch(exchange: HttpExchange) {
        val params = exchange.query()
        val query = params[QUERY_PARAM].orEmpty()
        when (val mode = params[MODE_PARAM]?.takeIf { it.isNotBlank() } ?: SearchResult.NAME) {
            SearchResult.NAME -> exchange.respondSearch(Graphs.byName(model.components(), query))
            SearchResult.PROBLEMS -> exchange.respondSearch(Graphs.problems(model.components(), errors.snapshot()))
            SearchResult.DATA -> exchange.respondSearch(dataSearch.search(query))
            else -> exchange.respond(400, problem("unknown search mode: $mode"), JSON)
        }
    }

    private fun HttpExchange.respondSearch(result: SearchResult) =
        respond(200, inspectorJson.encodeToString(SearchResult.serializer(), result), JSON)

    /**
     * The `/graph/{id}/…` subtree — one action today: `POST .../wake`
     * (M5-COLD ticket Implement §1).
     *
     * **202, not 200**: waking is a management call enqueued on each host's own
     * queue (`resumeHost` / `resume`), so the only honest thing this response
     * can claim is that the request was accepted. The client learns the actual
     * outcome the same way it learns everything else — from the `lifecycle` and
     * `graphs.changed` events that follow, which is also what makes the wake
     * *logged* rather than silent.
     *
     * An id no component carries is a 404 here, unlike `GET /topology?graph=`'s
     * empty 200: a stale id on a read is an ordinary race the client resolves
     * by resyncing, but "wake this graph" naming nothing did not happen, and
     * answering 202 would claim it did.
     *
     * **T19 — [WAKE_HEADER] is required.** Unlike every `GET` route, this one
     * is a real management mutation, so it cannot lean on [allowCrossOrigin]'s
     * wildcard the way reads do. Requiring a custom header turns a cross-
     * origin `POST` from a CORS *simple request* (no preflight, sent and
     * answered before anything can stop it) into one the browser must
     * preflight with `OPTIONS` first — and since this server registers no
     * `OPTIONS` handler anywhere, that preflight fails closed and the browser
     * never sends the real request cross-origin. The check below is the
     * belt-and-suspenders half: a non-browser caller (or a caller that adds
     * the header itself) still needs it, and its absence is answered with a
     * 4xx before [Waker.wake] is ever reached.
     */
    private fun serveGraph(exchange: HttpExchange) {
        val segments = exchange.tailSegments(GRAPH_PATH)
        val id = segments.firstOrNull()
        if (id == null || segments.drop(1) != listOf(WAKE) || exchange.requestMethod != "POST") {
            return exchange.respond(404, problem("expected POST /graph/{id}/wake"), JSON)
        }
        if (exchange.requestHeaders.getFirst(WAKE_HEADER) != WAKE_HEADER_VALUE) {
            return exchange.respond(400, problem("missing required header: $WAKE_HEADER"), JSON)
        }
        val component = model.components().firstOrNull { it.id == id }
            ?: return exchange.respond(404, problem("unknown graph: $id"), JSON)
        val report = waker.wake(component)
        exchange.respond(
            202,
            buildJsonObject {
                put("graph", component.id)
                put("hosts", report.hosts)
                put("cells", report.cells)
            }.toString(),
            JSON,
        )
    }

    private fun serveCell(exchange: HttpExchange) {
        val segments = exchange.tailSegments(CELL_PATH)
        val ref = segments.firstOrNull()?.let(::decodeRef)
            ?: return exchange.respond(404, problem("expected /cell/{ref}"), JSON)
        val tail = segments.drop(1)

        when {
            tail.isEmpty() && exchange.requestMethod == "GET" -> serveDetail(exchange, ref)
            tail == listOf(STATE) && exchange.requestMethod == "GET" -> serveState(exchange, ref)
            tail == listOf(OBSERVE) && exchange.requestMethod == "POST" -> startObserving(exchange, ref)
            tail == listOf(OBSERVE) && exchange.requestMethod == "DELETE" -> {
                // idempotent: releasing what was never opened is a success
                observations.stop(ref)
                exchange.noContent()
            }

            else -> exchange.respond(404, problem("no such inspector resource"), JSON)
        }
    }

    private fun serveDetail(exchange: HttpExchange, ref: CellRef) {
        val body = model.detailJson(ref) ?: return exchange.respond(404, problem("unknown cell"), JSON)
        exchange.respond(200, body, JSON)
    }

    /**
     * A state read, in four arms and one fixed order (V1C-BE).
     *
     * 1. An **open observation** answers from its already-materialized fold
     *    (`kind: "view"`), exactly as M1 did. It is free, it is already
     *    consistent, and it is never regressed: `?cursor=`/`?limit=` are
     *    *ignored* for an observed cell and the response carries no `page`, so a
     *    client's walk terminates on page 1 with whatever the encoder's 200-row
     *    budget renders. `View` paging is out of scope (V1C-KERNEL Decision 9).
     * 2. Otherwise the **bounded read** ([PagedState]) — `kind: "page"` for a
     *    `BoundedStateful` cell, `kind: "snapshot"` for a `Stateful` one that is
     *    not (byte-identical to what M5/V0 answered), and `kind: "unavailable"`
     *    with an `unreadable` reason for every case the kernel *decided*.
     * 3. When that seam did not answer at all — no local host, or a caller that
     *    installed [BoundedReadSource.Unavailable] — the older [SnapshotSource]
     *    remains the last fallback, so an app-supplied source still works.
     * 4. And otherwise the contract's `unavailable`, now saying *which* nothing
     *    it is.
     *
     * Also the "matching `GET state`" that renews the idle deadline.
     */
    private fun serveState(exchange: HttpExchange, ref: CellRef) {
        if (!model.knows(ref)) return exchange.respond(404, problem("unknown cell"), JSON)
        observations.touch(ref)
        val encoded = encodeRef(ref)
        observations.reading(ref)?.let { return exchange.respondState(stateOf(encoded, it)) }

        when (val outcome = pagedState.read(ref, encoded, exchange.query())) {
            is PagedState.Outcome.Answered -> exchange.respondState(outcome.state)
            is PagedState.Outcome.BadRequest -> exchange.respond(400, problem(outcome.reason), JSON)
            is PagedState.Outcome.Gone -> exchange.respond(410, problem(outcome.reason), JSON)
            PagedState.Outcome.NoSource -> {
                val reading = observations.snapshotReading(ref)
                exchange.respondState(
                    reading?.let { stateOf(encoded, it) }
                        ?: CellState(
                            ref = encoded,
                            kind = CellState.UNAVAILABLE,
                            // the bounded seam answers null exactly when this
                            // registry places no local host for the ref
                            unreadable = if (locations.locate(ref) == null) CellState.REMOTE else CellState.UNKNOWN,
                        ),
                )
            }
        }
    }

    /**
     * The M1 body for a [StateReading] — an observation's fold (`"view"`) or the
     * [SnapshotSource] fallback (`"snapshot"`). Unchanged field-for-field; V1C-BE
     * only adds the `provenance` a whole copy of a live cell now carries, and
     * leaves it null for a `"view"` (a fold materialized in the inspector's own
     * heap is neither a live cell read nor a checkpoint).
     */
    private fun stateOf(encodedRef: String, reading: StateReading): CellState = CellState(
        ref = encodedRef,
        frontier = reading.frontier?.let { WaveStamp(it.sourceId.toString(), it.counter) },
        kind = reading.kind,
        value = ValueEncoder.encode(reading.value),
        staleMs = reading.staleMs,
        provenance = if (reading.kind == CellState.SNAPSHOT) CellState.LIVE else null,
    )

    private fun HttpExchange.respondState(state: CellState) =
        respond(200, inspectorJson.encodeToString(CellState.serializer(), state), JSON)

    /**
     * Contract: `204; starts state summaries for this cell`. A cell the kernel
     * offers no fold for (no outlet, or a delta shape no built-in `View`
     * covers) cannot start them, and answering 204 would promise summaries that
     * never arrive — so that case is refused explicitly. A client that ignores
     * the refusal still behaves correctly: its `GET state` reports
     * `unavailable`.
     */
    private fun startObserving(exchange: HttpExchange, ref: CellRef) {
        if (!model.knows(ref)) return exchange.respond(404, problem("unknown cell"), JSON)
        if (observations.start(ref)) exchange.noContent()
        else exchange.respond(409, problem("no observable outlet on this cell"), JSON)
    }

    /**
     * (b) — `inspect/ui`'s built `dist/`, served directly so a demo started
     * with `--inspect-port` needs nothing but this one process in
     * production. Hash-routed app, no server-side path routing
     * (`inspect/ui/src/nav/route.ts`'s `parseHash`/`formatHash`) — so `GET /`
     * is the one special path (served as `index.html`); anything else that
     * does not resolve to a real file under [uiRoot] is a plain 404, not an
     * SPA fallback.
     *
     * `toRealPath()` on the resolved candidate is the traversal/symlink
     * guard: it both normalizes `..` segments and resolves symlinks, so the
     * `startsWith(uiRoot)` check below catches a request that would
     * otherwise escape the dist directory either way, and a request that
     * resolves to nothing at all simply throws into the same `null` ->
     * 404 path.
     */
    private fun serveStatic(exchange: HttpExchange) {
        val root = uiRoot ?: return exchange.respond(404, problem("no UI build available"), JSON)
        val requested = exchange.requestURI.path.removePrefix("/").ifEmpty { INDEX_HTML }
        val resolved = runCatching { root.resolve(requested).toRealPath() }.getOrNull()
        if (resolved == null || !resolved.startsWith(root) || !Files.isRegularFile(resolved)) {
            return exchange.respond(404, problem("not found"), JSON)
        }
        exchange.respond(200, Files.readString(resolved), contentTypeFor(resolved.fileName.toString()))
    }

    /**
     * The four extensions `inspect/ui`'s Vite build actually produces
     * (`inspect/ui/package.json`); anything else falls back to a generic
     * binary content type rather than guessing.
     */
    private fun contentTypeFor(fileName: String): String = when {
        fileName.endsWith(".html") -> "text/html"
        fileName.endsWith(".js") || fileName.endsWith(".mjs") -> "application/javascript"
        fileName.endsWith(".css") -> "text/css"
        fileName.endsWith(".svg") -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    /**
     * Label the graph that contains [anchorRef] (M4). The kernel has no `Graph`
     * entity and no naming mechanism — a graph is an emergent connected
     * component (`10-target-v3.md` §Known kernel gaps; membranes as the real
     * nameable boundary are tracked in Linear MRB-156) — so this is an
     * inspector-level annotation an application opts into for the components it
     * knows the meaning of. Everything else stays `name: null`, which the
     * navigator renders as its generated id; no name is ever invented.
     *
     * Anchored to a cell rather than to a component id on purpose: ids change
     * whenever components merge or split, and a name that evaporated on the
     * first merge would be worse than none. Nothing is persisted — a restarted
     * process re-annotates from its own code, as this one does.
     *
     * Safe to call before the cell is published; it simply names nothing until
     * the ref shows up.
     */
    fun nameGraph(anchorRef: CellRef, name: String): InspectorServer = apply { model.nameGraph(anchorRef, name) }

    /**
     * Declare a cross-boundary stream as an edge (M5-NET).
     *
     * The kernel records an edge only where `ManagedHost.connect` admitted one,
     * and that path resolves *both* endpoints in one host's own cell map — so a
     * link from a local outlet to a ref on another JVM is not expressible as a
     * `TopologyLink` and appears in no index, on either side of the wire. What
     * peered applications actually build is
     * `outlet.streamTo(RoutedPropagate(peerRef, port, registry::deliver))`: a
     * genuine consume-role subscription that the registry routes across the
     * bridge, and that nothing introspects.
     *
     * This is the same shape of opt-in annotation as [nameGraph]: the process
     * tells the inspector about wiring it performed, and the inspector reports
     * it rather than inferring or inventing it. Declare after both the
     * producing cell is published and (ideally) the peer has announced [toRef],
     * so the edge is tapped for flow and lands in the right component;
     * declaring the same stream twice is idempotent.
     *
     * Nothing about the graph changes — no link is created here, only reported.
     * A declared edge therefore also *survives* the peer going away, unlike a
     * mirrored one: the subscription is still there and still emitting (into
     * the registry's park queue), so retracting the edge would misreport the
     * process. The target cell leaves the topology, and a client draws what it
     * can anchor.
     */
    fun declareLink(
        fromRef: CellRef,
        fromPort: String,
        toRef: CellRef,
        toPort: String,
    ): InspectorServer = apply {
        model.declareLink(PortRef.of(fromRef, fromPort), PortRef.of(toRef, toPort))
    }

    /**
     * One polled collaborator action (T24): a name for diagnostics, the
     * period [start] schedules it at, and the action itself. Driving every
     * schedule off one list of these — rather than six inline
     * `scheduleAtFixedRate` calls, each welded to its own bespoke test
     * accessor — is what lets [tickAll] exist as a single seam instead of
     * six.
     *
     * **V2 retired the sixth.** `"lifecycleChanged"` sampled every known node
     * once a second because suspend/resume and drain/resumeHost had no
     * notification hook; V2-KERNEL's [ManagedHost.onLifecycle] reports all four
     * of those transitions from the host that performs them, so
     * [InspectorModel.lifecycleChanged] is now reached by push (see [Activity])
     * and the `graphs.changed` that tick owed the navigator is coalesced into
     * `"graphsChanged"` below — which was already the tick for exactly that
     * kind of card invalidation. Nothing that sampled a lifecycle remains, so
     * no ordering constraint between ticks remains either.
     */
    private class Tick(val name: String, val periodMs: Long, val action: () -> Unit)

    private val ticks: List<Tick> = listOf(
        // contract §SSE: "Server sends `heartbeat` every 15 s".
        Tick("heartbeat", HEARTBEAT_SECONDS * 1_000) { model.heartbeat() },
        Tick("sweep", SWEEP_SECONDS * 1_000) { observations.sweep() },
        // V1A-BE: the `state.summary` window, the same shape and the same
        // scheduler as `"flowSample"` below — an arbitrary number of settled
        // effective changes on an observed cell coalesces into one summary
        // carrying the latest reading, and a window is published even when
        // nothing changed so a client ages what it shows off received windows
        // rather than off silence. Registered after `"sweep"`: an observation
        // the sweep just released has already published its one trailing
        // window by the time this runs, so a single `tickAll()` never
        // publishes both a scheduled and a trailing summary for it.
        Tick("stateSummary", Observations.WINDOW_MS) { observations.sample() },
        Tick("errorPoll", ERROR_POLL_SECONDS * 1_000) { errors.poll() },
        // M3: the flow feed's single aggregation thread is this same
        // scheduler — snapshot-and-reset is a handful of atomic reads, and
        // sharing the one daemon thread keeps the inspector at exactly the
        // threads it had.
        Tick("flowSample", FlowCollector.WINDOW_MS) { flow.sample() },
        // V3: the wave-health heuristic (see [WaveHealth]). Registered
        // immediately after `"flowSample"` on purpose — the evaluator reads the
        // very state that tick has just drained, so this order keeps
        // `tickAll()` a faithful synchronous stand-in for the scheduled one.
        // Same period as the flow window: the conditions are measured in whole
        // seconds, so anything finer would only re-derive the same answer.
        Tick("waveHealth", WAVE_HEALTH_PERIOD_MS) { waveHealth.evaluate() },
        // M4: `graphs.changed` is published from here rather than from the
        // registry hooks, which coalesces a whole graph build — N publishes
        // and M links — into one hint and keeps the O(V+E) component sweep
        // off the thread that is linking cells. That coalescing is its own
        // reason to stay scheduled; T21 removed only the
        // `model.reconcilePeers()` call that used to share this tick, because
        // peer arrivals and departures now reach the model as registry events
        // (see [hooks]) and are therefore already in the partition when this
        // tick describes it. V2 folded the retired `"lifecycleChanged"` tick's
        // card invalidation in here as well (see [InspectorModel.publishGraphChanges]),
        // which coalesces a `resumeHost`'s one-transition-per-cell burst into
        // the single `graphs.changed` the poll used to emit per sweep.
        Tick("graphsChanged", GRAPHS_POLL_MS) { model.publishGraphChanges() },
    )

    fun start(): InspectorServer = apply {
        shell.start()
        ticks.forEach { tick ->
            heartbeats.scheduleAtFixedRate(
                { runCatching(tick.action) },
                tick.periodMs, tick.periodMs, TimeUnit.MILLISECONDS,
            )
        }
    }

    fun stop() = close()

    override fun close() {
        heartbeats.shutdownNow()
        // every registry feed detaches, including the two any-scope ones (T21):
        // a closed inspector is not merely disarmed, it is off the registry
        hooks.forEach { runCatching { it.close() } }
        // V2 — and every kernel lifecycle listener with them: a stopped
        // inspector is off every host it was watching, not merely quiet
        activity.close()
        // V1C-BE — the cursor table holds no cell, host or port, so this is
        // hygiene rather than detachment: a stopped inspector must not keep
        // resumable walks over a graph it is no longer serving
        pagedState.close()
        // before the broadcaster: releasing a sink emits topology deltas, and a
        // still-attached client should see its own subscriptions retract
        observations.close()
        errors.close()
        // V3 — no kernel attachment to release (the whole point of the class),
        // only its own open set; cleared here for symmetry so a closed inspector
        // holds no diagnostics about a graph it is no longer watching
        waveHealth.close()
        // untaps every outlet: a stopped inspector leaves no handler on a live graph
        flow.close()
        broadcaster.close()
        shell.stop()
    }

    /** Refs with a live observation — diagnostics and tests. */
    internal val observedRefs: Set<CellRef> get() = observations.openRefs

    /**
     * Run every scheduled [Tick]'s action once, synchronously, on the calling
     * thread — the single test seam that replaces the six `…Now()` accessors
     * this ticket (T24) collapsed (`sweepIdleObservations`, `pollErrorsNow`,
     * `sampleFlowNow`, `publishGraphChangesNow`, `publishLifecycleChangesNow`
     * — the last of which V2 retired outright with its tick — plus the
     * heartbeat tick no accessor ever exposed). Every action here is
     * a read-then-maybe-emit pass over its own collaborator's independent
     * state, so running them together introduces no test-visible interference
     * beyond an extra `heartbeat` frame on the wire, which every test that
     * cares filters its SSE stream by event kind anyway.
     */
    internal fun tickAll() = ticks.forEach { it.action() }

    /** Outlets the flow feed currently taps — tests. */
    internal val tappedOutlets: Set<PortRef> get() = flow.tappedOutlets

    /**
     * V3-BE — when a re-baseline beat was last observed on a tapped outlet of
     * [ref] (see [FlowCollector.reBaselineAtMsOf]). A test seam beside
     * [tappedOutlets], for the one race a supervision-timeline test cannot
     * otherwise bound: `ManagedHost` bumps the generation *before* it
     * re-baselines, so waiting on the generation alone can outrun the beat.
     */
    internal fun reBaselineAtMsOf(ref: CellRef): Long? = flow.reBaselineAtMsOf(ref)

    /** `GET /api/inspect/errors`'s body, decoded — tests. */
    internal fun errorSnapshot(): ErrorSnapshot = errors.snapshot()

    /**
     * `GET /api/inspect/activity`'s body, decoded — tests. Readable after
     * [close] too, which is how a test asserts that closing genuinely detached
     * the kernel listeners: the ring is still there, and it stops growing.
     */
    internal fun activitySnapshot(): ActivitySnapshot = activity.snapshot()

    /** Has this view adopted [ref] yet? The barrier a peer-announcement test waits on. */
    internal fun knowsNow(ref: CellRef): Boolean = model.knows(ref)

    /**
     * The lifecycle last announced for [ref] — the barrier a test waits on when
     * it needs a *previous* transition to be provably already announced before
     * it attaches an SSE client. See [InspectorModel.announcedLifecycleOf] for
     * why a kernel state flag will not do.
     */
    internal fun announcedLifecycle(ref: CellRef): String? = model.announcedLifecycleOf(ref)

    /** The live components, as `GET /graphs` and `GET /search` see them — tests. */
    internal fun componentsNow(): List<Component> = model.components()

    /**
     * How an [InspectorServer] obtains its HTTP shell — the seam that makes
     * T19's **named**-port half assertable (computenet-lxq).
     *
     * `DemoShell` exposes only its `boundPort`, never the address it bound, and
     * since computenet-dqy.33 an *ephemeral* shell binds loopback whether or not
     * it was told to — so over a socket `DemoShell(0)` and
     * `DemoShell(0, loopback)` are the same server and no probe can tell them
     * apart. The half that is left unpinned is the one that matters most: for a
     * **named** port the shell keeps the wildcard unless [shell]'s explicit
     * argument says otherwise, and a test cannot bind a named port to find out
     * — choosing a number now and binding it later is exactly the race
     * computenet-dqy.25 removed from this repo.
     *
     * So the *decision* is what gets asserted, in the spirit of the structural
     * route `DemoShellBindTest` takes to `DemoShell.endpoint`: a test passes its
     * own [Shells] as [shells], constructs an inspector on a named port, and
     * reads back what that construction asked for while its stand-in binds
     * nothing but an ephemeral loopback port. A purely structural helper would
     * not do here — a test that only asserts what some `bindAddressFor(port)`
     * returns stays green when the call site stops calling it, which is the
     * mutation this has to catch.
     *
     * [open]'s `bindAddress` carries the same `null` default `DemoShell` gives
     * it deliberately: dropping the argument at the call site has to stay
     * *expressible*, or the property this seam exists to pin could only ever
     * fail as a compile error, which is not the failure the exposure would
     * actually take.
     */
    internal interface Shells {
        fun open(port: Int, bindAddress: InetAddress? = null): DemoShell

        /** Exactly the construction this class has always performed. */
        object Real : Shells {
            override fun open(port: Int, bindAddress: InetAddress?): DemoShell = DemoShell(port, bindAddress)
        }
    }

    companion object {
        const val DEFAULT_PORT = 7071
        const val BASE_PATH = "/api/inspect"
        const val TOPOLOGY_PATH = "$BASE_PATH/topology"
        const val EVENTS_PATH = "$BASE_PATH/events"
        const val CELL_PATH = "$BASE_PATH/cell"
        const val ERRORS_PATH = "$BASE_PATH/errors"
        const val GRAPHS_PATH = "$BASE_PATH/graphs"

        /**
         * V2 — the activity feed's catch-up read (see [Activity]). Neither a
         * prefix nor a prefixee of any other route under [BASE_PATH], so it is
         * exempt from the registration-order discipline [GRAPH_PATH] documents.
         */
        const val ACTIVITY_PATH = "$BASE_PATH/activity"

        /**
         * M5-COLD — the per-graph action subtree: `POST $GRAPH_PATH/{id}/wake`.
         *
         * **Registration-order/prefix-length constraint, stated once, here,
         * beside the two constants it depends on**: [GRAPH_PATH] is
         * deliberately one character shorter than [GRAPHS_PATH], and its route
         * (in `init`) is registered *after* [GRAPHS_PATH]'s. The JDK http
         * server matches contexts by longest path prefix, so `/graphs` still
         * reaches its own handler while `/graph/{id}/wake` reaches this one —
         * get either the length or the order wrong and one swallows the
         * other. A future route under [BASE_PATH] whose path prefixes an
         * existing one must repeat this discipline (longer/more-specific
         * prefix registered first) or choose a path that does not collide.
         */
        const val GRAPH_PATH = "$BASE_PATH/graph"
        const val SEARCH_PATH = "$BASE_PATH/search"

        /** Contract §SSE: "Server sends `heartbeat` every 15 s". */
        const val HEARTBEAT_SECONDS = 15L

        /** How often idle observations are swept; the deadline itself is [Observations.IDLE_RELEASE_MS]. */
        const val SWEEP_SECONDS = 30L

        /** M2-BE ticket: "poll parked counts on a 2 s timer" — the same cadence covers restarts. */
        const val ERROR_POLL_SECONDS = 2L

        /** How often pending component changes are announced as `graphs.changed`. */
        const val GRAPHS_POLL_MS = 1_000L

        /**
         * V3 — how often [WaveHealth] re-derives its conditions. The thresholds
         * it applies are whole seconds ([WaveHealth.LAG_GRACE_MS],
         * [WaveHealth.STALL_WINDOW_MS]), so a faster cadence would buy nothing
         * but more passes over the same answer; a slower one would delay the
         * `cleared` event a resolved condition owes its client.
         */
        const val WAVE_HEALTH_PERIOD_MS = FlowCollector.WINDOW_MS

        /**
         * (a) — the [snapshots] default's bounded wait on
         * [ManagedHost.snapshotOf]. This runs synchronously on the HTTP
         * dispatcher thread inside one `GET /cell/{ref}/state` response, not
         * across a multi-cell fan-out the way [DataSearch.BUDGET_MS]'s 2000ms
         * budget does — so it is deliberately much shorter: long enough that
         * an ordinary snapshot (a momentarily busy but live host scheduler)
         * lands well inside it, short enough that a single slow or wedged
         * cell costs one HTTP response a barely-noticeable delay rather than
         * a perceptible stall.
         *
         * **V1C-BE — the same constant, not a second one, for the bounded read**
         * ([PagedState]). A page is strictly cheaper than the whole copy this
         * was sized for (`ManagedHost.readState` does O(limit) work per
         * scheduler task where `snapshotOf` copies the whole fold), so a second
         * constant would only be a second thing to keep in step. One request
         * spends this wait once: an `Unavailable` is an answer, never a reason
         * to spend it again on the older seam.
         */
        internal const val SNAPSHOT_WAIT_MS = 200L

        /**
         * V1C-BE — `?limit=` when the client did not say. Matches both
         * [ValueEncoder.MAX_ROWS] and `StateRead`'s own default: it is what the
         * detail panel renders, so asking a cell for more would buy a cost
         * nobody sees.
         */
        internal const val PAGE_LIMIT_DEFAULT = 200

        /**
         * V1C-BE — the ceiling `?limit=` is clamped to. Protects the invariant
         * the whole primitive rests on: **one page = one scheduler task**, so a
         * page must stay small enough that the task interleaves with the cell's
         * real work rather than owning its thread the way a whole copy does.
         */
        internal const val PAGE_LIMIT_MAX = 1_000

        /**
         * V1C-BE — how long an unclaimed `page.cursor` stays resumable. Protects
         * the server from a UI that abandoned a walk: one minute, then the entry
         * is gone and the client's next `?cursor=` is an honest 410 rather than
         * an entry pinned indefinitely.
         */
        internal const val CURSOR_TTL_MS = 60_000L

        /**
         * V1C-BE — simultaneously live walks; the oldest is evicted past this.
         * Protects the server from unbounded growth under many concurrent or
         * abandoned walks, the same drop-oldest discipline [SseBroadcaster]
         * applies to a slow client's queue (binding constraint 6).
         */
        internal const val CURSOR_MAX_OPEN = 256

        /**
         * V1C-BE — how many times a page whose entries the encoder's *byte*
         * budget cut is re-read at a smaller limit. Bounds what that
         * reconciliation costs: without a bound, a cell of pathologically wide
         * entries could drive a re-read per attempt inside one HTTP response.
         */
        internal const val PAGE_RENDER_RETRIES = 1

        /** (b) — Vite's own default build output directory (`inspect/ui/package.json`). */
        internal fun defaultUiDist(): Path = Path.of("inspect", "ui", "dist")

        /** (b) — `GET /`'s file, since the app is hash-routed and serves no other bare path. */
        private const val INDEX_HTML = "index.html"

        private const val JSON = "application/json"
        private const val STATE = "state"
        private const val OBSERVE = "observe"
        private const val WAKE = "wake"

        /**
         * T19 — required on every `POST .../wake`; see that route's KDoc and
         * [allowCrossOrigin]'s for why a header, not a stronger auth scheme,
         * is the right size for a developer instrument's one mutating route.
         */
        internal const val WAKE_HEADER = "X-Inspector"
        internal const val WAKE_HEADER_VALUE = "1"

        /** `GET /topology`'s M4 scoping parameter. */
        private const val GRAPH_PARAM = "graph"
        private const val MODE_PARAM = "mode"
        private const val QUERY_PARAM = "q"

        /** `"<uuid>:<instanceId>"` — the contract's ref encoding. */
        internal fun encodeRef(ref: CellRef): String = "${ref.id}:${ref.instanceId}"

        /** Inverse of [encodeRef]; null for anything that is not one. */
        internal fun decodeRef(encoded: String): CellRef? {
            val id = runCatching { UUID.fromString(encoded.substringBefore(':')) }.getOrNull() ?: return null
            val instance = encoded.substringAfter(':', "0").toLongOrNull() ?: return null
            return CellRef(id, instance)
        }

        private fun problem(reason: String): String =
            buildJsonObject { put("reason", reason) }.toString()
    }
}

/**
 * The non-empty `/`-separated segments of this request's path after
 * stripping [prefix] — "strip the route prefix, split on `/`, drop empty
 * segments", shared by [InspectorServer.serveGraph] and
 * [InspectorServer.serveCell] so a third sub-path handler under
 * [InspectorServer.BASE_PATH] has one helper to reach for instead of writing
 * a third slightly-different inline parse (T24).
 */
private fun HttpExchange.tailSegments(prefix: String): List<String> =
    requestURI.path.removePrefix(prefix).split('/').filter { it.isNotEmpty() }

/**
 * The request's decoded query parameters. The JDK http server hands over a raw
 * query string and nothing else, and the inspector's two parameterized
 * endpoints (`?graph=`, `?mode=&q=`) need no more than this: last value wins,
 * a valueless key reads as empty, and percent/`+` escapes are decoded so a
 * search for `civictech.cell.data` survives the wire.
 */
private fun HttpExchange.query(): Map<String, String> =
    requestURI.rawQuery
        ?.split('&')
        ?.filter { it.isNotEmpty() }
        ?.associate { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), StandardCharsets.UTF_8)
            name to URLDecoder.decode(pair.substringAfter('=', ""), StandardCharsets.UTF_8)
        }
        ?: emptyMap()

/** 204 has no body; the JDK server needs `-1`, not `0` (which means "chunked"). */
private fun HttpExchange.noContent() {
    sendResponseHeaders(204, -1)
    close()
}

/**
 * The inspector runs on its own port, so a dev UI (Vite, another port) is
 * cross-origin unless it proxies. `demo/agora/ui` proxies and the inspector UI
 * is expected to as well; this header only removes the failure mode where it
 * does not.
 *
 * **T19 — the real per-route posture, not one blanket claim.** The *reads*
 * that call this — `TOPOLOGY_PATH`, `ERRORS_PATH`, `GRAPHS_PATH`,
 * `SEARCH_PATH`, `EVENTS_PATH`, and `CELL_PATH`'s `GET {ref}` / `GET
 * {ref}/state` — carry no credentials and are safe to leave wildcard because a
 * developer instrument has no cross-origin caller to distrust *and* — since
 * [InspectorServer]'s own `shell` binds `InetAddress.getLoopbackAddress()` —
 * is not reachable from anywhere but this machine to begin with.
 *
 * Two things served through this helper are *not* reads, and saying so is the
 * whole point of this rewrite:
 *
 * - `POST GRAPH_PATH/{id}/wake` is a management mutation ([Waker.wake] resumes
 *   hosts and cells). It does not rely on this wildcard to stay safe — see
 *   that route's KDoc for [WAKE_HEADER], the mechanism that actually gates it.
 * - `CELL_PATH`'s `POST`/`DELETE {ref}/observe` start and stop an observation
 *   ([Observations]) — server-side state, not a read. They are deliberately
 *   left ungated: T19 scoped its gate to the wake route only, so what stands
 *   between `observe` and anything off this machine is the loopback bind.
 *   Recorded here rather than quietly widened, so the next reader is not told
 *   again that everything behind this helper is read-only.
 *
 * This stays one helper for all of them because the wildcard origin header
 * alone was never the problem on the wake route; the problem was treating "no
 * CORS error" as "safe to mutate", which the header requirement now makes
 * false for any caller that has not opted in.
 */
private fun HttpExchange.allowCrossOrigin() {
    responseHeaders.add("Access-Control-Allow-Origin", "*")
}
