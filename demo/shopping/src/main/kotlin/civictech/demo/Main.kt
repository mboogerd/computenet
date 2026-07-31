package civictech.demo

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.graph.TypedCellHandle
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.link.PeerId
import civictech.cell.observe.View
import civictech.cell.host.link
import civictech.cell.observe.observe
import civictech.cell.port.streamTo
import civictech.cell.host.RoutedPropagate
import civictech.cell.replication.Replication
import civictech.cell.wire.Peering
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.respond
import civictech.demo.shell.value
import civictech.wire.WsTransport
import com.sun.net.httpserver.HttpExchange
import java.net.URI
import java.net.URLDecoder
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.ObservedRemoveOps
import civictech.cell.data.op.UnionSetApi
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.IntersectSetCell

// The UI transport is still JDK httpserver + SSE pushing full state (an
// incremental browser client is M6+ material); the *peer* transport is the
// real M5 wire — WebSocket frames between symmetric JVMs.

/**
 * [netName] is this JVM's network-host name (`--net-name`). It is both what
 * [startInspector] labels local cells with and — since V4-PEERID — the
 * [PeerId] this JVM puts in its transport hello, so the *peer's* inspector can
 * group this JVM's cells under `jvm-a`/`jvm-b` and keep that grouping across a
 * reconnect. Null (the flag absent) means an **anonymous** peer: no name in
 * the hello, the peer keeps deriving a `peer-<id>` label, and the inspector
 * keeps reporting the contract's `"local"` for this JVM. Naming every unnamed
 * peer `"local"` instead would make two anonymous peers indistinguishable.
 *
 * [replicate] is V4-PILOT's `--replicate` mode, **off by default**. It adds one
 * genuine same-logical-id replica per JVM ([SHARED_ID], role-derived
 * `instanceId`) wired through [Replication], so the two JVMs gossip a single
 * logical cell across the real socket — a *different* distribution model from
 * the role-distinct union counterparts above (`unionRef(name, role)` mints two
 * different logical ids and chains them; a replica is one logical id in two
 * places). With the flag absent nothing here is constructed and this app is
 * byte-identical to what it was before the mode existed.
 */
class DemoApp(
    port: Int = 8080,
    private val wire: Wire? = null,
    journalDir: java.io.File? = null,
    private val netName: String? = null,
    replicate: Boolean = false,
) {
    /** Peer mode (M5.7): symmetric peers — one listens, the other dials. */
    sealed interface Wire {
        data class Listen(val wsPort: Int) : Wire
        data class Dial(val uri: String) : Wire
    }

    private val registry = LocationRegistry()

    // Durability (M10.4): the app host write-ahead journals every routed
    // invocation; on restart the same directory replays it — kill -9 safe.
    // The WAL file is minted via the KeyedCells helper so it matches exactly
    // what writerCells.recover() replays.
    private val host = ManagedHost(registry = registry, journal = KeyedCells.hostJournal(journalDir))
    private val manage = host.managementInlet.call

    // union refs are role-derived so each peer can address its counterpart's
    // unions without a discovery protocol (that's M6+ territory)
    private val myRole = when (wire) {
        is Wire.Listen -> "listener"
        is Wire.Dial -> "dialer"
        null -> "solo"
    }
    private val peerRole = if (myRole == "listener") "dialer" else "listener"

    private fun unionRef(name: String, role: String) =
        CellRef(UUID.nameUUIDFromBytes("demo-union:$name@$role".toByteArray()))

    private val state = Object()
    private var items: Set<String> = emptySet()
    private var votes: Set<String> = emptySet()
    private var produce: Set<String> = emptySet()
    private var wanted: Set<String> = emptySet()
    private var voteCount: Long = 0

    /** `--replicate` only: this JVM's fold of the one shared logical cell. */
    private var shared: Set<String> = emptySet()

    private val itemsUnion = UnionSetCell<String>(ref = unionRef("items", myRole))
    private val votesUnion = UnionSetCell<String>(ref = unionRef("votes", myRole))

    /**
     * V4-PILOT — the replica mesh linker, held for the process lifetime.
     *
     * A field, and constructed *here* rather than inside [init], because
     * `Replication`'s constructor installs the registry `onPublish`/`onUnpublish`
     * hooks the whole mesh is driven by (`Replication.kt:142-158`): property
     * initializers run before the `init` block, and the `init` block is where
     * peering is established — so every peer announcement this JVM will ever
     * see arrives after the hooks are in place.
     */
    private val replication: Replication? = if (replicate) Replication(registry) else null

    /**
     * V4-PILOT — this JVM's instance of the one shared logical cell.
     *
     * Same [SHARED_ID] on both sides, `instanceId` derived from the peering
     * role ([sharedInstance]) so no discovery protocol and no extra flag is
     * needed — the same trick [startInspector] already uses to address the
     * peer's union counterparts. `sameLogical` holds between the two; the refs
     * differ. Spawning is [Replication.replicate]'s job (`Replication.kt:204`),
     * so this is deliberately *not* also `manage.spawn`ed.
     */
    private val sharedCell: SetCell<String>? =
        if (replicate) SetCell<String>(CellRef(SHARED_ID, sharedInstance(myRole))) else null

    // Per-user writers: one durable, dynamically-keyed family (M10.4). Compound
    // keys "$user:items"/"$user:votes" pack both writers into a single family so
    // they share one journalDir without colliding on the `keys` file or double-
    // running recoverFrom (a single family, not two). The factory does the
    // streamTo wiring, so recover() re-establishes it for every known key.
    private val writerApi = mutableMapOf<String, Pair<SetOps<String>, SetOps<String>>>()
    private val writerCells = KeyedCells<String>(
        host = host,
        journalDir = journalDir,
        namespace = "demo-writer@$myRole",
        factory = { key, ref ->
            val union = if (key.endsWith(":items")) itemsUnion else votesUnion
            SetCell<String>(ref).also { it.outlet.streamTo(routedDelta(union.ref)) }
        },
    )

    private val shell = DemoShell(port)

    /**
     * The peering bridge's own host, hoisted out of [init] so [startInspector]
     * can name it: its cells (the bridge egress/ingress and the registry
     * mirror) are published on this registry like any other, and an
     * unrecognised host would otherwise show up under a generated name.
     */
    private val bridgeHost: ManagedHost? = wire?.let { ManagedHost(registry = registry) }

    /** The DSL-spawned derived views, kept for the inspector's cell names. */
    private var produceRef: CellRef? = null
    private var wantedRef: CellRef? = null

    private var inspector: civictech.inspect.InspectorServer? = null

    val boundPort: Int get() = shell.boundPort

    /** V4-PILOT: this JVM's replica instance id, or null with `--replicate` off. */
    val sharedInstanceId: Long? get() = sharedCell?.ref?.instanceId

    init {
        manage.spawn(itemsUnion)
        manage.spawn(votesUnion)

        // the derived views are DSL-built with pure { ref -> ... } factories
        // (replay-safe GraphSpec — no live instance captured in a step);
        // observation sinks fold them into UI state
        var produceHandle: TypedCellHandle<FilterCell<String>>? = null
        var wantedHandle: TypedCellHandle<IntersectSetCell<String>>? = null
        graph(host.managementInlet) {
            produceHandle = spawn("produce") { ref ->
                FilterCell<String>(ref) { s -> s.firstOrNull()?.lowercaseChar() in 'a'..'m' }
            }
            wantedHandle = spawn("wanted") { ref -> IntersectSetCell<String>(ref) }
        }
        val produceCell = produceHandle!!
        val wantedCell = wantedHandle!!
        produceRef = produceCell.ref
        wantedRef = wantedCell.ref
        manage.link(itemsUnion.outlet, produceCell.cell.inlet)

        host.observe(itemsUnion.ref, View.set<String>()) { synchronized(state) { items = it }; broadcast() }
        host.observe(votesUnion.ref, View.set<String>()) { synchronized(state) { votes = it }; broadcast() }
        host.observe(produceCell.ref, View.set<String>()) { synchronized(state) { produce = it }; broadcast() }

        // Derived view: items ∩ votes — "still wanted" is the incremental
        // intersection of two independently-mutating streams (the binary
        // set operator the filter chain didn't yet show). It feeds both the
        // "Still wanted" list and the vote count, so both track votes for
        // listed items only, without discarding the retained raw vote. The
        // vote count is derived from the wanted set's size (|items ∩ votes|):
        // a vote for a since-removed item stays in the raw votes set but drops
        // out of the intersection, so it neither shows a ★ nor inflates the count.
        manage.link(itemsUnion.outlet, wantedCell.cell.left)
        manage.link(votesUnion.outlet, wantedCell.cell.right)
        host.observe(wantedCell.ref, View.set<String>()) {
            synchronized(state) { wanted = it; voteCount = it.size.toLong() }; broadcast()
        }

        // V4-PILOT (`--replicate`): the shared replica, wired BEFORE peering so
        // Replication's registry hooks are already installed when the peer's
        // first announcement lands. `replicate` spawns the cell itself, then
        // links it to every already-known replica of SHARED_ID and mints the
        // delivered-watermark companion that rides the same mesh — so this one
        // call adds *two* cells to this JVM, and the peer's two show up as
        // mirrored refs once it announces.
        //
        // The `manage.link` into the items union is a real ManagedHost.connect,
        // so it lands in the topology index and the replica belongs to the
        // "shopping" component rather than floating as a singleton. Its visible
        // consequence — shared items appear in the items list — is the point:
        // it is what makes the pilot narratable in the UI.
        if (replication != null && sharedCell != null) {
            replication.replicate(sharedCell, host)
            manage.link(sharedCell.outlet, itemsUnion.inlet)
            host.observe(sharedCell.ref, View.set<String>()) {
                synchronized(state) { shared = it }; broadcast()
            }
        }

        if (wire != null) {
            // V4-PEERID: name this side, so the peer's inspector labels our
            // cells with our own --net-name and keeps that label across a
            // reconnect. Unset --net-name ⇒ anonymous, exactly as before.
            val side = Peering.Side(registry, bridgeHost!!, peer = netName?.let { PeerId(it) })
            when (wire) {
                is Wire.Listen -> WsTransport.listen(wire.wsPort, side)
                is Wire.Dial -> WsTransport.connect(URI(wire.uri), side)
            }
            // symmetric view chaining: my unions stream into the peer's counterparts.
            // Tag dedup + effective-only emission make the two-way chain cycle-safe;
            // sends park in the registry until the peer announces, so a late-starting
            // peer replays the full history in order and converges. Anti-entropy on
            // (re)announce (M10.4, T07 finding 2): a returning peer may have missed
            // deltas its dying socket swallowed — Peering.chainOnReannounce re-fires
            // the full on-link catch-up so the full state-as-delta flows again; tag
            // idempotence makes the repeat free.
            val chained = mapOf(
                unionRef("items", peerRole) to
                        (itemsUnion.outlet to itemsUnion.outlet.streamTo(routedDelta(unionRef("items", peerRole)))),
                unionRef("votes", peerRole) to
                        (votesUnion.outlet to votesUnion.outlet.streamTo(routedDelta(unionRef("votes", peerRole)))),
            )
            Peering.chainOnReannounce(registry, chained)
        }

        // recover() pre-spawns every known writer (the factory rewires streamTo)
        // then replays the shared WAL exactly once — the one correct ordering.
        if (journalDir != null) writerCells.recover()

        shell.route("/") { exchange -> exchange.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/op") { exchange -> handleOp(exchange) }
        shell.sse("/events") { stateJson() }
    }

    /**
     * Per-user writer cells, created on first op — every browser tab is a user.
     * The [writerCells] family mints the deterministic ref, lazily spawns, and
     * durably logs each compound key; the factory wires the union streamTo. This
     * caches the inlet API pair per user (M10.4): deterministic identity + routed
     * (journaled) deltas are what make a journal replay reconstruct the same
     * graph state after kill -9. The [TypedRef] lookup navigates the kernel's
     * own [SetApi] — no per-port proxy interface needed.
     */
    private fun writerFor(user: String): Pair<SetOps<String>, SetOps<String>> =
        synchronized(writerApi) {
            writerApi.getOrPut(user) {
                val itemCell = writerCells.getOrSpawn("$user:items")
                val voteCell = writerCells.getOrSpawn("$user:votes")
                val itemApi = host.lookup(TypedRef<SetApi<String>>(itemCell.ref))!!.inlet.call
                val voteApi = host.lookup(TypedRef<SetApi<String>>(voteCell.ref))!!.inlet.call
                itemApi to voteApi
            }
        }

    /**
     * The shared list's union-scoped remove (D-UNION), reached the same way
     * the per-user writers are: a routed proxy, so the invocation is
     * write-ahead journaled and replays deterministically after `kill -9`.
     * Lazy because [itemsUnion] is spawned in [init].
     */
    private val itemsRemoveOps: ObservedRemoveOps<String> by lazy {
        host.lookup(TypedRef<UnionSetApi<String>>(itemsUnion.ref))!!.removeInlet.call
    }

    /**
     * V4-PILOT — the shared replica's write path, reached the same routed way
     * every other write is ([writerFor]'s idiom): a hosted lookup, never the
     * cell object directly, so the invocation is routed and journaled like the
     * rest. Null (and `action=share` therefore a 400) when the mode is off.
     */
    private val sharedOps: SetOps<String>? by lazy {
        sharedCell?.let { host.lookup(TypedRef<SetApi<String>>(it.ref))!!.inlet.call }
    }

    private fun routedDelta(ref: CellRef): Propagate<SetDelta<String>> =
        RoutedPropagate(ref, "inlet", registry::deliver)

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
        val user = params["user"] ?: return exchange.respond(400, "missing user")
        val item = params["item"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return exchange.respond(400, "missing item")
        val (itemOps, voteOps) = writerFor(user)
        when (params["action"]) {
            "add" -> itemOps.add(item)
            // The two removal intents this demo used to conflate (D-UNION):
            //
            // "remove"  — remove *the item*: a union-scoped observed remove
            //   over the shared list. It tombstones every add-tag the merged
            //   view currently holds for the item, whichever user minted it,
            //   so one click retracts the item for everyone — the behavior the
            //   UI has always offered. Only tags this JVM has already observed
            //   are covered; a concurrent add at the peer survives by add-wins
            //   (spec 24 [24-SET-03]), which is the intended boundary.
            // "remove-mine" — retract *my* contribution: the writer-local op,
            //   which tombstones only this user's own add-tags and leaves
            //   another user's add of the same item standing.
            //
            // Both are routed (hence journaled) invocations, so replay stays
            // deterministic and the per-user writer identity M10.4 depends on
            // is untouched — the union-scoped del is minted at the union, not
            // by borrowing another user's writer.
            "remove" -> itemsRemoveOps.removeObserved(item)
            "remove-mine" -> itemOps.remove(item)
            "vote" -> voteOps.add(item)
            // V4-PILOT: a write onto the replicated cell. With `--replicate`
            // absent [sharedOps] is null and this is a 400, the same answer the
            // `else` branch gives — the action does not exist unless the mode does.
            "share" -> (sharedOps ?: return exchange.respond(400, "unknown action")).add(item)
            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    // ponytail: fires once per hub update, so a single op can push a few frames
    // whose four views are momentarily out of step (e.g. the filtered aisle
    // updates one frame before the master list) before converging. Fine for the
    // full-state SSE transport; coalescing to one frame per wave is M6+ material.
    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String = synchronized(state) {
        fun arr(values: Set<String>) =
            values.sorted().joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        // V4-PILOT: the `"shared"` field exists ONLY in replicate mode, so the
        // default payload is byte-identical to what every existing test and the
        // browser page already parse.
        val sharedField = if (sharedCell == null) "" else ""","shared":${arr(shared)}"""
        """{"items":${arr(items)},"votes":${arr(votes)},"produce":${arr(produce)},"wanted":${arr(wanted)},"voteCount":$voteCount$sharedField}"""
    }

    /**
     * Opt-in inspector (`--inspect-port <p>`): serves this JVM's live dataflow
     * graph on its own port. This demo is the M5-NET pilot — the one that runs
     * two symmetric JVMs over the real `:wire` transport — so what it shows
     * beyond the single-process case is:
     *
     * - **both sides.** The peer's announced cells appear with its network
     *   host and no process host — since V4-PEERID that is the peer's *own*
     *   `--net-name` (`jvm-b` as seen from `jvm-a`), stable across a peer
     *   restart, rather than a locally derived `peer-<id>`; this JVM's cells
     *   report [netName] (`--net-name`, e.g. `jvm-a`), so the canvas nests one
     *   dashed net hull per JVM around the solid process hulls inside them.
     * - **the cross-boundary stream, as an edge.** The symmetric view chain
     *   (`itemsUnion.outlet.streamTo(routedDelta(peerItemsRef))`) is a real
     *   subscription that no kernel index records — `ManagedHost.connect`,
     *   the only path that writes the topology index, resolves both endpoints
     *   in one host's cell map, so a cross-JVM link is not expressible as a
     *   `TopologyLink` at all. The app therefore declares it
     *   (`InspectorServer.declareLink`), the same way it annotates the graph's
     *   name: reported, never inferred.
     *
     * The peer's union refs are deterministic (`unionRef(name, peerRole)` —
     * that is exactly how each side addresses its counterpart without a
     * discovery protocol), so they can be both named and declared here before
     * the peer has ever connected.
     */
    fun startInspector(
        inspectPort: Int = civictech.inspect.InspectorServer.DEFAULT_PORT,
        netName: String = this.netName ?: "local",
    ): civictech.inspect.InspectorServer {
        val peerItems = unionRef("items", peerRole)
        val peerVotes = unionRef("votes", peerRole)
        val peerShared = CellRef(SHARED_ID, sharedInstance(peerRole))
        val names = buildMap {
            put(itemsUnion.ref, "items")
            put(votesUnion.ref, "votes")
            produceRef?.let { put(it, "produce") }
            wantedRef?.let { put(it, "wanted") }
            if (wire != null) {
                put(peerItems, "items@$peerRole")
                put(peerVotes, "votes@$peerRole")
            }
            // V4-PILOT: four names in replicate mode — the local data replica
            // and its watermark companion, plus the peer's mirrored two. The
            // peer's refs are deterministic (one shared logical id, role-derived
            // instance ids), so they can be named before the peer ever connects.
            sharedCell?.let { local ->
                put(local.ref, "shared")
                put(sharedWatermarkRef(local.ref.instanceId), "shared-watermark")
                if (wire != null) {
                    put(peerShared, "shared@$peerRole")
                    put(sharedWatermarkRef(peerShared.instanceId), "shared-watermark@$peerRole")
                }
            }
        }
        val hosts = buildMap {
            put("shopping", host)
            bridgeHost?.let { put("shopping-bridge", it) }
        }
        val started = civictech.inspect.InspectorServer(
            registry = registry,
            hosts = hosts,
            port = inspectPort,
            cellNames = names,
            netName = netName,
        ).nameGraph(itemsUnion.ref, "shopping").start()
        if (wire != null) {
            started.declareLink(itemsUnion.ref, "outlet", peerItems, "inlet")
            started.declareLink(votesUnion.ref, "outlet", peerVotes, "inlet")
            // V4-PILOT: the gossip subscription is `local.outlet.streamTo(sink)`
            // (`Replication.kt:442`), not a `ManagedHost.connect`, so no
            // TopologyLink records it and the inspector could never infer the
            // mesh. Same reported-never-inferred annotation as the union chain
            // above.
            sharedCell?.let { started.declareLink(it.ref, "outlet", peerShared, "deltaInlet") }
        }
        return started.also { inspector = it }
    }

    fun start(): DemoApp = apply { shell.start() }

    fun stop() {
        inspector?.stop()
        shell.stop()
    }

    companion object {
        /**
         * V4-PILOT — the one deterministic logical id both JVMs mint their
         * replica under. Derived exactly the way [unionRef] derives its refs;
         * the *sameness* is the whole point of the pilot, since a shared
         * logical id across a real socket is what has never been driven here.
         */
        val SHARED_ID: UUID = UUID.nameUUIDFromBytes("demo-replica:shared".toByteArray())

        /**
         * The replica instance id for a peering role: the listener (and a solo
         * process) take 0, the dialer takes 1. Role-derived, so each side can
         * name the peer's replica ref with no discovery protocol and no extra
         * flag — and so the two instance ids are distinct, which the replication
         * contract requires.
         */
        fun sharedInstance(role: String): Long = if (role == "dialer") 1L else 0L

        /**
         * The delivered-watermark companion's ref for the shared replica at
         * [instanceId].
         *
         * **Recomputed, not called**: `Replication.watermarkRef` is `internal`
         * to `:kernel`, so this demo cannot reach it. The derivation is copied
         * verbatim from `Replication.kt:98-99`
         * (`nameUUIDFromBytes("watermark:{logicalId}")`, sharing the data
         * replica's `instanceId`). A silent divergence here would mislabel a
         * node on the canvas rather than fail, which is why it is stated.
         */
        fun sharedWatermarkRef(instanceId: Long): CellRef =
            CellRef(UUID.nameUUIDFromBytes("watermark:$SHARED_ID".toByteArray()), instanceId)
    }
}

fun main(args: Array<String>) {
    val inspectPort = args.value("--inspect-port")?.trim()?.toIntOrNull()
        ?: System.getenv("INSPECT_PORT")?.trim()?.toIntOrNull()
    val netName = args.value("--net-name")?.trim()?.takeUnless { it.isEmpty() }
    // strip the inspector's own `--flag value` pairs before [demoPort], which
    // reads the first non-`--` argument as this demo's port and would
    // otherwise take one of their values (the skillmatch pilot's precedent)
    val demoArgs = stripPairs(args, "--inspect-port", "--net-name")

    val port = demoPort(demoArgs)
    val wire = args.value("--listen")?.let { DemoApp.Wire.Listen(it.toInt()) }
        ?: args.value("--peer")?.let { DemoApp.Wire.Dial(it) }
    val journalDir = args.value("--journal")?.let { java.io.File(it).apply { mkdirs() } }
    // V4-PILOT: a BARE boolean flag, so it is presence-tested rather than read
    // through `args.value`, and — unlike every `--flag value` pair above — it
    // needs NO stripPairs entry: `demoPort` skips any token starting with `--`
    // (DemoShell.kt:128-130), so it can never be mistaken for the demo's port.
    val replicate = "--replicate" in args

    val app = DemoApp(port, wire, journalDir, netName, replicate).start()
    println("computenet demo: http://localhost:${app.boundPort} — open two tabs to collaborate")
    when (wire) {
        is DemoApp.Wire.Listen -> println("  awaiting a peer on ws://localhost:${wire.wsPort}")
        is DemoApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
    if (replicate) {
        println("  replicate mode: shared logical cell ${DemoApp.SHARED_ID}, this JVM's instance ${app.sharedInstanceId}")
        // a lone replica is a legal replica — the mesh simply has one member
        if (wire == null) println("  (no --listen/--peer: the replica mesh has no peer to gossip with)")
    }
    inspectPort?.let { p ->
        val inspector = app.startInspector(p, netName ?: "local")
        println("computenet inspector: http://localhost:${inspector.boundPort}/api/inspect/topology")
        println("  this JVM's network host: ${netName ?: "local"}")
    }
}

/** Drop each `--flag value` pair from [args] — see [main]'s use. */
private fun stripPairs(args: Array<String>, vararg flags: String): Array<String> {
    val rest = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] in flags) i += 2 else rest += args[i++]
    }
    return rest.toTypedArray()
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>computenet — shared shopping list</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 2rem auto; padding: 0 1rem;
         color-scheme: light dark; background: Canvas; color: CanvasText; }
  h1 { font-size: 1.3rem; } h2 { font-size: 1rem; color: GrayText; }
  li { margin: .2rem 0; } button { margin-left: .5rem; }
  .voted { color: #b50; font-weight: bold; }
  #user { color: #888; font-size: .8rem; }
</style>
</head>
<body>
<h1>Shared shopping list <span id="user"></span></h1>
<form id="addForm"><input id="item" placeholder="new item" autofocus><button>Add</button></form>
<h2>Items (<span id="voteCount">0</span> voted)</h2>
<ul id="items"></ul>
<h2>Still wanted (items &cap; votes)</h2>
<ul id="wanted"></ul>
<h2>A–M aisle (filtered view)</h2>
<ul id="produce"></ul>
<script>
const user = sessionStorage.userId ??= Math.random().toString(36).slice(2, 8);
document.getElementById('user').textContent = 'you are ' + user;
const op = (action, item) => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams({ user, action, item }) });
document.getElementById('addForm').onsubmit = e => {
  e.preventDefault();
  const input = document.getElementById('item');
  if (input.value.trim()) op('add', input.value.trim());
  input.value = '';
};
new EventSource('/events').onmessage = e => {
  const s = JSON.parse(e.data);
  document.getElementById('voteCount').textContent = s.voteCount;
  const render = (id, items, actions) => {
    const ul = document.getElementById(id); ul.innerHTML = '';
    for (const item of items) {
      const li = document.createElement('li');
      li.textContent = item;
      if (s.votes.includes(item)) { li.classList.add('voted'); li.textContent += ' ★'; }
      if (actions) {
        // "remove" is union-scoped — it retracts the item for everyone;
        // "remove mine" retracts only this user's own add (D-UNION).
        for (const [label, action] of [['vote','vote'],['remove','remove'],['remove mine','remove-mine']]) {
          const b = document.createElement('button');
          b.textContent = label; b.onclick = () => op(action, item);
          li.appendChild(b);
        }
      }
      ul.appendChild(li);
    }
  };
  render('items', s.items, true);
  render('wanted', s.wanted, false);
  render('produce', s.produce, false);
};
</script>
</body>
</html>
"""
