package civictech.demo.alignment

import civictech.cell.data.view.MapHubCell
import civictech.cell.graph.lookup
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.shell.DemoShell
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.flag
import civictech.demo.shell.respond
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.OutputStream
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*

/** A topic's creator-defined dimension (presentation-only; its weight lives in the `weights` MapCell). */
internal data class Dimension(val name: String)

/** A topic: write-side index, journaled, never in the dataflow. Dims and ideas are keyed by their slug ids. */
internal class Topic(val id: TopicId, val title: String, val creator: String) {
    val dims = TreeMap<String, Dimension>()
    val ideas = TreeMap<String, Idea>()
}

/** An idea's presentation fields (write-side index, never in the dataflow). */
internal data class Idea(val id: String, val title: String, val description: String, val proposer: String)

/** An HTTP failure: answered as `{"error": error}` with [status]. */
private class Fail(val status: Int, val error: String) : RuntimeException(error, null, false, false)

private fun fail(status: Int, error: String): Nothing = throw Fail(status, error)

/**
 * alignment: participants rate ideas on a topic's creator-defined dimensions
 * (1..9 sliders); the dataflow of [AlignmentPipeline] folds the ratings into
 * per-dimension statistics and a weighted per-idea score, and this app serves
 * the result as JSON + `/events` SSE (feature computenet-sigl0).
 *
 * Only ratings and weights enter the dataflow (`Refs.ratings`, `Refs.weights`).
 * Topics, dimensions and ideas are app-level write-side indices, as are the
 * synchronous mirrors [ratings]/[weights]: validation and cascades read THOSE,
 * never the async read model folded off the fusion hub ([scored]).
 *
 * Persistence is backlog-triage's app-level op journal: one JSON object per
 * line, DSYNC appends, replayed on boot through the same op functions the
 * handlers call with [journal] still null (so replay records nothing).
 *
 * Ranking and its tie-break (score desc, rating count desc, id asc) are
 * app-side (computenet-sigl0-D6), and every emitted list is sorted by its ids
 * so two instances over one journal serve byte-identical `/state`.
 */
class AlignmentApp(port: Int = 8080, private val journalPath: Path? = null) {
    private val registry = LocationRegistry()
    private val host = ManagedHost(registry = registry)
    private val manage = host.managementInlet.call
    private val refs = AlignmentPipeline.build(host)
    private val ratingOps = host.lookup(refs.ratings)!!.inlet.call
    private val weightOps = host.lookup(refs.weights)!!.inlet.call

    private val state = Object()

    // authoritative write-side indices (journaled)
    private val topics = TreeMap<String, Topic>()
    private val ratings = HashMap<RatingKey, Int>()
    private val weights = HashMap<DimKey, DimConfig>() // every direction VALUE until the journal/API carry one

    // async read model, folded off the fusion outlet
    private var scored: Map<IdeaKey, Scored> = emptyMap()

    private var journal: OutputStream? = null // null while replaying → record() no-ops

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    init {
        // one hub suffices: Scored carries the per-dimension n/mean/stdev
        val hub = MapHubCell<IdeaKey, Scored>({ m -> synchronized(state) { scored = m }; broadcast() })
        manage.spawn(hub)
        manage.connect(refs.fusion, "outlet", hub.ref, "inlet")

        journalPath?.let { p ->
            if (Files.exists(p)) Files.readAllLines(p).forEach { if (it.isNotBlank()) applyJournalLine(it) }
            Files.createDirectories(p.toAbsolutePath().parent)
            journal = Files.newOutputStream(
                p, StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.DSYNC,
            )
        }

        shell.route("/") { ex ->
            if (ex.requestURI.path == "/") ex.respond(200, PAGE, "text/html; charset=utf-8")
            else ex.respond(404, """{"error":"not found"}""", "application/json")
        }
        shell.route("/topics") { ex -> serve(ex) { handleTopics(ex) } }
        shell.route("/state") { it.respond(200, stateJson(), "application/json") }
        shell.sse("/events") { stateJson() }
    }

    // ── ops (shared by HTTP handlers and journal replay) ────────────────

    private fun record(line: String) {
        journal?.write((line + "\n").toByteArray())
    }

    private fun applyJournalLine(line: String) {
        val j = Json.parseToJsonElement(line).jsonObject
        fun s(k: String) = (j[k] as? JsonPrimitive)?.content ?: ""
        fun t() = TopicId(s("topic"))
        fun rk() = RatingKey(t(), s("idea"), s("dim"), s("participant"))
        when (s("op")) {
            "topic" -> createTopic(TopicId(s("id")), s("title"), s("creator"))
            "dimension" -> addDimension(t(), s("id"), s("name"), s("weight").toDouble())
            "undimension" -> removeDimension(t(), s("id"))
            "weight" -> setWeight(t(), s("dim"), s("weight").toDouble())
            "idea" -> addIdea(t(), Idea(s("id"), s("title"), s("description"), s("proposer")))
            "unidea" -> removeIdea(t(), s("id"))
            "rate" -> rate(rk(), s("value").toInt())
            "unrate" -> unrate(rk())
            else -> error("unknown journal op in line: $line")
        }
    }

    private fun createTopic(id: TopicId, title: String, creator: String) = synchronized(state) {
        topics[id.value] = Topic(id, title, creator)
        record("""{"op":"topic","id":${esc(id.value)},"title":${esc(title)},"creator":${esc(creator)}}""")
    }

    /** A dimension's creation line carries its initial weight, so replay restores both from one line. */
    private fun addDimension(topic: TopicId, id: String, name: String, weight: Double) = synchronized(state) {
        topics.getValue(topic.value).dims[id] = Dimension(name)
        val config = DimConfig(weight, Direction.VALUE)
        weights[DimKey(topic, id)] = config
        record(
            """{"op":"dimension","topic":${esc(topic.value)},"id":${esc(id)},"name":${esc(name)},"weight":$weight}""",
        )
        weightOps.put(DimKey(topic, id), config)
    }

    /** Cascades: unrates every rating on the dimension (journaled as `unrate`), then drops its weight row. */
    private fun removeDimension(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.dim == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        topics.getValue(topic.value).dims.remove(id)
        weights.remove(DimKey(topic, id))
        record("""{"op":"undimension","topic":${esc(topic.value)},"id":${esc(id)}}""")
        weightOps.remove(DimKey(topic, id))
    }

    private fun setWeight(topic: TopicId, dim: String, weight: Double) = synchronized(state) {
        val old = weights[DimKey(topic, dim)]
        if (old?.weight == weight) return@synchronized
        val config = DimConfig(weight, old?.direction ?: Direction.VALUE)
        weights[DimKey(topic, dim)] = config
        record("""{"op":"weight","topic":${esc(topic.value)},"dim":${esc(dim)},"weight":$weight}""")
        weightOps.put(DimKey(topic, dim), config)
    }

    private fun addIdea(topic: TopicId, idea: Idea) = synchronized(state) {
        topics.getValue(topic.value).ideas[idea.id] = idea
        record(
            """{"op":"idea","topic":${esc(topic.value)},"id":${esc(idea.id)},"title":${esc(idea.title)},""" +
                """"description":${esc(idea.description)},"proposer":${esc(idea.proposer)}}""",
        )
    }

    /** Cascades: unrates every rating on the idea (journaled as `unrate`), then drops it. */
    private fun removeIdea(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.idea == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        topics.getValue(topic.value).ideas.remove(id)
        record("""{"op":"unidea","topic":${esc(topic.value)},"id":${esc(id)}}""")
    }

    private fun rate(key: RatingKey, value: Int) = synchronized(state) {
        if (ratings[key] == value) return@synchronized // idempotent: no journal line, no delta
        ratings[key] = value
        record(
            """{"op":"rate","topic":${esc(key.topic.value)},"idea":${esc(key.idea)},"dim":${esc(key.dim)},""" +
                """"participant":${esc(key.participant)},"value":$value}""",
        )
        ratingOps.put(key, Rating(key, value))
    }

    /** Unrated is absence (computenet-sigl0-D5): the key leaves the KeyedSetCell. */
    private fun unrate(key: RatingKey) = synchronized(state) {
        if (ratings.remove(key) == null) return@synchronized
        record(
            """{"op":"unrate","topic":${esc(key.topic.value)},"idea":${esc(key.idea)},"dim":${esc(key.dim)},""" +
                """"participant":${esc(key.participant)}}""",
        )
        ratingOps.remove(key)
    }

    // ── HTTP ─────────────────────────────────────────────────────────────

    private fun serve(ex: HttpExchange, handler: () -> String) {
        val body = try {
            handler()
        } catch (f: Fail) {
            return ex.respond(f.status, """{"error":${esc(f.error)}}""", "application/json")
        }
        ex.respond(200, body, "application/json")
    }

    /** `/topics[/{t}[/ideas[/{i}]|/dimensions[/{d}]|/weights|/rate|/me|/aggregate]]`, dispatched here. */
    private fun handleTopics(ex: HttpExchange): String {
        val seg = ex.requestURI.path.removePrefix("/topics").split('/').filter { it.isNotEmpty() }
        val method = ex.requestMethod
        if (seg.isEmpty()) return when (method) {
            "GET" -> synchronized(state) { topics.values.joinToString(",", "[", "]") { topicJson(it) } }
            "POST" -> postTopic(ex.jsonBody()).also { broadcast() } // a new topic reaches no hub either
            else -> fail(405, "method not allowed")
        }
        val topic = synchronized(state) { topics[seg[0]] } ?: fail(404, "no such topic")
        val result = when {
            seg.size == 2 && seg[1] == "ideas" && method == "POST" -> postIdea(topic, ex.jsonBody())
            seg.size == 3 && seg[1] == "ideas" && method == "DELETE" -> deleteIdea(topic, seg[2])
            seg.size == 2 && seg[1] == "dimensions" && method == "POST" -> postDimension(topic, ex.jsonBody())
            seg.size == 3 && seg[1] == "dimensions" && method == "DELETE" ->
                deleteDimension(topic, seg[2], ex.query("creator"))
            seg.size == 2 && seg[1] == "weights" && method == "PUT" -> putWeight(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "rate" && method == "POST" -> postRate(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "me" && method == "GET" ->
                return meJson(topic, name(ex.query("participant"), "participant"))
            seg.size == 2 && seg[1] == "aggregate" && method == "GET" ->
                return synchronized(state) { aggregateJson(topic) }
            else -> fail(if (seg.size <= 3) 405 else 404, "no such route or method")
        }
        broadcast() // write-side changes (topics, ideas) reach no hub; announce them here
        return result
    }

    private fun postTopic(json: JsonObject): String {
        val creator = name(json.str("creator"), "creator")
        val title = json.str("title")?.takeIf { it.length <= 200 } ?: fail(400, "missing title")
        val id = slug(title).ifEmpty { fail(400, "title slug is empty") }
        val dims = (json["dimensions"] as? JsonArray)?.map { d ->
            val o = d as? JsonObject ?: fail(400, "a dimension must be an object {name, weight?}")
            val name = o.str("name")?.takeIf { it.length <= 80 } ?: fail(400, "a dimension needs a name")
            Triple(slug(name).ifEmpty { fail(400, "dimension slug is empty") }, name, weight(o))
        } ?: fail(400, "missing dimensions")
        if (dims.isEmpty()) fail(400, "a topic needs at least one dimension")
        if (dims.map { it.first }.toSet().size != dims.size) fail(409, "exists")
        synchronized(state) {
            if (id in topics) fail(409, "exists")
            val t = TopicId(id)
            createTopic(t, title, creator)
            dims.forEach { (dimId, name, w) -> addDimension(t, dimId, name, w) }
        }
        return """{"id":${esc(id)}}"""
    }

    private fun postIdea(topic: Topic, json: JsonObject): String {
        val proposer = name(json.str("participant"), "participant")
        val title = json.str("title")?.takeIf { it.length <= 200 } ?: fail(400, "missing title")
        val description = json.str("description")?.takeIf { it.length <= 4000 } ?: ""
        val id = slug(title).ifEmpty { fail(400, "title slug is empty") }
        synchronized(state) {
            if (id in topic.ideas) fail(409, "exists")
            addIdea(topic.id, Idea(id, title, description, proposer))
        }
        return """{"id":${esc(id)}}"""
    }

    private fun deleteIdea(topic: Topic, id: String): String = synchronized(state) {
        if (id !in topic.ideas) fail(404, "no such idea")
        removeIdea(topic.id, id)
        """{"removed":${esc(id)}}"""
    }

    private fun postDimension(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val name = json.str("name")?.takeIf { it.length <= 80 } ?: fail(400, "missing name")
        val w = weight(json)
        val id = slug(name).ifEmpty { fail(400, "name slug is empty") }
        synchronized(state) {
            if (id in topic.dims) fail(409, "exists")
            addDimension(topic.id, id, name, w)
        }
        return """{"id":${esc(id)}}"""
    }

    private fun deleteDimension(topic: Topic, id: String, creator: String?): String {
        requireCreator(topic, creator)
        synchronized(state) {
            if (id !in topic.dims) fail(404, "no such dimension")
            removeDimension(topic.id, id)
        }
        return """{"removed":${esc(id)}}"""
    }

    private fun putWeight(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val dim = json.str("dim") ?: fail(400, "missing dim")
        val w = weight(json)
        synchronized(state) {
            if (dim !in topic.dims) fail(404, "no such dimension")
            setWeight(topic.id, dim, w)
        }
        return """{"dim":${esc(dim)},"weight":${num(w)}}"""
    }

    /**
     * `value` 1..9 (a JSON integer) rates; `value: null` or `"retract": true`
     * removes the rating (D5: unrated is absence); anything else is a 400 and
     * changes nothing.
     */
    private fun postRate(topic: Topic, json: JsonObject): String {
        val participant = name(json.str("participant"), "participant")
        val idea = json.str("idea") ?: fail(400, "missing idea")
        val dim = json.str("dim") ?: fail(400, "missing dim")
        val retract = (json["retract"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        val raw = json["value"]
        val value: Int? = when {
            retract || raw is JsonNull -> null
            raw is JsonPrimitive && !raw.isString -> raw.content.toIntOrNull()?.takeIf { it in 1..9 }
                ?: fail(400, "value must be an integer 1..9 or null")
            else -> fail(400, "value must be an integer 1..9 or null")
        }
        synchronized(state) {
            if (idea !in topic.ideas) fail(400, "no such idea")
            if (dim !in topic.dims) fail(400, "no such dimension")
            val key = RatingKey(topic.id, idea, dim, participant)
            if (value == null) unrate(key) else rate(key, value)
        }
        return """{"ok":true}"""
    }

    private fun requireCreator(topic: Topic, creator: String?) {
        if (creator == null || creator.trim() != topic.creator) fail(403, "only the topic creator may do this")
    }

    /** A participant or creator name: free text, non-empty, ≤ 40 chars. */
    private fun name(raw: String?, what: String): String =
        raw?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: fail(400, "$what must be 1..40 characters")

    /** A weight: absent → 1.0; otherwise a JSON number, finite and > 0 (D3). */
    private fun weight(json: JsonObject): Double {
        val raw = json["weight"] ?: return 1.0
        val w = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toDoubleOrNull()
        return w?.takeIf { it.isFinite() && it > 0.0 } ?: fail(400, "weight must be a number > 0")
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    // ── json ─────────────────────────────────────────────────────────────

    private fun num(d: Double) = "%.4f".format(Locale.ROOT, d)

    private fun topicJson(t: Topic): String =
        """{"id":${esc(t.id.value)},"title":${esc(t.title)},"creator":${esc(t.creator)},"dimensions":""" +
            t.dims.entries.joinToString(",", "[", "]") { (id, d) ->
                """{"id":${esc(id)},"name":${esc(d.name)},"weight":${weights[DimKey(t.id, id)]?.weight?.let(::num) ?: "null"}}"""
            } + "}"

    /**
     * The bias-safe view: only [participant]'s own ratings (null when unrated).
     * Deliberately carries no aggregate key and no other participant's name —
     * not even an idea's proposer or the topic's creator.
     */
    private fun meJson(topic: Topic, participant: String): String = synchronized(state) {
        val ideas = topic.ideas.values.joinToString(",", "[", "]") { idea ->
            val mine = topic.dims.keys.associateWith { ratings[RatingKey(topic.id, idea.id, it, participant)] }
            """{"id":${esc(idea.id)},"title":${esc(idea.title)},"description":${esc(idea.description)},""" +
                """"ratings":""" + mine.entries.joinToString(",", "{", "}") { (d, v) -> "${esc(d)}:${v ?: "null"}" } +
                ""","rated":${mine.values.count { it != null }},"total":${mine.size}}"""
        }
        """{"topic":${esc(topic.id.value)},"participant":${esc(participant)},"ideas":$ideas}"""
    }

    /**
     * Ranked from the fusion read model (computenet-k1d4g-D4): ideas with a
     * non-null score by score desc, rating count desc, id asc; then every other
     * idea — a [Scored] whose score is null (it still carries its per-dimension
     * stats), or no [Scored] at all — by id with `"rank":null`.
     */
    private fun aggregateJson(topic: Topic): String {
        val w = topic.dims.keys.joinToString(",", "{", "}") { d ->
            "${esc(d)}:${weights[DimKey(topic.id, d)]?.weight?.let(::num) ?: "null"}"
        }
        val ranked = topic.ideas.keys
            .mapNotNull { id -> scored[IdeaKey(topic.id, id)]?.takeIf { it.score != null }?.let { id to it } }
            .sortedWith(
                compareByDescending<Pair<String, Scored>> { it.second.score }
                    .thenByDescending { count(it.second) }
                    .thenBy { it.first },
            )
        val rankedIds = ranked.mapTo(HashSet()) { it.first }
        fun byDim(s: Scored) = s.byDim.toSortedMap().entries.joinToString(",", "{", "}") { (d, st) ->
            """${esc(d)}:{"n":${st.n},"mean":${num(st.mean)},"stdev":${num(st.stdev)},""" +
                """"contribution":${num(s.contributions[d] ?: 0.0)}}"""
        }
        val rows = ranked.mapIndexed { i, (id, s) ->
            """{"rank":${i + 1},"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":${num(s.score!!)},"split":${s.split},"ratings":${count(s)},"byDim":${byDim(s)}}"""
        } + topic.ideas.keys.filter { it !in rankedIds }.map { id ->
            val s = scored[IdeaKey(topic.id, id)] // present with a null score, or absent
            """{"rank":null,"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":null,"split":${s?.split ?: false},"ratings":${s?.let(::count) ?: 0},""" +
                """"byDim":${s?.let(::byDim) ?: "{}"}}"""
        }
        return """{"weights":$w,"ideas":${rows.joinToString(",", "[", "]")}}"""
    }

    private fun count(s: Scored): Long = s.byDim.values.sumOf { it.n }

    private fun stateJson(): String = synchronized(state) {
        val topicList = topics.values.joinToString(",", "[", "]") { topicJson(it) }
        val ideaList = topics.values.flatMap { t -> t.ideas.values.map { t to it } }
            .joinToString(",", "[", "]") { (t, i) ->
                """{"topic":${esc(t.id.value)},"id":${esc(i.id)},"title":${esc(i.title)},""" +
                    """"description":${esc(i.description)},"proposer":${esc(i.proposer)}}"""
            }
        val ratingList = ratings.entries.sortedWith(compareBy(RATING_ORDER) { it.key })
            .joinToString(",", "[", "]") { (k, v) ->
                """{"topic":${esc(k.topic.value)},"idea":${esc(k.idea)},"dim":${esc(k.dim)},""" +
                    """"participant":${esc(k.participant)},"value":$v}"""
            }
        val aggregates = topics.values.joinToString(",", "{", "}") { "${esc(it.id.value)}:${aggregateJson(it)}" }
        """{"topics":$topicList,"ideas":$ideaList,"ratings":$ratingList,"aggregates":$aggregates}"""
    }

    private fun HttpExchange.jsonBody(): JsonObject = try {
        Json.parseToJsonElement(requestBody.readBytes().decodeToString()) as? JsonObject
    } catch (_: Exception) {
        null
    } ?: fail(400, "body must be a JSON object")

    private fun HttpExchange.query(key: String): String? =
        requestURI.rawQuery?.split("&")?.firstOrNull { it.startsWith("$key=") }
            ?.let { URLDecoder.decode(it.substringAfter("="), Charsets.UTF_8).trim() }

    private fun Map<String, JsonElement>.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun start(): AlignmentApp = apply { shell.start() }

    fun stop() {
        shell.stop()
        journal?.close()
    }

    private companion object {
        val RATING_ORDER: Comparator<RatingKey> =
            compareBy({ it.topic.value }, { it.idea }, { it.dim }, { it.participant })
    }
}

fun main(args: Array<String>) {
    val app = AlignmentApp(demoPort(args), journalPath = args.flag("--journal")?.let { Path.of(it) }).start()
    println("computenet alignment: http://localhost:${app.boundPort}")
}
