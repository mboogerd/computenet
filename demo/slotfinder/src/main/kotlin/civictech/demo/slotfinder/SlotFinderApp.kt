package civictech.demo.slotfinder

import civictech.cell.data.Aggregators
import civictech.cell.data.FilterCell
import civictech.cell.data.FilterSetApi
import civictech.cell.data.GroupByApi
import civictech.cell.data.GroupByCell
import civictech.cell.data.QuorumSetApi
import civictech.cell.data.QuorumSetCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.refAs
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.observeAll
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.Serializable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Meeting-slot finder: three participants each maintain a set of available
 * time slots; the common slots, the business-hours subset, and per-day counts
 * are all *incremental* views — toggling one slot flows one delta through
 * quorum → filter → groupBy, never a recompute. Every intermediate stage
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
 * incremental-vs-batch test. Every participant fans into one `QuorumSetCell`
 * inlet; the quorum threshold reads the live-source count `n`, so `common`
 * (∩, `{ n -> n }`) and `nearMiss` (all-but-one, `{ n -> n - 1 }`) are the same
 * fan-in under two thresholds — no chained binary intersects, any participant
 * count:
 *
 *   alice ─┐
 *   bob   ─┼─► common   (quorum n)     ─► filtered (business hours) ─► byDay (count)
 *   carol ─┴─► nearMiss (quorum n − 1)
 */
object SlotPipeline {
    data class Refs(
        val participants: Map<String, TypedRef<SetApi<Slot>>>,
        val common: TypedRef<QuorumSetApi<Slot>>,
        val nearMiss: TypedRef<QuorumSetApi<Slot>>,
        val filtered: TypedRef<FilterSetApi<Slot>>,
        val byDay: TypedRef<GroupByApi<Slot, String, Long>>,
    )

    fun build(host: ManagedHost): Refs {
        lateinit var refs: Refs
        // Factories stay pure (replay-safe: each takes the resolved ref, captures
        // no instance), while wiring stays compile-checked via the handles'
        // link(a.cell.outlet, b.cell.inlet): a payload-type or direction
        // mismatch is a Kotlin compile error, not a runtime surprise.
        graph(host.managementInlet) {
            val sources = PARTICIPANTS.associateWith { name ->
                spawn(name) { ref -> SetCell<Slot>(ref = ref) }
            }
            val common = spawn("common") { ref -> QuorumSetCell<Slot>(ref = ref, threshold = { n -> n }) }
            val nearMiss = spawn("nearMiss") { ref -> QuorumSetCell<Slot>(ref = ref, threshold = { n -> n - 1 }) }
            val filtered = spawn("filtered") { ref ->
                FilterCell<Slot>(ref = ref, predicate = { it.hour in Slot.BUSINESS_HOURS })
            }
            val byDay = spawn("byDay") { ref ->
                GroupByCell(ref = ref, keyFn = { s: Slot -> s.day }, aggregator = Aggregators.count<Slot>())
            }

            PARTICIPANTS.forEach { p ->
                val source = sources.getValue(p)
                link(source.cell.outlet, common.cell.inlet)   // fan-in: many sources → one quorum inlet
                link(source.cell.outlet, nearMiss.cell.inlet)
            }
            link(common.cell.outlet, filtered.cell.inlet)
            link(filtered.cell.outlet, byDay.cell.inlet)

            refs = Refs(
                participants = sources.mapValues { it.value.refAs<SetApi<Slot>>() },
                common = common.refAs(),
                nearMiss = nearMiss.refAs(),
                filtered = filtered.refAs(),
                byDay = byDay.refAs(),
            )
        }
        return refs
    }
}

class SlotFinderApp(port: Int = 8080) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val refs = SlotPipeline.build(host)
    private val writers: Map<String, SetOps<Slot>> = refs.participants.mapValues { (_, tref) ->
        host.lookup(tref)!!.inlet.call
    }

    // The observation edge: one composite sink folds every observed outlet into a
    // materialized, thread-safe snapshot with built-in late-join catch-up — no hand-rolled
    // hub cells, no synchronized mutable snapshot.
    private val view = host.observeAll {
        PARTICIPANTS.forEach { set(it, refs.participants.getValue(it).ref) }
        set("nearMiss", refs.nearMiss.ref)
        set("common", refs.common.ref)
        set("filtered", refs.filtered.ref)
        count("byDay", refs.byDay.ref)
    }

    private val clients = CopyOnWriteArrayList<HttpExchange>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        view.onChange { broadcast() }

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

    private fun stateJson(): String {
        val snapshot = view.current()

        @Suppress("UNCHECKED_CAST")
        fun slotsOf(name: String) = snapshot[name] as? Set<Slot> ?: emptySet()

        @Suppress("UNCHECKED_CAST")
        val byDay = snapshot["byDay"] as? Map<String, Long> ?: emptyMap()

        fun arr(values: Set<Slot>) =
            values.sortedWith(compareBy({ Slot.DAYS.indexOf(it.day) }, { it.hour }))
                .joinToString(",", "[", "]") { "\"$it\"" }

        val sets = (PARTICIPANTS + listOf("nearMiss", "common", "filtered"))
            .joinToString(",") { "\"$it\":${arr(slotsOf(it))}" }
        val counts = Slot.DAYS.filter { it in byDay }
            .joinToString(",", "{", "}") { "\"$it\":${byDay.getValue(it)}" }
        return """{$sets,"byDay":$counts}"""
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
  .chip.near { background: #eff6ff; color: var(--on); border-color: #bfdbfe; }
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
  <div class="card"><h2>near-miss — everyone but one</h2><div class="chips" id="nearMiss"></div></div>
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
  // near-miss ≥ n-1 already contains the fully-common slots; show only the
  // "if one more person freed up" delta — a display-only set difference over
  // the two already-materialized views, not a dataflow recompute.
  const commonSet = new Set(state.common || []);
  chips('nearMiss', (state.nearMiss || []).filter(s => !commonSet.has(s)), 'near');
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
