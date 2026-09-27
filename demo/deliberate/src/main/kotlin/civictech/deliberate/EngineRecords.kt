package civictech.deliberate

import civictech.cell.CellRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * SPEC §11: the durability codec of [DeliberationEngine]'s metadata — one
 * record per claim, per link and per question in a [MetaStore], one field
 * per property — and the rebuild of the engine's trees from the graph's
 * structure plus those records. Every function that touches an
 * [EngineState] or a [Claim] runs under the engine's lock.
 */
internal object EngineRecords {

    val RECORDS = Json { encodeDefaults = false; ignoreUnknownKeys = true }
    const val CLAIM_KEY = "c:"
    /** SPEC §3 "Links as claims": a link's record, keyed by its edge ref. */
    const val LINK_KEY = "l:"
    /** EXP-10: one record per question holding its round yields and whether they diminished. */
    const val QUESTION_KEY = "q:"
    /** SPEC §12: a question record's per-backend cost field is `cost.<backend>`. */
    const val COST_FIELD = "cost."

    /**
     * One claim's engine metadata as the store keeps it, one field per
     * property; a property at its default is not stored. [text] is stored
     * only when a rewrite (EXP-03 REPLACE/MERGE) changed it: the structure
     * log holds the original. The `jev` stances are [plausibility] and
     * [edgeStrength]; nothing else of the credence graph is persisted.
     */
    @Serializable
    data class ClaimRecord(
        /** The root ref of the question tree this claim belongs to. */
        val question: String? = null,
        val text: String? = null,
        val proposer: String? = null,
        val status: Status = Status.QUEUED,
        val override: Override = Override.AUTO,
        val roundLimit: Int? = null,
        val rounds: Int = 0,
        val plausibility: Double? = null,
        val relevance: Double? = null,
        val quality: Double? = null,
        val reach: Double? = null,
        val contribution: Double? = null,
        val proSaturation: Double? = null,
        val conSaturation: Double? = null,
        val saturated: List<Side> = emptyList(),
        val duplicatesDropped: Int = 0,
        val triage: Map<String, Int> = emptyMap(),
        val alsoProposedBy: List<String> = emptyList(),
        /**
         * EXP-03 REFINE (model B). Optional with an empty default, so a record
         * written before it existed decodes unchanged and an empty list is not stored.
         */
        val evidence: List<String> = emptyList(),
        val merged: Boolean = false,
        val error: String? = null,
        val anyCallSucceeded: Boolean = false,
        val edgeStrength: Double? = null,
    )

    /** EXP-10: a question's record — its non-root round yields and whether they halted queued work. */
    @Serializable
    data class QuestionRecord(
        /** Required so a question with no non-root round yet still has its one durable record (DUR-03). */
        val yields: List<Double>,
        val diminished: Boolean = false,
        /** CTL-05: the question is paused. */
        val paused: Boolean = false,
    )

    fun recordOf(c: Claim) = ClaimRecord(
        question = c.root.id.toString(),
        // A link's text is built from its ends and it has no proposer: neither is stored.
        text = c.text.takeIf { !c.isLink && it != c.structureText }, proposer = c.proposer.takeIf { !c.isLink },
        status = c.status, override = c.override,
        roundLimit = c.roundLimit, rounds = c.rounds,
        plausibility = c.plausibility, relevance = c.relevance, quality = c.quality,
        reach = c.reach.takeIf { c.parent != null }, contribution = c.contribution.takeIf { c.parent != null },
        proSaturation = c.proSaturation, conSaturation = c.conSaturation, saturated = c.saturated.toList(),
        duplicatesDropped = c.duplicatesDropped, triage = c.triage.mapKeys { it.key.name },
        alsoProposedBy = c.alsoProposedBy.toList(), evidence = c.evidence.toList(), merged = c.merged, error = c.error,
        anyCallSucceeded = c.anyCallSucceeded, edgeStrength = c.edge?.strength,
    )

    fun apply(c: Claim, r: ClaimRecord) {
        r.text?.let { c.text = it }
        r.proposer?.let { c.proposer = it }
        // SPEC §11: whatever was still active re-enters the queue; an interrupted round re-runs.
        c.status = if (r.status in ExplorationPolicy.ACTIVE) Status.QUEUED else r.status
        c.override = r.override
        r.roundLimit?.let { c.roundLimit = it }
        c.rounds = r.rounds
        c.plausibility = r.plausibility
        c.relevance = r.relevance
        c.quality = r.quality
        if (c.parent != null) {
            c.reach = r.reach
            // Restored as stored: a contribution persisted before model B lacks the 4·p·(1 − p)
            // factor, and is not recomputed here.
            c.contribution = r.contribution
        }
        c.proSaturation = r.proSaturation
        c.conSaturation = r.conSaturation
        c.saturated += r.saturated
        c.duplicatesDropped = r.duplicatesDropped
        r.triage.forEach { (k, v) -> TriageAction.entries.firstOrNull { it.name == k }?.let { c.triage[it] = v } }
        c.alsoProposedBy += r.alsoProposedBy
        c.evidence += r.evidence
        c.merged = r.merged
        c.error = r.error
        c.anyCallSucceeded = r.anyCallSucceeded
        c.edge?.strength = r.edgeStrength
    }

    fun fieldsOf(r: ClaimRecord): Map<String, String> =
        RECORDS.encodeToJsonElement(ClaimRecord.serializer(), r).jsonObject.mapValues { it.value.toString() }

    fun recordFrom(fields: Map<String, String>): ClaimRecord =
        RECORDS.decodeFromJsonElement(ClaimRecord.serializer(), JsonObject(fields.mapValues { RECORDS.parseToJsonElement(it.value) }))

    fun questionFieldsOf(q: CellRef, state: EngineState, ledger: CostLedger): Map<String, String> =
        RECORDS.encodeToJsonElement(
            QuestionRecord.serializer(),
            QuestionRecord(state.yields[q].orEmpty().toList(), q in state.diminished, q in state.paused),
        ).jsonObject.mapValues { it.value.toString() } +
            // SPEC §12: one field per backend, so a call rewrites only its backend's counters.
            ledger.tallies(q).map { (b, t) -> COST_FIELD + b to RECORDS.encodeToString(BackendTally.serializer(), t) }

    /** Every record's current fields, by store key. */
    fun current(state: EngineState, ledger: CostLedger): List<Pair<String, Map<String, String>>> =
        state.claims.values.map { c -> (if (c.isLink) LINK_KEY else CLAIM_KEY) + c.ref.id to fieldsOf(recordOf(c)) } +
            state.questions.keys.map { q -> QUESTION_KEY + q.id to questionFieldsOf(q, state, ledger) }

    /**
     * SPEC §11: rebuilds every tree into [state] from the graph's structure
     * (claims and the edges linking them, in creation order) and the [meta]
     * records. A claim whose record never reached the store is rebuilt from
     * the structure alone; a claim the structure holds without the edge that
     * would place it in a tree (the process died between the two writes) is
     * left out. DUR-06: with [startPaused] every restored question is paused.
     */
    fun rebuild(
        graph: List<CredenceGraph.Node>,
        meta: Map<String, Map<String, String>>,
        state: EngineState,
        ledger: CostLedger,
        roundLimit: Int,
        startPaused: Boolean,
    ) {
        val records = meta.filterKeys { it.startsWith(CLAIM_KEY) }.entries.associate { (k, v) ->
            CellRef(UUID.fromString(k.removePrefix(CLAIM_KEY))) to recordFrom(v)
        }
        val linkRecords = meta.filterKeys { it.startsWith(LINK_KEY) }.entries.associate { (k, v) ->
            CellRef(UUID.fromString(k.removePrefix(LINK_KEY))) to recordFrom(v)
        }
        val questionRecords = meta.filterKeys { it.startsWith(QUESTION_KEY) }.entries.associate { (k, v) ->
            CellRef(UUID.fromString(k.removePrefix(QUESTION_KEY))) to RECORDS.decodeFromJsonElement(
                QuestionRecord.serializer(),
                JsonObject(v.filterKeys { !it.startsWith(COST_FIELD) }.mapValues { RECORDS.parseToJsonElement(it.value) }),
            )
        }
        // SPEC §12: the cost counters; an unreadable one is dropped rather than failing the boot.
        val costRecords = meta.filterKeys { it.startsWith(QUESTION_KEY) }.entries.associate { (k, v) ->
            CellRef(UUID.fromString(k.removePrefix(QUESTION_KEY))) to v.filterKeys { it.startsWith(COST_FIELD) }.mapNotNull { (f, json) ->
                runCatching { f.removePrefix(COST_FIELD) to RECORDS.decodeFromString(BackendTally.serializer(), json) }.getOrNull()
            }.toMap()
        }
        val attaching = graph.filter { it.info.kind == CredenceGraph.Kind.EDGE }.groupBy { it.info.source }
        for (n in graph) {
            if (n.info.kind != CredenceGraph.Kind.CLAIM) continue
            val rec = records[n.ref]
            val structureText = n.info.text.orEmpty()
            val claim = if (n.info.question || rec?.question == n.ref.id.toString()) {
                state.questions[n.ref] = structureText
                Claim(n.ref, n.ref, null, null, structureText, 0, Claim.QUESTION, roundLimit)
            } else run {
                val e = attaching[n.ref]?.firstOrNull() ?: return@run null
                val target = e.info.target!!
                // The target is a claim, or an edge — then the parent is that edge's link
                // (an undercutter or link supporter; created with its argument, just below).
                val parent = state.claims[target] ?: return@run null
                val side = e.info.polarity!!
                Claim(n.ref, parent.root, parent, side, structureText, parent.depth + 1, "unknown", roundLimit).also { child ->
                    val edge = Edge(e.ref, parent.root, n.ref, target, side)
                    child.edge = edge
                    state.edges[e.ref] = edge
                    parent.children += child
                }
            } ?: continue
            rec?.let { r -> apply(claim, r) }
            state.claims[n.ref] = claim
            state.treeSize.merge(claim.root, 1, Int::plus)
            if (claim.edge != null) state.linkFor(claim, roundLimit).also { l -> linkRecords[l.ref]?.let { apply(l, it) } }
        }
        for ((q, r) in questionRecords) {
            if (q !in state.questions) continue
            state.yields[q] = r.yields.toMutableList()
            if (r.diminished) state.diminished += q
            if (r.paused) state.paused += q
        }
        // DUR-06: --start-paused pauses every restored question before anything is scheduled.
        if (startPaused) state.paused += state.questions.keys
        for ((q, tallies) in costRecords) {
            if (q in state.questions) ledger.restore(q, tallies)
        }
    }
}

/**
 * SPEC §11 DUR-02: writes, per record, the fields that changed since the
 * last [write] (a removed field — back at its default — is written as
 * null). A record that did not change writes nothing. Seeded with the fold
 * the engine restored from, so a quiet restart appends nothing.
 */
internal class RecordWriter(private val store: MetaStore, seed: Map<String, Map<String, String>>) {
    /** The record fields [write] last wrote (or found at boot), by key. */
    private val persisted = HashMap(seed)

    fun write(current: List<Pair<String, Map<String, String>>>) {
        for ((key, fields) in current) {
            val old = persisted[key].orEmpty()
            val delta = LinkedHashMap<String, String?>()
            fields.forEach { (f, v) -> if (old[f] != v) delta[f] = v }
            old.keys.forEach { if (it !in fields) delta[it] = null }
            if (delta.isNotEmpty()) {
                store.put(key, delta)
                persisted[key] = fields
            }
        }
    }
}
