package civictech.demo.skillmatch

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.refAs
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.ObservationSink
import civictech.cell.observe.View
import civictech.cell.observe.observe
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.respond
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange
import java.io.Serializable
import java.net.URLDecoder
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.JoinSetApi
import civictech.cell.data.op.SemiJoinCell
import civictech.cell.data.op.SemiJoinApi
import civictech.cell.data.op.GroupByCell
import civictech.cell.data.op.GroupByApi
import civictech.cell.data.op.LookupJoinCell
import civictech.cell.data.op.LookupJoinApi
import civictech.cell.data.op.CombineLatestCell
import civictech.cell.data.op.CombineLatestApi

/**
 * Skill matching: candidates declare skills, jobs declare required skills.
 * A relational equi-join yields per-skill matches; grouped counts against the
 * jobs' required counts yield qualification; a negated semijoin yields the
 * skills gap (required skills no candidate has); two more grouped counts yield
 * the per-skill market (supply = candidates who have it, demand = jobs that
 * want it). All views are incremental — adding or removing one skill flows one
 * delta through join/groupBy/antijoin.
 *
 * The market (supply vs demand) is a real incremental CombineLatestCell — an
 * outer per-key combine of the two count streams. Qualification (matched ==
 * required) is a real incremental LookupJoinCell — a foreign-key/dimension join
 * of the per-pair match counts against the per-job required counts (closing the
 * former hub-edge computation, finding F-1 in doc/demo-findings.md).
 */
data class CandidateSkill(val candidate: String, val skill: String) : Serializable

data class JobSkill(val job: String, val skill: String) : Serializable

data class Match(val candidate: String, val job: String, val skill: String) : Serializable

data class CandidateJob(val candidate: String, val job: String) : Serializable, Comparable<CandidateJob> {
    override fun compareTo(other: CandidateJob) =
        compareValuesBy(this, other, { it.candidate }, { it.job })
}

data class MarketEntry(val supply: Long, val demand: Long, val scarce: Boolean) : Serializable

data class QualEntry(val matched: Long, val required: Long, val qualified: Boolean) : Serializable

/**
 * The dataflow pipeline, shared verbatim by the app and the seeded test:
 *
 *   candSkills ─┬► matches (⋈ on skill) ─► matchCounts (count per candidate×job)
 *   jobSkills ──┼► required (count per job)
 *               └► gap (jobSkills ▷ candSkills on skill — required by nobody-has)
 */
object SkillPipeline {
    data class Refs(
        val candSkills: TypedRef<SetApi<CandidateSkill>>,
        val jobSkills: TypedRef<SetApi<JobSkill>>,
        val matches: TypedRef<JoinSetApi<CandidateSkill, JobSkill, Match>>,
        val matchCounts: TypedRef<GroupByApi<Match, CandidateJob, Long>>,
        val required: TypedRef<GroupByApi<JobSkill, String, Long>>,
        val qualification: TypedRef<LookupJoinApi<CandidateJob, Long, String, Long, QualEntry>>,
        val gap: TypedRef<SemiJoinApi<JobSkill, CandidateSkill>>,
        val supply: TypedRef<GroupByApi<CandidateSkill, String, Long>>,
        val demand: TypedRef<GroupByApi<JobSkill, String, Long>>,
        val market: TypedRef<CombineLatestApi<String, Long, Long, MarketEntry>>,
    )

    fun build(host: ManagedHost): Refs {
        lateinit var refs: Refs
        graph(host.managementInlet) {
            // Factories stay pure (replay-safe): each spawn's lambda constructs
            // the cell from the resolved ref, and `spawn` returns a
            // TypedCellHandle whose `.cell` exposes the typed ports for `link`.
            // `link` recovers each port's owner handle from the builder index,
            // so the recorded connects are byte-identical to the former
            // stringly-typed connect(...) calls.
            val cand = spawn("candSkills") { ref -> SetCell<CandidateSkill>(ref = ref) }
            val jobs = spawn("jobSkills") { ref -> SetCell<JobSkill>(ref = ref) }
            val matches = spawn("matches") { ref ->
                JoinSetCell(
                    ref = ref,
                    leftKey = { cs: CandidateSkill -> cs.skill },
                    rightKey = { js: JobSkill -> js.skill },
                    combine = { cs: CandidateSkill, js: JobSkill -> Match(cs.candidate, js.job, cs.skill) },
                )
            }
            val matchCounts = spawn("matchCounts") { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { m: Match -> CandidateJob(m.candidate, m.job) },
                    aggregator = Aggregators.count<Match>(),
                )
            }
            val required = spawn("required") { ref ->
                GroupByCell(ref = ref, keyFn = { js: JobSkill -> js.job }, aggregator = Aggregators.count<JobSkill>())
            }
            // qualification = incremental foreign-key join: each (candidate,job)
            // fact enriched with its job's required-skill count (dimension via
            // fk = job), qualified iff the match count equals a positive
            // requirement. Reactive on both sides — a change to a job's required
            // count re-emits every pair for that job.
            val qualification = spawn("qualification") { ref ->
                LookupJoinCell<CandidateJob, Long, String, Long, QualEntry>(
                    ref = ref,
                    fk = { it.job },
                    combine = { _, matched, need ->
                        val nd = need ?: 0L
                        QualEntry(matched, nd, matched == nd && nd > 0L)
                    },
                )
            }
            val gap = spawn("gap") { ref ->
                SemiJoinCell(
                    ref = ref,
                    leftKey = { js: JobSkill -> js.skill },
                    rightKey = { cs: CandidateSkill -> cs.skill },
                    negated = true,
                )
            }
            // market view: per-skill supply (candidates who have it) and demand
            // (jobs that require it), each an incremental count over the same
            // set outlets that already feed the join.
            val supply = spawn("supply") { ref ->
                GroupByCell(
                    ref = ref,
                    keyFn = { cs: CandidateSkill -> cs.skill },
                    aggregator = Aggregators.count<CandidateSkill>(),
                )
            }
            val demand = spawn("demand") { ref ->
                GroupByCell(ref = ref, keyFn = { js: JobSkill -> js.skill }, aggregator = Aggregators.count<JobSkill>())
            }
            // market = per-key outer combine of supply vs demand, a real
            // incremental cell (CombineLatestCell) rather than an edge union.
            val market = spawn("market") { ref ->
                CombineLatestCell<String, Long, Long, MarketEntry>(
                    ref = ref,
                    combine = { _, s, d ->
                        val sv = s ?: 0L
                        val dv = d ?: 0L
                        MarketEntry(sv, dv, dv > sv)
                    },
                )
            }
            // Typed, compile-checked wiring: each link's out/inn must share the
            // same Api payload type, so a mismatch or wrong-direction wiring is a
            // compile error rather than a runtime reject.
            link(cand.cell.outlet, matches.cell.left)
            link(jobs.cell.outlet, matches.cell.right)
            link(matches.cell.outlet, matchCounts.cell.inlet)
            link(jobs.cell.outlet, required.cell.inlet)
            link(matchCounts.cell.outlet, qualification.cell.fact)
            link(required.cell.outlet, qualification.cell.dimension)
            link(jobs.cell.outlet, gap.cell.left)
            link(cand.cell.outlet, gap.cell.right)
            link(cand.cell.outlet, supply.cell.inlet)
            link(jobs.cell.outlet, demand.cell.inlet)
            link(supply.cell.outlet, market.cell.left)
            link(demand.cell.outlet, market.cell.right)
            refs = Refs(
                candSkills = cand.refAs(),
                jobSkills = jobs.refAs(),
                matches = matches.refAs(),
                matchCounts = matchCounts.refAs(),
                required = required.refAs(),
                qualification = qualification.refAs(),
                gap = gap.refAs(),
                supply = supply.refAs(),
                demand = demand.refAs(),
                market = market.refAs(),
            )
        }
        return refs
    }
}

/**
 * A second, disjoint graph on the same host — two saved-search sets, one
 * mirroring the other, sharing no link with [SkillPipeline].
 *
 * It exists for the inspector's M4 navigator, which is about a *process*
 * running many unrelated graphs: with only the pipeline there is one component
 * and nothing to navigate between. Deliberately left unnamed, so the navigator
 * also has the unnamed case — a card showing its generated `g-…` id — to
 * render beside the named one. Its cells are named, so name search has a hit
 * outside the pipeline.
 *
 * Off by default and wired only from `main`: the demo's own views never read
 * it, and nothing about skillmatch changes when it is absent.
 */
object SideGraph {
    data class Refs(val saved: CellRef, val mirror: CellRef) {
        val names: Map<CellRef, String> get() = mapOf(saved to "savedSearches", mirror to "savedSearchesLog")
    }

    fun build(host: ManagedHost): Refs {
        lateinit var built: Refs
        graph(host.managementInlet) {
            val saved = spawn("savedSearches") { ref -> SetCell<String>(ref = ref) }
            val mirror = spawn("savedSearchesLog") { ref -> SetCell<String>(ref = ref) }
            link(saved.cell.outlet, mirror.cell.deltaInlet)
            built = Refs(saved.ref, mirror.ref)
        }
        return built
    }
}

class SkillMatchApp(port: Int = 8080) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val refs = SkillPipeline.build(host)
    private val candOps = host.lookup(refs.candSkills)!!.inlet.call
    private val jobOps = host.lookup(refs.jobSkills)!!.inlet.call

    // Observation sinks: each folds one pipeline outlet's delta stream into a
    // thread-safe, immutable materialized snapshot (the host observation sink).
    // `observe(...)` spawns+connects the sink cell immediately; `current()` is a
    // consistent snapshot readable from any thread, so `stateJson()` needs no
    // external monitor. The `broadcast()` onChange listener is wired in `init`,
    // once `clients`/`server` exist (see below).
    private val candSkills: ObservationSink<Set<CandidateSkill>> =
        host.observe(refs.candSkills.ref, View.set<CandidateSkill>())
    private val jobSkills: ObservationSink<Set<JobSkill>> =
        host.observe(refs.jobSkills.ref, View.set<JobSkill>())
    private val matches: ObservationSink<Set<Match>> =
        host.observe(refs.matches.ref, View.set<Match>())
    private val gap: ObservationSink<Set<JobSkill>> =
        host.observe(refs.gap.ref, View.set<JobSkill>())
    private val qualification: ObservationSink<Map<CandidateJob, QualEntry>> =
        host.observe(refs.qualification.ref, View.map<CandidateJob, QualEntry>())
    private val market: ObservationSink<Map<String, MarketEntry>> =
        host.observe(refs.market.ref, View.map<String, MarketEntry>())

    private val shell = DemoShell(port)
    private var inspector: InspectorServer? = null

    val boundPort: Int get() = shell.boundPort

    /**
     * Opt-in inspector (`--inspect-port <p>` / `INSPECT_PORT`): serves this
     * app's live dataflow graph on its own port (97-inspector-plan M0). Off
     * unless asked for — nothing about the demo changes when it is not.
     *
     * The kernel has no cell-name registry (graph-builder handle names live in
     * the `GraphSpec`, not at runtime), so the app hands the inspector the
     * names it knows; the observation-sink cells `host.observe` spawns are
     * unnamed and report `null`, per the contract.
     *
     * [withSideGraph] spawns [SideGraph] first, so the M4 navigator has a
     * second, deliberately unnamed component to render (see its doc). The
     * pilot `main` asks for it; the demo itself never does.
     *
     * [coldSideGraph] then suspends every cell of that side component
     * (`HostManagementApi.suspend`, spec 34/G-26), so the M5 navigator has a
     * genuinely cold graph to list, ghost and offer to wake — the pilot half of
     * the M5-COLD ticket. It applies only to the side graph: the pipeline stays
     * hot, because a process with nothing running is not a demonstration of the
     * difference. Suspension is a management call, so this waits for it to take
     * effect rather than racing the inspector's first read.
     */
    fun startInspector(
        port: Int = InspectorServer.DEFAULT_PORT,
        withSideGraph: Boolean = false,
        coldSideGraph: Boolean = false,
    ): InspectorServer {
        val side = if (withSideGraph) SideGraph.build(host) else null
        if (coldSideGraph) side?.let { refs ->
            listOf(refs.saved, refs.mirror).forEach { host.managementInlet.call.suspend(it) }
            awaitSuspended(listOf(refs.saved, refs.mirror))
        }
        return InspectorServer(
            registry = registry,
            hosts = mapOf("skillmatch" to host),
            port = port,
            cellNames = mapOf(
                refs.candSkills.ref to "candSkills",
                refs.jobSkills.ref to "jobSkills",
                refs.matches.ref to "matches",
                refs.matchCounts.ref to "matchCounts",
                refs.required.ref to "required",
                refs.qualification.ref to "qualification",
                refs.gap.ref to "gap",
                refs.supply.ref to "supply",
                refs.demand.ref to "demand",
                refs.market.ref to "market",
            ) + side?.names.orEmpty(),
        )
            // M4: the pipeline's component is the one this process can name.
            // The anchor is a cell, not the component id, because ids change
            // whenever components merge or split (97-inspector-plan M4-BE §2).
            .nameGraph(refs.candSkills.ref, "skillmatch")
            .start().also { inspector = it }
    }

    init {
        shell.route("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.route("/op") { handleOp(it) }
        shell.sse("/events") { stateJson() }

        // Wire broadcast now that the SSE machinery exists. onChange fires once
        // immediately (late-join catch-up with current state) then on every
        // settled effective change; no SSE clients are connected yet during
        // construction, so the catch-up broadcast is a no-op.
        listOf(candSkills, jobSkills, matches, gap, qualification, market)
            .forEach { sink -> sink.onChange { broadcast() } }
    }

    private fun handleOp(exchange: HttpExchange) {
        val params = exchange.requestBody.readBytes().decodeToString()
            .split("&").filter { it.contains("=") }
            .associate {
                val (k, v) = it.split("=", limit = 2)
                k to URLDecoder.decode(v, Charsets.UTF_8)
            }

        fun name(key: String): String? =
            params[key]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.length <= 40 }

        val skill = name("skill") ?: return exchange.respond(400, "missing skill")
        when (params["action"]) {
            "cskill", "uncskill" -> {
                val candidate = name("candidate") ?: return exchange.respond(400, "missing candidate")
                val element = CandidateSkill(candidate, skill)
                if (params["action"] == "cskill") candOps.add(element) else candOps.remove(element)
            }

            "jskill", "unjskill" -> {
                val job = name("job") ?: return exchange.respond(400, "missing job")
                val element = JobSkill(job, skill)
                if (params["action"] == "jskill") jobOps.add(element) else jobOps.remove(element)
            }

            else -> return exchange.respond(400, "unknown action")
        }
        exchange.respond(200, "ok")
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    private fun stateJson(): String {
        fun grouped(pairs: List<Pair<String, String>>): String =
            pairs.groupBy({ it.first }, { it.second }).toSortedMap()
                .entries.joinToString(",", "{", "}") { (owner, skills) ->
                    "${esc(owner)}:${skills.sorted().joinToString(",", "[", "]") { esc(it) }}"
                }

        // qualification = incremental foreign-key join (LookupJoinCell): each
        // (candidate,job) fact enriched with its job's required-skill count,
        // folded into `qualification`. Replaces the former edge computation
        // (kernel gap F-1 — now closed by the join cell).
        val qualNow = qualification.current()
        val progress = qualNow.entries.sortedBy { it.key }.joinToString(",", "[", "]") { (cj, e) ->
            """{"candidate":${esc(cj.candidate)},"job":${esc(cj.job)},"matched":${e.matched},"required":${e.required},"qualified":${e.qualified}}"""
        }
        val gaps = gap.current().sortedWith(compareBy({ it.job }, { it.skill }))
            .joinToString(",", "[", "]") { """{"job":${esc(it.job)},"skill":${esc(it.skill)}}""" }
        val matchList = matches.current().sortedWith(compareBy({ it.candidate }, { it.job }, { it.skill }))
            .joinToString(",", "[", "]") {
                """{"candidate":${esc(it.candidate)},"job":${esc(it.job)},"skill":${esc(it.skill)}}"""
            }
        // market = per-skill supply vs demand, now a real incremental
        // CombineLatestCell (outer combine of the two count streams) folded into
        // `market`. A skill demanded by more jobs than candidates supply it is
        // under-supplied; demand with zero supply is exactly the gap, generalized
        // to counts.
        val marketNow = market.current()
        val marketJson = marketNow.keys.sorted().joinToString(",", "[", "]") { skill ->
            val e = marketNow.getValue(skill)
            """{"skill":${esc(skill)},"supply":${e.supply},"demand":${e.demand},"scarce":${e.scarce}}"""
        }

        return """{"candidates":${grouped(candSkills.current().map { it.candidate to it.skill })},""" +
                """"jobs":${grouped(jobSkills.current().map { it.job to it.skill })},""" +
                """"matches":$matchList,"progress":$progress,"gap":$gaps,"market":$marketJson}"""
    }

    /**
     * Block until every [refs] cell reports suspended, or give up after a
     * second. Suspension rides the host's management queue, and a launcher that
     * printed "cold" before the queue ran would be describing a state that had
     * not happened yet. Giving up rather than throwing keeps a demo flag from
     * being able to fail a demo: the inspector's own 1 Hz lifecycle poll
     * reports the transition whenever it lands.
     */
    private fun awaitSuspended(refs: List<CellRef>) {
        val deadline = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < deadline && refs.any { !host.isSuspended(it) }) Thread.sleep(5)
    }

    fun start(): SkillMatchApp = apply { shell.start() }

    fun stop() {
        inspector?.stop()
        shell.stop()
    }
}

fun main(args: Array<String>) {
    val (inspectPort, demoArgs) = splitInspectorPort(args)
    val cold = COLD_FLAG in args
    val app = SkillMatchApp(demoPort(demoArgs.filterNot { it == COLD_FLAG }.toTypedArray())).start()
    println("computenet skillmatch: http://localhost:${app.boundPort}")
    inspectPort?.let { port ->
        // the pilot runs two graphs (see [SideGraph]) so the M4 navigator has
        // something to navigate: the named pipeline and one unnamed component
        val inspector = app.startInspector(port, withSideGraph = true, coldSideGraph = cold)
        println("computenet inspector: http://localhost:${inspector.boundPort}${InspectorServer.TOPOLOGY_PATH}")
        if (cold) println("  side graph started cold ($COLD_FLAG) — wake it from the navigator")
    }
}

private const val INSPECT_FLAG = "--inspect-port"

/**
 * M5-COLD pilot flag: start the side graph suspended, so the navigator has a
 * cold card, a ghosted cold screen and a wake to demonstrate. Only meaningful
 * together with `--inspect-port`; ignored otherwise (there is nothing to look
 * at without an inspector).
 */
private const val COLD_FLAG = "--cold-graph"

/**
 * The inspector port — `--inspect-port <p>`, `--inspect-port=<p>`, or the
 * `INSPECT_PORT` environment variable — and the remaining args. The flag is
 * stripped because [demoPort] reads the first non-`--` argument as the demo's
 * own port and would otherwise take the inspector's.
 */
private fun splitInspectorPort(args: Array<String>): Pair<Int?, Array<String>> {
    val rest = mutableListOf<String>()
    var value: String? = null
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == INSPECT_FLAG -> { value = args.getOrNull(i + 1); i++ }
            arg.startsWith("$INSPECT_FLAG=") -> value = arg.substringAfter('=')
            else -> rest += arg
        }
        i++
    }
    return (value ?: System.getenv("INSPECT_PORT"))?.trim()?.toIntOrNull() to rest.toTypedArray()
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>skillmatch — incremental hiring board</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; --green: #059669; --amber: #b45309; --red: #b91c1c; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1080px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; align-items: stretch; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 300px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  form { display: flex; gap: .4rem; margin-bottom: .6rem; }
  input { flex: 1; min-width: 0; padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  form button { padding: .35rem .7rem; border: none; border-radius: 6px; background: var(--blue); color: #fff; cursor: pointer; }
  .owner { margin: .35rem 0; font-size: .9rem; }
  .owner b { margin-right: .35rem; }
  .chip { display: inline-flex; align-items: center; gap: .25rem; background: #f3f4f6; border: 1px solid var(--line); border-radius: 999px; padding: .05rem .3rem .05rem .55rem; font-size: .75rem; margin: .1rem; }
  .chip button { border: none; background: none; cursor: pointer; color: var(--dim); font-size: .8rem; padding: 0; }
  .match { background: #eff6ff; border-color: #bfdbfe; color: var(--blue); padding: .1rem .55rem; }
  .gapchip { background: #fef2f2; border-color: #fecaca; color: var(--red); padding: .1rem .55rem; }
  .prog { margin: .3rem 0; font-size: .9rem; display: flex; align-items: center; gap: .5rem; }
  .prog .meter { flex: 0 0 90px; height: 8px; background: #f3f4f6; border-radius: 4px; overflow: hidden; }
  .prog .meter div { height: 100%; background: var(--amber); }
  .prog.ok .meter div { background: var(--green); }
  .badge { font-size: .7rem; border-radius: 999px; padding: .1rem .5rem; background: #ecfdf5; color: var(--green); border: 1px solid #a7f3d0; }
  .mkt { display: flex; align-items: center; gap: .5rem; margin: .25rem 0; font-size: .9rem; }
  .mkt .name { flex: 0 0 8rem; }
  .mkt .nums { color: var(--dim); font-variant-numeric: tabular-nums; }
  .mkt.scarce .name { color: var(--red); font-weight: 600; }
  .mkt .tag { font-size: .7rem; border-radius: 999px; padding: .05rem .45rem; background: #fef2f2; color: var(--red); border: 1px solid #fecaca; }
</style>
</head>
<body>
<h1>Skill matching <small>join · group-count · antijoin — all incremental views</small></h1>
<div class="row">
  <div class="card">
    <h2>Candidates</h2>
    <form id="candForm"><input id="candName" placeholder="candidate"><input id="candSkill" placeholder="skill"><button>Add</button></form>
    <div id="candidates"></div>
  </div>
  <div class="card">
    <h2>Jobs (required skills)</h2>
    <form id="jobForm"><input id="jobName" placeholder="job"><input id="jobSkill" placeholder="required skill"><button>Add</button></form>
    <div id="jobs"></div>
  </div>
</div>
<div class="row">
  <div class="card"><h2>Qualification (matched / required)</h2><div id="progress"></div></div>
  <div class="card"><h2>Matches (candidate ⋈ job on skill)</h2><div id="matches"></div></div>
  <div class="card"><h2>Skills gap (required, nobody has)</h2><div id="gap"></div></div>
</div>
<div class="row">
  <div class="card"><h2>Skill market (supply vs demand)</h2><div id="market"></div></div>
</div>
<script>
const op = body => fetch('/op', { method: 'POST',
  headers: {'Content-Type': 'application/x-www-form-urlencoded'},
  body: new URLSearchParams(body) });

document.getElementById('candForm').onsubmit = e => {
  e.preventDefault();
  const candidate = candName.value.trim(), skill = candSkill.value.trim();
  if (candidate && skill) op({ action: 'cskill', candidate, skill });
  candSkill.value = '';
};
document.getElementById('jobForm').onsubmit = e => {
  e.preventDefault();
  const job = jobName.value.trim(), skill = jobSkill.value.trim();
  if (job && skill) op({ action: 'jskill', job, skill });
  jobSkill.value = '';
};

function owners(id, byOwner, removeAction, ownerKey) {
  const el = document.getElementById(id); el.innerHTML = '';
  for (const [owner, skills] of Object.entries(byOwner)) {
    const div = document.createElement('div'); div.className = 'owner';
    const b = document.createElement('b'); b.textContent = owner; div.appendChild(b);
    for (const skill of skills) {
      const chip = document.createElement('span'); chip.className = 'chip';
      chip.append(skill);
      const x = document.createElement('button'); x.textContent = '×';
      x.onclick = () => op({ action: removeAction, [ownerKey]: owner, skill });
      chip.appendChild(x); div.appendChild(chip);
    }
    el.appendChild(div);
  }
}
function chips(id, items, cls, label) {
  const el = document.getElementById(id); el.innerHTML = '';
  for (const it of items) {
    const c = document.createElement('span'); c.className = 'chip ' + cls;
    c.textContent = label(it); el.appendChild(c);
  }
}
new EventSource('/events').onmessage = e => {
  const s = JSON.parse(e.data);
  owners('candidates', s.candidates, 'uncskill', 'candidate');
  owners('jobs', s.jobs, 'unjskill', 'job');
  chips('matches', s.matches, 'match', m => m.candidate + ' ⋈ ' + m.job + ' · ' + m.skill);
  chips('gap', s.gap, 'gapchip', g => g.job + ': ' + g.skill);
  const mkt = document.getElementById('market'); mkt.innerHTML = '';
  for (const m of s.market) {
    const div = document.createElement('div');
    div.className = 'mkt' + (m.scarce ? ' scarce' : '');
    const name = document.createElement('span'); name.className = 'name'; name.textContent = m.skill;
    const nums = document.createElement('span'); nums.className = 'nums';
    nums.textContent = m.supply + ' have · ' + m.demand + ' want';
    div.append(name, nums);
    if (m.scarce) { const t = document.createElement('span'); t.className = 'tag'; t.textContent = 'under-supplied'; div.appendChild(t); }
    mkt.appendChild(div);
  }
  const el = document.getElementById('progress'); el.innerHTML = '';
  for (const p of s.progress) {
    const div = document.createElement('div');
    div.className = 'prog' + (p.qualified ? ' ok' : '');
    const meter = document.createElement('div'); meter.className = 'meter';
    const fill = document.createElement('div');
    fill.style.width = (p.required ? Math.min(100, p.matched / p.required * 100) : 0) + '%';
    meter.appendChild(fill);
    div.appendChild(meter);
    div.append(p.candidate + ' → ' + p.job + ' · ' + p.matched + '/' + p.required + ' ');
    if (p.qualified) { const b = document.createElement('span'); b.className = 'badge'; b.textContent = 'qualified'; div.appendChild(b); }
    el.appendChild(div);
  }
};
</script>
</body>
</html>
"""
