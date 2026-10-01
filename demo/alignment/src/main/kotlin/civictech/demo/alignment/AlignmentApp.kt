package civictech.demo.alignment

import civictech.cell.data.view.MapHubCell
import civictech.cell.data.OrMapCell
import civictech.cell.durability.FileJournal
import civictech.cell.graph.ApplyContext
import civictech.cell.graph.lookup
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.demo.shell.DemoShell
import civictech.demo.shell.announcePort
import civictech.demo.shell.demoPort
import civictech.demo.shell.esc
import civictech.demo.shell.flag
import civictech.demo.shell.respond
import civictech.inspect.InspectorFlag
import civictech.inspect.InspectorFlag.serve
import civictech.inspect.InspectorServer
import com.sun.net.httpserver.HttpExchange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.Serializable
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.TimeUnit

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
 * A topic's synchronous write-side mirror. Its durable [TopicRecord] lives in the graph's `topics`
 * cell; dimensions and ideas are keyed here by their slug ids.
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
    val overrides = TreeMap<String, Double>()
    var revealed: Boolean = false

    /**
     * The gut-check round (teu97-D2, experimental, epic computenet-9y79n R14): off and a 3-dot
     * budget until the facilitator switches it on through `PUT /policy`.
     */
    var gutCheck: Boolean = false
    var dotBudget: Int = 3
}

private val Direction.wire: String get() = name.lowercase()

/** An idea's presentation fields, mirrored synchronously from the durable topic record. */
internal data class Idea(val id: String, val title: String, val description: String, val proposer: String)

/**
 * A per-idea discussion note ("what the team decided", computenet-w0i5h-D2). Kept on [Topic.notes]
 * keyed by idea id rather than as an [Idea] field: [AlignmentApp]'s `putIdea` replaces
 * `topic.ideas[id]` wholesale, so keeping the note separate avoids dropping it on an edit.
 */
internal data class Note(val text: String, val author: String)

/**
 * A participant's ABSOLUTE dot count on an idea within a topic's gut-check round (teu97-D1/D3).
 * Mirrored on [AlignmentApp.dots] from its durable graph cell. It is not linked into score
 * derivation: the score is computed with no knowledge dots exist.
 */
internal data class DotKey(val topic: TopicId, val idea: String, val participant: String) : Serializable

/**
 * Whose pairwise judgements on which dimension (k6rrk-D2): one participant's set on one dimension of
 * one topic, the unit [PairwiseFit] re-fits. Ordered by [AlignmentApp]'s `JUDGE_ORDER`.
 */
internal data class JudgeKey(val topic: TopicId, val dim: String, val participant: String)

/** A judgement outcome's wire string, on the API and in durable records: "a", "b", "equal". */
private val Outcome.wire: String get() = name.lowercase()

/** An HTTP failure: answered as `{"error": error}` with [status]. */
private class Fail(val status: Int, val error: String) : RuntimeException(error, null, false, false)

private fun fail(status: Int, error: String): Nothing = throw Fail(status, error)

private const val HOST_JOURNAL_ID = "host"
internal const val ALIGNMENT_JOURNAL_FILE = "host.journal"

/** The one host/context pair that owns an optional alignment journal. */
private data class AlignmentRuntime(
    val registry: LocationRegistry,
    val host: ManagedHost,
    val context: ApplyContext,
    val journal: FileJournal?,
    val refs: AlignmentPipeline.Refs,
    val recovered: Boolean,
) {
    companion object {
        fun create(journalDir: Path?): AlignmentRuntime {
            if (journalDir != null && Files.exists(journalDir) && !Files.isDirectory(journalDir)) {
                throw IllegalArgumentException("--journal must name a directory; legacy JSONL files are not migrated: $journalDir")
            }
            journalDir?.let(Files::createDirectories)
            val journal = journalDir?.resolve(ALIGNMENT_JOURNAL_FILE)?.toFile()?.let(::FileJournal)
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
                AlignmentPipeline.recovered(context)
            } else {
                AlignmentPipeline.build(context, journalId = journal?.let { HOST_JOURNAL_ID })
            }
            return AlignmentRuntime(registry, host, context, journal, refs, recovered)
        }
    }
}

/**
 * alignment: participants rate ideas on a topic's creator-defined dimensions
 * (continuous [1, 9] sliders, held as thousandths — [RatingScale]); the dataflow of [AlignmentPipeline] folds the ratings into
 * per-dimension statistics and a weighted per-idea score, and this app serves
 * the result as JSON + `/events` SSE (feature computenet-sigl0).
 *
 * Ratings and weights feed the derived scoring pipeline. Topics, dots and
 * judgements also live in graph cells but have no derived links. The app keeps
 * synchronous mirrors for validation and cascades; those paths never read the
 * async model folded off the fusion hub ([scored]).
 *
 * With `--journal <dir>`, durable inputs and write-side indices are journaled
 * kernel cells declared by [AlignmentPipeline]. Recovery first rebuilds their
 * topology, then restores their state and rebuilds these synchronous mirrors.
 *
 * Ranking and its tie-break (score desc, rating count desc, id asc) are
 * app-side (computenet-sigl0-D6), and every emitted list is sorted by its ids
 * so two instances over one journal serve byte-identical `/state`.
 */
class AlignmentApp(
    port: Int = 8080,
    journalPath: Path? = null,
    inspector: InspectorFlag.Options? = null,
) {
    private val inspectorOptions = inspector

    private val runtime = AlignmentRuntime.create(journalPath)
    private val registry = runtime.registry
    private val host = runtime.host
    private val context = runtime.context
    private val journal = runtime.journal
    private val manage = host.managementInlet.call
    private val refs = runtime.refs
    private val ratingOps = host.lookup(refs.ratings)!!.inlet.call
    private val weightOps = host.lookup(refs.weights)!!.inlet.call
    private val topicOps = host.lookup(refs.topics)!!.inlet.call
    private val dotOps = host.lookup(refs.dots)!!.inlet.call
    private val judgementOps = host.lookup(refs.judgements)!!.inlet.call

    private val state = Object()

    // authoritative write-side indices (journaled)
    private val topics = TreeMap<String, Topic>()
    private val ratings = HashMap<RatingKey, Int>() // thousandths (RatingScale)
    private val weights = HashMap<DimKey, DimConfig>() // every direction VALUE until the journal/API carry one
    private val dots = HashMap<DotKey, Int>() // absolute per-participant counts (teu97-D1), never in the dataflow
    // pairwise judgements (k6rrk-D2): per (topic, dim, participant), keyed by the unordered pair id "min|max"
    private val judgements = TreeMap<JudgeKey, TreeMap<String, Judgement>>(JUDGE_ORDER)

    // async read model, folded off the fusion outlet
    private var scored: Map<IdeaKey, Scored> = emptyMap()

    private val shell = DemoShell(port)

    val boundPort: Int get() = shell.boundPort

    /** Non-null once [start] has run with an opt-in `--inspect-port` (`InspectorFlag`). */
    var inspector: InspectorServer? = null
        private set

    init {
        if (runtime.recovered) {
            rebuildMirrors()
            host.checkpoint(checkNotNull(journal))
        }

        // one hub suffices: Scored carries the per-dimension n/mean/stdev
        val hub = MapHubCell<IdeaKey, Scored>({ m -> synchronized(state) { scored = m }; broadcast() })
        manage.spawn(hub)
        manage.connect(refs.fusion, "outlet", hub.ref, "inlet")

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

    private fun snapshot(ref: civictech.cell.CellRef): Serializable =
        checkNotNull(host.snapshotOf(ref).get(30, TimeUnit.SECONDS)) { "missing state snapshot for $ref" }

    private fun <K, V> recoveredMap(ref: civictech.cell.CellRef): Map<K, V> {
        val restored = OrMapCell<K, V>(ref)
        restored.restore(snapshot(ref))
        return restored.membership().associateWith { key -> checkNotNull(restored.value(key)) }
    }

    /** The cells are authoritative after recovery; mirrors exist only for synchronous validation/read paths. */
    @Suppress("UNCHECKED_CAST")
    private fun rebuildMirrors() = synchronized(state) {
        val ratingSnapshot = snapshot(refs.ratings.ref) as Map<String, Any>
        val current = ratingSnapshot.getValue("current") as Map<RatingKey, List<Any>>
        current.forEach { (key, entry) -> ratings[key] = (entry[0] as Rating).milli }

        weights.putAll(snapshot(refs.weights.ref) as Map<DimKey, DimConfig>)

        recoveredMap<String, TopicRecord>(refs.topics.ref).values.forEach { record ->
            topics[record.id] = record.toTopic()
        }
        dots.putAll(recoveredMap(refs.dots.ref))
        recoveredMap<JudgementRecordKey, JudgementRecord>(refs.judgements.ref).forEach { (key, value) ->
            val judgeKey = JudgeKey(TopicId(key.topic), key.dim, key.participant)
            judgements.getOrPut(judgeKey) { TreeMap() }[key.pair] = value.toJudgement()
        }
    }

    private fun TopicRecord.toTopic(): Topic = Topic(
        id = TopicId(id),
        title = title,
        creator = creator,
        ideaPolicy = parseWire(ideaPolicy, IdeaPolicy.entries) { it.wire },
        boardVisibility = parseWire(boardVisibility, BoardVisibility.entries) { it.wire },
    ).also { topic ->
        dimensions.forEach { (id, record) ->
            topic.dims[id] = Dimension(record.name, record.lowLabel, record.highLabel)
        }
        ideas.forEach { (id, record) -> topic.ideas[id] = Idea(record.id, record.title, record.description, record.proposer) }
        notes.forEach { (id, record) -> topic.notes[id] = Note(record.text, record.author) }
        topic.overrides.putAll(overrides)
        topic.revealed = revealed
        topic.gutCheck = gutCheck
        topic.dotBudget = dotBudget
    }

    private fun Topic.toRecord(): TopicRecord = TopicRecord(
        id = id.value,
        title = title,
        creator = creator,
        ideaPolicy = ideaPolicy.wire,
        boardVisibility = boardVisibility.wire,
        dimensions = dims.mapValues { (dimId, dim) ->
            val config = weights.getValue(DimKey(id, dimId))
            DimensionRecord(dim.name, dim.lowLabel, dim.highLabel, config.weight, config.direction.wire)
        },
        ideas = ideas.mapValues { (_, idea) -> IdeaRecord(idea.id, idea.title, idea.description, idea.proposer) },
        notes = notes.mapValues { (_, note) -> NoteRecord(note.text, note.author) },
        overrides = overrides.toMap(),
        revealed = revealed,
        gutCheck = gutCheck,
        dotBudget = dotBudget,
    )

    private fun persistTopic(topic: Topic) = topicOps.put(topic.id.value, topic.toRecord())

    private fun JudgementRecord.toJudgement(): Judgement =
        Judgement(a, b, parseWire(outcome, Outcome.entries) { it.wire })

    private fun judgementRecordKey(key: JudgeKey, pair: String) =
        JudgementRecordKey(key.topic.value, key.dim, key.participant, pair)

    // ── ops (HTTP handlers write cells, then update synchronous mirrors) ──

    /** Creates the durable topic record with its initial facilitator settings. */
    private fun createTopic(
        id: TopicId,
        title: String,
        creator: String,
        policy: IdeaPolicy,
        visibility: BoardVisibility,
    ) = synchronized(state) {
        val topic = Topic(id, title, creator, policy, visibility)
        topicOps.put(id.value, topic.toRecord())
        topics[id.value] = topic
    }

    /**
     * A dimension's durable state spans the weights cell and its enclosing topic record.
     */
    private fun addDimension(topic: TopicId, id: String, dim: Dimension, config: DimConfig) = synchronized(state) {
        weightOps.put(DimKey(topic, id), config)
        weights[DimKey(topic, id)] = config
        topics.getValue(topic.value).let { it.dims[id] = dim; persistTopic(it) }
    }

    /** Cascades: removes every rating and judgement on the dimension, then drops its weight row. */
    private fun removeDimension(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.dim == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        judgements.keys.filter { it.topic == topic && it.dim == id }.forEach(::removeJudgementSet)
        weightOps.remove(DimKey(topic, id))
        weights.remove(DimKey(topic, id))
        topics.getValue(topic.value).let { it.dims.remove(id); persistTopic(it) }
    }

    private fun setWeight(topic: TopicId, dim: String, weight: Double) = synchronized(state) {
        val old = weights[DimKey(topic, dim)]
        if (old?.weight == weight) return@synchronized
        val config = DimConfig(weight, old?.direction ?: Direction.VALUE)
        weightOps.put(DimKey(topic, dim), config)
        weights[DimKey(topic, dim)] = config
        persistTopic(topics.getValue(topic.value))
    }

    private fun setDirection(topic: TopicId, dim: String, direction: Direction) = synchronized(state) {
        val old = weights.getValue(DimKey(topic, dim))
        if (old.direction == direction) return@synchronized
        val config = old.copy(direction = direction)
        weightOps.put(DimKey(topic, dim), config)
        weights[DimKey(topic, dim)] = config
        persistTopic(topics.getValue(topic.value))
    }

    /** Anchor labels are presentation-only: the write-side index changes, the dataflow does not. */
    private fun setLabels(topic: TopicId, dim: String, low: String, high: String) = synchronized(state) {
        val dims = topics.getValue(topic.value).dims
        val old = dims.getValue(dim)
        if (old.lowLabel == low && old.highLabel == high) return@synchronized
        dims[dim] = old.copy(lowLabel = low, highLabel = high)
        persistTopic(topics.getValue(topic.value))
    }

    private fun setPolicy(topic: TopicId, policy: IdeaPolicy) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.ideaPolicy == policy) return@synchronized
        t.ideaPolicy = policy
        persistTopic(t)
    }

    private fun setVisibility(topic: TopicId, visibility: BoardVisibility) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.boardVisibility == visibility) return@synchronized
        t.boardVisibility = visibility
        persistTopic(t)
    }

    /**
     * The gut-check round's settings (teu97-D2): idempotent — the same (enabled, budget) pair writes
     * no frame, so a `PUT /policy` that only changes `ideas`/`boardVisibility` never touches this state.
     */
    private fun setGutCheck(topic: TopicId, enabled: Boolean, budget: Int) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.gutCheck == enabled && t.dotBudget == budget) return@synchronized
        t.gutCheck = enabled
        t.dotBudget = budget
        persistTopic(t)
    }

    /** The facilitator's reveal: one-way topic state in the shared frame; the page decides what it unlocks. */
    private fun reveal(topic: TopicId) = synchronized(state) {
        val t = topics.getValue(topic.value)
        if (t.revealed) return@synchronized
        t.revealed = true
        persistTopic(t)
    }

    /** Also the edit: replacing an idea under the same id updates its title/description (D5). */
    private fun addIdea(topic: TopicId, idea: Idea) = synchronized(state) {
        topics.getValue(topic.value).let { it.ideas[idea.id] = idea; persistTopic(it) }
    }

    /**
     * Cascades: removes every rating, dot and judgement on the idea, then drops the idea, its note
     * and its override from the durable topic record (teu97-D4, w0i5h-D2, w61az-D1, k6rrk-D3).
     */
    private fun removeIdea(topic: TopicId, id: String) = synchronized(state) {
        ratings.keys.filter { it.topic == topic && it.idea == id }.sortedWith(RATING_ORDER).forEach { unrate(it) }
        dots.keys.filter { it.topic == topic && it.idea == id }.toList().forEach { key ->
            dotOps.remove(key)
            dots.remove(key)
        }
        dropJudgementsOn(topic, id) // no line, no refit of the survivors (k6rrk-D3)
        topics.getValue(topic.value).let {
            it.ideas.remove(id)
            it.notes.remove(id)
            it.overrides.remove(id)
            persistTopic(it)
        }
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
        persistTopic(topics.getValue(topic.value))
    }

    /**
     * The facilitator's explicit consensus score per idea (epic computenet-9y79n R16, w61az-D1/D3):
     * kept on [Topic.overrides] keyed by idea id, like [Topic.notes], never as an [Idea] field — an
     * idea edit ([addIdea]) replaces `topic.ideas[id]` wholesale and would otherwise drop it. Never
     * enters the dataflow: it is a write-side ranking key read only by [aggregateJson]. Idempotent
     * (like [rate]): setting the same score twice journals no frame.
     */
    private fun setOverride(topic: TopicId, idea: String, score: Double) = synchronized(state) {
        val overrides = topics.getValue(topic.value).overrides
        if (overrides[idea] == score) return@synchronized
        overrides[idea] = score
        persistTopic(topics.getValue(topic.value))
    }

    /** Clearing an absent override journals no frame (idempotent, like [unrate]). */
    private fun clearOverride(topic: TopicId, idea: String) = synchronized(state) {
        val overrides = topics.getValue(topic.value).overrides
        if (overrides.remove(idea) == null) return@synchronized
        persistTopic(topics.getValue(topic.value))
    }

    /** [milli] is thousandths and is stored verbatim in the journaled ratings cell. */
    private fun rate(key: RatingKey, milli: Int) = synchronized(state) {
        if (ratings[key] == milli) return@synchronized // idempotent: no journal line, no delta
        ratingOps.put(key, Rating(key, milli))
        ratings[key] = milli
    }

    /** Unrated is absence (computenet-sigl0-D5): the key leaves the KeyedSetCell. */
    private fun unrate(key: RatingKey) = synchronized(state) {
        if (key !in ratings) return@synchronized
        ratingOps.remove(key)
        ratings.remove(key)
    }

    /**
     * [count] is the participant's ABSOLUTE dot count on the idea (teu97-D3); `count == 0` removes
     * the entry. Idempotent: the same count writes no frame. Recovery bypasses the enabled/budget
     * checks in [postDots] because the durable cell is authoritative.
     */
    private fun setDots(key: DotKey, count: Int) = synchronized(state) {
        if (count == 0) {
            if (key !in dots) return@synchronized
            dotOps.remove(key)
            dots.remove(key)
        } else {
            if (dots[key] == count) return@synchronized
            dotOps.put(key, count)
            dots[key] = count
        }
    }

    /**
     * Stores one pairwise judgement (k6rrk-D2/D3), normalized so `a < b` by id with the outcome
     * re-expressed, replacing any earlier judgement of the same unordered pair; an identical judgement
     * is a no-op (no frame, no refit). Otherwise it updates the durable judgement row, re-fits the
     * participant's whole set on the dimension with [PairwiseFit], and writes every derived rating
     * through [rate], the slider's own operation.
     */
    private fun judge(key: JudgeKey, judgement: Judgement) = synchronized(state) {
        val j = if (judgement.a <= judgement.b) judgement else Judgement(
            judgement.b, judgement.a,
            when (judgement.outcome) { Outcome.A -> Outcome.B; Outcome.B -> Outcome.A; Outcome.EQUAL -> Outcome.EQUAL },
        )
        val set = judgements.getOrPut(key) { TreeMap() }
        val pair = j.a + "|" + j.b
        if (set[pair] == j) return@synchronized
        judgementOps.put(judgementRecordKey(key, pair), JudgementRecord(j.a, j.b, j.outcome.wire))
        set[pair] = j
        PairwiseFit.ratings(set.values).forEach { (idea, milli) ->
            rate(RatingKey(key.topic, idea, key.dim, key.participant), milli)
        }
    }

    /** Clears the participant's judgements on the dimension; the derived ratings stay (k6rrk-D3). */
    private fun unjudge(key: JudgeKey): Int = synchronized(state) {
        val cleared = judgements[key]?.size ?: 0
        if (cleared == 0) return@synchronized 0
        removeJudgementSet(key)
        cleared
    }

    private fun removeJudgementSet(key: JudgeKey) {
        judgements[key]?.keys?.forEach { pair -> judgementOps.remove(judgementRecordKey(key, pair)) }
        judgements.remove(key)
    }

    /** The idea-removal cascade: every participant's judgements mentioning [idea], on every dimension. */
    private fun dropJudgementsOn(topic: TopicId, idea: String) {
        val it = judgements.entries.iterator()
        while (it.hasNext()) {
            val (key, set) = it.next()
            if (key.topic != topic) continue
            set.entries.removeIf { (pair, judgement) ->
                val remove = judgement.a == idea || judgement.b == idea
                if (remove) judgementOps.remove(judgementRecordKey(key, pair))
                remove
            }
            if (set.isEmpty()) it.remove()
        }
    }

    // ── triage seeding (feature computenet-i00bh) ────────────────────────

    /**
     * Creates the topic with [dims] when it is absent and answers true; a no-op
     * answering false when it already exists, whatever its dimensions now are —
     * a facilitator's later edits to a standing round are never undone by a
     * re-seed.
     */
    internal fun ensureTopic(
        id: TopicId,
        title: String,
        creator: String,
        dims: List<Triple<String, Dimension, DimConfig>>,
    ): Boolean = synchronized(state) {
        if (id.value in topics) return@synchronized false
        createTopic(id, title, creator, IdeaPolicy.FACILITATOR, BoardVisibility.AFTER_RATING)
        dims.forEach { (dimId, dim, config) -> addDimension(id, dimId, dim, config) }
        true
    }

    /**
     * Upserts an idea under an EXPLICIT [id] — the seeding path, where the id is
     * the bead id verbatim (`computenet-8x9`, already a valid [slug]) rather
     * than a slug of the title, which is [postIdea]'s rule. Phase 2 therefore
     * maps an ordered board back onto `bd` ids with no lookup table.
     *
     * Re-seeding an unchanged idea writes no journal frame, so a standing round's
     * journal grows only when the tracker actually changed.
     */
    internal fun seedIdea(topic: TopicId, id: String, title: String, description: String, proposer: String) =
        synchronized(state) {
            val ideas = topics.getValue(topic.value).ideas
            val idea = Idea(id, title.take(200), description.take(4000), proposer)
            if (ideas[id] != idea) addIdea(topic, idea)
        }

    /**
     * The seeding path's rating write: [value] on the `[1, 9]` scale, or null to
     * leave the slot UNRATED (absence, computenet-sigl0-D5) — which is how
     * [Jev]'s abstention reaches the board, rather than as a middling 5.
     */
    internal fun seedRating(topic: TopicId, idea: String, dim: String, participant: String, value: Double?) =
        synchronized(state) {
            val key = RatingKey(topic, idea, dim, participant)
            if (value == null) unrate(key) else rate(key, RatingScale.toMilli(value))
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
     * `/topics[/{t}[/ideas[/{i}[/note|/override]]|/dimensions[/{d}]|/weights|/policy|/reveal|/rate|/dots|/judge|/worklist|/me|/aggregate]]`,
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
            seg.size == 4 && seg[1] == "ideas" && seg[3] == "override" && method == "PUT" ->
                putOverride(topic, seg[2], ex.jsonBody())
            seg.size == 4 && seg[1] == "ideas" && seg[3] == "override" -> fail(405, "method not allowed")
            seg.size == 2 && seg[1] == "dimensions" && method == "POST" -> postDimension(topic, ex.jsonBody())
            seg.size == 3 && seg[1] == "dimensions" && method == "PUT" -> putDimension(topic, seg[2], ex.jsonBody())
            seg.size == 3 && seg[1] == "dimensions" && method == "DELETE" ->
                deleteDimension(topic, seg[2], ex.query("creator"))
            seg.size == 2 && seg[1] == "weights" && method == "PUT" -> putWeight(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "policy" && method == "PUT" -> putPolicy(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "reveal" && method == "POST" -> postReveal(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "rate" && method == "POST" -> postRate(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "dots" && method == "POST" -> postDots(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "judge" && method == "POST" -> postJudge(topic, ex.jsonBody())
            seg.size == 2 && seg[1] == "judge" && method == "DELETE" ->
                deleteJudge(topic, ex.query("participant"), ex.query("dim"))
            seg.size == 2 && seg[1] == "worklist" && method == "GET" ->
                return worklistJson(topic, name(ex.query("participant"), "participant"), ex.query("dim"))
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

    /**
     * `PUT /topics/{t}/ideas/{i}/override` `{creator, score}` (w61az-D5): 404 unknown idea first;
     * then [requireCreator]; then `score`: JSON `null` clears; a non-string finite number in (0, 9] —
     * the computed score's own range (w61az-D4) — sets; anything else (absent, a string, ≤0, >9) is a
     * 400 and changes nothing. The response carries only the resulting override — no participant name.
     */
    private fun putOverride(topic: Topic, id: String, json: JsonObject): String {
        if (id !in topic.ideas) fail(404, "no such idea")
        requireCreator(topic, json.str("creator"))
        val raw = json["score"]
        val score: Double? = when {
            raw is JsonNull ->
                null
            raw is JsonPrimitive && !raw.isString ->
                raw.content.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it <= 9.0 }
                    ?: fail(400, "score must be a number in (0, 9] or null")
            else -> fail(400, "score must be a number in (0, 9] or null")
        }
        synchronized(state) {
            if (score == null) clearOverride(topic.id, id) else setOverride(topic.id, id, score)
        }
        return """{"id":${esc(id)},"override":${score?.let(::num) ?: "null"}}"""
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

    /**
     * `{creator, ideas?, boardVisibility?, gutCheck?, dotBudget?}`: the topic's facilitator settings
     * (teu97-D2 adds the gut-check round's on/off switch and dot budget); 400 when none is given.
     */
    private fun putPolicy(topic: Topic, json: JsonObject): String {
        requireCreator(topic, json.str("creator"))
        val policy = wireField(json, "ideas", IdeaPolicy.entries) { it.wire }
        val visibility = wireField(json, "boardVisibility", BoardVisibility.entries) { it.wire }
        val gutCheck = booleanField(json, "gutCheck")
        val dotBudget = intField(json, "dotBudget", 1..20, "dotBudget must be an integer 1..20")
        if (policy == null && visibility == null && gutCheck == null && dotBudget == null) {
            fail(400, "nothing to change: give ideas, boardVisibility, gutCheck and/or dotBudget")
        }
        synchronized(state) {
            policy?.let { setPolicy(topic.id, it) }
            visibility?.let { setVisibility(topic.id, it) }
            if (gutCheck != null || dotBudget != null) {
                setGutCheck(topic.id, gutCheck ?: topic.gutCheck, dotBudget ?: topic.dotBudget)
            }
        }
        return """{"ideas":${esc(topic.ideaPolicy.wire)},"boardVisibility":${esc(topic.boardVisibility.wire)},""" +
            """"gutCheck":${topic.gutCheck},"dotBudget":${topic.dotBudget}}"""
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
     * `{participant, idea, count}` (teu97-D3): checked in order — `participant`; `idea` present in
     * `topic.ideas` else 400 "no such idea" (as [postRate]); `count` a non-negative JSON integer else
     * 400; the round enabled (`topic.gutCheck`) else 409; then budget — refused with 400 only when
     * `count` exceeds the participant's CURRENT count on this idea AND the new total across the
     * topic's other ideas would exceed `topic.dotBudget` (a decrease is always allowed, even over a
     * shrunk budget). `count == 0` removes the entry. Response carries `used`, the participant's total
     * across every idea after the write.
     */
    private fun postDots(topic: Topic, json: JsonObject): String {
        val participant = name(json.str("participant"), "participant")
        val idea = json.str("idea") ?: fail(400, "missing idea")
        val count = intField(json, "count", 0..Int.MAX_VALUE, "count must be an integer 0 or more")
            ?: fail(400, "count must be an integer 0 or more")
        return synchronized(state) {
            if (idea !in topic.ideas) fail(400, "no such idea")
            if (!topic.gutCheck) fail(409, "the gut check is not enabled on this topic")
            val key = DotKey(topic.id, idea, participant)
            val current = dots[key] ?: 0
            val usedElsewhere = dots.entries
                .filter { (k, _) -> k.topic == topic.id && k.participant == participant && k.idea != idea }
                .sumOf { it.value }
            if (count > current && usedElsewhere + count > topic.dotBudget) {
                fail(400, "over budget: ${usedElsewhere + count} of ${topic.dotBudget} dots")
            }
            setDots(key, count)
            """{"idea":${esc(idea)},"count":$count,"used":${usedElsewhere + count},"budget":${topic.dotBudget}}"""
        }
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

    /**
     * A JSON integer within [range]: absent → null; a string, a non-integer number, or one outside
     * [range] → 400 [message] (teu97-D2/D3: `dotBudget` and `count`).
     */
    private fun intField(json: JsonObject, key: String, range: IntRange, message: String): Int? {
        val raw = json[key] ?: return null
        val p = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString } ?: fail(400, message)
        val d = p.content.toDoubleOrNull() ?: fail(400, message)
        val i = d.toInt()
        return i.takeIf { d == i.toDouble() && it in range } ?: fail(400, message)
    }

    /** A JSON boolean: absent → null; anything else → 400 (teu97-D2: `gutCheck`). */
    private fun booleanField(json: JsonObject, key: String): Boolean? {
        val raw = json[key] ?: return null
        val p = (raw as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString } ?: fail(400, "$key must be a boolean")
        return when (p.content) {
            "true" -> true
            "false" -> false
            else -> fail(400, "$key must be a boolean")
        }
    }

    private fun broadcast() = shell.broadcast { stateJson() }

    // ── json ─────────────────────────────────────────────────────────────

    private fun num(d: Double) = "%.4f".format(Locale.ROOT, d)

    private fun topicJson(t: Topic): String =
        """{"id":${esc(t.id.value)},"title":${esc(t.title)},"creator":${esc(t.creator)},""" +
            """"ideas":${esc(t.ideaPolicy.wire)},"boardVisibility":${esc(t.boardVisibility.wire)},""" +
            """"revealed":${t.revealed},"gutCheck":${t.gutCheck},"dotBudget":${t.dotBudget},"dimensions":""" +
            t.dims.entries.joinToString(",", "[", "]") { (id, d) ->
                val config = weights[DimKey(t.id, id)]
                """{"id":${esc(id)},"name":${esc(d.name)},"weight":${config?.weight?.let(::num) ?: "null"},""" +
                    """"direction":${config?.direction?.let { esc(it.wire) } ?: "null"},""" +
                    """"lowLabel":${esc(d.lowLabel)},"highLabel":${esc(d.highLabel)}}"""
            } + "}"

    /**
     * The bias-safe, coverage-ordered worklist for one participant on one
     * dimension (feature computenet-i00bh), ported from `demo/backlog-triage`'s
     * `/triage` ([civictech.demo.backlogtriage] `TriageApp.kt:326`) rather than
     * imported — `:demo:alignment` does not depend on `:demo:backlog-triage`,
     * the same boundary [PairwiseFit] keeps against its `BradleyTerry`.
     *
     * Why this is not [meJson]: `/me` is a read of one participant's own state
     * in board order, which tells an agent nothing about WHERE to spend its next
     * judgement. This orders ideas by the caller's own coverage on this
     * dimension — least-judged first, shuffled within ties so two agents
     * starting together do not walk the same pairs — and suggests a still
     * unjudged pair.
     *
     * Bias-safety is the same contract as `/triage`'s: no score, no rank, no
     * aggregate, no other participant's ratings or name, not even an idea's
     * proposer. `mine` is the caller's own judgement count on the idea and
     * `rated` its own rating, because a rater may use the slider instead of the
     * pairwise path and needs to see which it has done.
     *
     * `phase1Complete` marks the coverage floor: at least two ideas, and every
     * idea in at least two of the caller's own judgements.
     */
    private fun worklistJson(topic: Topic, participant: String, dim: String?): String = synchronized(state) {
        if (dim.isNullOrEmpty() || dim !in topic.dims) fail(400, "no such dimension")
        val mine = judgements[JudgeKey(topic.id, dim, participant)].orEmpty()
        val cover = mutableMapOf<String, Int>()
        mine.values.forEach { cover.merge(it.a, 1, Int::plus); cover.merge(it.b, 1, Int::plus) }
        // shuffle first, then stable-sort by own coverage: random within ties
        val ordered = topic.ideas.keys.shuffled().sortedBy { cover[it] ?: 0 }
        val judged = mine.values.mapTo(HashSet()) { setOf(it.a, it.b) }
        // ponytail: O(n²) first-unjudged-pair scan; fine at backlog scale
        val next = ordered.asSequence()
            .flatMapIndexed { i, a -> ordered.drop(i + 1).asSequence().map { b -> a to b } }
            .firstOrNull { (a, b) -> setOf(a, b) !in judged }
        val ideas = ordered.joinToString(",", "[", "]") { id ->
            val idea = topic.ideas.getValue(id)
            val own = ratings[RatingKey(topic.id, id, dim, participant)]
            """{"id":${esc(id)},"title":${esc(idea.title)},"description":${esc(idea.description)},""" +
                """"mine":${cover[id] ?: 0},"rated":${own?.let(RatingScale::format) ?: "null"}}"""
        }
        val minePairs = mine.values.sortedWith(compareBy({ it.a }, { it.b }))
            .joinToString(",", "[", "]") { """{"a":${esc(it.a)},"b":${esc(it.b)},"outcome":${esc(it.outcome.wire)}}""" }
        val complete = topic.ideas.size >= 2 && topic.ideas.keys.all { (cover[it] ?: 0) >= 2 }
        """{"topic":${esc(topic.id.value)},"participant":${esc(participant)},"dim":${esc(dim)},""" +
            """"ideas":$ideas,"next":${next?.let { (a, b) -> """{"a":${esc(a)},"b":${esc(b)}}""" } ?: "null"},""" +
            """"judgements":$minePairs,"phase1Complete":$complete}"""
    }

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
                ""","rated":${mine.values.count { it != null }},"total":${mine.size},""" +
                """"dots":${dots[DotKey(topic.id, idea.id, participant)] ?: 0}}"""
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
     * Ranked by "effective" score (w61az-D6): `topic.overrides[id] ?: scored[id]?.score` — the
     * facilitator's consensus override when set, else the unchanged computed score. Ideas with a
     * non-null effective are ranked, by effective desc, rating count desc (0 with no [Scored]), id
     * asc; every other idea — no override and a null or absent computed score — is unranked, by id
     * with `"rank":null`. `score` keeps its computed meaning unchanged in every row; `override` is the
     * facilitator's raw value, null unless set. An override on an idea with no [Scored] entry (or a
     * null computed score) still ranks it, carrying `"score":null` and its otherwise-empty byDim/tail.
     *
     * `participants` (top level) and each row's `raters` are COUNTS of distinct participants with a
     * live rating, read from the synchronous write-side [ratings] index (k1d4g-D7): no participant
     * name ever enters the aggregate — the override carries none either (w61az-D2). `value`/`cost`/
     * `factor` are the idea's weighted means per side (`factor` the weighted GEOMETRIC mean, [0, 1]),
     * null when that side is unrated (factor dimensions, design contract 2026-09-22); a byDim
     * `contribution` is null on a cost or factor dimension and whenever the score is.
     */
    private fun aggregateJson(topic: Topic): String {
        val w = topic.dims.keys.joinToString(",", "{", "}") { d ->
            "${esc(d)}:${weights[DimKey(topic.id, d)]?.weight?.let(::num) ?: "null"}"
        }
        fun effective(id: String) = topic.overrides[id] ?: scored[IdeaKey(topic.id, id)]?.score
        val ranked = topic.ideas.keys
            .mapNotNull { id -> effective(id)?.let { id to it } }
            .sortedWith(
                compareByDescending<Pair<String, Double>> { it.second }
                    .thenByDescending { (id, _) -> scored[IdeaKey(topic.id, id)]?.let(::count) ?: 0L }
                    .thenBy { it.first },
            )
        val rankedIds = ranked.mapTo(HashSet()) { it.first }
        val live = ratings.keys.filter { it.topic == topic.id }
        val ratersOf = live.groupBy({ it.idea }, { it.participant }).mapValues { (_, who) -> who.toSet().size }
        // dots total per idea (teu97-D5): a COUNT summed over every participant, never a name
        val dotsOf = dots.entries.filter { (k, _) -> k.topic == topic.id }
            .groupBy({ (k, _) -> k.idea }, { (_, v) -> v }).mapValues { (_, vs) -> vs.sum() }
        fun byDim(s: Scored) = s.byDim.toSortedMap().entries.joinToString(",", "{", "}") { (d, st) ->
            """${esc(d)}:{"n":${st.n},"mean":${num(st.mean)},"stdev":${num(st.stdev)},""" +
                """"contribution":${s.contributions[d]?.let(::num) ?: "null"}}"""
        }
        // the Eisenhower 2x2 the single score necessarily flattens (feature
        // computenet-i00bh): derived per read from the same byDim means below,
        // null while either axis is unrated, and null on a topic configured
        // with other dimensions than importance/urgency
        fun tail(id: String, s: Scored?): String {
            val quadrant = s?.byDim?.let(Eisenhower::quadrantOf)
            return """"value":${s?.value?.let(::num) ?: "null"},"cost":${s?.cost?.let(::num) ?: "null"},""" +
                """"factor":${s?.factor?.let(::num) ?: "null"},"raters":${ratersOf[id] ?: 0},""" +
                """"dots":${dotsOf[id] ?: 0},"quadrant":${quadrant?.let { esc(it.wire) } ?: "null"}}"""
        }
        val rows = ranked.mapIndexed { i, (id, _) ->
            val s = scored[IdeaKey(topic.id, id)] // present with a score (possibly null), or absent
            """{"rank":${i + 1},"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":${s?.score?.let(::num) ?: "null"},"override":${topic.overrides[id]?.let(::num) ?: "null"},""" +
                """"split":${s?.split ?: false},"ratings":${s?.let(::count) ?: 0},"byDim":${s?.let(::byDim) ?: "{}"},""" +
                tail(id, s)
        } + topic.ideas.keys.filter { it !in rankedIds }.map { id ->
            val s = scored[IdeaKey(topic.id, id)] // present with a null score, or absent
            """{"rank":null,"id":${esc(id)},"title":${esc(topic.ideas.getValue(id).title)},""" +
                """"score":null,"override":null,"split":${s?.split ?: false},"ratings":${s?.let(::count) ?: 0},""" +
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

    fun start(): AlignmentApp = apply {
        shell.start()
        inspectorOptions?.let { inspector = it.serve(registry, mapOf("alignment" to host)) }
    }

    fun stop() {
        inspector?.stop()
        shell.stop()
    }

    private fun parseDirection(v: String): Direction = parseWire(v, Direction.entries) { it.wire }

    private companion object {
        /** A recovered record's enum value; an unknown one is corrupt durable state, not a request error. */
        fun <E> parseWire(v: String, values: List<E>, wire: (E) -> String): E =
            values.firstOrNull { wire(it) == v } ?: error("unknown durable value: $v")

        val RATING_ORDER: Comparator<RatingKey> =
            compareBy({ it.topic.value }, { it.idea }, { it.dim }, { it.participant })

        val JUDGE_ORDER: Comparator<JudgeKey> = compareBy({ it.topic.value }, { it.dim }, { it.participant })
    }
}

/**
 * The standing triage round's topic id (feature computenet-i00bh). One round,
 * re-seeded in place, rather than one per period: ratings accrue against a
 * moving candidate set, which keeps a fortnight of human judgement alive
 * instead of throwing it away. The cost is staleness, which Phase 2's
 * `triage-order.sh` is required to bound before any of this steers `/work`.
 */
internal val TRIAGE_TOPIC = TopicId("triage")

/**
 * Seeds (or re-seeds) the standing triage round from [source] and answers how
 * many candidates it saw (feature computenet-i00bh).
 *
 * Idempotent: the topic is created once, an unchanged idea writes no journal
 * frame, and an unchanged [Jev] rating is [AlignmentApp.rate]'s own no-op. So
 * this is safe to run on every boot, which is what makes a standing round
 * survivable — the journal carries the human ratings and the seed only tops up
 * what the tracker has added.
 *
 * ponytail: a candidate that LEAVES `bd ready` (closed, deferred, newly
 * blocked) is left on the board rather than removed. Removal is not cheap here —
 * `removeIdea` cascades through every participant's ratings and judgements, and
 * that is irreversible human input. Phase 2 intersects the order with a live
 * `bd ready` anyway, so a stale row costs a line on the board and nothing else.
 * Revisit if a round ever accumulates enough closed rows to be unreadable; the
 * upgrade path is a `closed` presentation flag, not a delete.
 */
internal fun seedBeadsTriage(
    app: AlignmentApp,
    source: CandidateSource,
    facilitator: String = "facilitator",
): Int {
    // The fetch comes BEFORE the first write (computenet-1f8b4): it is the step
    // that can fail — a bad workspace path, a `bd` that is not on PATH, a
    // refused export — and a failure after `ensureTopic` would journal a topic
    // and its dimensions and then leave that half-seeded round on disk for the
    // next boot to inherit. Fetching first means a failed seed writes nothing
    // at all.
    val candidates = source.candidates()
    app.ensureTopic(TRIAGE_TOPIC, "Triage", facilitator, Eisenhower.DIMENSIONS)
    for (c in candidates) app.seedIdea(TRIAGE_TOPIC, c.id, c.title, c.description, facilitator)
    // Jev rates the ROUND, not the item: its terms are rank-normalized across the
    // whole candidate set, so every axis is one batch call. A candidate it abstains
    // on is absent from the answer and written as null — which UNRATES it, so an
    // abstention arriving later clears the rating an earlier round derived.
    for ((dim, _, _) in Eisenhower.DIMENSIONS) {
        val rated = Jev.rate(candidates, dim)
        for (c in candidates) app.seedRating(TRIAGE_TOPIC, c.id, dim, Jev.PARTICIPANT, rated[c.id])
    }
    return candidates.size
}

fun main(args: Array<String>) {
    // InspectorFlag.parse first (3iv0w-D2): it strips its own tokens, then
    // this demo's own flag/positional reading runs over parsed.rest, so an
    // inspector flag's value is never mistaken for this demo's own.
    val parsed = InspectorFlag.parse(args)
    val app = AlignmentApp(
        demoPort(parsed.rest),
        journalPath = parsed.rest.flag("--journal")?.let { Path.of(it) },
        inspector = parsed.options,
    )
    // Seed BEFORE the socket opens (computenet-1f8b4), the discipline
    // :demo:beadsmirror states for itself: "the socket opens after every
    // workspace's start-time baseline has swapped its projector in". A seed
    // that throws must take the process down instead of leaving a reachable,
    // EMPTY triage board — which an operator cannot tell apart from a tracker
    // that genuinely has no ready epics.
    parsed.rest.flag("--seed-beads")?.let { workspace ->
        val n = seedBeadsTriage(app, BdCandidateSource(Path.of(workspace)))
        println("computenet alignment: seeded $n ready epics from $workspace into /t/${TRIAGE_TOPIC.value}")
    }
    app.start()
    announcePort("http", app.boundPort)
    println("computenet alignment: http://localhost:${app.boundPort}")
    parsed.options?.let { InspectorFlag.announce(app.inspector!!, it) }
}
