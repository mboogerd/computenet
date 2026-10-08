package civictech.demo.slotfinder

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.WaterlineCell
import civictech.cell.data.Windows
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graphOf
import civictech.cell.graph.lookupOrThrow
import civictech.cell.graph.refAs
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.Observation
import civictech.cell.observe.ObservationFrame
import civictech.cell.observe.observation
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.FilterSetApi
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.QuorumSetCell
import civictech.cell.data.op.QuorumSetApi
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.respond
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange
import java.io.Serializable
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

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
 * The demo's explicit **element-derived event-time attribute** (`[KE4-37]`,
 * `[24-WL-01]`): hours since Monday 00:00 of the week the grid shows —
 * `DAYS.indexOf(day) * 24 + hour`. It is an ordering the [Slot] itself
 * carries, read off the element; it is never a wave counter, an arrival tick
 * or a wall clock. A named `Serializable` object, not a lambda, because a
 * lateness declaration is part of the cell's spawnable (replayed) definition.
 */
object SlotTime : (Slot) -> Long, Serializable {
    override fun invoke(s: Slot): Long = Slot.DAYS.indexOf(s.day) * 24L + s.hour
    private fun readResolve(): Any = SlotTime
}

/**
 * `byDay`'s eviction clock (`[24-WL-06]`): a day window's **exclusive end** in
 * [SlotTime] — `(DAYS.indexOf(day) + 1) * 24`. A day is passed, and evicted,
 * once the floor reaches it (`keyTime(day) <= floor`).
 */
object DayEnd : (String) -> Long, Serializable {
    override fun invoke(day: String): Long = (Slot.DAYS.indexOf(day) + 1) * 24L
    private fun readResolve(): Any = DayEnd
}

/** One day of lateness: a common slot may trail the newest common slot by up to 24 event-time hours. */
const val SLOT_LATENESS = 24L

/**
 * The dataflow pipeline, shared verbatim by the app and the seeded
 * incremental-vs-batch test. Every participant fans into one `QuorumSetCell`
 * inlet; the quorum threshold reads the live-source count `n`, so `common`
 * (∩, `{ n -> n }`) and `nearMiss` (all-but-one, `{ n -> n - 1 }`) are the same
 * fan-in under two thresholds — no chained binary intersects, any participant
 * count:
 *
 *   alice ─┐                                                  ┌─► waterline ──(floor)──┐
 *   bob   ─┼─► common   (quorum n)     ─► filtered (9–17) ────┤                        ▼
 *   carol ─┴─► nearMiss (quorum n − 1)                        └──────────────────► byDay (count) ─► outlet
 *                                                                                        └─► late
 *
 * **Event time and lateness (KE4.6).** `byDay` is a window-keyed operator over
 * the explicit event time [SlotTime] with a declared lateness of one day
 * ([SLOT_LATENESS]) and eviction clock [DayEnd]. A [WaterlineCell] beside it,
 * fed from `filtered`, folds the per-source maxima of [SlotTime] into one
 * monotone floor (`[24-WL-02]`); its outlet drives `byDay.waterline`. What that
 * means for the grid, stated honestly:
 *
 * - The waterline's contributing sources are the waves that carry `filtered`
 *   adds. Once every contributing source's waves have carried a common slot
 *   more than a day past the end of some day — floor = min over sources of
 *   (max [SlotTime] − 24) reaching [DayEnd] of that day — that day's `byDay`
 *   count is **evicted** (leaves as a `MapDelta` removal, `[24-WL-06]`) and a
 *   later common slot with `SlotTime < floor` is **dropped** onto `byDay`'s
 *   `late` outlet and counted in `droppedBelowFloor` rather than folded
 *   (`[24-WL-07]`). An evicted day is not re-created by a late slot.
 * - A source whose wave carried a common slot and then went idle **freezes**
 *   the floor at its promise until it emits again (`[24-WL-14]`); a source
 *   contributing for the first time below the floor leaves the floor where it
 *   is (`[24-WL-20]`). No wall clock ever moves it.
 * - This is the demo's event-time model, chosen to make the waterline visible
 *   in a five-day grid — not a claim that meeting slots are naturally a
 *   stream in event-time order. `common`, `nearMiss` and `filtered` carry no
 *   lateness and are unaffected.
 */
object SlotPipeline {
    data class Refs(
        val participants: Map<String, TypedRef<SetApi<Slot>>>,
        val common: TypedRef<QuorumSetApi<Slot>>,
        val nearMiss: TypedRef<QuorumSetApi<Slot>>,
        val filtered: TypedRef<FilterSetApi<Slot>>,
        val byDay: TypedRef<GroupByApi<Slot, String, Long>>,
        /** The [WaterlineCell] feeding `byDay.waterline`: delta-only, no Api type, so a plain [CellRef]. */
        val waterline: CellRef,
        /**
         * The locally-built `byDay` instance. `floor()` and `droppedBelowFloor`
         * are not port methods, and `host.lookup` cannot reach them: it builds a
         * hosted proxy, which refuses a concrete class ("Only interfaces can be
         * represented") — so the concrete cell is the only reader
         * (doc/demo-findings.md F-28). Local-apply only; read it at idle.
         */
        val byDayCell: GroupByCell<Slot, String, Long, Long>,
        /** The locally-built [WaterlineCell] instance, for `floor()` — same reason as [byDayCell]. */
        val waterlineCell: WaterlineCell<Slot>,
    )

    fun build(host: ManagedHost): Refs {
        // Factories stay pure (replay-safe: each takes the resolved ref, captures
        // no instance), while wiring stays compile-checked via the handles'
        // link(a.cell.outlet, b.cell.inlet): a payload-type or direction
        // mismatch is a Kotlin compile error, not a runtime surprise. graphOf
        // returns the block's result directly (T08 finding 3) — no
        // lateinit/!! round trip to get the refs back out.
        val (refs, _) = graphOf(host.managementInlet) {
            val sources = PARTICIPANTS.associateWith { name ->
                spawn(name) { ref -> SetCell<Slot>(ref = ref) }
            }
            val common = spawn("common") { ref -> QuorumSetCell<Slot>(ref = ref, threshold = { n -> n }) }
            val nearMiss = spawn("nearMiss") { ref -> QuorumSetCell<Slot>(ref = ref, threshold = { n -> n - 1 }) }
            val filtered = spawn("filtered") { ref ->
                FilterCell<Slot>(ref = ref, predicate = { it.hour in Slot.BUSINESS_HOURS })
            }
            val waterline = spawn("waterline") { ref ->
                WaterlineCell(ref = ref, lateness = Windows.Lateness(SlotTime, SLOT_LATENESS))
            }
            val byDay = spawn("byDay") { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { s: Slot -> s.day },
                    aggregator = Aggregators.count<Slot>(),
                    lateness = Windows.Lateness(SlotTime, SLOT_LATENESS),
                    keyTime = DayEnd,
                )
            }

            PARTICIPANTS.forEach { p ->
                val source = sources.getValue(p)
                link(source.cell.outlet, common.cell.inlet)   // fan-in: many sources → one quorum inlet
                link(source.cell.outlet, nearMiss.cell.inlet)
            }
            link(common.cell.outlet, filtered.cell.inlet)
            // The waterline arm is linked BEFORE the data arm (nt17o-D2, fh1fo-D2), so
            // a wave's own floor rise is attached ahead of its fold into byDay.
            // `waterline`/`late` live on the class, not on GroupByApi, so the handle's
            // concrete `.cell` is what reaches them.
            link(filtered.cell.outlet, waterline.cell.inlet)
            link(waterline.cell.outlet, byDay.cell.waterline)
            link(filtered.cell.outlet, byDay.cell.inlet)

            Refs(
                participants = sources.mapValues { it.value.refAs<SetApi<Slot>>() },
                common = common.refAs(),
                nearMiss = nearMiss.refAs(),
                filtered = filtered.refAs(),
                byDay = byDay.refAs(),
                waterline = waterline.ref,
                byDayCell = byDay.cell,
                waterlineCell = waterline.cell,
            )
        }
        return refs
    }
}

class SlotFinderApp(port: Int = 8080, inspector: InspectorFlag.Options? = null) {
    private val inspectorOptions = inspector

    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val refs = SlotPipeline.build(host)
    private val writers: Map<String, SetOps<Slot>> = refs.participants.mapValues { (_, tref) ->
        host.lookupOrThrow(tref).inlet.call
    }

    // The observation edge: each structural root set is an aligned group, and the
    // observation assembles their latest views into one point-consistent frame with
    // built-in late-join catch-up — no hand-rolled hub cells, no synchronized mutable
    // snapshot. Typed overloads (T08 finding 2): the element/key type flows from each
    // TypedRef's API shape, so a wrong-shaped source here is a compile error, not an
    // Any?-erased fold read back with an unchecked cast.
    private val view: Observation = host.observation {
        PARTICIPANTS.forEach { set(it, refs.participants.getValue(it)) }
        set("nearMiss", refs.nearMiss)
        set("common", refs.common)
        set("filtered", refs.filtered)
        count("byDay", refs.byDay)
        // [24-WL-07] / [KE4-39]: the observable half of a late drop — byDay's `late`
        // outlet (a SetDelta port not on GroupByApi, so observed by CellRef + name).
        set("late", refs.byDay.ref, outletName = "late")
    }

    internal val observationGroups: Map<String, String>
        get() = view.current().groupOf

    internal val observationGroupBufferedWaves: Map<String, Int>
        get() = view.groups.associateWith { view.group(it).bufferedWaves }

    /** Test-only proof that each rendered payload obtains one observation frame. */
    internal val observationCurrentReads = AtomicLong()

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    /** Non-null once [start] has run with an opt-in `--inspect-port` (`InspectorFlag`). */
    var inspector: InspectorServer? = null
        private set

    init {
        view.onChange { broadcast() }

        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        // closeOnFailure: one user op fans out through 7 hubs, each on its own
        // scheduler thread, each calling broadcast(); a failed write must close
        // the exchange so the browser's EventSource sees the close and
        // reconnects (rather than sitting OPEN forever on a half-dead stream).
        shell.sse("/events", closeOnFailure = true) { stateJson() }
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

    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String {
        val frame = currentFrame()

        // T08 finding 2: checked accessors — a wrong-shaped registration now
        // throws naming what was registered vs requested, instead of degrading
        // to a silently-empty panel (the prior `as? ... ?: emptySet()` unwrap).
        fun slotsOf(name: String) = frame.get<Set<Slot>>(name)

        val byDay = frame.get<Map<String, Long>>("byDay")

        fun arr(values: Set<Slot>) =
            values.sortedWith(compareBy({ Slot.DAYS.indexOf(it.day) }, { it.hour }))
                .joinToString(",", "[", "]") { "\"$it\"" }

        val sets = (PARTICIPANTS + listOf("nearMiss", "common", "filtered", "late"))
            .joinToString(",") { "\"$it\":${arr(slotsOf(it))}" }
        val counts = Slot.DAYS.filter { it in byDay }
            .joinToString(",", "{", "}") { "\"$it\":${byDay.getValue(it)}" }
        return """{$sets,"byDay":$counts}"""
    }

    private fun currentFrame(): ObservationFrame {
        observationCurrentReads.incrementAndGet()
        return view.current()
    }

    fun start(): SlotFinderApp = apply {
        shell.start()
        inspectorOptions?.let { inspector = it.serve(registry, mapOf("slotfinder" to host)) }
    }

    fun stop() {
        inspector?.stop()
        shell.stop()
        // Stop the canonical observation's group dispatchers; the demo keeps its
        // graph alive for the process lifetime, so stop() is its shutdown hook.
        view.close()
    }
}

private inline fun <reified T> ObservationFrame.get(view: String): T {
    require(view in views) { "no observe named '$view' (available: ${views.keys})" }
    val value = views.getValue(view)
    return value as? T ?: throw IllegalStateException(
        "'$view' has snapshot type ${value?.let { it::class.simpleName } ?: "null"}, " +
            "requested ${T::class.simpleName ?: T::class}",
    )
}

fun main(args: Array<String>) {
    // InspectorFlag.parse first (3iv0w-D2): it strips its own tokens, then
    // demoPort reads the remaining positional port, so an inspector flag's
    // value is never mistaken for this demo's own port.
    val parsed = InspectorFlag.parse(args)
    val app = SlotFinderApp(demoPort(parsed.rest), inspector = parsed.options).start()
    println("computenet slotfinder: http://localhost:${app.boundPort}")
    parsed.options?.let { InspectorFlag.announce(app.inspector!!, it) }
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
  .chip.late { background: #fef2f2; color: #b91c1c; border-color: #fecaca; }
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
  <div class="card"><h2>late — arrived after its day closed</h2><div class="chips" id="late"></div></div>
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
  chips('late', state.late || [], 'late');
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
