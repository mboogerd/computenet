package civictech.demo.exchange

import civictech.cell.CellRef
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.data.Aggregators
import civictech.cell.Propagate
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.graph.TypedRef
import civictech.cell.graph.lookup
import civictech.cell.host.KeyedCells
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.View
import civictech.cell.host.link
import civictech.cell.observe.observe
import civictech.cell.port.streamTo
import civictech.cell.host.RoutedPropagate
import civictech.cell.link.PeerId
import civictech.cell.partition.PartitionedCell
import civictech.cell.replication.Replication
import civictech.cell.wire.PeerConnection
import civictech.cell.wire.PeerListener
import civictech.cell.wire.PeerTransports
import civictech.cell.wire.Peering
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.respond
import civictech.demo.shell.value
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange
import java.net.URI
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.UnionSetCell

// The composition probe (:demo:exchange, CP-E1). Two symmetric JVM peers hold
// region-keyed orders in per-peer writer SetCells; the writers stream into a
// per-peer order union that is chained to the counterpart over the M5 wire
// (the "existing mesh" replicating the order inputs). Each peer folds its union
// through one replicated, region-partitioned board and observes it behind a
// glitch-free cell (CP-A4 inlet policy). Writer intake is journaled per-cell
// (CP-C1) so a killed peer recovers its own orders; the partitioned board
// recomputes from membership gossip at each replica, so both peers converge.

/** An order carries a region, an id and a Long amount, packed into one wire-safe
 *  string element (the M5 codec ships String/Long/deltas, not arbitrary classes). */
private const val SEP = ''

private fun encodeOrder(region: String, id: String, amount: Long): String =
    "$region$SEP$id$SEP$amount"

private fun regionOf(order: String): String = order.substringBefore(SEP)
private fun amountOf(order: String): Long = order.substringAfterLast(SEP).toLong()

/**
 * [netName] (`--net-name`) is this peer's transport identity, matching
 * `:demo:shopping`'s flag of the same name (V4-PEERID): it becomes the [PeerId]
 * in this JVM's hello, so the counterpart's registry records *which peer*
 * announced a ref rather than only which (per-connection, reconnect-fresh)
 * bridge egress it arrived through. Absent ⇒ anonymous, byte-identical to the
 * pre-V4-PEERID hello.
 *
 * [inspector] (`--inspect-port`, computenet-3iv0w rule R4) is this demo's
 * opt-in into the shared [InspectorFlag] wiring: absent, this app is
 * byte-identical to what it was before the flag existed.
 */
class ExchangeApp(
    port: Int = 8080,
    private val wire: Wire? = null,
    journalDir: java.io.File? = null,
    private val netName: String? = null,
    inspector: InspectorFlag.Options? = null,
) {
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

    // orders → union (mesh-replicated inputs)
    private val orderUnion = UnionSetCell<String>(ref = unionRef("orders", myRole))

    // Partitioned aggregation (CP-E2): one composite board per peer. The
    // composite owns its private GroupBy organelles and is the replicated
    // instance; membership deltas gossip through its membrane while each
    // replica computes its own region sums.
    private val replication = Replication(registry)
    private val boardId = UUID.nameUUIDFromBytes("exchange-board".toByteArray())
    private val boardInstance: Long = if (myRole == "dialer") 1L else 0L
    private val partitioned = PartitionedCell<String, String, Long, Long>(
        ref = CellRef(boardId, boardInstance),
        initialShardCount = 2,
        keyFn = ::regionOf,
        aggregator = Aggregators.sumOf(::amountOf),
    )

    // partitioned board → glitch-free board (CP-A4): a whole-cell fan-in whose inlet carries
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

    private val shell = DemoShell(port)

    /**
     * The peering bridge's own host, hoisted out of [init] (`demo/tiering`'s
     * exact shape) so the inspector's hosts map can name it: its cells (the
     * bridge egress/ingress and the registry mirror) are published on this
     * registry like any other, and an unrecognised host would otherwise show
     * up under a generated name.
     */
    private val bridgeHost: ManagedHost? = wire?.let { ManagedHost(registry = registry) }

    private val inspectorOptions: InspectorFlag.Options? = inspector

    /**
     * Opt-in inspector (`--inspect-port`): serves this JVM's live dataflow
     * graph on its own port; non-null after [start] iff [inspectorOptions]
     * was given. Hosts: `exchange` (the app host), and `exchange-bridge`
     * when peered. Declaring the cross-JVM order-union chain as a link is
     * shopping's M5-NET pilot precedent, not this task — follow-up territory
     * (feature design 3iv0w-D2, D3 non-goals).
     */
    var inspector: InspectorServer? = null
        private set

    /** The `--listen` listener, kept so [boundWsPort] can report what it actually bound. */
    private var wsListener: PeerListener? = null

    /** The `--peer` dial connection, kept for the lifetime of the app. */
    private var wsConnection: PeerConnection? = null

    val boundPort: Int get() = shell.boundPort

    /**
     * The peering port this JVM is listening on, or null in dial/solo mode.
     *
     * Distinct from `Wire.Listen.wsPort`, which is what was *asked for*: `--listen 0`
     * means "any free port", and only the listener knows which one it got. The
     * two-JVM tests launch with `--listen 0` precisely so that no test ever picks a
     * port it does not yet hold (computenet-dqy.25), so this — not the requested
     * value — is what `main` announces for the dialer to reach.
     */
    val boundWsPort: Int? get() = wsListener?.let { URI(it.boundAddress.text).port }

    init {
        manage.spawn(orderUnion)
        replication.replicate(partitioned, host)
        manage.spawn(boardCell)

        // the order union feeds the replicated partitioned board; its composite
        // membrane routes each order to exactly one private organelle.
        orderUnion.outlet.streamTo(routedDelta(partitioned.ref))
        manage.link(partitioned.outlet, boardCell.inlet)

        // observe the board's aligned outlet → SSE state
        host.observe(boardCell.ref, View.map<String, Long>()) {
            synchronized(state) { board = it }; broadcast()
        }

        if (wire != null) {
            val side = Peering.Side(registry, bridgeHost!!, peer = netName?.let { PeerId(it) })
            val ws = PeerTransports.forScheme("ws")
            when (wire) {
                is Wire.Listen -> wsListener = ws.listen(ws.parseAddress("ws://0.0.0.0:${wire.wsPort}"), side)
                is Wire.Dial -> wsConnection = ws.dial(ws.parseAddress(wire.uri), side)
            }
            // symmetric union chaining over the mesh: my order union streams into
            // the peer's counterpart. Tag dedup + effective-only emission make the
            // two-way chain cycle-safe; sends park until the peer announces, so a
            // late/returning peer replays full history in order and converges.
            // Anti-entropy on (re)announce (T07 finding 2): a returning peer's
            // re-announce re-fires the full on-link catch-up via
            // Peering.chainOnReannounce; tag idempotence makes the repeat free.
            val chained = mapOf(
                unionRef("orders", peerRole) to
                        (orderUnion.outlet to orderUnion.outlet.streamTo(routedDelta(unionRef("orders", peerRole)))),
            )
            Peering.chainOnReannounce(registry, chained)
        }

        // recover() pre-spawns every known writer (registering its ref + streamTo)
        // then replays the writer-only WAL exactly once — the aggregates rebuild
        // as the replayed adds flow through the live graph.
        if (journalDir != null) writerCells.recover()

        shell.route("/") { exchange -> exchange.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/op") { exchange -> handleOp(exchange) }
        shell.sse("/events") { stateJson() }
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

    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String = synchronized(state) {
        val board = board
        val body = board.entries.sortedBy { it.key }
            .joinToString(",") { "\"${it.key.replace("\\", "\\\\").replace("\"", "\\\"")}\":${it.value}" }
        val total = board.values.sum()
        """{"board":{$body},"total":$total}"""
    }

    private fun inspectorHosts(): Map<String, ManagedHost> = buildMap {
        put("exchange", host)
        bridgeHost?.let { put("exchange-bridge", it) }
    }

    private fun inspectorCellNames(): Map<CellRef, String> = buildMap {
        put(orderUnion.ref, "orders")
        put(partitioned.ref, "board-partitioned")
        put(boardCell.ref, "board")
    }

    fun start(): ExchangeApp = apply {
        shell.start()
        inspectorOptions?.let { inspector = it.serve(registry, inspectorHosts(), inspectorCellNames()) }
    }

    fun stop() {
        inspector?.stop()
        shell.stop()
    }
}

fun main(args: Array<String>) {
    // InspectorFlag.parse first (3iv0w-D2): `rest` has every inspector token
    // (and, since V4-PEERID, `--net-name`'s pair) already stripped, so demoPort
    // and the flag reads below never see one of their values.
    val parsed = InspectorFlag.parse(args)
    val port = demoPort(parsed.rest)
    val wire = parsed.rest.value("--listen")?.let { ExchangeApp.Wire.Listen(it.toInt()) }
        ?: parsed.rest.value("--peer")?.let { ExchangeApp.Wire.Dial(it) }
    val journalDir = parsed.rest.value("--journal")?.let { java.io.File(it).apply { mkdirs() } }

    val app = ExchangeApp(port, wire, journalDir, parsed.netName, parsed.options).start()
    println("computenet exchange: http://localhost:${app.boundPort} — region→sum board across two JVM peers")
    // a port this process HOLDS, so a supervising test never has to pick one for it
    // — see [announcePort] (computenet-dqy.25)
    announcePort("http", app.boundPort)
    when (wire) {
        is ExchangeApp.Wire.Listen -> {
            // the BOUND port, not `wire.wsPort`: `--listen 0` asks for any free one
            val wsPort = checkNotNull(app.boundWsPort) { "a listening peer must have a bound ws port" }
            println("  awaiting a peer on ws://localhost:$wsPort")
            announcePort("ws", wsPort)
        }
        is ExchangeApp.Wire.Dial -> println("  peered with ${wire.uri}")
        null -> println("  single-process mode; add --listen <wsPort> or --peer <ws-uri> to span two JVMs")
    }
    parsed.options?.let { InspectorFlag.announce(app.inspector!!, it) }
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
