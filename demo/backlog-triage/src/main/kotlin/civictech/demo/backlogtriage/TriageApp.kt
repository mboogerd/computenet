package civictech.demo.backlogtriage

import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.OrMapApi
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.TypedRef
import civictech.cell.graph.graph
import civictech.cell.graph.lookup
import civictech.cell.graph.refAs
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.observe.ObservationFrame
import civictech.cell.observe.observation
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.flag
import civictech.demo.shell.respond
import civictech.demograph.ranking.BradleyTerry
import civictech.demograph.ranking.Contribution
import civictech.demograph.ranking.Elo
import civictech.demograph.ranking.Glicko
import civictech.demograph.ranking.MetaRankCell
import civictech.demograph.ranking.PairwisePreference
import civictech.demograph.ranking.RatingCell
import civictech.demograph.ranking.TrueSkill
import civictech.demograph.ranking.WengLin
import civictech.demograph.ranking.WilsonAggregator
import civictech.demograph.ranking.contributions
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Serializable
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.GroupByCell

/**
 * backlog-triage: agents submit backlog features and pairwise value
 * preferences ("x is more valuable than y"); the pipeline folds all
 * preferences into one collective ranking, incrementally re-sorted on every
 * vote. The dataflow carries feature *ids* and preferences; titles/bodies
 * are presentation-only app state.
 *
 *   features (SetCell<String>)                                   [membership]
 *   prefs (SetCell<Pref>) ─► contribs (flatMap ±1) ─► score (GroupBy avg) ─► meta.mean
 *            │                        └─────────────► votes (GroupBy count)
 *            ├─► elo       (RatingCell) ─► meta.elo
 *            ├─► bt        (RatingCell) ─► meta.bt
 *            └─► trueskill (RatingCell) ─► meta.trueskill ─► meta (MetaRankCell)
 *
 * Collective score per feature = mean of ±1 over every comparison it appears
 * in, a value in [-1, 1]. Rank = score desc, comparison volume desc, id.
 * ponytail: mean-of-signs, biased when comparison coverage is uneven;
 * upgrade path is Bradley–Terry over the same prefs set.
 */
typealias Pref = PairwisePreference

/** The demo calls demograph's [PairwisePreference.actor] an agent. */
val PairwisePreference.agent: String get() = actor

object TriagePipeline {
    data class Refs(
        val features: TypedRef<SetApi<String>>,
        val prefs: TypedRef<SetApi<Pref>>,
        val meta: TypedRef<OrMapApi<String, FeatureMeta>>,
        val score: CellRef,
        val votes: CellRef,
        val ratings: Map<String, CellRef>,   // algo name → rating-cell ref ("elo", "bt", "trueskill", "meta")
    )

    fun build(host: ManagedHost): Refs = build(ApplyContext(host))

    internal fun build(context: ApplyContext, journalId: String? = null): Refs {
        val refs = mutableMapOf<String, CellRef>()
        lateinit var featuresRef: TypedRef<SetApi<String>>
        lateinit var prefsRef: TypedRef<SetApi<Pref>>
        lateinit var featureMetaRef: TypedRef<OrMapApi<String, FeatureMeta>>
        graph(context) {
            val features = spawn("features", journalId = journalId) { SetCell<String>(it) }
            val prefs = spawn("prefs", journalId = journalId) { SetCell<Pref>(it) }
            val featureMeta = spawn("meta", journalId = journalId) { OrMapCell<String, FeatureMeta>(it) }
            featuresRef = features.refAs()
            prefsRef = prefs.refAs()
            featureMetaRef = featureMeta.refAs()
            val contribs = spawn("contribs") {
                FlatMapSetCell(ref = it, f = { p: Pref -> p.contributions() })
            }
            val score = spawn("score") {
                GroupByCell(
                    ref = it,
                    keyFn = { c: Contribution -> c.item },
                    aggregator = Aggregators.avgOf { c: Contribution -> c.sign },
                )
            }
            val votes = spawn("votes") {
                GroupByCell(
                    ref = it,
                    keyFn = { c: Contribution -> c.item },
                    aggregator = Aggregators.count<Contribution>(),
                )
            }
            val elo = spawn("elo") { RatingCell(Elo(), ref = it) }
            val bt = spawn("bt") { RatingCell(BradleyTerry(), ref = it) }
            val trueskill = spawn("trueskill") { RatingCell(TrueSkill(), ref = it) }
            val glicko = spawn("glicko") { RatingCell(Glicko(), ref = it) }
            val wenglin = spawn("wenglin") { RatingCell(WengLin(), ref = it) }
            // wilson is per-key independent — a plain kernel GroupBy aggregator
            val wilson = spawn("wilson") {
                GroupByCell(ref = it, keyFn = { c: Contribution -> c.item }, aggregator = WilsonAggregator())
            }
            val metaRank = spawn("meta-rank") { MetaRankCell(ref = it) }
            connect(prefs, "outlet", contribs, "inlet")
            connect(contribs, "outlet", score, "inlet")
            connect(contribs, "outlet", votes, "inlet")
            connect(contribs, "outlet", wilson, "inlet")
            for (rating in listOf(elo, bt, trueskill, wenglin, glicko)) {
                connect(prefs, "outlet", rating, "inlet")
            }
            connect(score, "outlet", metaRank, "mean")
            for (rating in listOf(elo, bt, trueskill, glicko, wenglin, wilson)) {
                connect(rating, "outlet", metaRank, rating.name)
            }
            listOf(features, prefs, featureMeta, score, votes, elo, bt, trueskill, glicko, wenglin, wilson, metaRank)
                .forEach { refs[it.name] = it.ref }
        }
        return Refs(
            features = featuresRef,
            prefs = prefsRef,
            meta = featureMetaRef,
            score = refs.getValue("score"),
            votes = refs.getValue("votes"),
            ratings = listOf("elo", "bt", "trueskill", "glicko", "wenglin", "wilson")
                .associateWith { refs.getValue(it) } + ("meta" to refs.getValue("meta-rank")),
        )
    }

    /** Recovered graphs preserve these handles; no app-side respawn is needed. */
    internal fun recovered(context: ApplyContext): Refs = Refs(
        features = TypedRef(context.handles.getValue("features")),
        prefs = TypedRef(context.handles.getValue("prefs")),
        meta = TypedRef(context.handles.getValue("meta")),
        score = context.handles.getValue("score"),
        votes = context.handles.getValue("votes"),
        ratings = listOf("elo", "bt", "trueskill", "glicko", "wenglin", "wilson")
            .associateWith { context.handles.getValue(it) } + ("meta" to context.handles.getValue("meta-rank")),
    )
}

data class FeatureMeta(val title: String, val body: String) : Serializable

val ALGOS = listOf("mean", "elo", "bt", "trueskill", "glicko", "wenglin", "wilson", "meta")

private const val HOST_JOURNAL_ID = "host"
internal const val TRIAGE_JOURNAL_FILE = "host.journal"

@Suppress("UNCHECKED_CAST")
private fun <T> ObservationFrame.view(name: String): T = views.getValue(name) as T

/** The one host/context pair that owns an optional backlog-triage journal. */
private data class TriageRuntime(
    val registry: LocationRegistry,
    val host: ManagedHost,
    val context: ApplyContext,
    val journal: FileJournal?,
    val refs: TriagePipeline.Refs,
    val recovered: Boolean,
) {
    companion object {
        fun create(journalDir: Path?): TriageRuntime {
            if (journalDir != null && Files.exists(journalDir) && !Files.isDirectory(journalDir)) {
                throw IllegalArgumentException("--journal must name a directory; legacy JSONL files are not migrated: $journalDir")
            }
            journalDir?.let(Files::createDirectories)
            val journal = journalDir?.resolve(TRIAGE_JOURNAL_FILE)?.toFile()?.let(::FileJournal)
            val registry = LocationRegistry()
            lateinit var context: ApplyContext
            val host = ManagedHost(
                registry = registry,
                journalFor = { ref -> context.journalFor(ref) },
            )
            context = ApplyContext(
                host = host,
                journals = journal?.let { mapOf(HOST_JOURNAL_ID to it) }.orEmpty(),
                topology = journal,
            )
            val recovered = journal?.replay()?.isNotEmpty() == true
            val refs = if (recovered) {
                context.recover(checkNotNull(journal)).awaitApplied()
                TriagePipeline.recovered(context)
            } else {
                TriagePipeline.build(context, journalId = journal?.let { HOST_JOURNAL_ID })
            }
            return TriageRuntime(registry, host, context, journal, refs, recovered)
        }
    }
}

class TriageApp(
    port: Int = 8080,
    journalPath: Path? = null,
    inspector: InspectorFlag.Options? = null,
) {
    private val inspectorOptions = inspector

    private val runtime = TriageRuntime.create(journalPath)
    private val registry = runtime.registry
    private val host = runtime.host
    private val refs = runtime.refs
    private val featureOps = host.lookup(refs.features)!!.inlet.call
    private val prefOps = host.lookup(refs.prefs)!!.inlet.call
    private val metaOps = host.lookup(refs.meta)!!.inlet.call

    private val state = Object()
    // async read model, folded off the hub cells
    private var features: Set<String> = emptySet()
    private var prefs: Set<Pref> = emptySet()
    private var score: Map<String, Double> = emptyMap()
    private var votes: Map<String, Long> = emptyMap()
    // authoritative write-side indices: cascades and validation use THESE,
    // never the async read model (see tiering finding F-3)
    private val meta = mutableMapOf<String, FeatureMeta>()
    private val livePrefs = mutableSetOf<Pref>()

    // per-algorithm rating read models, folded off the RatingCell /
    // MetaRankCell outlets — the algorithms run *inside* the dataflow
    // (fed by the prefs cell, rebuilt by journal replay through it);
    // "mean" is the kernel-operator pipeline's own `score` map
    private val algoScores = mutableMapOf<String, Map<String, Double>>()

    private val shell = DemoShell(port)

    internal val observationGroups: Map<String, String>
        get() = observation.current().groupOf

    /**
     * The MetaRank view is a disclosed interim second observation
     * (computenet-5otve): its dynamic fan-in can fail to acknowledge an
     * effective no-op, so it must not hold the builder's other views at rest.
     */
    internal val metaObservationGroups: Map<String, String>
        get() = metaObservation.current().groupOf

    private val observation = host.observation {
        set("features", refs.features)
        set("prefs", refs.prefs)
        map("score", refs.score)
        map("votes", refs.votes)
        refs.ratings.filterKeys { it != "meta" }.forEach { (algo, ref) -> map("rating:$algo", ref) }
    }

    // MetaRankCell is an ungated dynamic fan-in: when its effective map does
    // not change it emits neither a delta nor an absorb acknowledgement. Keep
    // that non-progressing arm from holding the point-consistent application
    // frame, while still exposing it through the same canonical observation
    // API and preserving the old /features?algo=meta read model. This split is
    // the disclosed interim tracked by computenet-5otve.
    private val metaObservation = host.observation {
        map("rating:meta", refs.ratings.getValue("meta"))
    }

    val boundPort: Int get() = shell.boundPort

    /** Non-null once [start] has run with an opt-in `--inspect-port` (`InspectorFlag`). */
    var inspector: InspectorServer? = null
        private set

    init {
        if (runtime.recovered) {
            rebuildMirrors()
            host.checkpoint(checkNotNull(runtime.journal))
        }

        observation.onChange { frame ->
            synchronized(state) {
                features = frame.view("features")
                prefs = frame.view("prefs")
                score = frame.view("score")
                votes = frame.view("votes")
                refs.ratings.keys.filter { it != "meta" }.forEach { algo ->
                    algoScores[algo] = frame.view("rating:$algo")
                }
            }
            broadcast()
        }

        metaObservation.onChange { frame ->
            synchronized(state) { algoScores["meta"] = frame.view("rating:meta") }
            broadcast()
        }

        shell.route("/") { ex ->
            if (ex.requestURI.path == "/") ex.respond(200, PAGE, "text/html; charset=utf-8")
            else ex.respond(404, "not found")
        }
        shell.route("/features") { handleFeatures(it) }
        shell.route("/triage") { handleTriage(it) }
        shell.route("/prefer") { handlePrefer(it) }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.sse("/events") { stateJson() }
    }

    // ── ops (HTTP handlers and --seed write journaled kernel cells) ────────

    private fun snapshot(ref: CellRef): Serializable =
        checkNotNull(host.snapshotOf(ref).get(30, TimeUnit.SECONDS)) { "missing state snapshot for $ref" }

    private fun <E> recoveredSet(ref: CellRef): Set<E> {
        val restored = SetCell<E>(ref)
        restored.restore(snapshot(ref))
        return restored.membership()
    }

    private fun <K, V> recoveredMap(ref: CellRef): Map<K, V> {
        val restored = OrMapCell<K, V>(ref)
        restored.restore(snapshot(ref))
        return restored.membership().associateWith { key -> checkNotNull(restored.value(key)) }
    }

    /** Durable cells are authoritative after recovery; these mirrors serve synchronous validation paths. */
    private fun rebuildMirrors() = synchronized(state) {
        meta.putAll(recoveredMap(refs.meta.ref))
        livePrefs.addAll(recoveredSet(refs.prefs.ref))
    }

    fun addFeature(id: String, title: String, body: String) {
        synchronized(state) {
            val m = FeatureMeta(title, body)
            if (meta[id] == m) return
            metaOps.put(id, m)
            featureOps.add(id)
            meta[id] = m
        }
    }

    // ponytail: a deleted feature whose file still sits in the --seed dir
    // resurrects on the next boot (seeding runs after replay); remove the
    // file too if the deletion should stick.
    private fun removeFeature(id: String): Boolean {
        synchronized(state) {
            if (id !in meta) return false
            metaOps.remove(id)
            featureOps.remove(id)
            // cascade the feature's preferences so it doesn't haunt the ranking
            livePrefs.filter { it.winner == id || it.loser == id }.forEach {
                prefOps.remove(it)
                livePrefs -= it
            }
            meta.remove(id)
        }
        return true
    }

    private fun applyPref(p: Pref) = synchronized(state) {
        // an agent holds at most one direction per pair: adding a preference
        // retracts the same agent's reverse vote
        val reverse = Pref(p.agent, p.loser, p.winner)
        if (reverse in livePrefs) {
            prefOps.remove(reverse)
            livePrefs.remove(reverse)
        }
        if (p !in livePrefs) {
            prefOps.add(p)
            livePrefs.add(p)
        }
    }

    private fun retractPref(p: Pref) = synchronized(state) {
        if (p in livePrefs) {
            prefOps.remove(p)
            livePrefs.remove(p)
        }
    }

    private fun handleFeatures(exchange: HttpExchange) {
        val sub = exchange.requestURI.path.removePrefix("/features").trim('/')
        when {
            exchange.requestMethod == "GET" && sub.isEmpty() -> {
                val algo = exchange.requestURI.query?.split("&")
                    ?.firstOrNull { it.startsWith("algo=") }?.substringAfter("=")
                if (algo != null && algo !in ALGOS)
                    return exchange.respond(400, """{"error":"algo must be one of $ALGOS"}""", "application/json")
                exchange.respond(
                    200,
                    """{"algo":${esc(algo ?: "mean")},"features":${featuresJson(algo)}}""",
                    "application/json",
                )
            }

            exchange.requestMethod == "GET" ->
                synchronized(state) {
                    val m = meta[sub] ?: return exchange.respond(404, """{"error":"no such feature"}""", "application/json")
                    val mine = livePrefs.filter { it.winner == sub || it.loser == sub }
                        .joinToString(",", "[", "]") { prefJson(it) }
                    exchange.respond(
                        200,
                        """{"id":${esc(sub)},"title":${esc(m.title)},"body":${esc(m.body)},"prefs":$mine}""",
                        "application/json",
                    )
                }

            exchange.requestMethod == "POST" && sub.isEmpty() -> {
                val json = exchange.jsonBody() ?: return exchange.respond(400, """{"error":"body must be a JSON object"}""", "application/json")
                val title = json.str("title", max = 200) ?: return exchange.respond(400, """{"error":"missing title"}""", "application/json")
                val id = json.str("id", max = 80)?.let { slug(it) } ?: slug(title)
                if (id.isEmpty()) return exchange.respond(400, """{"error":"id slug is empty"}""", "application/json")
                val body = json.str("body", max = 65536) ?: ""
                addFeature(id, title, body)   // same id = upsert of title/body
                exchange.respond(200, """{"id":${esc(id)}}""", "application/json")
            }

            exchange.requestMethod == "DELETE" && sub.isNotEmpty() -> {
                if (!removeFeature(sub)) return exchange.respond(404, """{"error":"no such feature"}""", "application/json")
                exchange.respond(200, """{"removed":${esc(sub)}}""", "application/json")
            }

            else -> exchange.respond(405, """{"error":"method not allowed"}""", "application/json")
        }
    }

    /**
     * The phase-1 worklist: features WITHOUT rank/score/wins/losses (so an
     * agent can't be biased by the collective ranking), ordered by the
     * calling agent's own coverage (least-covered first, random tiebreaks;
     * fully randomized without ?agent=), plus a suggested still-unvoted
     * pair, the agent's own prefs, and a phase-1 completion flag.
     */
    private fun handleTriage(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") return exchange.respond(405, """{"error":"GET only"}""", "application/json")
        val agent = exchange.requestURI.query?.split("&")
            ?.firstOrNull { it.startsWith("agent=") }
            ?.let { URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8).trim() }
            ?.takeIf { it.isNotEmpty() }
        synchronized(state) {
            val mine = if (agent == null) emptyList() else livePrefs.filter { it.agent == agent }
            val cover = mutableMapOf<String, Int>()
            mine.forEach { cover.merge(it.winner, 1, Int::plus); cover.merge(it.loser, 1, Int::plus) }
            // shuffle first, then stable-sort by own coverage: random within ties
            val ordered = meta.keys.shuffled().sortedBy { cover[it] ?: 0 }
            val voted = mine.map { setOf(it.winner, it.loser) }.toSet()
            // ponytail: O(n²) first-unvoted-pair scan; fine at backlog scale
            val next = ordered.asSequence()
                .flatMapIndexed { i, a -> ordered.drop(i + 1).asSequence().map { b -> a to b } }
                .firstOrNull { (a, b) -> setOf(a, b) !in voted }
            val features = ordered.joinToString(",", "[", "]") { id ->
                """{"id":${esc(id)},"title":${esc(meta.getValue(id).title)},""" +
                        """"comparisons":${votes[id] ?: 0},"mine":${cover[id] ?: 0}}"""
            }
            val minePrefs = mine.sortedWith(compareBy({ it.winner }, { it.loser }))
                .joinToString(",", "[", "]") { """{"winner":${esc(it.winner)},"loser":${esc(it.loser)}}""" }
            val complete = meta.size >= 2 && meta.keys.all { (cover[it] ?: 0) >= 2 }
            exchange.respond(
                200,
                """{"features":$features,""" +
                        """"next":${next?.let { (a, b) -> """{"a":${esc(a)},"b":${esc(b)}}""" } ?: "null"},""" +
                        """"prefs":$minePrefs,"phase1Complete":$complete}""",
                "application/json",
            )
        }
    }

    private fun handlePrefer(exchange: HttpExchange) {
        if (exchange.requestMethod != "POST") return exchange.respond(405, """{"error":"POST only"}""", "application/json")
        val json = exchange.jsonBody() ?: return exchange.respond(400, """{"error":"body must be a JSON object"}""", "application/json")
        val agent = json.str("agent", max = 40) ?: return exchange.respond(400, """{"error":"missing agent"}""", "application/json")
        val winner = json.str("winner", max = 80) ?: return exchange.respond(400, """{"error":"missing winner"}""", "application/json")
        val loser = json.str("loser", max = 80) ?: return exchange.respond(400, """{"error":"missing loser"}""", "application/json")
        if (winner == loser) return exchange.respond(400, """{"error":"winner and loser must differ"}""", "application/json")
        val retract = json["retract"]?.jsonPrimitive?.content == "true"
        synchronized(state) {
            if (meta[winner] == null || meta[loser] == null)
                return exchange.respond(400, """{"error":"winner and loser must be existing feature ids"}""", "application/json")
        }
        val p = Pref(agent, winner, loser)
        if (retract) retractPref(p) else applyPref(p)
        exchange.respond(200, """{"ok":true}""", "application/json")
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    // ── json ─────────────────────────────────────────────────────────────

    // `esc` is :demo:shell's shared JsonPrimitive-backed escaper (T12 finding
    // 5) — this file's private copy was its reference implementation and is
    // now the shared one, imported above.

    private fun num(d: Double) = "%.4f".format(Locale.ROOT, d)

    private fun prefJson(p: Pref) =
        """{"agent":${esc(p.agent)},"winner":${esc(p.winner)},"loser":${esc(p.loser)}}"""

    /** algo = null/"mean" → the kernel-operator pipeline; otherwise a RatingCell's folded output. */
    private fun featuresJson(algo: String? = null): String = synchronized(state) {
        val scores = if (algo == null || algo == "mean") score else algoScores[algo] ?: emptyMap()
        val wins = prefs.groupingBy { it.winner }.eachCount()
        val losses = prefs.groupingBy { it.loser }.eachCount()
        val ranked = features.filter { scores.containsKey(it) }
            .sortedWith(compareByDescending<String> { scores.getValue(it) }
                .thenByDescending { votes[it] ?: 0L }.thenBy { it })
        val unranked = (features - scores.keys).sorted()
        (ranked.mapIndexed { i, id -> Triple(id, i + 1, scores.getValue(id)) } +
                unranked.map { Triple(it, null, null) })
            .joinToString(",", "[", "]") { (id, rank, s) ->
                """{"rank":${rank ?: "null"},"id":${esc(id)},"title":${esc(meta[id]?.title ?: id)},""" +
                        """"score":${s?.let { num(it) } ?: "null"},"wins":${wins[id] ?: 0},""" +
                        """"losses":${losses[id] ?: 0},"comparisons":${votes[id] ?: 0}}"""
            }
    }

    private fun stateJson(): String = synchronized(state) {
        val prefList = prefs.sortedWith(compareBy({ it.agent }, { it.winner }, { it.loser }))
            .joinToString(",", "[", "]") { prefJson(it) }
        """{"features":${featuresJson()},"prefs":$prefList}"""
    }

    private fun HttpExchange.jsonBody() = try {
        Json.parseToJsonElement(requestBody.readBytes().decodeToString()).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.str(key: String, max: Int): String? =
        (this[key] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() && it.length <= max }

    fun start(): TriageApp = apply {
        shell.start()
        inspectorOptions?.let { inspector = it.serve(registry, mapOf("backlog-triage" to host)) }
    }

    fun stop() {
        inspector?.stop()
        shell.stop()
    }
}

fun slug(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(80)

fun seedFrom(app: TriageApp, dir: Path) {
    Files.list(dir).use { paths ->
        paths.filter { it.extension == "md" }.sorted().forEach { p ->
            val text = Files.readString(p)
            val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
                ?: p.nameWithoutExtension
            app.addFeature(slug(p.nameWithoutExtension), title.take(200), text)
        }
    }
}

fun main(args: Array<String>) {
    // InspectorFlag.parse first (3iv0w-D2): it strips its own tokens, then
    // this demo's own flag/positional reading runs over parsed.rest, so an
    // inspector flag's value is never mistaken for this demo's own.
    val parsed = InspectorFlag.parse(args)
    val port = demoPort(parsed.rest)
    val app = TriageApp(
        port,
        journalPath = parsed.rest.flag("--journal")?.let { Path.of(it) },
        inspector = parsed.options,
    ).start()
    parsed.rest.flag("--seed")?.let { seedFrom(app, Path.of(it)) }
    announcePort("http", app.boundPort)
    println("computenet backlog-triage: http://localhost:${app.boundPort}")
    parsed.options?.let { InspectorFlag.announce(app.inspector!!, it) }
}

private val PAGE = """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<title>backlog-triage — collective feature ranking</title>
<style>
  :root { --line: #e3e5e8; --ink: #1c1e21; --dim: #6b7280; --blue: #2563eb; --bar: #93c5fd; --neg: #fca5a5;
          --up: #16a34a; --down: #dc2626; }
  * { box-sizing: border-box; }
  body { font-family: system-ui, sans-serif; color: var(--ink); background: #fff; max-width: 1100px; margin: 2rem auto; padding: 0 1rem; }
  h1 { font-size: 1.25rem; } h1 small { color: var(--dim); font-weight: normal; font-size: .8rem; }
  .row { display: flex; gap: 1rem; flex-wrap: wrap; align-items: flex-start; }
  .card { border: 1px solid var(--line); border-radius: 10px; padding: .8rem 1rem; flex: 1; min-width: 320px; }
  .card h2 { font-size: .85rem; margin: 0 0 .5rem; color: var(--dim); text-transform: uppercase; letter-spacing: .04em; }
  #ranking { position: relative; }
  .rankrow { position: absolute; left: 0; right: 0; top: 0; height: 38px; background: #fff;
             border-bottom: 1px solid var(--line); will-change: transform; overflow: hidden;
             transition: transform .55s cubic-bezier(.22,1,.36,1), height .35s, opacity .3s; }
  .rankrow .main { display: flex; align-items: center; gap: .55rem; height: 38px; padding: 0 .3rem; cursor: pointer; }
  .rankrow .main:hover { background: #f8fafc; }
  .rankrow.open { height: 318px; z-index: 1; }
  .rankrow .detail { height: 280px; overflow-y: auto; border-top: 1px solid var(--line); background: #fafafa; }
  .rankrow .detail pre { margin: 0; padding: .6rem .8rem; font-size: .72rem; line-height: 1.45;
                         white-space: pre-wrap; word-break: break-word;
                         font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
  .rankrow.moving { z-index: 2; }
  .rankrow .pos { flex: 0 0 1.6rem; text-align: right; font-weight: 700; font-size: .85rem; color: var(--dim);
                  font-variant-numeric: tabular-nums; }
  .rankrow.top .pos { color: var(--ink); }
  .rankrow .delta { flex: 0 0 1.9rem; font-size: .68rem; font-weight: 600; opacity: 0; transition: opacity .4s;
                    white-space: nowrap; font-variant-numeric: tabular-nums; }
  .rankrow .delta.show { opacity: 1; }
  .rankrow .delta.up { color: var(--up); } .rankrow .delta.down { color: var(--down); }
  .rankrow .who { flex: 1 1 46%; min-width: 0; line-height: 1.15; }
  .rankrow .who b { display: block; font-size: .8rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rankrow .who small { display: block; color: var(--dim); font-size: .66rem;
                        white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .rankrow .barbox { flex: 1 1 38%; height: .55rem; background: #f3f4f6; border-radius: 4px; position: relative; overflow: hidden; }
  .rankrow .barbox::after { content: ''; position: absolute; left: 50%; top: 0; bottom: 0; width: 1px; background: var(--line); }
  .rankrow .bar { position: absolute; top: 0; bottom: 0; transition: left .55s, width .55s; border-radius: 4px; }
  .rankrow .score { flex: 0 0 3rem; text-align: right; font-size: .74rem; font-variant-numeric: tabular-nums; }
  .rankrow .wl { flex: 0 0 3.4rem; text-align: right; font-size: .66rem; color: var(--dim); font-variant-numeric: tabular-nums; }
  .rankrow.unranked .main { opacity: .5; }
  .rankrow.enter, .rankrow.leave { opacity: 0; }
  @keyframes flashUp   { from { background: #f0fdf4; } to { background: #fff; } }
  @keyframes flashDown { from { background: #fef2f2; } to { background: #fff; } }
  .rankrow.flash-up { animation: flashUp 1.2s ease-out; }
  .rankrow.flash-down { animation: flashDown 1.2s ease-out; }
  .pair { display: flex; gap: .6rem; }
  .pair button { flex: 1; border: 1px solid var(--line); background: #fff; border-radius: 8px; padding: .6rem; cursor: pointer;
                 font: inherit; font-size: .82rem; text-align: left; }
  .pair button:hover { border-color: var(--blue); }
  .pair button small { color: var(--dim); display: block; }
  .skip { margin-top: .4rem; border: none; background: none; color: var(--dim); cursor: pointer; }
  form { display: flex; flex-direction: column; gap: .4rem; margin-bottom: .6rem; }
  input, textarea { padding: .35rem .5rem; border: 1px solid var(--line); border-radius: 6px; font: inherit; }
  textarea { min-height: 4rem; font-size: .8rem; }
  form button { padding: .35rem .7rem; border: none; border-radius: 6px; background: var(--blue); color: #fff; cursor: pointer; align-self: flex-start; }
  #algoSel { font: inherit; font-size: .72rem; text-transform: none; letter-spacing: 0;
             border: 1px solid var(--line); border-radius: 5px; padding: .1rem .3rem; margin-right: .4rem; }
  .prefline { font-size: .82rem; margin: .2rem 0; }
  .prefline button { border: none; background: none; color: var(--dim); cursor: pointer; }
  #agent { width: 9rem; }
</style>
</head>
<body>
<h1>Backlog triage <small>pairwise value preferences, folded into one collective ranking, live</small></h1>
<div class="row">
  <div class="card" style="flex:2">
    <h2>Collective ranking
      <select id="algoSel">
        <option value="mean">mean of signs</option>
        <option value="elo">elo</option>
        <option value="bt">bradley&#8211;terry</option>
        <option value="trueskill">trueskill</option>
        <option value="glicko">glicko</option>
        <option value="wenglin">weng&#8211;lin (online bt)</option>
        <option value="wilson">wilson bound</option>
        <option value="meta">meta (borda of all)</option>
      </select>
      <small id="meta"></small></h2>
    <div id="ranking"></div>
  </div>
  <div class="card">
    <h2>You</h2>
    <form onsubmit="return false">as <input id="agent"></form>
    <h2>Which is more valuable?</h2>
    <div class="pair" id="pair"></div>
    <button class="skip" id="skip">show another pair</button>
    <h2>Submit feature</h2>
    <form id="featForm">
      <input id="featTitle" placeholder="title">
      <textarea id="featBody" placeholder="markdown body (optional)"></textarea>
      <button>Add</button>
    </form>
    <h2>Preferences</h2>
    <div id="prefs"></div>
  </div>
</div>
<script>
let state = { features: [], prefs: [] };
const agentInput = document.getElementById('agent');
agentInput.value = sessionStorage.agent ??= 'user-' + Math.random().toString(36).slice(2, 6);
agentInput.onchange = () => { sessionStorage.agent = agentInput.value.trim(); renderPair(); };
const me = () => agentInput.value.trim();
const post = (url, body) => fetch(url, { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body) });

const ROW = 38, EXP = 280; // px, keep in sync with .rankrow / .detail heights
const rows = new Map();
const bodies = new Map();
let prevIndex = new Map();
let expanded = null;
function toggleDetail(id) {
  expanded = expanded === id ? null : id;
  if (expanded === id && !bodies.has(id))
    fetch('/features/' + id).then(r => r.json()).then(j => {
      bodies.set(id, j.body || '(no body)');
      const row = rows.get(id);
      if (row && expanded === id) row.querySelector('.detail pre').textContent = bodies.get(id);
    });
  render();
}
function render() {
  const box = document.getElementById('ranking');
  const fs = boardFeatures || state.features;
  // bars: mean is naturally [-1,1]; other algos normalize to their range
  const rankedScores = fs.filter(f => f.score !== null).map(f => f.score);
  const lo = Math.min(...rankedScores), hi = Math.max(...rankedScores);
  const mid = (lo + hi) / 2, half = Math.max((hi - lo) / 2, 1e-9);
  const norm = s => algo === 'mean' ? s : (s - mid) / half;
  const fmt = s => algo === 'mean' ? (s > 0 ? '+' : '') + s.toFixed(2)
                 : Math.abs(s) >= 100 ? s.toFixed(0) : s.toFixed(2);
  const openIdx = fs.findIndex(f => f.id === expanded);
  box.style.height = (fs.length * ROW + (openIdx >= 0 ? EXP : 0)) + 'px';
  const y = i => (i * ROW + (openIdx >= 0 && i > openIdx ? EXP : 0));
  const seen = new Set();
  fs.forEach((f, i) => {
    seen.add(f.id);
    let row = rows.get(f.id), fresh = false;
    if (!row) {
      fresh = true;
      row = document.createElement('div');
      row.className = 'rankrow enter';
      row.innerHTML = '<div class="main"><div class="pos"></div><div class="delta"></div>' +
                      '<div class="who"><b></b><small></small></div>' +
                      '<div class="barbox"><div class="bar"></div></div><div class="score"></div><div class="wl"></div></div>' +
                      '<div class="detail"><pre></pre></div>';
      row.querySelector('.main').onclick = () => toggleDetail(f.id);
      // place without transition, then fade in
      row.style.transition = 'none';
      row.style.transform = 'translateY(' + y(i) + 'px)';
      rows.set(f.id, row); box.appendChild(row);
      requestAnimationFrame(() => requestAnimationFrame(() => {
        row.style.transition = ''; row.classList.remove('enter');
      }));
    }
    row.style.transform = 'translateY(' + y(i) + 'px)';
    row.classList.toggle('open', f.id === expanded);
    if (f.id === expanded) {
      const pre = row.querySelector('.detail pre');
      const want = bodies.get(f.id) ?? 'loading…';
      if (pre.textContent !== want) pre.textContent = want;
    }
    const was = prevIndex.get(f.id);
    if (!fresh && was !== undefined && was !== i) {
      const upBy = was - i;
      const d = row.querySelector('.delta');
      d.textContent = (upBy > 0 ? '▲' : '▼') + Math.abs(upBy);
      d.classList.toggle('up', upBy > 0);
      d.classList.toggle('down', upBy < 0);
      d.classList.add('show');
      row.classList.add('moving');                  // moving rows cross above resting ones
      row.classList.remove('flash-up', 'flash-down');
      void row.offsetWidth;                         // restart the flash animation
      row.classList.add(upBy > 0 ? 'flash-up' : 'flash-down');
      clearTimeout(row._deltaT);
      row._deltaT = setTimeout(() => { d.classList.remove('show'); row.classList.remove('moving'); }, 1600);
    }
    row.classList.toggle('unranked', f.rank === null);
    row.classList.toggle('top', f.rank !== null && f.rank <= 3);
    row.querySelector('.pos').textContent = f.rank ?? '·';
    const title = row.querySelector('.who b');
    title.textContent = f.title; title.title = f.title;
    row.querySelector('.who small').textContent = f.id;
    const bar = row.querySelector('.bar');
    if (f.score === null) { bar.style.width = '0'; }
    else {
      const n = norm(f.score);
      const pct = Math.abs(n) * 50;
      bar.style.width = pct + '%';
      bar.style.left = (n >= 0 ? 50 : 50 - pct) + '%';
      bar.style.background = n >= 0 ? 'var(--bar)' : 'var(--neg)';
    }
    row.querySelector('.score').textContent = f.score === null ? '—' : fmt(f.score);
    row.querySelector('.wl').textContent = f.wins + 'w·' + f.losses + 'l';
  });
  for (const [id, row] of rows) if (!seen.has(id)) {
    rows.delete(id);
    row.classList.add('leave');
    setTimeout(() => row.remove(), 350);
  }
  prevIndex = new Map(fs.map((f, i) => [f.id, i]));
  document.getElementById('meta').textContent =
    fs.length + ' features, ' + state.prefs.length + ' preferences';

  const prefsEl = document.getElementById('prefs'); prefsEl.innerHTML = '';
  for (const p of state.prefs) {
    const div = document.createElement('div'); div.className = 'prefline';
    div.append(p.agent + ': ' + p.winner + ' ≻ ' + p.loser + ' ');
    const x = document.createElement('button'); x.textContent = '×';
    x.onclick = () => post('/prefer', { agent: p.agent, winner: p.winner, loser: p.loser, retract: 'true' });
    div.appendChild(x); prefsEl.appendChild(div);
  }
  renderPair();
}

let currentPair = null;
function renderPair(fresh) {
  const el = document.getElementById('pair');
  const fs = state.features;
  if (fs.length < 2) { el.innerHTML = '<small>need at least two features</small>'; return; }
  const ids = new Set(fs.map(f => f.id));
  if (fresh || !currentPair || !currentPair.every(id => ids.has(id))) {
    // prefer a pair this agent hasn't voted on yet
    const voted = new Set(state.prefs.filter(p => p.agent === me())
      .map(p => [p.winner, p.loser].sort().join('|')));
    const pairs = [];
    const arr = fs.map(f => f.id);
    for (let i = 0; i < arr.length; i++) for (let j = i + 1; j < arr.length; j++) pairs.push([arr[i], arr[j]]);
    const open = pairs.filter(p => !voted.has([...p].sort().join('|')));
    const pool = open.length ? open : pairs;
    currentPair = pool[Math.floor(Math.random() * pool.length)];
  }
  el.innerHTML = '';
  const title = id => (state.features.find(f => f.id === id) || {}).title || id;
  for (const [a, b] of [[currentPair[0], currentPair[1]], [currentPair[1], currentPair[0]]]) {
    const btn = document.createElement('button');
    btn.innerHTML = '<b></b><small></small>';
    btn.querySelector('b').textContent = title(a);
    btn.querySelector('small').textContent = a;
    btn.onclick = () => { post('/prefer', { agent: me(), winner: a, loser: b }); renderPair(true); };
    el.appendChild(btn);
  }
}
document.getElementById('skip').onclick = () => renderPair(true);

document.getElementById('featForm').onsubmit = e => {
  e.preventDefault();
  const title = featTitle.value.trim();
  if (title) post('/features', { title, body: featBody.value });
  featTitle.value = ''; featBody.value = '';
};

// one vote fans out to several hub broadcasts milliseconds apart; coalesce
// the burst so it animates as a single reorder (tick reads latest state).
// Non-default algos re-rank via the API on each tick; the SSE stream stays
// the change signal.
let algo = 'mean';
let boardFeatures = null;
function tick() {
  if (algo === 'mean') { boardFeatures = null; render(); return; }
  fetch('/features?algo=' + algo).then(r => r.json())
    .then(j => { boardFeatures = j.features; render(); })
    .catch(() => { boardFeatures = null; render(); });
}
document.getElementById('algoSel').onchange = e => { algo = e.target.value; tick(); };
let renderPending = false;
new EventSource('/events').onmessage = e => {
  state = JSON.parse(e.data);
  if (renderPending) return;
  renderPending = true;
  setTimeout(() => { renderPending = false; tick(); }, 60);
};
</script>
</body>
</html>
"""
