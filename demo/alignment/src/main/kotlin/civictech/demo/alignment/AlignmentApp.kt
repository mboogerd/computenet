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

/**
 * A topic's creator-defined dimension (presentation-only; its weight and direction live in the
 * `weights` MapCell as a [DimConfig]). [lowLabel]/[highLabel] are the facilitator's anchors — what a
 * rating of 1 and of 9 mean on it (epic computenet-9y79n R3); empty when unset.
 */
internal data class Dimension(val name: String, val lowLabel: String = "", val highLabel: String = "")

/** Who may add ideas to a topic (computenet-k1d4g-D5): everyone, or only its facilitator (the creator). */
internal enum class IdeaPolicy(val wire: String) { EVERYONE("everyone"), FACILITATOR("facilitator") }

/**
 * When the Board is shown (epic computenet-9y79n decision 1): after the participant rated
 * everything (the default), or only after the facilitator reveals it. The PAGE enforces it — the
 * server never withholds the aggregate, which is one shared frame for everyone.
 */
internal enum class BoardVisibility(val wire: String) { AFTER_RATING("after-rating"), AFTER_REVEAL("after-reveal") }

/**
 * A topic: write-side index, journaled, never in the dataflow. Dims and ideas are keyed by their slug
 * ids. The facilitator settings are mutable; a v1 topic line (none of them) replays to the defaults.
 */
internal class Topic(
    val id: TopicId,
    val title: String,
    val creator: String,
    var ideaPolicy: IdeaPolicy = IdeaPolicy.EVERYONE,
    var boardVisibility: BoardVisibility = BoardVisibility.AFTER_RATING,
) {
    val dims = TreeMap<String, Dimension>()
    val ideas = TreeMap<String, Idea>()
    val notes = TreeMap<String, Note>()
    var revealed: Boolean = false
}

private val Direction.wire: String get() = name.lowercase()

/** An idea's presentation fields (write-side index, never in the dataflow). */
internal data class Idea(val id: String, val title: String, val description: String, val proposer: String)

/**
 * A per-idea discussion note ("what the team decided", computenet-w0i5h-D2). Kept on [Topic.notes]
 * keyed by idea id rather than as an [Idea] field: the `idea` journal op replaces `topic.ideas[id]`
 * wholesale (it is also the edit path, [AlignmentApp]'s `putIdea`), so a note on [Idea] would be
 * dropped by replaying an edit recorded after the note.
 */
internal data class Note(val text: String, val author: String)

/**
 * Whose pairwise judgements on which dimension (k6rrk-D2): one participant's set on one dimension of
 * one topic, the unit [PairwiseFit] re-fits. Ordered by [AlignmentApp]'s `JUDGE_ORDER`.
 */
internal data class JudgeKey(val topic: TopicId, val dim: String, val participant: String)

/** A judgement outcome's wire string, on the API and in the journal: "a", "b", "equal". */
private val Outcome.wire: String get() = name.lowercase()

/** An HTTP failure: answered as `{"error": error}` with [status]. */
private class Fail(val status: Int, val error: String) : RuntimeException(error, null, false, false)

private fun fail(status: Int, error: String): Nothing = throw Fail(status, error)

/**
 * alignment: participants rate ideas on a topic's creator-defined dimensions
 * (continuous [1, 9] sliders, held as thousandths — [RatingScale]); the dataflow of [AlignmentPipeline] folds the ratings into
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
    private val ratings = HashMap<RatingKey, Int>() // thousandths (RatingScale)
    private val weights = HashMap<DimKey, DimConfig>() // every direction VALUE until the journal/API carry one
    // pairwise judgements (k6rrk-D2): per (topic, dim, participant), keyed by the unordered pair id "min|max"
    private val judgements = TreeMap<JudgeKey, TreeMap<String, Judgement>>(JUDGE_ORDER)

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
            // `/t/{id}` is the per-topic page URL (computenet-k1d4g-D8): same page, any id
            val path = ex.requestURI.path
            if (path == "/" || path.startsWith("/t/")) ex.respond(200, PAGE, "text/html; charset=utf-8")
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
        // every field added after v1 is optional on replay: absent → the v1 meaning (k1d4g-D5)
        fun direction() = if (s("direction").isEmpty()) Direction.VALUE else parseDirection(s("direction"))
        when (s("op")) {
            "topic" -> createTopic(
                TopicId(s("id")), s("title"), s("creator"),
                if (s("ideas").isEmpty()) IdeaPolicy.EVERYONE else parseWire(s("ideas"), IdeaPolicy.entries) { it.wire },
                if (s("boardVisibility").isEmpty()) BoardVisibility.AFTER_RATING
                else parseWire(s("boardVisibility"), BoardVisibility.entries) { it.wire },
            )
            "dimension" -> addDimension(
                t(), s("id"), Dimension(s("name"), s("lowLabel"), s("highLabel")),
                DimConfig(s("weight").toDouble(), direction()),
            )
            "undimension" -> removeDimension(t(), s("id"))
            "weight" -> setWeight(t(), s("dim"), s("weight").toDouble())
            "direction" -> setDirection(t(), s("dim"), direction())
            "labels" -> setLabels(t(), s("dim"), s("lowLabel"), s("highLabel"))
            "policy" -> setPolicy(t(), parseWire(s("ideas"), IdeaPolicy.entries) { it.wire })
            "visibility" -> setVisibility(t(), parseWire(s("boardVisibility"), BoardVisibility.entries) { it.wire })
            "reveal" -> reveal(t())
            "idea" -> addIdea(t(), Idea(s("id"), s("title"), s("description"), s("proposer")))
            "unidea" -> removeIdea(t(), s("id"))
            "note" -> setNote(t(), s("idea"), s("text"), s("author"))
            "rate" -> rate(rk(), RatingScale.toMilli(s("value").toDouble())) // v1 integer lines parse too
            "unrate" -> unrate(rk())
            "judge" -> judge(
                JudgeKey(t(), s("dim"), s("participant")),
                Judgement(s("a"), s("b"), parseWire(s("outcome"), Outcome.entries) { it.wire }),
            )
            "unjudge" -> unjudge(JudgeKey(t(), s("dim"), s("participant")))
            else -> error("unknown journal op in line: $line")
        }
    }

    /** The topic line carries its initial facilitator settings (additive keys; a v1 line has none). */
    private fun createTopic(
        id: TopicId,
        title: String,
        creator: String,
        policy: IdeaPolicy,
        visibility: BoardVisibility,
    ) = synchronized(state) {
        topics[id.value] = Topic(id, title, creator, policy, visibility)
        record(
            """{"op":"topic","id":${esc(id.value)},"title":${esc(title)},"creator":${esc(creator)},""" +
                """"ideas":${esc(policy.wire)},"boardVisibility":${esc(visibility.wire)}}""",
        )
    }

    /**
     * A dimension's creation line carries its initial weight, direction and anchor labels, so replay
     * restores all of them from one line (a v1 line has neither direction nor labels → value, empty).
     */
    private fun addDimension(topic: TopicId, id: String, dim: Dimension, config: DimConfig) = synchronized(state) {
        topics.getValue(topic.value).dims[id] = dim
        weights[DimKey(topic, id)] = config
        record(
            """{"op":"dimension","topic":${esc(topic.value)},"id":${esc(id)},"name":${esc(dim.name)},""" +
                """"weight":${config.weight},"direction":${esc(config.direction.wire)},""" +
                """"lowLabel":${esc(dim.lowLabel)},"highLabel":${esc(dim.highLabel)}}""",
        )
        weightOps.put(DimKey(topic, id), config)
    }

    /** Cascades: unrates every rating on the dimension (journaled as `unrate`), then drops its weight row. */
    private fun removeDimension(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.dim == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        topics.getValue(topic.value).dims.remove(id)
        judgements.keys.removeIf { it.topic == topic && it.dim == id } // no line, no refit (k6rrk-D3)
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

    private fun setDirection(topic: TopicId, dim: String, direction: Direction) = synchronized(state) {
        val old = weights.getValue(DimKey(topic, dim))
        if (old.direction == direction) return@synchronized
        val config = old.copy(direction = direction)
        weights[DimKey(topic, dim)] = config
        record(
            """{"op":"direction","topic":${esc(topic.value)},"dim":${esc(dim)},"direction":${esc(direction.wire)}}""",
        )
        weightOps.put(DimKey(topic, dim), config)
    }

    /** Anchor labels are presentation-only: the write-side index changes, the dataflow does not. */
    private fun setLabels(topic: TopicId, dim: String, low: String, high: String) = synchronized(state) {
        val dims = topics.getValue(topic.value).dims
        val old = dims.getValue(dim)
        if (old.lowLabel == low && old.highLabel == high) return@synchronized
        dims[dim] = old.copy(lowLabel = low, highLabel = high)
        record(
            """{"op":"labels","topic":${esc(topic.value)},"dim":${esc(dim)},""" +
                """"lowLabel":${esc(low)},"highLabel":${esc(high)}}""",
        )
    }

    private fun setPolicy(topic: TopicId, policy: IdeaPolicy) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.ideaPolicy == policy) return@synchronized
        t.ideaPolicy = policy
        record("""{"op":"policy","topic":${esc(topic.value)},"ideas":${esc(policy.wire)}}""")
    }

    private fun setVisibility(topic: TopicId, visibility: BoardVisibility) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.boardVisibility == visibility) return@synchronized
        t.boardVisibility = visibility
        record("""{"op":"visibility","topic":${esc(topic.value)},"boardVisibility":${esc(visibility.wire)}}""")
    }

    /** The facilitator's reveal: one-way topic state in the shared frame; the page decides what it unlocks. */
    private fun reveal(topic: TopicId) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.revealed) return@synchronized
        t.revealed = true
        record("""{"op":"reveal","topic":${esc(topic.value)}}""")
    }

    /** Also the edit: re-recording an `idea` line under the same id replaces its title/description (D5). */
    private fun addIdea(topic: TopicId, idea: Idea) = synchronized(state) {
        topics.getValue(topic.value).ideas[idea.id] = idea
        record(
            """{"op":"idea","topic":${esc(topic.value)},"id":${esc(idea.id)},"title":${esc(idea.title)},""" +
                """"description":${esc(idea.description)},"proposer":${esc(idea.proposer)}}""",
        )
    }

    /** Cascades: unrates every rating on the idea (journaled as `unrate`), then drops it and its note (no extra line: replaying `unidea` drops it the same way, w0i5h-D2). */
    private fun removeIdea(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.idea == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        topics.getValue(topic.value).ideas.remove(id)
        topics.getValue(topic.value).notes.remove(id)
        dropJudgementsOn(topic, id) // no line, no refit of the survivors (k6rrk-D3)
        record("""{"op":"unidea","topic":${esc(topic.value)},"id":${esc(id)}}""")
    }

    /**
     * A per-idea discussion note (w0i5h-D2/D3): empty [text] removes the entry (no line when already
     * absent); the same [text] and [author] as today's is a no-op (idempotent, like [rate]); otherwise
     * the note is stored/replaced and journaled.
     */
    private fun setNote(topic: TopicId, idea: String, text: String, author: String) = synchronized(state) {
        val notes = topics.getValue(topic.value).notes
        if (text.isEmpty()) {
            if (notes.remove(idea) == null) return@synchronized
        } else {
            if (notes[idea] == Note(text, author)) return@synchronized
            notes[idea] = Note(text, author)
        }
        record(
            """{"op":"note","topic":${esc(topic.value)},"idea":${esc(idea)},"text":${esc(text)},""" +
                """"author":${esc(author)}}""",
        )
    }

    /** [milli] is thousandths; journaled via [RatingScale.format], so an integer rating writes the v1 line. */
    private fun rate(key: RatingKey, milli: Int) = synchronized(state) {
        if (ratings[key] == milli) return@synchronized // idempotent: no journal line, no delta
        ratings[key] = milli
        record(
            """{"op":"rate","topic":${esc(key.topic.value)},"idea":${esc(key.idea)},"dim":${esc(key.dim)},""" +
                """"participant":${esc(key.participant)},"value":${RatingScale.format(milli)}}""",
        )
        ratingOps.put(key, Rating(key, milli))
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

    /**
     * Stores one pairwise judgement (k6rrk-D2/D3), normalized so `a < b` by id with the outcome
     * re-expressed, replacing any earlier judgement of the same unordered pair; an identical judgement
     * is a no-op (no line, no refit). Otherwise it journals a `judge` line, re-fits the participant's
     * whole set on the dimension with [PairwiseFit] and writes every derived rating through [rate] — the
     * slider's own op — so the ratings follow as ordinary `rate` lines, which replay as no-ops after the
     * `judge` line has re-derived the same values (the fit is deterministic over the set).
     */
    private fun judge(key: JudgeKey, judgement: Judgement) = synchronized(state) {
        val j = if (judgement.a <= judgement.b) judgement else Judgement(
            judgement.b, judgement.a,
            when (judgement.outcome) { Outcome.A -> Outcome.B; Outcome.B -> Outcome.A; Outcome.EQUAL -> Outcome.EQUAL },
        )
        val set = judgements.getOrPut(key) { TreeMap() }
        val pair = j.a + "|" + j.b
        if (set[pair] == j) return@synchronized
        set[pair] = j
        record(
            """{"op":"judge","topic":${esc(key.topic.value)},"dim":${esc(key.dim)},""" +
                """"participant":${esc(key.participant)},"a":${esc(j.a)},"b":${esc(j.b)},"outcome":${esc(j.outcome.wire)}}""",
        )
        PairwiseFit.ratings(set.values).forEach { (idea, milli) ->
            rate(RatingKey(key.topic, idea, key.dim, key.participant), milli)
        }
    }

    /** Clears the participant's judgements on the dimension (no line when none); the derived ratings stay (k6rrk-D3). */
    private fun unjudge(key: JudgeKey): Int = synchronized(state) {
        val cleared = judgements.remove(key)?.size ?: 0
        if (cleared == 0) return@synchronized 0
        record(
            """{"op":"unjudge","topic":${esc(key.topic.value)},"dim":${esc(key.dim)},""" +
                """"participant":${esc(key.participant)}}""",
        )
        cleared
    }

    /** The idea-removal cascade: every participant's judgements mentioning [idea], on every dimension. */
    private fun dropJudgementsOn(topic: TopicId, idea: String) {
        val it = judgements.entries.iterator()
        while (it.hasNext()) {
            val (key, set) = it.next()
            if (key.topic != topic) continue
            set.values.removeIf { j -> j.a == idea || j.b == idea }
            if (set.isEmpty()) it.remove()
        }
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

    /**
     * `/topics[/{t}[/ideas[/{i}[/note]]|/dimensions[/{d}]|/weights|/policy|/reveal|/rate|/judge|/me|/aggregate]]`,
     * dispatched here.
     */
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
            seg.size == 3 && seg[1] == "ideas" && method == "PUT" -> putIdea(topic, seg[2], ex.jsonBody())
            seg.size == 3 && seg[1] == "ideas" && method == "DELETE" -> deleteIdea(topic, seg[2], ex.query("creator"))
            seg.size == 4 && seg[1] == "ideas" && seg[3] == "note" && method == "PUT" ->
                putNote(topic, seg[2], ex.jsonBody())
            seg.size == 4 && seg[1] == "ideas" && seg[3] == "note" -> fail(405, "method not allowed")
            seg.size == 2 && seg[1] == "dimensions" && method == "POST" -> postDimension(topic, ex.jsonBody())
            seg.size == 3 && seg[1] == "dimensions" && method == "PUT" -> putDimension(topic, seg[2], ex.jsonBody())
            seg.size == 3 && seg[1] == "dimensions" && method == "DELETE" ->
                deleteDimension(topic, seg[2], ex.query("creator"))
            seg.size == 2 && seg[1] == "weights" && method == "PUT" -> putWeight(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "policy" && method == "PUT" -> putPolicy(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "reveal" && method == "POST" -> postReveal(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "rate" && method == "POST" -> postRate(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "judge" && method == "POST" -> postJudge(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "judge" && method == "DELETE" ->
                deleteJudge(topic, ex.query("participant"), ex.query("dim"))
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
        val policy = wireField(json, "ideas", IdeaPolicy.entries) { it.wire } ?: IdeaPolicy.EVERYONE
        val visibility = wireField(json, "boardVisibility", BoardVisibility.entries) { it.wire }
            ?: BoardVisibility.AFTER_RATING
        val dims = (json["dimensions"] as? JsonArray)?.map { d ->
            val o = d as? JsonObject
                ?: fail(400, "a dimension must be an object {name, weight?, direction?, lowLabel?, highLabel?}")
            newDimension(o)
        } ?: fail(400, "missing dimensions")
        if (dims.isEmpty()) fail(400, "a topic needs at least one dimension")
        if (dims.map { it.first }.toSet().size != dims.size) fail(409, "exists")
        synchronized(state) {
            if (id in topics) fail(409, "exists")
            val t = TopicId(id)
            createTopic(t, title, creator, policy, visibility)
            dims.forEach { (dimId, dim, config) -> addDimension(t, dimId, dim, config) }
        }
        return """{"id":${esc(id)}}"""
    }

    /** A dimension object `{name, weight?, direction?, lowLabel?, highLabel?}` → (slug id, presentation, config). */
    private fun newDimension(o: JsonObject): Triple<String, Dimension, DimConfig> {
        val name = o.str("name")?.takeIf { it.length <= 80 } ?: fail(400, "a dimension needs a name")
        val id = slug(name).ifEmpty { fail(400, "dimension slug is empty") }
        val direction = wireField(o, "direction", Direction.entries) { it.wire } ?: Direction.VALUE
        return Triple(id, Dimension(name, label(o, "lowLabel") ?: "", label(o, "highLabel") ?: ""), DimConfig(weight(o), direction))
    }

    private fun postIdea(topic: Topic, json: JsonObject): String {
        val proposer = name(json.str("participant"), "participant")
        val title = json.str("title")?.takeIf { it.length <= 200 } ?: fail(400, "missing title")
        val description = json.str("description")?.takeIf { it.length <= 4000 } ?: ""
        val id = slug(title).ifEmpty { fail(400, "title slug is empty") }
        synchronized(state) {
            // under the facilitator policy only the creator proposes (k1d4g-D6); checked with the add, atomically
            if (topic.ideaPolicy == IdeaPolicy.FACILITATOR && proposer != topic.creator) {
                fail(403, "only the topic creator may add ideas to this topic")
            }
            if (id in topic.ideas) fail(409, "exists")
            addIdea(topic.id, Idea(id, title, description, proposer))
        }
        return """{"id":${esc(id)}}"""
    }

    /** `{creator, title?, description?}`: edits in place — the id (a slug of the ORIGINAL title) never changes. */
    private fun putIdea(topic: Topic, id: String, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val title = if ("title" in json) {
            json.str("title")?.takeIf { it.length <= 200 } ?: fail(400, "title must be 1..200 characters")
        } else null
        val description = if ("description" in json) {
            (json["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.length <= 4000 } ?: fail(400, "description must be a string of at most 4000 characters")
        } else null
        if (title == null && description == null) fail(400, "nothing to change: give title and/or description")
        synchronized(state) {
            val old = topic.ideas[id] ?: fail(404, "no such idea")
            val new = old.copy(title = title ?: old.title, description = description ?: old.description)
            if (new != old) addIdea(topic.id, new)
        }
        return """{"id":${esc(id)}}"""
    }

    /**
     * `{participant, text}`: writes/clears the idea's discussion note (w0i5h-D1/D4). 404 unknown idea
     * first; then `participant` (1..40 chars) and `text` (a JSON string, trimmed, ≤ 4000 chars) are
     * validated; then, while the topic's idea policy is [IdeaPolicy.FACILITATOR], only the creator may
     * write — a deliberate reuse of the idea-authoring policy, not a second field. `text: ""` clears it.
     */
    private fun putNote(topic: Topic, id: String, json: JsonObject): String {
        if (id !in topic.ideas) fail(404, "no such idea")
        val participant = name(json.str("participant"), "participant")
        val text = (json["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
            ?.takeIf { it.length <= 4000 } ?: fail(400, "text must be a string of at most 4000 characters")
        if (topic.ideaPolicy == IdeaPolicy.FACILITATOR && participant != topic.creator) {
            fail(403, "only the topic creator may edit notes on this topic")
        }
        return synchronized(state) {
            setNote(topic.id, id, text, participant)
            val note = topic.notes[id]
            """{"id":${esc(id)},"note":${esc(note?.text ?: "")},"noteBy":${esc(note?.author ?: "")}}"""
        }
    }

    /** Creator-only (k1d4g-D6, a deliberate change from v1's open delete); an unknown idea is 404 first. */
    private fun deleteIdea(topic: Topic, id: String, creator: String?): String = synchronized(state) {
        if (id !in topic.ideas) fail(404, "no such idea")
        requireCreator(topic, creator)
        removeIdea(topic.id, id)
        """{"removed":${esc(id)}}"""
    }

    private fun postDimension(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        if (json.str("name") == null) fail(400, "missing name")
        val (id, dim, config) = newDimension(json)
        synchronized(state) {
            if (id in topic.dims) fail(409, "exists")
            addDimension(topic.id, id, dim, config)
        }
        return """{"id":${esc(id)}}"""
    }

    /**
     * `{creator, weight?, direction?, lowLabel?, highLabel?}`: 403 non-creator, 400 on a bad value or
     * when no field is given, 404 unknown dimension — all before any write. A label left out keeps its value.
     */
    private fun putDimension(topic: Topic, dim: String, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val w = if ("weight" in json) weight(json) else null
        val direction = wireField(json, "direction", Direction.entries) { it.wire }
        val low = label(json, "lowLabel")
        val high = label(json, "highLabel")
        if (w == null && direction == null && low == null && high == null) {
            fail(400, "nothing to change: give weight, direction, lowLabel and/or highLabel")
        }
        synchronized(state) {
            val old = topic.dims[dim] ?: fail(404, "no such dimension")
            w?.let { setWeight(topic.id, dim, it) }
            direction?.let { setDirection(topic.id, dim, it) }
            if (low != null || high != null) setLabels(topic.id, dim, low ?: old.lowLabel, high ?: old.highLabel)
        }
        return """{"id":${esc(dim)}}"""
    }

    private fun deleteDimension(topic: Topic, id: String, creator: String?): String {
        requireCreator(topic, creator)
        synchronized(state) {
            if (id !in topic.dims) fail(404, "no such dimension")
            removeDimension(topic.id, id)
        }
        return """{"removed":${esc(id)}}"""
    }

    /** `{creator, ideas?, boardVisibility?}`: the topic's facilitator settings; 400 when neither is given. */
    private fun putPolicy(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val policy = wireField(json, "ideas", IdeaPolicy.entries) { it.wire }
        val visibility = wireField(json, "boardVisibility", BoardVisibility.entries) { it.wire }
        if (policy == null && visibility == null) fail(400, "nothing to change: give ideas and/or boardVisibility")
        synchronized(state) {
            policy?.let { setPolicy(topic.id, it) }
            visibility?.let { setVisibility(topic.id, it) }
        }
        return """{"ideas":${esc(topic.ideaPolicy.wire)},"boardVisibility":${esc(topic.boardVisibility.wire)}}"""
    }

    /** `{creator}`: the facilitator reveals the Board (idempotent; allowed in either visibility mode). */
    private fun postReveal(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        reveal(topic.id)
        return """{"revealed":true}"""
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
     * `value` a finite JSON number with 1 ≤ value ≤ 9 rates (rounded to
     * thousandths, [RatingScale]); `value: null` or `"retract": true` removes
     * the rating (D5: unrated is absence); anything else is a 400 and changes
     * nothing.
     */
    private fun postRate(topic: Topic, json: JsonObject): String {
        val participant = name(json.str("participant"), "participant")
        val idea = json.str("idea") ?: fail(400, "missing idea")
        val dim = json.str("dim") ?: fail(400, "missing dim")
        val retract = (json["retract"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true
        val raw = json["value"]
        val value: Int? = when {
            retract || raw is JsonNull -> null
            raw is JsonPrimitive && !raw.isString ->
                raw.content.toDoubleOrNull()?.takeIf(RatingScale::valid)?.let(RatingScale::toMilli)
                    ?: fail(400, "value must be a number 1..9 or null")
            else -> fail(400, "value must be a number 1..9 or null")
        }
        synchronized(state) {
            if (idea !in topic.ideas) fail(400, "no such idea")
            if (dim !in topic.dims) fail(400, "no such dimension")
            val key = RatingKey(topic.id, idea, dim, participant)
            if (value == null) unrate(key) else rate(key, value)
        }
        return """{"ok":true}"""
    }

    /**
     * `{participant, dim, a, b, outcome}` (k6rrk-D4): checks in order before any write — participant,
     * a known dim, two known ideas, `a != b`, outcome "a" | "b" | "equal" — then [judge]. Answers
     * `{"judged":N,"ratings":{idea:value}}`: the participant's judged pairs on the dim and the values
     * [PairwiseFit] derives from them, ideas in id order.
     */
    private fun postJudge(topic: Topic, json: JsonObject): String {
        val participant = name(json.str("participant"), "participant")
        val dim = json.str("dim")
        val a = json.str("a")
        val b = json.str("b")
        return synchronized(state) {
            if (dim == null || dim !in topic.dims) fail(400, "no such dimension")
            if (a == null || b == null || a !in topic.ideas || b !in topic.ideas) fail(400, "no such idea")
            if (a == b) fail(400, "a and b must differ")
            val outcome = wireField(json, "outcome", Outcome.entries) { it.wire }
                ?: fail(400, "outcome must be one of \"a\", \"b\", \"equal\"")
            val key = JudgeKey(topic.id, dim, participant)
            judge(key, Judgement(a, b, outcome))
            val set = judgements[key].orEmpty()
            val derived = PairwiseFit.ratings(set.values).entries
                .joinToString(",", "{", "}") { (idea, milli) -> "${esc(idea)}:${RatingScale.format(milli)}" }
            """{"judged":${set.size},"ratings":$derived}"""
        }
    }

    /** `?participant=&dim=`: clears that participant's judgements on the dim (k6rrk-D4); 400 when dim is missing or unknown. */
    private fun deleteJudge(topic: Topic, participant: String?, dim: String?): String {
        val who = name(participant, "participant")
        return synchronized(state) {
            if (dim.isNullOrEmpty() || dim !in topic.dims) fail(400, "no such dimension")
            """{"cleared":${unjudge(JudgeKey(topic.id, dim, who))}}"""
        }
    }

    private fun requireCreator(topic: Topic, creator: String?) {
        if (creator == null || creator.trim() != topic.creator) fail(403, "only the topic creator may do this")
    }

    /** A participant or creator name: free text, non-empty, ≤ 40 chars. */
    private fun name(raw: String?, what: String): String =
        raw?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: fail(400, "$what must be 1..40 characters")

    /** An enum-valued field by its wire string: absent → null; any other string or non-string → 400. */
    private fun <E> wireField(json: JsonObject, key: String, values: List<E>, wire: (E) -> String): E? {
        val raw = json[key] ?: return null
        val v = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
        return values.firstOrNull { v != null && wire(it) == v }
            ?: fail(400, "$key must be one of ${values.joinToString(", ") { "\"${wire(it)}\"" }}")
    }

    /** An anchor label: absent → null; otherwise a JSON string, trimmed, ≤ 80 chars ("" clears it). */
    private fun label(json: JsonObject, key: String): String? {
        val raw = json[key] ?: return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.length <= 80 }
            ?: fail(400, "$key must be a string of at most 80 characters")
    }

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
        """{"id":${esc(t.id.value)},"title":${esc(t.title)},"creator":${esc(t.creator)},""" +
            """"ideas":${esc(t.ideaPolicy.wire)},"boardVisibility":${esc(t.boardVisibility.wire)},""" +
            """"revealed":${t.revealed},"dimensions":""" +
            t.dims.entries.joinToString(",", "[", "]") { (id, d) ->
                val config = weights[DimKey(t.id, id)]
                """{"id":${esc(id)},"name":${esc(d.name)},"weight":${config?.weight?.let(::num) ?: "null"},""" +
                    """"direction":${config?.direction?.let { esc(it.wire) } ?: "null"},""" +
                    """"lowLabel":${esc(d.lowLabel)},"highLabel":${esc(d.highLabel)}}"""
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
                """"ratings":""" + mine.entries.joinToString(",", "{", "}") { (d, v) -> "${esc(d)}:${v?.let(RatingScale::format) ?: "null"}" } +
                ""","rated":${mine.values.count { it != null }},"total":${mine.size}}"""
        }
        // the caller's own pairwise judgements only (k6rrk-D5), sorted by (dim, pair id)
        val judged = judgements.entries.filter { (k, _) -> k.topic == topic.id && k.participant == participant }
            .flatMap { (k, set) -> set.values.map { k.dim to it } }
            .joinToString(",", "[", "]") { (d, j) ->
                """{"dim":${esc(d)},"a":${esc(j.a)},"b":${esc(j.b)},"outcome":${esc(j.outcome.wire)}}"""
            }
        """{"topic":${esc(topic.id.value)},"participant":${esc(participant)},"ideas":$ideas,"judgements":$judged}"""
    }

    /**
     * Ranked from the fusion read model (computenet-k1d4g-D4): ideas with a
     * non-null score by score desc, rating count desc, id asc; then every other
     * idea — a [Scored] whose score is null (it still carries its per-dimension
     * stats), or no [Scored] at all — by id with `"rank":null`.
     *
     * `participants` (top level) and each row's `raters` are COUNTS of distinct participants with a
     * live rating, read from the synchronous write-side [ratings] index (k1d4g-D7): no participant
     * name ever enters the aggregate. `value`/`cost` are the idea's weighted means per side, null when
     * that side is unrated; a byDim `contribution` is null on a cost dimension and whenever the score is.
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
        val live = ratings.keys.filter { it.topic == topic.id }
        val ratersOf = live.groupBy({ it.idea }, { it.participant }).mapValues { (_, who) -> who.toSet().size }
        fun byDim(s: Scored) = s.byDim.toSortedMap().entries.joinToString(",", "{", "}") { (d, st) ->
            """${esc(d)}:{"n":${st.n},"mean":${num(st.mean)},"stdev":${num(st.stdev)},""" +
                """"contribution":${s.contributions[d]?.let(::num) ?: "null"}}"""
        }
        fun tail(id: String, s: Scored?) =
            """"value":${s?.value?.let(::num) ?: "null"},"cost":${s?.cost?.let(::num) ?: "null"},""" +
                """"raters":${ratersOf[id] ?: 0}}"""
        val rows = ranked.mapIndexed { i, (id, s) ->
            """{"rank":${i + 1},"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":${num(s.score!!)},"split":${s.split},"ratings":${count(s)},"byDim":${byDim(s)},""" +
                tail(id, s)
        } + topic.ideas.keys.filter { it !in rankedIds }.map { id ->
            val s = scored[IdeaKey(topic.id, id)] // present with a null score, or absent
            """{"rank":null,"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":null,"split":${s?.split ?: false},"ratings":${s?.let(::count) ?: 0},""" +
                """"byDim":${s?.let(::byDim) ?: "{}"},""" + tail(id, s)
        }
        val participants = live.mapTo(HashSet()) { it.participant }.size
        return """{"weights":$w,"participants":$participants,"ideas":${rows.joinToString(",", "[", "]")}}"""
    }

    private fun count(s: Scored): Long = s.byDim.values.sumOf { it.n }

    private fun stateJson(): String = synchronized(state) {
        val topicList = topics.values.joinToString(",", "[", "]") { topicJson(it) }
        val ideaList = topics.values.flatMap { t -> t.ideas.values.map { t to it } }
            .joinToString(",", "[", "]") { (t, i) ->
                val note = t.notes[i.id]
                """{"topic":${esc(t.id.value)},"id":${esc(i.id)},"title":${esc(i.title)},""" +
                    """"description":${esc(i.description)},"proposer":${esc(i.proposer)},""" +
                    """"note":${esc(note?.text ?: "")},"noteBy":${esc(note?.author ?: "")}}"""
            }
        val ratingList = ratings.entries.sortedWith(compareBy(RATING_ORDER) { it.key })
            .joinToString(",", "[", "]") { (k, v) ->
                """{"topic":${esc(k.topic.value)},"idea":${esc(k.idea)},"dim":${esc(k.dim)},""" +
                    """"participant":${esc(k.participant)},"value":${RatingScale.format(v)}}"""
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

    private fun parseDirection(v: String): Direction = parseWire(v, Direction.entries) { it.wire }

    private companion object {
        /** A journal line's enum value; an unknown one is a corrupt journal, not a request error. */
        fun <E> parseWire(v: String, values: List<E>, wire: (E) -> String): E =
            values.firstOrNull { wire(it) == v } ?: error("unknown value in journal: $v")

        val RATING_ORDER: Comparator<RatingKey> =
            compareBy({ it.topic.value }, { it.idea }, { it.dim }, { it.participant })

        val JUDGE_ORDER: Comparator<JudgeKey> = compareBy({ it.topic.value }, { it.dim }, { it.participant })
    }
}

fun main(args: Array<String>) {
    val app = AlignmentApp(demoPort(args), journalPath = args.flag("--journal")?.let { Path.of(it) }).start()
    println("computenet alignment: http://localhost:${app.boundPort}")
}
