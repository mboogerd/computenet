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
import civictech.cell.observe.View
import civictech.cell.host.link
import civictech.cell.observe.observe
import civictech.cell.port.streamTo
import civictech.cell.host.RoutedPropagate
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

class DemoApp(port: Int = 8080, private val wire: Wire? = null, journalDir: java.io.File? = null) {
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

    private val itemsUnion = UnionSetCell<String>(ref = unionRef("items", myRole))
    private val votesUnion = UnionSetCell<String>(ref = unionRef("votes", myRole))

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

        if (wire != null) {
            val side = Peering.Side(registry, bridgeHost!!)
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
        """{"items":${arr(items)},"votes":${arr(votes)},"produce":${arr(produce)},"wanted":${arr(wanted)},"voteCount":$voteCount}"""
    }

    /**
     * Opt-in inspector (`--inspect-port <p>`): serves this JVM's live dataflow
     * graph on its own port. This demo is the M5-NET pilot — the one that runs
     * two symmetric JVMs over the real `:wire` transport — so what it shows
     * beyond the single-process case is:
     *
     * - **both sides.** The peer's announced cells appear with its network
     *   host and no process host; this JVM's cells report [netName]
     *   (`--net-name`, e.g. `jvm-a`), so the canvas nests one dashed net hull
     *   per JVM around the solid process hulls inside them.
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
        netName: String = "local",
    ): civictech.inspect.InspectorServer {
        val peerItems = unionRef("items", peerRole)
        val peerVotes = unionRef("votes", peerRole)
        val names = buildMap {
            put(itemsUnion.ref, "items")
            put(votesUnion.ref, "votes")
            produceRef?.let { put(it, "produce") }
            wantedRef?.let { put(it, "wanted") }
            if (wire != null) {
                put(peerItems, "items@$peerRole")
                put(peerVotes, "votes@$peerRole")
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
        }
        return started.also { inspector = it }
    }

    fun start(): DemoApp = apply { shell.start() }

    fun stop() {
        inspector?.stop()
        shell.stop()
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

    val app = DemoApp(port, wire, journalDir).start()
    println("computenet demo: http://localhost:${app.boundPort} — open two tabs to collaborate")
    when (wire) {
        is DemoApp.Wire.Listen -> println("  awaiting a peer on ws://localhost:${wire.wsPort}")
        is DemoApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
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
