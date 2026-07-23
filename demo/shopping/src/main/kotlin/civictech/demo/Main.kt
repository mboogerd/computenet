package civictech.demo

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.CounterDelta
import civictech.cell.data.FilterCell
import civictech.cell.data.CountCell
import civictech.cell.data.IntersectSetCell
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetHubCell
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.data.onEach
import civictech.cell.durability.FileJournal
import civictech.cell.graph.graph
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.port.streamTo
import civictech.cell.proxy.HostedCellProxy
import civictech.cell.wire.Peering
import civictech.wire.WsTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

// The UI transport is still JDK httpserver + SSE pushing full state (an
// incremental browser client is M6+ material); the *peer* transport is the
// real M5 wire — WebSocket frames between symmetric JVMs.

private class CounterHubCell(
    private val onUpdate: (Long) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private var total = 0L
    val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

    init {
        inlet.onEach { total += it.amount; onUpdate(total) }
    }
}

interface SetInletProxy {
    val inlet: Use<SetOps<String>>
}

interface DeltaInletProxy {
    val inlet: Use<Propagate<SetDelta<String>>>
}

class DemoApp(port: Int = 8080, private val wire: Wire? = null, journalDir: java.io.File? = null) {
    /** Peer mode (M5.7): symmetric peers — one listens, the other dials. */
    sealed interface Wire {
        data class Listen(val wsPort: Int) : Wire
        data class Dial(val uri: String) : Wire
    }

    private val registry = LocationRegistry()

    // Durability (M10.4): the app host write-ahead journals every routed
    // invocation; on restart the same directory replays it — kill -9 safe.
    private val journal = journalDir?.let { FileJournal(java.io.File(it, "host.journal")) }
    private val usersFile = journalDir?.let { java.io.File(it, "users.txt") }

    private val host = ManagedHost(registry = registry, journal = journal)
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
    private val clients = CopyOnWriteArrayList<OutputStream>()

    private val itemsUnion = UnionSetCell<String>(ref = unionRef("items", myRole))
    private val votesUnion = UnionSetCell<String>(ref = unionRef("votes", myRole))
    private val writers = mutableMapOf<String, Pair<SetOps<String>, SetOps<String>>>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        manage.spawn(itemsUnion)
        manage.spawn(votesUnion)

        // the derived views are DSL-built; hubs fold them into UI state
        val refs = mutableMapOf<String, CellRef>()
        graph(host.managementInlet) {
            val produceCell = spawn("produce") { FilterCell<String> { s -> s.firstOrNull()?.lowercaseChar() in 'a'..'m' } }
            val count = spawn("count") { CountCell<String>() }
            refs["produce"] = produceCell.ref
            refs["count"] = count.ref
        }
        manage.connect(itemsUnion.ref, "outlet", refs.getValue("produce"), "inlet")
        // `count` is fed by the items ∩ votes intersection wired below (not the
        // raw votes union), so "N voted" counts listed items that carry a vote.
        // A vote for a since-removed item is retained in the votes set but drops
        // out of the intersection, so it neither shows a ★ nor inflates the count.

        val itemsHub = SetHubCell<String>({ synchronized(state) { items = it }; broadcast() })
        val votesHub = SetHubCell<String>({ synchronized(state) { votes = it }; broadcast() })
        val produceHub = SetHubCell<String>({ synchronized(state) { produce = it }; broadcast() })
        val countHub = CounterHubCell({ synchronized(state) { voteCount = it }; broadcast() })
        listOf(itemsHub, votesHub, produceHub, countHub).forEach { manage.spawn(it) }
        manage.connect(itemsUnion.ref, "outlet", itemsHub.ref, "inlet")
        manage.connect(votesUnion.ref, "outlet", votesHub.ref, "inlet")
        manage.connect(refs.getValue("produce"), "outlet", produceHub.ref, "inlet")
        manage.connect(refs.getValue("count"), "outlet", countHub.ref, "inlet")

        // Derived view: items ∩ votes — "still wanted" is the incremental
        // intersection of two independently-mutating streams (the binary
        // set operator the filter/count chain didn't yet show). It feeds both
        // the "Still wanted" list and the vote count, so both track votes for
        // listed items only, without discarding the retained raw vote.
        val wantedCell = IntersectSetCell<String>()
        manage.spawn(wantedCell)
        manage.connect(itemsUnion.ref, "outlet", wantedCell.ref, "left")
        manage.connect(votesUnion.ref, "outlet", wantedCell.ref, "right")
        manage.connect(wantedCell.ref, "outlet", refs.getValue("count"), "inlet")
        val wantedHub = SetHubCell<String>({ synchronized(state) { wanted = it }; broadcast() })
        manage.spawn(wantedHub)
        manage.connect(wantedCell.ref, "outlet", wantedHub.ref, "inlet")

        if (wire != null) {
            val bridgeHost = ManagedHost(registry = registry)
            val side = Peering.Side(registry, bridgeHost)
            when (wire) {
                is Wire.Listen -> WsTransport.listen(wire.wsPort, side)
                is Wire.Dial -> WsTransport.connect(URI(wire.uri), side)
            }
            // symmetric view chaining: my unions stream into the peer's counterparts.
            // Tag dedup + effective-only emission make the two-way chain cycle-safe;
            // sends park in the registry until the peer announces, so a late-starting
            // peer replays the full history in order and converges.
            val chained = mapOf(
                unionRef("items", peerRole) to
                        (itemsUnion to itemsUnion.outlet.streamTo(routedDelta(unionRef("items", peerRole)))),
                unionRef("votes", peerRole) to
                        (votesUnion to votesUnion.outlet.streamTo(routedDelta(unionRef("votes", peerRole)))),
            )
            // Anti-entropy on (re)announce (M10.4): a returning peer may have
            // missed deltas its dying socket swallowed — re-fire the catch-up
            // hook so the full state-as-delta flows again; tag idempotence
            // makes the repeat free. Same pattern as Replication.maybeLink.
            registry.onPublish { ref ->
                chained[ref]?.let { (cell, link) -> cell.outlet.linking.onLinked(link) }
            }
        }

        if (journal != null) {
            knownUsers().forEach { writerFor(it) } // graph first: replayed ops need their cells
            host.recoverFrom(journal)
        }

        server.createContext("/") { exchange -> exchange.respond(200, PAGE, "text/html; charset=utf-8") }
        server.createContext("/op") { exchange -> handleOp(exchange) }
        server.createContext("/events") { exchange -> handleEvents(exchange) }
        server.executor = null
    }

    /**
     * Per-user writer cells, created on first op — every browser tab is a user.
     * Refs are user-derived and the union links are registry-routed (M10.4):
     * deterministic identity + routed (journaled) deltas are what make a
     * journal replay reconstruct the same graph state after kill -9. Recovery
     * pre-spawns writers for every user in [usersFile], so replayed ops run
     * through the same cells and re-mint the same replay-stable tags.
     */
    private fun writerFor(user: String): Pair<SetOps<String>, SetOps<String>> =
        synchronized(writers) {
            writers.getOrPut(user) {
                val itemCell = SetCell<String>(CellRef(UUID.nameUUIDFromBytes("demo-writer:items:$user@$myRole".toByteArray())))
                val voteCell = SetCell<String>(CellRef(UUID.nameUUIDFromBytes("demo-writer:votes:$user@$myRole".toByteArray())))
                manage.spawn(itemCell)
                manage.spawn(voteCell)
                itemCell.outlet.streamTo(routedDelta(itemsUnion.ref))
                voteCell.outlet.streamTo(routedDelta(votesUnion.ref))
                usersFile?.takeIf { user !in knownUsers() }?.appendText(user + "\n")
                val itemApi = host.lookup<SetInletProxy>(itemCell.ref)!!.inlet.call
                val voteApi = host.lookup<SetInletProxy>(voteCell.ref)!!.inlet.call
                itemApi to voteApi
            }
        }

    private fun knownUsers(): List<String> =
        usersFile?.takeIf { it.exists() }?.readLines()?.filter { it.isNotBlank() } ?: emptyList()

    private fun routedDelta(ref: CellRef): Propagate<SetDelta<String>> =
        (HostedCellProxy.create(ref, registry, DeltaInletProxy::class.java) as DeltaInletProxy).inlet.call

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
            // ponytail: remove is writer-local — it tombstones only this user's
            // own add-tags, so an item added by another user survives until that
            // user removes it too. This keeps the per-user writer identity that
            // makes journal replay deterministic (M10.4). Upgrade path for
            // shared removal: tombstone the element's currently-observed union
            // tags across writers, not just the caller's.
            "remove" -> itemOps.remove(item)
            "vote" -> voteOps.add(item)
            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    private fun handleEvents(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        clients += out
        send(out, stateJson()) // a fresh tab catches up immediately
    }

    // ponytail: fires once per hub update, so a single op can push a few frames
    // whose four views are momentarily out of step (e.g. the filtered aisle
    // updates one frame before the master list) before converging. Fine for the
    // full-state SSE transport; coalescing to one frame per wave is M6+ material.
    private fun broadcast() {
        val json = stateJson()
        clients.forEach { send(it, json) }
    }

    private fun send(out: OutputStream, json: String) {
        try {
            out.write("data: $json\n\n".toByteArray())
            out.flush()
        } catch (_: Exception) {
            clients -= out
        }
    }

    private fun stateJson(): String = synchronized(state) {
        fun arr(values: Set<String>) =
            values.sorted().joinToString(",", "[", "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
        """{"items":${arr(items)},"votes":${arr(votes)},"produce":${arr(produce)},"wanted":${arr(wanted)},"voteCount":$voteCount}"""
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "text/plain") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    fun start(): DemoApp = apply { server.start() }

    fun stop() = server.stop(0)
}

fun main(args: Array<String>) {
    fun value(flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    val port = args.firstOrNull { !it.startsWith("--") }?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
    val wire = value("--listen")?.let { DemoApp.Wire.Listen(it.toInt()) }
        ?: value("--peer")?.let { DemoApp.Wire.Dial(it) }
    val journalDir = value("--journal")?.let { java.io.File(it).apply { mkdirs() } }

    val app = DemoApp(port, wire, journalDir).start()
    println("computenet demo: http://localhost:${app.boundPort} — open two tabs to collaborate")
    when (wire) {
        is DemoApp.Wire.Listen -> println("  awaiting a peer on ws://localhost:${wire.wsPort}")
        is DemoApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
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
        for (const [label, action] of [['vote','vote'],['remove','remove']]) {
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
