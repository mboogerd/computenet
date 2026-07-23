package civictech.demo.slotfinder

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.data.Aggregators
import civictech.cell.data.FilterCell
import civictech.cell.data.GroupByCell
import civictech.cell.data.IntersectSetCell
import civictech.cell.data.MapDelta
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.graph.graph
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.port.FanInlet
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Serializable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Meeting-slot finder: three participants each maintain a set of available
 * time slots; the common slots, the business-hours subset, and per-day counts
 * are all *incremental* views — toggling one slot flows one delta through
 * intersect → filter → groupBy, never a recompute. Every intermediate stage
 * is observable in the UI; that is the demo.
 */
data class Slot(val day: String, val hour: Int) : Serializable {
    override fun toString() = "$day-$hour"

    companion object {
        val DAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri")
        val HOURS = 8..20
        val BUSINESS_HOURS = 9..17
    }
}

val PARTICIPANTS = listOf("alice", "bob", "carol")

/**
 * The dataflow pipeline, shared verbatim by the app and the seeded
 * incremental-vs-batch test:
 *
 *   alice ─┐
 *   bob   ─┴► pairAB (∩) ─┐
 *   carol ─────────────────┴► common (∩) ─► filtered (business hours) ─► byDay (count)
 */
object SlotPipeline {
    data class Refs(
        val participants: Map<String, CellRef>,
        val pairAB: CellRef,
        val common: CellRef,
        val filtered: CellRef,
        val byDay: CellRef,
    )

    fun build(host: ManagedHost): Refs {
        val refs = mutableMapOf<String, CellRef>()
        graph(host.managementInlet) {
            val sources = PARTICIPANTS.associateWith { spawn(it) { SetCell<Slot>() } }
            val pairAB = spawn("pairAB") { IntersectSetCell<Slot>() }
            val common = spawn("common") { IntersectSetCell<Slot>() }
            val filtered = spawn("filtered") { FilterCell<Slot> { it.hour in Slot.BUSINESS_HOURS } }
            val byDay = spawn("byDay") {
                GroupByCell(keyFn = { s: Slot -> s.day }, aggregator = Aggregators.count<Slot>())
            }
            connect(sources.getValue("alice"), "outlet", pairAB, "left")
            connect(sources.getValue("bob"), "outlet", pairAB, "right")
            connect(pairAB, "outlet", common, "left")
            connect(sources.getValue("carol"), "outlet", common, "right")
            connect(common, "outlet", filtered, "inlet")
            connect(filtered, "outlet", byDay, "inlet")
            (sources.values + listOf(pairAB, common, filtered, byDay)).forEach { refs[it.name] = it.ref }
        }
        return Refs(
            participants = PARTICIPANTS.associateWith { refs.getValue(it) },
            pairAB = refs.getValue("pairAB"),
            common = refs.getValue("common"),
            filtered = refs.getValue("filtered"),
            byDay = refs.getValue("byDay"),
        )
    }
}

interface SlotInletProxy {
    val inlet: Use<SetOps<Slot>>
}

/** Folds tagged slot deltas into current membership (the demo-side tag fold). */
class SlotMembership {
    private val live = mutableMapOf<Slot, MutableSet<Timestamp>>()

    fun apply(delta: SetDelta<Slot>) {
        delta.adds.forEach { (e, tags) -> live.getOrPut(e) { mutableSetOf() } += tags }
        delta.dels.forEach { (e, tags) ->
            live[e]?.let { it -= tags; if (it.isEmpty()) live.remove(e) }
        }
    }

    fun current(): Set<Slot> = live.keys.toSet()
}

/** A hub cell: folds one derived slot stream and pushes app state to SSE clients. */
private class SlotHubCell(
    private val onUpdate: (Set<Slot>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private val membership = SlotMembership()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Slot>>>())

    init {
        inlet.serve(object : Propagate<SetDelta<Slot>> {
            override fun propagate(value: SetDelta<Slot>) {
                membership.apply(value)
                onUpdate(membership.current())
            }
        })
    }
}

/** A hub cell folding the per-day count MapDeltas. */
private class DayCountHubCell(
    private val onUpdate: (Map<String, Long>) -> Unit,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    private val counts = mutableMapOf<String, Long>()
    val inlet = registerPort("inlet", FanInlet.create<Propagate<MapDelta<String, Long>>>())

    init {
        inlet.serve(object : Propagate<MapDelta<String, Long>> {
            override fun propagate(value: MapDelta<String, Long>) {
                counts.putAll(value.puts)
                value.removals.forEach { counts.remove(it) }
                onUpdate(counts.toMap())
            }
        })
    }
}

class SlotFinderApp(port: Int = 8080) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val manage = host.managementInlet.call
    private val refs = SlotPipeline.build(host)
    private val writers: Map<String, SetOps<Slot>> = refs.participants.mapValues { (_, ref) ->
        host.lookup<SlotInletProxy>(ref)!!.inlet.call
    }

    private val state = Object()
    private val slots = mutableMapOf<String, Set<Slot>>() // participant + stage name → membership
    private var byDay: Map<String, Long> = emptyMap()
    private val clients = CopyOnWriteArrayList<HttpExchange>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        val observed = refs.participants + mapOf(
            "pair" to refs.pairAB, "common" to refs.common, "filtered" to refs.filtered,
        )
        observed.forEach { (name, ref) ->
            val hub = SlotHubCell({ synchronized(state) { slots[name] = it }; broadcast() })
            manage.spawn(hub)
            manage.connect(ref, "outlet", hub.ref, "inlet")
        }
        val dayHub = DayCountHubCell({ synchronized(state) { byDay = it }; broadcast() })
        manage.spawn(dayHub)
        manage.connect(refs.byDay, "outlet", dayHub.ref, "inlet")

        server.createContext("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        server.createContext("/state") { it.respond(200, stateJson(), "application/json") }
        server.createContext("/op") { handleOp(it) }
        server.createContext("/events") { handleEvents(it) }
        server.executor = null
    }

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }
        val user = params["user"]?.takeIf { it in PARTICIPANTS }
            ?: return exchange.respond(400, "user must be one of $PARTICIPANTS")
        val day = params["day"]?.takeIf { it in Slot.DAYS }
            ?: return exchange.respond(400, "day must be one of ${Slot.DAYS}")
        val hour = params["hour"]?.toIntOrNull()?.takeIf { it in Slot.HOURS }
            ?: return exchange.respond(400, "hour must be in ${Slot.HOURS}")
        val slot = Slot(day, hour)
        when (params["action"]) {
            "add" -> writers.getValue(user).add(slot)
            "remove" -> writers.getValue(user).remove(slot)
            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    private fun handleEvents(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        clients += exchange
        send(exchange, stateJson()) // a fresh tab catches up immediately
    }

    private fun broadcast() {
        val json = stateJson()
        clients.forEach { send(it, json) }
    }

    // One user op fans out through 7 hubs, each on its own scheduler thread, each calling
    // broadcast(); those writes must not interleave on a shared stream, and a failed write must
    // close the exchange so the browser's EventSource sees the close and reconnects (rather than
    // sitting OPEN forever on a half-dead stream). Lock per-exchange; drop-and-close on failure.
    private fun send(ex: HttpExchange, json: String) {
        try {
            synchronized(ex) {
                ex.responseBody.write("data: $json\n\n".toByteArray())
                ex.responseBody.flush()
            }
        } catch (_: Exception) {
            clients -= ex
            try { ex.close() } catch (_: Exception) {}
        }
    }

    private fun stateJson(): String = synchronized(state) {
        fun arr(values: Set<Slot>?) =
            (values ?: emptySet()).sortedWith(compareBy({ Slot.DAYS.indexOf(it.day) }, { it.hour }))
                .joinToString(",", "[", "]") { "\"$it\"" }

        val sets = (PARTICIPANTS + listOf("pair", "common", "filtered"))
            .joinToString(",") { "\"$it\":${arr(slots[it])}" }
        val counts = Slot.DAYS.filter { it in byDay }
            .joinToString(",", "{", "}") { "\"$it\":${byDay.getValue(it)}" }
        """{$sets,"byDay":$counts}"""
    }

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "text/plain") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    fun start(): SlotFinderApp = apply { server.start() }

    fun stop() = server.stop(0)
}

fun main(args: Array<String>) {
    val port = args.firstOrNull { !it.startsWith("--") }?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
    val app = SlotFinderApp(port).start()
    println("computenet slotfinder: http://localhost:${app.boundPort}")
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>slotfinder — incremental meeting slots</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --on: #2563eb; --hit: #059669; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1080px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 240px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  table { border-collapse: collapse; } th { font-size: .65rem; color: var(--dim); font-weight: normal; padding: 2px; }
  td { padding: 1px; }
  td button { width: 34px; height: 20px; border: 1px solid var(--line); border-radius: 4px; background: #fff; cursor: pointer; font-size: .6rem; color: var(--dim); }
  td button.on { background: var(--on); border-color: var(--on); color: #fff; }
  td button.hit { outline: 2px solid var(--hit); outline-offset: -1px; }
  .chips { display: flex; flex-wrap: wrap; gap: .3rem; min-height: 1.6rem; }
  .chip { background: #ecfdf5; color: var(--hit); border: 1px solid #a7f3d0; border-radius: 999px; padding: .1rem .55rem; font-size: .75rem; cursor: pointer; }
  .chip.pair { background: #eff6ff; color: var(--on); border-color: #bfdbfe; }
  #result { font-size: .9rem; color: var(--dim); margin: .1rem 0 1rem; }
  #result b { color: var(--hit); }
  @keyframes flash { 30% { box-shadow: 0 0 0 3px var(--hit); } }
  td button.flash { animation: flash .9s ease; }
  .bars { display: flex; gap: .6rem; align-items: flex-end; height: 90px; }
  .bar { display: flex; flex-direction: column; align-items: center; gap: .2rem; font-size: .7rem; color: var(--dim); }
  .bar div { width: 30px; background: var(--hit); border-radius: 4px 4px 0 0; min-height: 2px; }
</style>
</head>
<body>
<h1>Meeting-slot finder <small>every panel is a live incremental view — no recompute</small></h1>
<p id="result">—</p>
<div class="row" id="grids"></div>
<div class="row">
  <div class="card"><h2>alice ∩ bob</h2><div class="chips" id="pair"></div></div>
  <div class="card"><h2>common (all three)</h2><div class="chips" id="common"></div></div>
  <div class="card"><h2>business hours (9–17)</h2><div class="chips" id="filtered"></div></div>
  <div class="card"><h2>options per day</h2><div class="bars" id="byDay"></div></div>
</div>
<script>
const DAYS = ["Mon","Tue","Wed","Thu","Fri"], HOURS = [];
for (let h = 8; h <= 20; h++) HOURS.push(h);
const USERS = ["alice","bob","carol"];
let state = {};
const op = (action, user, day, hour) => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams({ action, user, day, hour }) });

const grids = document.getElementById('grids');
for (const user of USERS) {
  const card = document.createElement('div'); card.className = 'card';
  card.innerHTML = '<h2>' + user + '</h2>';
  const table = document.createElement('table');
  table.innerHTML = '<tr><th></th>' + DAYS.map(d => '<th>' + d + '</th>').join('') + '</tr>';
  for (const h of HOURS) {
    const tr = document.createElement('tr');
    tr.innerHTML = '<th>' + h + '</th>';
    for (const d of DAYS) {
      const td = document.createElement('td');
      const b = document.createElement('button');
      b.id = user + '-' + d + '-' + h; b.textContent = h;
      b.onclick = () => op(b.classList.contains('on') ? 'remove' : 'add', user, d, h);
      td.appendChild(b); tr.appendChild(td);
    }
    table.appendChild(tr);
  }
  card.appendChild(table); grids.appendChild(card);
}

function chips(id, slots, cls) {
  const el = document.getElementById(id); el.innerHTML = '';
  for (const s of slots) {
    const c = document.createElement('span'); c.className = 'chip ' + (cls || '');
    c.textContent = s;
    c.title = 'trace this slot back to who picked it';
    c.onclick = () => USERS.forEach(u => {         // flash the slot across all three input grids
      const b = document.getElementById(u + '-' + s);
      if (b) { b.classList.remove('flash'); void b.offsetWidth; b.classList.add('flash'); }
    });
    el.appendChild(c);
  }
}
function render() {
  for (const user of USERS) {
    const mine = new Set(state[user] || []), common = new Set(state.common || []);
    for (const d of DAYS) for (const h of HOURS) {
      const b = document.getElementById(user + '-' + d + '-' + h);
      b.classList.toggle('on', mine.has(d + '-' + h));
      b.classList.toggle('hit', common.has(d + '-' + h));
    }
  }
  chips('pair', state.pair || [], 'pair');
  chips('common', state.common || []);
  chips('filtered', state.filtered || []);
  const f = state.filtered || [], r = document.getElementById('result');
  r.innerHTML = f.length
    ? '<b>' + f.length + '</b> business-hours slot' + (f.length > 1 ? 's' : '') + ' work for everyone — ' + f.join(', ')
    : 'no business-hours slot works for all three yet';
  const bars = document.getElementById('byDay'); bars.innerHTML = '';
  const max = Math.max(1, ...Object.values(state.byDay || {}));
  for (const d of DAYS) {
    const n = (state.byDay || {})[d] || 0;
    const bar = document.createElement('div'); bar.className = 'bar';
    const fill = document.createElement('div'); fill.style.height = (n / max * 70) + 'px';
    bar.appendChild(fill); bar.append(d + ' · ' + n); bars.appendChild(bar);
  }
}
const apply = s => { state = s; render(); };
function connect() {
  const es = new EventSource('/events');
  es.onmessage = e => apply(JSON.parse(e.data));
  es.onerror = () => { es.close(); setTimeout(connect, 1000); }; // reconnect + re-catch-up
}
connect();
// Safety net: /state serves the already-computed views (no dataflow recompute), so a periodic
// resync guarantees the UI can never sit on stale data if a stream ever stalls silently.
setInterval(() => fetch('/state').then(r => r.json()).then(apply).catch(() => {}), 8000);
</script>
</body>
</html>
"""
