package civictech.demo.tiering

import civictech.cell.data.Aggregators
import civictech.cell.data.KeyedSetApi
import civictech.cell.data.KeyedSetCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.refAs
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import com.sun.net.httpserver.HttpExchange
import java.io.Serializable
import java.net.URLDecoder
import java.util.*
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.CombineLatestApi

/**
 * Incremental tiering: agents emit absolute tier valuations (S..F per item)
 * and relative pairwise preferences ("x beats y"); both fold into one global
 * S–F tier board, incrementally re-tiered on any change. Score fusion +
 * fixed thresholds live in [Tiering]; the two signal averages are ordinary
 * GroupBy cells; combining them per key is the kernel
 * [CombineLatestCell] — the outer per-key combine this demo prototyped
 * (F-1/F-2), wired with `combine = Tiering.fuse`.
 */
data class Valuation(val agent: String, val item: String, val score: Long) : Serializable

data class Pref(val agent: String, val winner: String, val loser: String) : Serializable

/**
 * One pairwise vote projected onto one item. Carries agent AND opponent so
 * distinct preferences never collide as set elements (FlatMapSetCell folds
 * colliding outputs by tag-union — identity must stay per-preference).
 */
data class Contribution(val item: String, val agent: String, val opponent: String, val sign: Long) : Serializable

/**
 * The dataflow pipeline, shared verbatim by the app and the seeded test:
 *
 *   vals  (KeyedSetCell<(agent,item), Valuation>) ─► tierAvg (GroupBy item, avg score)  ─► fuse.left
 *   prefs (SetCell<Pref>) ─► contribs (flatMap ±1) ─► prefAvg (GroupBy item, avg sign) ─► fuse.right
 *   fuse (CombineLatestCell, combine = Tiering.fuse) ─► MapDelta<item, Tiered>
 */
object TierPipeline {
    data class Refs(
        val items: TypedRef<SetApi<String>>,
        val vals: TypedRef<KeyedSetApi<Pair<String, String>, Valuation>>,
        val prefs: TypedRef<SetApi<Pref>>,
        val tierAvg: TypedRef<GroupByApi<Valuation, String, Double>>,
        val prefAvg: TypedRef<GroupByApi<Contribution, String, Double>>,
        val fused: TypedRef<CombineLatestApi<String, Double, Double, Tiered>>,
    )

    fun build(host: ManagedHost): Refs {
        lateinit var built: Refs
        graph(host.managementInlet) {
            val items = spawn("items") { SetCell<String>() }
            val vals = spawn("vals") { KeyedSetCell<Pair<String, String>, Valuation>() }
            val prefs = spawn("prefs") { SetCell<Pref>() }
            val contribs = spawn("contribs") {
                FlatMapSetCell(f = { p: Pref ->
                    listOf(
                        Contribution(p.winner, p.agent, p.loser, +1),
                        Contribution(p.loser, p.agent, p.winner, -1),
                    )
                })
            }
            val tierAvg = spawn("tierAvg") {
                GroupByCell(keyFn = { v: Valuation -> v.item }, aggregator = Aggregators.avgOf { v: Valuation -> v.score })
            }
            val prefAvg = spawn("prefAvg") {
                GroupByCell(keyFn = { c: Contribution -> c.item }, aggregator = Aggregators.avgOf { c: Contribution -> c.sign })
            }
            val fused = spawn("fused") { CombineLatestCell<String, Double, Double, Tiered>(combine = { _, t, p -> Tiering.fuse(t, p) }) }
            link(vals.cell.outlet, tierAvg.cell.inlet)
            link(prefs.cell.outlet, contribs.cell.inlet)
            link(contribs.cell.outlet, prefAvg.cell.inlet)
            link(tierAvg.cell.outlet, fused.cell.left)
            link(prefAvg.cell.outlet, fused.cell.right)
            built = Refs(
                items = items.refAs(),
                vals = vals.refAs(),
                prefs = prefs.refAs(),
                tierAvg = tierAvg.refAs(),
                prefAvg = prefAvg.refAs(),
                fused = fused.refAs(),
            )
        }
        return built
    }
}

class TieringApp(port: Int = 8080) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val refs = TierPipeline.build(host)
    private val itemOps = host.lookup(refs.items)!!.inlet.call
    private val valOps = host.lookup(refs.vals)!!.inlet.call
    private val prefOps = host.lookup(refs.prefs)!!.inlet.call

    private val state = Object()
    // Read model: each derived outlet materialized by a kernel observation sink,
    // read via current() in stateJson. Constructed WITHOUT an onChange listener
    // so no broadcast() fires before all six sinks exist; listeners are
    // registered in init once construction is complete.
    private val itemsView = host.observe(refs.items.ref, View.set<String>())
    private val valuationsView = host.observe(refs.vals.ref, View.set<Valuation>())
    private val prefsView = host.observe(refs.prefs.ref, View.set<Pref>())
    private val tierAvgView = host.observe(refs.tierAvg.ref, View.map<String, Double>())
    private val prefAvgView = host.observe(refs.prefAvg.ref, View.map<String, Double>())
    private val fusedView = host.observe(refs.fused.ref, View.map<String, Tiered>())

    // KeyedSetCell now owns the retract-old memory (F-3), so the app no longer
    // keeps a Valuation-valued shadow index. This lightweight KEY set exists only
    // so `unitem` can enumerate an item's valuation keys to cascade — the F-3
    // residual. These are the app's *authoritative* write-side record, maintained
    // synchronously in the op handlers. The `valuations`/`prefs` fields above
    // are the async read model (folded off the SSE hubs); cascades must use
    // THESE indices, never the read model, or a signal added just before an
    // unitem is missed and ghosts the removed item onto the board.
    private val liveValKeys = mutableSetOf<Pair<String, String>>()  // (agent,item) keys with a live valuation; authoritative write-side record for the unitem cascade
    private val livePrefs = mutableSetOf<Pref>()

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    init {
        // Register one broadcast per sink now that all six exist; registering
        // fires an immediate catch-up (harmless — clients is still empty).
        itemsView.onChange { broadcast() }
        valuationsView.onChange { broadcast() }
        prefsView.onChange { broadcast() }
        tierAvgView.onChange { broadcast() }
        prefAvgView.onChange { broadcast() }
        fusedView.onChange { broadcast() }

        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        shell.sse("/events") { stateJson() }
    }

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }

        fun name(key: String): String? =
            params[key]?.trim()?.takeIf { it.isNotEmpty() && it.length <= 40 }

        when (params["action"]) {
            "item" -> {
                val item = name("name") ?: return exchange.respond(400, "missing name")
                itemOps.add(item)
            }

            "unitem" -> {
                val item = name("name") ?: return exchange.respond(400, "missing name")
                itemOps.remove(item)
                // cascade the item's own signals so it doesn't haunt the board.
                // Drive from the authoritative write-side indices, not the async
                // read model, so a signal added moments earlier is never missed.
                synchronized(state) {
                    liveValKeys.filter { it.second == item }.forEach { key ->
                        valOps.remove(key); liveValKeys -= key
                    }
                    livePrefs.filter { it.winner == item || it.loser == item }.forEach {
                        livePrefs -= it; prefOps.remove(it)
                    }
                }
            }

            "tier" -> {
                val agent = name("agent") ?: return exchange.respond(400, "missing agent")
                val item = name("item") ?: return exchange.respond(400, "missing item")
                val tier = params["tier"]?.takeIf { it in Tiering.TIERS || it == "none" }
                    ?: return exchange.respond(400, "tier must be one of ${Tiering.TIERS} or none")
                synchronized(state) {
                    if (tier != "none") {
                        valOps.put(agent to item, Valuation(agent, item, Tiering.SCORE_OF.getValue(tier)))
                        liveValKeys += agent to item
                    } else {
                        valOps.remove(agent to item)
                        liveValKeys -= agent to item
                    }
                }
            }

            "pref", "unpref" -> {
                val agent = name("agent") ?: return exchange.respond(400, "missing agent")
                val winner = name("winner") ?: return exchange.respond(400, "missing winner")
                val loser = name("loser") ?: return exchange.respond(400, "missing loser")
                if (winner == loser) return exchange.respond(400, "winner and loser must differ")
                val p = Pref(agent, winner, loser)
                synchronized(state) {
                    if (params["action"] == "pref") { livePrefs += p; prefOps.add(p) }
                    else { livePrefs -= p; prefOps.remove(p) }
                }
            }

            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String {
        fun num(d: Double) = "%.4f".format(Locale.ROOT, d)

        val items = itemsView.current()
        val valuations = valuationsView.current()
        val prefs = prefsView.current()
        val tierAvg = tierAvgView.current()
        val prefAvg = prefAvgView.current()
        val fused = fusedView.current()

        val board = Tiering.TIERS.joinToString(",") { tier ->
            val entries = fused.filterValues { it.tier == tier }.entries
                .sortedByDescending { it.value.score }
                .joinToString(",", "[", "]") { (item, t) -> """{"item":${esc(item)},"score":${num(t.score)}}""" }
            "${esc(tier)}:$entries"
        }
        val unrated = (items - fused.keys).sorted().joinToString(",", "[", "]") { esc(it) }
        val signals = (fused.keys + items).sorted().joinToString(",", "[", "]") { item ->
            val t = tierAvg[item]?.let { num(it) } ?: "null"
            val p = prefAvg[item]?.let { num(it) } ?: "null"
            val f = fused[item]
            """{"item":${esc(item)},"tierAvg":$t,"prefAvg":$p,""" +
                    """"score":${f?.let { num(it.score) } ?: "null"},"tier":${f?.let { esc(it.tier) } ?: "null"}}"""
        }
        val vals = valuations.sortedWith(compareBy({ it.agent }, { it.item }))
            .joinToString(",", "[", "]") {
                """{"agent":${esc(it.agent)},"item":${esc(it.item)},"tier":${esc(Tiering.TIER_OF_SCORE.getValue(it.score))}}"""
            }
        val prefList = prefs.sortedWith(compareBy({ it.agent }, { it.winner }, { it.loser }))
            .joinToString(",", "[", "]") {
                """{"agent":${esc(it.agent)},"winner":${esc(it.winner)},"loser":${esc(it.loser)}}"""
            }

        return """{"items":${items.sorted().joinToString(",", "[", "]") { esc(it) }},""" +
                """"board":{$board,"unrated":$unrated},"signals":$signals,""" +
                """"valuations":$vals,"prefs":$prefList}"""
    }

    fun start(): TieringApp = apply { shell.start() }

    fun stop() = shell.stop()
}

fun main(args: Array<String>) {
    val app = TieringApp(demoPort(args)).start()
    println("computenet tiering: http://localhost:${app.boundPort}")
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>tiering — incremental tier board</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1080px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; align-items: flex-start; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 300px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  .tierrow { display: flex; align-items: stretch; border: 1px solid var(--line); border-radius: 8px; margin: .25rem 0; overflow: hidden; min-height: 2.2rem; }
  .tierrow .label { flex: 0 0 2.4rem; display: flex; align-items: center; justify-content: center; font-weight: 700; }
  .tierrow .slots { flex: 1; display: flex; flex-wrap: wrap; gap: .3rem; padding: .3rem .5rem; align-items: center; }
  .t-S .label { background: #fecaca; } .t-A .label { background: #fed7aa; } .t-B .label { background: #fde68a; }
  .t-C .label { background: #fef08a; } .t-D .label { background: #d9f99d; } .t-E .label { background: #bbf7d0; }
  .t-F .label { background: #bfdbfe; } .t-U .label { background: #f3f4f6; color: var(--dim); font-size: .7rem; }
  .chip { background: #fff; border: 1px solid var(--line); border-radius: 999px; padding: .1rem .55rem; font-size: .8rem; }
  .chip small { color: var(--dim); }
  form { display: flex; gap: .4rem; margin-bottom: .6rem; flex-wrap: wrap; }
  input, select { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  form button, .beats { padding: .35rem .7rem; border: none; border-radius: 6px; background: var(--blue); color: #fff; cursor: pointer; }
  .itemrow { display: flex; align-items: center; gap: .4rem; margin: .25rem 0; font-size: .9rem; flex-wrap: wrap; }
  .itemrow b { min-width: 5.5rem; }
  .pick button { width: 1.7rem; height: 1.5rem; border: 1px solid var(--line); background: #fff; cursor: pointer; font-size: .7rem; border-radius: 4px; }
  .pick button.mine { background: var(--blue); border-color: var(--blue); color: #fff; }
  .itemrow .del { margin-left: .5rem; border: none; background: none; color: var(--dim); cursor: pointer; font-size: 1rem; line-height: 1; }
  .itemrow .del:hover { color: #dc2626; }
  .prefline { font-size: .85rem; margin: .2rem 0; }
  .prefline button { border: none; background: none; color: var(--dim); cursor: pointer; }
  table { border-collapse: collapse; font-size: .8rem; width: 100%; }
  th, td { text-align: left; padding: .2rem .5rem; border-bottom: 1px solid var(--line); }
  th { color: var(--dim); font-weight: normal; }
  #agent { width: 8rem; }
</style>
</head>
<body>
<h1>Tier board <small>absolute valuations ⊕ pairwise preferences, fused incrementally</small></h1>
<div class="row">
  <div class="card" style="flex:2">
    <h2>Global tiers</h2>
    <div id="board"></div>
  </div>
  <div class="card">
    <h2>You</h2>
    <form id="agentForm">as <input id="agent"></form>
    <h2>Add item</h2>
    <form id="itemForm"><input id="itemName" placeholder="item"><button>Add</button></form>
    <h2>Your tier per item</h2>
    <div id="rate"></div>
    <h2>Preference</h2>
    <form id="prefForm"><select id="winner"></select><button class="beats">beats</button><select id="loser"></select></form>
    <div id="prefs"></div>
  </div>
</div>
<div class="row">
  <div class="card"><h2>Signals (intermediate views)</h2>
    <table><thead><tr><th>item</th><th>tier avg (0–6)</th><th>pref avg (−1…1)</th><th>fused</th><th>tier</th></tr></thead><tbody id="signals"></tbody></table>
  </div>
</div>
<script>
const TIERS = ["S","A","B","C","D","E","F"];
let state = {};
const agentInput = document.getElementById('agent');
agentInput.value = sessionStorage.agent ??= 'user-' + Math.random().toString(36).slice(2, 6);
agentInput.onchange = () => { sessionStorage.agent = agentInput.value.trim(); render(); };
const me = () => agentInput.value.trim();
const op = body => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams(body) });

document.getElementById('agentForm').onsubmit = e => e.preventDefault();
document.getElementById('itemForm').onsubmit = e => {
  e.preventDefault();
  const name = itemName.value.trim();
  if (name) op({ action: 'item', name });
  itemName.value = '';
};
document.getElementById('prefForm').onsubmit = e => {
  e.preventDefault();
  if (winner.value && loser.value && winner.value !== loser.value)
    op({ action: 'pref', agent: me(), winner: winner.value, loser: loser.value });
};

function render() {
  const board = document.getElementById('board'); board.innerHTML = '';
  for (const tier of TIERS.concat(['unrated'])) {
    const row = document.createElement('div');
    row.className = 'tierrow t-' + (tier === 'unrated' ? 'U' : tier);
    const label = document.createElement('div'); label.className = 'label';
    label.textContent = tier === 'unrated' ? 'new' : tier; row.appendChild(label);
    const slots = document.createElement('div'); slots.className = 'slots';
    for (const entry of (state.board || {})[tier] || []) {
      const chip = document.createElement('span'); chip.className = 'chip';
      if (tier === 'unrated') chip.textContent = entry;
      else { chip.innerHTML = ''; chip.append(entry.item + ' '); const s = document.createElement('small'); s.textContent = entry.score; chip.appendChild(s); }
      slots.appendChild(chip);
    }
    row.appendChild(slots); board.appendChild(row);
  }

  const mine = {};
  for (const v of state.valuations || []) if (v.agent === me()) mine[v.item] = v.tier;
  const rate = document.getElementById('rate'); rate.innerHTML = '';
  for (const item of state.items || []) {
    const div = document.createElement('div'); div.className = 'itemrow';
    const b = document.createElement('b'); b.textContent = item; div.appendChild(b);
    const pick = document.createElement('span'); pick.className = 'pick';
    for (const t of TIERS.concat(['none'])) {
      const btn = document.createElement('button');
      btn.textContent = t === 'none' ? '∅' : t;
      if (mine[item] === t) btn.classList.add('mine');
      btn.onclick = () => op({ action: 'tier', agent: me(), item, tier: t });
      pick.appendChild(btn);
    }
    div.appendChild(pick);
    const del = document.createElement('button');
    del.textContent = '×'; del.className = 'del'; del.title = 'remove item (retracts it and its signals globally)';
    del.onclick = () => op({ action: 'unitem', name: item });
    div.appendChild(del); rate.appendChild(div);
  }

  for (const sel of [winner, loser]) {
    const prev = sel.value; sel.innerHTML = '';
    for (const item of state.items || []) {
      const o = document.createElement('option'); o.value = o.textContent = item; sel.appendChild(o);
    }
    if (prev) sel.value = prev;
  }

  const prefsEl = document.getElementById('prefs'); prefsEl.innerHTML = '';
  for (const p of state.prefs || []) {
    const div = document.createElement('div'); div.className = 'prefline';
    div.append(p.agent + ': ' + p.winner + ' ≻ ' + p.loser + ' ');
    const x = document.createElement('button'); x.textContent = '×';
    x.onclick = () => op({ action: 'unpref', agent: p.agent, winner: p.winner, loser: p.loser });
    div.appendChild(x); prefsEl.appendChild(div);
  }

  const tbody = document.getElementById('signals'); tbody.innerHTML = '';
  for (const s of state.signals || []) {
    const tr = document.createElement('tr');
    for (const v of [s.item, s.tierAvg, s.prefAvg, s.score, s.tier]) {
      const td = document.createElement('td'); td.textContent = v ?? '—'; tr.appendChild(td);
    }
    tbody.appendChild(tr);
  }
}
new EventSource('/events').onmessage = e => { state = JSON.parse(e.data); render(); };
</script>
</body>
</html>
"""
