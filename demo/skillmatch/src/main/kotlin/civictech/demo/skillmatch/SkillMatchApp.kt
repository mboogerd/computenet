package civictech.demo.skillmatch

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.CombineLatestCell
import civictech.cell.data.GroupByCell
import civictech.cell.data.JoinSetCell
import civictech.cell.data.LookupJoinCell
import civictech.cell.data.SemiJoinCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.graph.graph
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.ObservationSink
import civictech.cell.host.View
import civictech.cell.host.observe
import civictech.cell.port.Use
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.io.Serializable
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

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
        val candSkills: CellRef,
        val jobSkills: CellRef,
        val matches: CellRef,
        val matchCounts: CellRef,
        val required: CellRef,
        val qualification: CellRef,
        val gap: CellRef,
        val supply: CellRef,
        val demand: CellRef,
        val market: CellRef,
    )

    fun build(host: ManagedHost): Refs {
        val refs = mutableMapOf<String, CellRef>()
        graph(host.managementInlet) {
            val cand = spawn("candSkills") { SetCell<CandidateSkill>() }
            val jobs = spawn("jobSkills") { SetCell<JobSkill>() }
            val matches = spawn("matches") {
                JoinSetCell(
                    leftKey = { cs: CandidateSkill -> cs.skill },
                    rightKey = { js: JobSkill -> js.skill },
                    combine = { cs: CandidateSkill, js: JobSkill -> Match(cs.candidate, js.job, cs.skill) },
                )
            }
            val matchCounts = spawn("matchCounts") {
                GroupByCell(
                    keyFn = { m: Match -> CandidateJob(m.candidate, m.job) },
                    aggregator = Aggregators.count<Match>(),
                )
            }
            val required = spawn("required") {
                GroupByCell(keyFn = { js: JobSkill -> js.job }, aggregator = Aggregators.count<JobSkill>())
            }
            // qualification = incremental foreign-key join: each (candidate,job)
            // fact enriched with its job's required-skill count (dimension via
            // fk = job), qualified iff the match count equals a positive
            // requirement. Reactive on both sides — a change to a job's required
            // count re-emits every pair for that job.
            val qualification = spawn("qualification") {
                LookupJoinCell<CandidateJob, Long, String, Long, QualEntry>(
                    fk = { it.job },
                    combine = { _, matched, need ->
                        val nd = need ?: 0L
                        QualEntry(matched, nd, matched == nd && nd > 0L)
                    },
                )
            }
            val gap = spawn("gap") {
                SemiJoinCell(
                    leftKey = { js: JobSkill -> js.skill },
                    rightKey = { cs: CandidateSkill -> cs.skill },
                    negated = true,
                )
            }
            // market view: per-skill supply (candidates who have it) and demand
            // (jobs that require it), each an incremental count over the same
            // set outlets that already feed the join.
            val supply = spawn("supply") {
                GroupByCell(keyFn = { cs: CandidateSkill -> cs.skill }, aggregator = Aggregators.count<CandidateSkill>())
            }
            val demand = spawn("demand") {
                GroupByCell(keyFn = { js: JobSkill -> js.skill }, aggregator = Aggregators.count<JobSkill>())
            }
            // market = per-key outer combine of supply vs demand, a real
            // incremental cell (CombineLatestCell) rather than an edge union.
            val market = spawn("market") {
                CombineLatestCell<String, Long, Long, MarketEntry>(
                    combine = { _, s, d ->
                        val sv = s ?: 0L
                        val dv = d ?: 0L
                        MarketEntry(sv, dv, dv > sv)
                    },
                )
            }
            connect(cand, "outlet", matches, "left")
            connect(jobs, "outlet", matches, "right")
            connect(matches, "outlet", matchCounts, "inlet")
            connect(jobs, "outlet", required, "inlet")
            connect(matchCounts, "outlet", qualification, "fact")
            connect(required, "outlet", qualification, "dimension")
            connect(jobs, "outlet", gap, "left")
            connect(cand, "outlet", gap, "right")
            connect(cand, "outlet", supply, "inlet")
            connect(jobs, "outlet", demand, "inlet")
            connect(supply, "outlet", market, "left")
            connect(demand, "outlet", market, "right")
            listOf(cand, jobs, matches, matchCounts, required, qualification, gap, supply, demand, market)
                .forEach { refs[it.name] = it.ref }
        }
        return Refs(
            candSkills = refs.getValue("candSkills"),
            jobSkills = refs.getValue("jobSkills"),
            matches = refs.getValue("matches"),
            matchCounts = refs.getValue("matchCounts"),
            required = refs.getValue("required"),
            qualification = refs.getValue("qualification"),
            gap = refs.getValue("gap"),
            supply = refs.getValue("supply"),
            demand = refs.getValue("demand"),
            market = refs.getValue("market"),
        )
    }
}

interface CandidateInletProxy {
    val inlet: Use<SetOps<CandidateSkill>>
}

interface JobInletProxy {
    val inlet: Use<SetOps<JobSkill>>
}

class SkillMatchApp(port: Int = 8080) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val refs = SkillPipeline.build(host)
    private val candOps = host.lookup<CandidateInletProxy>(refs.candSkills)!!.inlet.call
    private val jobOps = host.lookup<JobInletProxy>(refs.jobSkills)!!.inlet.call

    // Observation sinks: each folds one pipeline outlet's delta stream into a
    // thread-safe, immutable materialized snapshot (the host observation sink).
    // `observe(...)` spawns+connects the sink cell immediately; `current()` is a
    // consistent snapshot readable from any thread, so `stateJson()` needs no
    // external monitor. The `broadcast()` onChange listener is wired in `init`,
    // once `clients`/`server` exist (see below).
    private val candSkills: ObservationSink<Set<CandidateSkill>> =
        host.observe(refs.candSkills, View.set<CandidateSkill>())
    private val jobSkills: ObservationSink<Set<JobSkill>> =
        host.observe(refs.jobSkills, View.set<JobSkill>())
    private val matches: ObservationSink<Set<Match>> =
        host.observe(refs.matches, View.set<Match>())
    private val gap: ObservationSink<Set<JobSkill>> =
        host.observe(refs.gap, View.set<JobSkill>())
    private val qualification: ObservationSink<Map<CandidateJob, QualEntry>> =
        host.observe(refs.qualification, View.map<CandidateJob, QualEntry>())
    private val market: ObservationSink<Map<String, MarketEntry>> =
        host.observe(refs.market, View.map<String, MarketEntry>())

    private val clients = CopyOnWriteArrayList<OutputStream>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0)

    val boundPort: Int get() = server.address.port

    init {
        server.createContext("/") { it.respond(200, PAGE, "text/html; charset=utf-8") }
        server.createContext("/state") { it.respond(200, stateJson(), "application/json") }
        server.createContext("/op") { handleOp(it) }
        server.createContext("/events") { handleEvents(it) }
        server.executor = null

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

    private fun handleEvents(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        clients += out
        send(out, stateJson())
    }

    private fun broadcast() {
        val json = stateJson()
        clients.forEach { send(it, json) }
    }

    private fun send(out: OutputStream, json: String) {
        // per-stream lock: a connect-time send (HTTP thread) and a concurrent
        // broadcast (scheduler thread) both write this same OutputStream, and
        // java.io streams aren't thread-safe — interleaved frames would corrupt
        // the SSE and silently freeze the client.
        try {
            synchronized(out) {
                out.write("data: $json\n\n".toByteArray())
                out.flush()
            }
        } catch (_: Exception) {
            clients -= out
        }
    }

    private fun stateJson(): String {
        fun esc(s: String) = "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
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

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "text/plain") {
        responseHeaders.add("Content-Type", contentType)
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    fun start(): SkillMatchApp = apply { server.start() }

    fun stop() = server.stop(0)
}

fun main(args: Array<String>) {
    val port = args.firstOrNull { !it.startsWith("--") }?.toIntOrNull()
        ?: System.getenv("PORT")?.toIntOrNull() ?: 8080
    val app = SkillMatchApp(port).start()
    println("computenet skillmatch: http://localhost:${app.boundPort}")
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
