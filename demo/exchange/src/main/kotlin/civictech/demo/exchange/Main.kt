package civictech.demo.exchange

import civictech.cell.CellRef
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.Aggregators
import civictech.cell.data.GroupByCell
import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetDelta
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.data.MergeableGroupByCell
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.View
import civictech.cell.host.link
import civictech.cell.host.observe
import civictech.cell.port.streamTo
import civictech.cell.proxy.RoutedPropagate
import civictech.cell.replication.Interest
import civictech.cell.wire.Peering
import civictech.wire.WsTransport
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

// The composition probe (:demo:exchange, CP-E1). Two symmetric JVM peers hold
// region-keyed orders in per-peer writer SetCells; the writers stream into a
// per-peer order union that is chained to the counterpart over the M5 wire
// (the "existing mesh" replicating the order inputs). Each peer folds its union
// through a per-region GroupBy(sum) and observes it behind a glitch-free board
// (CP-A4 inlet policy). Writer intake is journaled per-cell (CP-C1) so a killed
// peer recovers its own orders; the aggregate cells are volatile and recomputed
// from the replayed/replicated inputs — GroupBy is a deterministic function of
// convergent membership (kernel spec 42), so both peers' boards converge.

/** An order carries a region, an id and a Long amount, packed into one wire-safe
 *  string element (the M5 codec ships String/Long/deltas, not arbitrary classes). */
private const val SEP = ''

private fun encodeOrder(region: String, id: String, amount: Long): String =
    "$region$SEP$id$SEP$amount"

private fun regionOf(order: String): String = order.substringBefore(SEP)
private fun amountOf(order: String): Long = order.substringAfterLast(SEP).toLong()

class ExchangeApp(port: Int = 8080, private val wire: Wire? = null, journalDir: java.io.File? = null) {
    /** Peer mode (M5.7): symmetric peers — one listens, the other dials. */
    sealed interface Wire {
        data class Listen(val wsPort: Int) : Wire
        data class Dial(val uri: String) : Wire
    }

    private val registry = LocationRegistry()

    // Durability (CP-C1): per-cell journaling. Only the writer intake cells are
    // journaled; the union / groupBy / board aggregates are volatile and rebuilt
    // by replaying the writers on restart. `journalFor` returns the WAL for a
    // writer ref and null (volatile) for everything else — the whole WAL a
    // recovering peer replays holds nothing but its own writer ops.
    private val journal = KeyedCells.hostJournal(journalDir)
    private val writerRefs: MutableSet<CellRef> = ConcurrentHashMap.newKeySet()
    private val host = ManagedHost(
        registry = registry,
        journalFor = { ref -> if (ref in writerRefs) journal else null },
    )
    private val manage = host.managementInlet.call

    // union refs are role-derived so each peer can address its counterpart's
    // union without a discovery protocol (M6+ territory).
    private val myRole = when (wire) {
        is Wire.Listen -> "listener"
        is Wire.Dial -> "dialer"
        null -> "solo"
    }
    private val peerRole = if (myRole == "listener") "dialer" else "listener"

    private fun unionRef(name: String, role: String) =
        CellRef(UUID.nameUUIDFromBytes("exchange-union:$name@$role".toByteArray()))

    private val state = Object()
    private var board: Map<String, Long> = emptyMap()
    private val clients = CopyOnWriteArrayList<OutputStream>()

    // orders → union (mesh-replicated inputs)
    private val orderUnion = UnionSetCell<String>(ref = unionRef("orders", myRole))

    // Partitioned aggregation (CP-E2, spec 42 §Interest-scoped instance sets):
    // the plain single GroupBy(sum) is swapped for a region-partitioned set of
    // shard GroupBys, EACH ON ITS OWN HOST, owning a disjoint region-slot range.
    // The router forwards each order's region slice to exactly its owning shard;
    // because ranges are disjoint, the shard region-sums never collide, so the
    // scatter-gather union of shard outputs is the board with no partial-sum
    // merge (the GroupBy-not-Replicable / no-MapDelta-merge gap is designed
    // around: we partition the INPUT and recompute per shard, never merging
    // aggregates). Shards on different hosts = the C–F pairwise cell in one graph.
    private val shardCount = 2
    private val totalSlots = 12
    private val shardHosts = List(shardCount) { ManagedHost(registry = registry) }
    private val shardInterests = List(shardCount) { Interest.Slots.forShard(it, shardCount, totalSlots) }
    private fun shardRef(i: Int) = CellRef(UUID.nameUUIDFromBytes("exchange-shard:$i@$myRole".toByteArray()))
    private val shards = List(shardCount) { i ->
        GroupByCell<String, String, Long, Long>(
            ref = shardRef(i),
            keyFn = ::regionOf,
            aggregator = Aggregators.sumOf(::amountOf),
        )
    }

    // Disjoint-merge scatter-gather (spec 42, CP-G1): the kernel
    // [MergeableGroupByCell] folds the per-shard region-sums per key on its
    // aggregate `deltaInlet`, replacing the demo-side forward. Shards own
    // disjoint region ranges AND stream *absolute* region totals that update
    // over time, so the sound operator here is replace-per-key (last value
    // wins) — a summing operator would double-count a region's successive
    // totals. The merge is thus never combining across shards (ranges are
    // disjoint); the cell's operator would converge genuine partials only for
    // an *idempotent* accumulator (max/min) under overlapping keys — see
    // MergeableGroupByTest's max mesh.
    private val boardMerge = MergeableGroupByCell<String, String, Long>(
        ref = CellRef(UUID.nameUUIDFromBytes("exchange-board-merge@$myRole".toByteArray())),
        keyOf = ::regionOf,
        accumulate = ::amountOf,
        merge = { _, incoming -> incoming },
    )

    // merge → glitch-free board (CP-A4): a whole-cell fan-in whose inlet carries
    // WaveFrontier(WAIT). It surfaces the scatter-gathered board as one aligned
    // MapDelta per wave, so the SSE never shows a half-applied shard update.
    @Suppress("UNCHECKED_CAST")
    private val boardApi = Propagate::class.java as Class<Propagate<MapDelta<String, Long>>>
    private val boardCell = GlitchFreeCell(boardApi)

    // Per-region durable writers (CP-C1): one SetCell per region, journaled, its
    // ref registered so `journalFor` selects the WAL for it. The factory wires
    // the streamTo into the union, so recover() re-establishes it per key.
    private val writerApi = mutableMapOf<String, SetOps<String>>()
    private val writerCells = KeyedCells<String>(
        host = host,
        journalDir = journalDir,
        namespace = "exchange-writer@$myRole",
        factory = { _, ref ->
            writerRefs += ref
            SetCell<String>(ref).also { it.outlet.streamTo(routedDelta(orderUnion.ref)) }
        },
    )

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        manage.spawn(orderUnion)
        manage.spawn(boardMerge)
        manage.spawn(boardCell)
        shards.forEachIndexed { i, shard -> shardHosts[i].managementInlet.call.spawn(shard) }

        // each shard's region-sums → the mergeable aggregate's `deltaInlet`
        // (cross-host, routed via the registry) → glitch-free board
        shards.forEach { it.outlet.streamTo(routedMapDelta(boardMerge.ref, "deltaInlet")) }
        manage.link(boardMerge.outlet, boardCell.inlet)

        // the region router: fan the union stream out to the owning shard only.
        // Each order's group key (region) hashes to exactly one shard's slot
        // range, so the partition is total and disjoint (spec 42).
        orderUnion.outlet.streamTo(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                shards.forEachIndexed { i, shard ->
                    val slice = value.within(shardInterests[i]) { regionOf(it as String) } ?: return@forEachIndexed
                    routedDelta(shard.ref).propagate(slice)
                }
            }
        })

        // observe the board's aligned outlet → SSE state
        host.observe(boardCell.ref, View.map<String, Long>()) {
            synchronized(state) { board = it }; broadcast()
        }

        if (wire != null) {
            val bridgeHost = ManagedHost(registry = registry)
            val side = Peering.Side(registry, bridgeHost)
            when (wire) {
                is Wire.Listen -> WsTransport.listen(wire.wsPort, side)
                is Wire.Dial -> WsTransport.connect(URI(wire.uri), side)
            }
            // symmetric union chaining over the mesh: my order union streams into
            // the peer's counterpart. Tag dedup + effective-only emission make the
            // two-way chain cycle-safe; sends park until the peer announces, so a
            // late/returning peer replays full history in order and converges.
            val chained = mapOf(
                unionRef("orders", peerRole) to
                        (orderUnion to orderUnion.outlet.streamTo(routedDelta(unionRef("orders", peerRole)))),
            )
            // Anti-entropy on (re)announce: a returning peer re-fires the catch-up
            // hook so the full state-as-delta flows again; tag idempotence makes
            // the repeat free.
            registry.onPublish { ref ->
                chained[ref]?.let { (cell, linkHandle) -> cell.outlet.linking.onLinked(linkHandle) }
            }
        }

        // recover() pre-spawns every known writer (registering its ref + streamTo)
        // then replays the writer-only WAL exactly once — the aggregates rebuild
        // as the replayed adds flow through the live graph.
        if (journalDir != null) writerCells.recover()

        server.createContext("/") { exchange -> exchange.respond(200, PAGE, "text/html; charset=utf-8") }
        server.createContext("/op") { exchange -> handleOp(exchange) }
        server.createContext("/events") { exchange -> handleEvents(exchange) }
        server.executor = null
    }

    /** Per-region writer, created on first op. Deterministic ref + journaled ops
     *  are what make a journal replay reconstruct the same board after kill -9. */
    private fun writerFor(region: String): SetOps<String> =
        synchronized(writerApi) {
            writerApi.getOrPut(region) {
                val cell = writerCells.getOrSpawn(region)
                host.lookup(TypedRef<SetApi<String>>(cell.ref))!!.inlet.call
            }
        }

    private fun routedDelta(ref: CellRef): Propagate<SetDelta<String>> =
        RoutedPropagate(ref, "inlet", registry::deliver)

    private fun routedMapDelta(ref: CellRef, port: String = "inlet"): Propagate<MapDelta<String, Long>> =
        RoutedPropagate(ref, port, registry::deliver)

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
        val region = params["region"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return exchange.respond(400, "missing region")
        val id = params["id"]?.trim().takeUnless { it.isNullOrEmpty() }
            ?: return exchange.respond(400, "missing id")
        val amount = params["amount"]?.trim()?.toLongOrNull()
            ?: return exchange.respond(400, "missing/invalid amount")
        if (SEP in region || SEP in id) return exchange.respond(400, "bad region/id")
        val order = encodeOrder(region, id, amount)
        val ops = writerFor(region)
        when (params["action"]) {
            "add" -> ops.add(order)
            "remove" -> ops.remove(order)
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
        val board = board
        val body = board.entries.sortedBy { it.key }
            .joinToString(",") { "\"${it.key.replace("\\", "\\\\").replace("\"", "\\\"")}\":${it.value}" }
        val total = board.values.sum()
        """{"board":{$body},"total":$total}"""
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "text/plain") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    fun start(): ExchangeApp = apply { server.start() }

    fun stop() = server.stop(0)
}

fun main(args: Array<String>) {
    fun value(flag: String): String? {
        val i = args.indexOf(flag)
        return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
    }

    val port = args.firstOrNull { !it.startsWith("--") }?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
    val wire = value("--listen")?.let { ExchangeApp.Wire.Listen(it.toInt()) }
        ?: value("--peer")?.let { ExchangeApp.Wire.Dial(it) }
    val journalDir = value("--journal")?.let { java.io.File(it).apply { mkdirs() } }

    val app = ExchangeApp(port, wire, journalDir).start()
    println("computenet exchange: http://localhost:${app.boundPort} — region→sum board across two JVM peers")
    when (wire) {
        is ExchangeApp.Wire.Listen -> println("  awaiting a peer on ws://localhost:${wire.wsPort}")
        is ExchangeApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>computenet — exchange (region→sum board)</title>
<style>
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 2rem auto; padding: 0 1rem;
         color-scheme: light dark; background: Canvas; color: CanvasText; }
  h1 { font-size: 1.3rem; } h2 { font-size: 1rem; color: GrayText; }
  li { margin: .2rem 0; } label { margin-right: .5rem; } input { width: 6rem; }
  .total { font-weight: bold; }
</style>
</head>
<body>
<h1>Exchange — orders by region</h1>
<form id="addForm">
  <label>region <input id="region" placeholder="north"></label>
  <label>id <input id="id" placeholder="o1"></label>
  <label>amount <input id="amount" type="number" placeholder="10"></label>
  <button>Add order</button>
</form>
<h2>Board (region &rarr; sum) — total <span id="total" class="total">0</span></h2>
<ul id="board"></ul>
<script>
const op = (action, region, id, amount) => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams({ action, region, id, amount }) });
document.getElementById('addForm').onsubmit = e => {
  e.preventDefault();
  const region = document.getElementById('region').value.trim();
  const id = document.getElementById('id').value.trim();
  const amount = document.getElementById('amount').value.trim();
  if (region && id && amount) op('add', region, id, amount);
};
new EventSource('/events').onmessage = e => {
  const s = JSON.parse(e.data);
  document.getElementById('total').textContent = s.total;
  const ul = document.getElementById('board'); ul.innerHTML = '';
  for (const region of Object.keys(s.board).sort()) {
    const li = document.createElement('li');
    li.textContent = region + ' → ' + s.board[region];
    ul.appendChild(li);
  }
};
</script>
</body>
</html>
"""
