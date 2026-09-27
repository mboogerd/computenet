package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.time.Duration

/**
 * Grows one deliberation tree per question (SPEC §3–§5) on top of one
 * [CredenceGraph] (every credence layer in one cell graph): proposers
 * generate arguments, the [judge] steers the exploration and supplies the
 * `jev` stances, the graph propagates credence vectors.
 *
 * Threading: claims are expanded by `config.workers` threads pulling from one
 * priority queue (SPEC §3 "Exploration order"). One queue task runs one round:
 * a task's priority is the claim's contribution (reach × relevance × quality,
 * judged by Jev when the argument was attached; the root is 1) times
 * `roundDecay` per round the claim already ran, so a claim with rounds left
 * re-enters the queue behind stronger fresh work. Within a round the
 * proposers take turns (EXP-02); a turn fans its per-side calls out on a
 * separate call executor, and so does the assessment of the round's new
 * arguments, after which each joins the queue. The app-wide bound on
 * concurrent CLI processes (EXP-07) is not the engine's: it is the one
 * [ProcessGate] the CLI proposers share. All engine metadata sits behind
 * [lock], held only for short reads/writes and never across a Judge/Proposer
 * call. Graph mutations go through [serviceLock], preserving the graph's
 * single-writer mutation model while worker threads expand.
 *
 * Durability (SPEC §11): with a [store], every claim's metadata is written to
 * it as one record per claim, field by field (only the fields that changed,
 * every [persistEveryMs] and on [close]); the `jev` stances are part of it
 * (plausibility, edge strength). A new engine over a [store] that holds
 * records rebuilds its trees from the graph's structure plus those records,
 * re-applies the stances — the graph recomputes every credence from them —
 * and re-queues whatever was still active ([restore]).
 *
 * Cost (SPEC §12): every Judge/Proposer/Merger call runs with its question's
 * [UsageSink] bound ([Usage.within]); the adapters report what each call
 * used, and the engine prices it ([pricing]) into per-question, per-backend
 * counters that are part of the question's durable record.
 */
class DeliberationEngine(
    private val service: CredenceGraph,
    private val judge: Judge,
    private val proposers: List<Proposer>,
    private val config: Config = Config(),
    /** EXP-03 MERGE; without one, a MERGE verdict is handled as DUPLICATE. */
    private val merger: Merger? = null,
    private val store: MetaStore? = null,
    /** SPEC §12: how each backend's usage is priced. */
    private val pricing: Pricing = Pricing(),
    private val persistEveryMs: Long = 100,
    private val onChange: () -> Unit = {},
) : AutoCloseable {

    /** Knobs of SPEC §3; the Jev thresholds' defaults come from `CALIBRATION.md` (SPEC §10). */
    data class Config(
        val argsPerCall: Int = 1,
        val maxRounds: Int = 3,
        /** EXP-04: a side whose Jev saturation (1 − p(missing)) reaches this gets no more proposals. */
        val saturation: Double = DEFAULT_SATURATION,
        /** EXP-04: a side of the root holding this many arguments is saturated regardless of Jev. */
        val maxArgsPerSide: Int = 6,
        /** EXP-04: the same cap for every claim below the root. */
        val maxArgsPerSideChild: Int = 3,
        /** EXP-05: a non-root claim is expanded only when its contribution reaches this. */
        val minInfluence: Double = DEFAULT_MIN_INFLUENCE,
        /** SPEC §3 "Exploration order": a claim's next round is queued at contribution × roundDecay^(rounds run). */
        val roundDecay: Double = 0.5,
        val maxDepth: Int = 3,
        val maxClaims: Int = 180,
        val workers: Int = 8,
        /** EXP-10: the per-question diminishing-returns stop; null disables it (yields are still recorded). */
        val yieldStop: YieldStop? = YieldStop(),
    ) {
        init {
            require(workers > 0) { "workers must be positive" }
            require(maxArgsPerSide > 0) { "maxArgsPerSide must be positive" }
            require(maxArgsPerSideChild > 0) { "maxArgsPerSideChild must be positive" }
            require(roundDecay in 0.0..1.0) { "roundDecay must be in [0,1]" }
        }

        companion object {
            const val DEFAULT_SATURATION = 0.22
            /** Iteration 5: quality is the construction Noul alone; 0.10 balances tree sizes across questions (CALIBRATION.md). */
            const val DEFAULT_MIN_INFLUENCE = 0.10
            /**
             * EXP-05: reach assumes this edge strength when the CRED-02 judgment
             * failed — middling, so one failure neither prunes nor frees a subtree.
             */
            const val FALLBACK_STRENGTH = 0.5
            /** EXP-06: the error of a claim that explored and then ran out of budget (ROUND_LIMIT). */
            const val BUDGET_EXHAUSTED = "budget exhausted"
            /** CTL-02: a claim the human forced to expand is queued ahead of every contribution (≤ 1). */
            const val FORCED_PRIORITY = 2.0
        }
    }

    /**
     * EXP-10: a question stops when, once it holds at least [minClaims] claims
     * and 2 × [window] recorded non-root round yields, the mean yield of its
     * last [window] rounds falls below [ratio] × the mean of all its earlier ones.
     */
    data class YieldStop(val window: Int = 8, val ratio: Double = 0.6, val minClaims: Int = 40) {
        init {
            require(window > 0) { "yield window must be positive" }
            require(ratio > 0.0) { "yield ratio must be positive" }
            require(minClaims >= 0) { "yield min claims must not be negative" }
        }

        /** Whether [yields] (in completion order) have diminished, for a tree of [claims] claims. */
        fun diminished(yields: List<Double>, claims: Int): Boolean {
            if (claims < minClaims || yields.size < 2 * window) return false
            val recent = yields.takeLast(window).average()
            val earlier = yields.dropLast(window).average()
            return recent < ratio * earlier
        }
    }

    private class Claim(
        val ref: CellRef,
        val root: CellRef,
        /** The claim this one argues about: its edge's target, or for an undercutter the undercut edge's target. */
        val parent: Claim?,
        /** The polarity of the edge attaching it (ATTACK for an undercutter). */
        val side: Side?,
        /** EXP-03 REPLACE may swap it while the claim is still unexplored (the graph's text is immutable). */
        var text: String,
        val depth: Int,
        var proposer: String,
        var roundLimit: Int,
        /** EXP-03 UNDERCUT: the argument whose link to [parent] this claim attacks; null for an ordinary argument. */
        val undercuts: Claim? = null,
    ) {
        /** The text the structure log holds for it; the record stores [text] only when a rewrite changed it. */
        val structureText: String = text
        var status = Status.QUEUED
        var override = Override.AUTO
        var plausibility: Double? = null
        var relevance: Double? = null
        var quality: Double? = null
        /** EXP-05: 1 for the root; parent reach × edge strength once the edge is judged. */
        var reach: Double? = if (parent == null) 1.0 else null
        /** SPEC §3 "Exploration order": reach × relevance × quality; 1 for the root. */
        var contribution: Double? = if (parent == null) 1.0 else null
        var proSaturation: Double? = null
        var conSaturation: Double? = null
        var rounds = 0
        var duplicatesDropped = 0
        val triage = sortedMapOf<TriageAction, Int>()
        val alsoProposedBy = mutableListOf<String>()
        /** EXP-03 MERGE rewrote this argument together with an overlapping one. */
        var merged = false
        var error: String? = null
        /** EXP-04: the sides Jev last judged saturated (the cap and the balance rule apply on top, see saturatedSides). */
        val saturated = mutableSetOf<Side>()
        /** CTL-02: the next round is forced (saturation, depth, contribution and budget ignored). */
        var forceRound = false
        var roundInFlight = false
        /** EXPLORING with rounds left; its next round is queued. */
        var waiting = false
        /** Invalidates stale priority-queue entries when a queued claim is reprioritized. */
        var queueGeneration = 0L
        /** Keeps a queued target from starting while REPLACE/MERGE and re-assessment are in progress. */
        var rewriteInFlight = false
        /** Whether the first rewrite reservation invalidated an already queued task. */
        var rewriteWasQueued = false
        var anyCallSucceeded = false
        var edge: Edge? = null
        /** Its pro and con arguments (never its undercutters). */
        val children = mutableListOf<Claim>()
        /** EXP-03 UNDERCUT: claims attacking this argument's edge. */
        val undercutters = mutableListOf<Claim>()
    }

    private class Edge(val ref: CellRef, val root: CellRef, val source: CellRef, val target: CellRef, val side: Side) {
        var strength: Double? = null
    }

    /** A proposal that survived exact-text dedupe; [also] are the other proposers of the same text. */
    private class Fresh(val text: String, val side: Side, val proposer: String) {
        val also = mutableListOf<String>()
    }

    private class Task(val claim: Claim, val priority: Double, val seq: Long, val generation: Long)

    private data class RewriteReservation(val created: Boolean, val wasQueued: Boolean)

    private val lock = Any()
    private val serviceLock = Any()
    private val claims = LinkedHashMap<CellRef, Claim>()
    private val edges = LinkedHashMap<CellRef, Edge>()
    private val questions = LinkedHashMap<CellRef, String>()
    private val treeSize = HashMap<CellRef, Int>()
    /** EXP-10: per question, the yield of every recorded non-root round in completion order. */
    private val yields = HashMap<CellRef, MutableList<Double>>()
    /** EXP-10: questions stopped because their returns diminished. */
    private val diminished = HashSet<CellRef>()
    /** SPEC §12: per question, per backend, the usage of every call made for it. */
    private val costs = HashMap<CellRef, MutableMap<String, BackendTally>>()
    /** Questions whose complete lifetime is covered by [costs]; absent for records created before cost tracking. */
    private val completeCosts = HashSet<CellRef>()

    private val pending = AtomicInteger()
    private val idle = Object()
    private val queue = PriorityBlockingQueue<Task>(64, compareByDescending<Task> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    @Volatile private var closed = false
    private val calls: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val workerThreads: List<Thread> = List(config.workers) { i ->
        Thread.ofPlatform().daemon().name("deliberate-worker-$i").start(::work)
    }

    /** The record fields [persistNow] last wrote (or found at boot), by key. */
    private val persisted = HashMap<String, Map<String, String>>()
    private val persister: ScheduledExecutorService? = store?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deliberate-persist").apply { isDaemon = true } }
    }

    private companion object {
        val FINISHED = setOf(
            Status.SATURATED, Status.ROUND_LIMIT, Status.PRUNED, Status.DEPTH_LIMIT,
            Status.BUDGET, Status.DIMINISHING, Status.STOPPED, Status.FAILED,
        )
        val ACTIVE = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
        val SIDES = listOf(Polarity.SUPPORT, Polarity.ATTACK)
        val Side.opposite get() = if (this == Polarity.SUPPORT) Polarity.ATTACK else Polarity.SUPPORT
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';')
        val RECORDS = Json { encodeDefaults = false; ignoreUnknownKeys = true }
        const val CLAIM_KEY = "c:"
        /** EXP-10: one record per question holding its round yields and whether they diminished. */
        const val QUESTION_KEY = "q:"
        const val JEV = "jev"
        /** SPEC §12: a question record's per-backend cost field is `cost.<backend>`. */
        const val COST_FIELD = "cost."
        val BACKEND_ORDER = listOf(Pricing.CLAUDE, Pricing.CODEX, Pricing.JEV)
        /** SPEC §12: the projection needs at least this many completed rounds. */
        const val PROJECTION_MIN_ROUNDS = 3
        /** A node's credence before its first emission reached the hub. */
        const val NEUTRAL = 0.5
    }

    init {
        if (store != null) {
            val restored = store.load()
            // DUR-02 writes only changed records. Seed the write-behind cache
            // from the fold we just restored, so a quiet restart does not
            // append every question and claim to the host journal again.
            persisted.putAll(restored)
            restore(restored)
            persister!!.scheduleWithFixedDelay({
                try {
                    persistNow()
                } catch (e: Exception) {
                    System.err.println("deliberate: persisting metadata failed: $e")
                }
            }, persistEveryMs, persistEveryMs, TimeUnit.MILLISECONDS)
        }
    }

    // ---------------------------------------------------------------- API

    /** EXP-01: create the root claim and start expanding it; returns at once. */
    fun ask(question: String): CellRef {
        val ref = synchronized(serviceLock) { service.createClaim(question, question = true) }
        val root = Claim(ref, ref, null, null, question, 0, "question", config.maxRounds)
        synchronized(lock) {
            claims[ref] = root
            questions[ref] = question
            treeSize[ref] = 1
            completeCosts += ref
        }
        onChange()
        enqueue(root)
        return ref
    }

    /** CTL-01..04. */
    fun setOverride(ref: CellRef, mode: Override) {
        var enqueueAgain: Claim? = null
        val toSchedule: List<Claim> = synchronized(lock) {
            val c = requireNotNull(claims[ref]) { "unknown claim ${ref.id}" }
            c.override = mode
            when (mode) {
                // CTL-03: queued work is cancelled at once; a running claim stops at its next round boundary.
                Override.STOP -> when {
                    c.status == Status.EXPLORING && c.waiting -> {
                        c.waiting = false
                        c.status = Status.STOPPED
                        emptyList()
                    }
                    c.status !in setOf(Status.JUDGING, Status.EXPLORING) -> {
                        c.status = Status.STOPPED
                        emptyList()
                    }
                    else -> emptyList()
                }
                // CTL-04: back through the normal gates.
                Override.AUTO -> if (c.status == Status.STOPPED) {
                    c.status = Status.QUEUED
                    listOf(c)
                } else emptyList()
                // CTL-02: the claim's next round is forced — whatever its status, budget included.
                Override.EXPAND -> when {
                    c.status in FINISHED -> {
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + 1)
                        c.forceRound = true
                        c.status = Status.QUEUED
                        listOf(c)
                    }
                    c.status == Status.QUEUED -> {
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + 1)
                        c.forceRound = true
                        // Jump the queue: the stale task finds the claim taken and falls through.
                        enqueueAgain = c
                        emptyList()
                    }
                    else -> {
                        // JUDGING or EXPLORING. If a round is already in flight, the override belongs
                        // to the next round; do not let completion of this one consume the request.
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + if (c.roundInFlight) 2 else 1)
                        c.forceRound = true
                        if (c.waiting) enqueueAgain = c
                        emptyList()
                    }
                }
            }
        }
        onChange()
        toSchedule.forEach(::schedule)
        enqueueAgain?.let(::enqueue)
    }

    /**
     * The UI's view (SPEC §6). It only reads: every credence, the consensus
     * and the spread come from the graph's hub fold, where the cells put
     * them (CRED-06).
     */
    fun snapshot(): GraphDto {
        val graph = service.graph()
        val layers = service.layers
        val neutral = List(layers.ids.size) { NEUTRAL }
        return synchronized(lock) {
            val nodes = graph.mapNotNull { n ->
                val values = n.credence?.values ?: neutral
                val named = layers.named(values)
                val credence = values[layers.headlineIndex]
                val consensus = n.credence?.consensus ?: NEUTRAL
                val low = n.credence?.spreadLow ?: NEUTRAL
                val high = n.credence?.spreadHigh ?: NEUTRAL
                claims[n.ref]?.let { c ->
                    NodeDto(
                        ref = c.ref.id.toString(), kind = "CLAIM", credence = credence, root = c.root.id.toString(),
                        credences = named, consensus = consensus, spreadLow = low, spreadHigh = high,
                        text = c.text, depth = c.depth, status = c.status, override = c.override,
                        proposer = c.proposer, plausibility = c.plausibility, relevance = c.relevance, reach = c.reach,
                        quality = c.quality, contribution = c.contribution,
                        proSaturation = c.proSaturation, conSaturation = c.conSaturation, rounds = c.rounds,
                        duplicatesDropped = c.duplicatesDropped, error = c.error,
                        alsoProposedBy = c.alsoProposedBy.toList().ifEmpty { null },
                        merged = c.merged.takeIf { it },
                        triage = c.triage.mapKeys { it.key.name }.ifEmpty { null },
                        undercuts = c.undercuts?.edge?.ref?.id?.toString(),
                    )
                } ?: edges[n.ref]?.let { e ->
                    NodeDto(
                        ref = e.ref.id.toString(), kind = "EDGE", credence = credence, root = e.root.id.toString(),
                        credences = named, consensus = consensus, spreadLow = low, spreadHigh = high,
                        polarity = e.side.name, source = e.source.id.toString(), target = e.target.id.toString(),
                        strength = e.strength,
                    )
                }
            }
            val window = config.yieldStop?.window ?: YieldStop().window
            val qs = questions.map { (root, text) ->
                val tree = claims.values.filter { it.root == root }
                val ys = yields[root].orEmpty()
                QuestionDto(
                    root.id.toString(), text, tree.size, tree.any { it.status in ACTIVE },
                    yieldRounds = ys.size,
                    yieldRecent = ys.takeLast(window).takeIf { it.isNotEmpty() }?.average(),
                    yieldEarlier = ys.dropLast(window).takeIf { it.isNotEmpty() }?.average(),
                    stoppedBy = stoppedBy(root),
                ).withCost(costOf(root, tree))
            }
            GraphDto(qs, nodes, layers.members)
        }
    }

    /** Wait until no claim is queued or being expanded. */
    fun awaitIdle(timeout: Duration): Boolean {
        val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds
        synchronized(idle) {
            while (pending.get() > 0) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return false
                idle.wait(left)
            }
        }
        return true
    }

    /**
     * SPEC §11 DUR-02: writes, per claim, the record fields that changed since
     * the last call (a removed field — back at its default — is written as
     * null). A record that did not change writes nothing.
     */
    @Synchronized
    fun persistNow() {
        val s = store ?: return
        val current = synchronized(lock) {
            claims.values.map { c -> CLAIM_KEY + c.ref.id to fieldsOf(recordOf(c)) } +
                questions.keys.map { q -> QUESTION_KEY + q.id to questionFieldsOf(q) }
        }
        for ((key, fields) in current) {
            val old = persisted[key].orEmpty()
            val delta = LinkedHashMap<String, String?>()
            fields.forEach { (f, v) -> if (old[f] != v) delta[f] = v }
            old.keys.forEach { if (it !in fields) delta[it] = null }
            if (delta.isNotEmpty()) {
                s.put(key, delta)
                persisted[key] = fields
            }
        }
    }

    /**
     * Stops exploring. The metadata is persisted once more *before* the workers
     * are interrupted, so a close is no different from a kill at that instant:
     * a round cut short is re-run by the next engine over the same store.
     */
    override fun close() {
        closed = true
        // Never interrupt a write in progress: an interrupted journal channel closes for good.
        persister?.shutdown()
        persister?.awaitTermination(5, TimeUnit.SECONDS)
        try {
            persistNow()
        } catch (e: Exception) {
            System.err.println("deliberate: final metadata persist failed: $e")
        }
        workerThreads.forEach(Thread::interrupt)
        calls.shutdownNow()
    }

    // ---------------------------------------------------------------- scheduling

    private fun work() {
        while (!closed) {
            val task = try {
                queue.take()
            } catch (e: InterruptedException) {
                return
            }
            try {
                process(task)
            } finally {
                done()
            }
        }
    }

    /** Caller holds [lock]. SPEC §3 "Exploration order": the queue priority of [c]'s next round. */
    private fun priorityOf(c: Claim): Double =
        if (c.override == Override.EXPAND) Config.FORCED_PRIORITY
        else contributionOf(c) * config.roundDecay.pow(c.rounds)

    /** Caller holds [lock]. A claim whose assessment failed falls back to its reach (EXP-05). */
    private fun contributionOf(c: Claim): Double = c.contribution ?: c.reach ?: Config.FALLBACK_STRENGTH

    private fun enqueue(c: Claim) {
        if (closed) return
        val (priority, generation) = synchronized(lock) {
            c.queueGeneration++
            priorityOf(c) to c.queueGeneration
        }
        pending.incrementAndGet()
        queue.add(Task(c, priority, seq.getAndIncrement(), generation))
    }

    /**
     * Queues a QUEUED claim for its first round unless a gate that needs no
     * judgment ends it first (EXP-05; CTL-02 skips them): beyond `maxDepth` it
     * is DEPTH_LIMIT, below the `minInfluence` floor it is PRUNED — so an
     * irrelevant or poorly constructed argument is never explored — and in a
     * question whose returns diminished (EXP-10) it is DIMINISHING.
     */
    private fun schedule(c: Claim) {
        val gate = synchronized(lock) {
            if (c.status != Status.QUEUED) return
            if (c.parent == null || c.override == Override.EXPAND || c.forceRound) null
            else when {
                c.depth > config.maxDepth -> Status.DEPTH_LIMIT
                contributionOf(c) < config.minInfluence -> Status.PRUNED
                treeSize.getValue(c.root) >= config.maxClaims -> Status.BUDGET
                c.root in diminished -> Status.DIMINISHING
                else -> null
            }
        }
        if (gate == null) enqueue(c) else finish(c, gate)
    }

    private fun done() {
        if (pending.decrementAndGet() == 0) synchronized(idle) { idle.notifyAll() }
    }

    private inline fun <T> update(block: () -> T): T {
        val r = synchronized(lock, block)
        onChange()
        return r
    }

    private fun process(task: Task) {
        val c = task.claim
        // Claim ownership: a QUEUED claim is started; a waiting EXPLORING claim continues with
        // its next round. Stale/cancelled tasks (e.g. a STOP while waiting) fall through.
        val continuing = synchronized(lock) {
            when {
                task.generation != c.queueGeneration || c.rewriteInFlight -> null
                c.status == Status.QUEUED -> false.also { c.status = Status.JUDGING }
                c.status == Status.EXPLORING && c.waiting -> true.also { c.waiting = false }
                else -> null
            }
        } ?: return
        if (!continuing) onChange()
        val finished = try {
            if (continuing) step(c) else start(c)
        } catch (t: Throwable) {
            // EXP-08: never let an exception kill a worker or leave a claim stuck.
            finish(c, Status.FAILED, error = t.toString())
            true
        }
        if (!finished) {
            // SPEC §3 "Exploration order": rounds left, so back into the queue at the decayed priority.
            enqueue(c)
            return
        }
        // An EXPAND racing the terminal transition either queued itself in
        // setOverride(), or left forceRound behind while this invocation was
        // still active. The latter must not be lost at the finish boundary.
        val forcedRequeue = synchronized(lock) {
            (c.override == Override.EXPAND && c.forceRound && c.status in FINISHED)
                .also { if (it) c.status = Status.QUEUED }
        }
        if (forcedRequeue) {
            onChange()
            enqueue(c)
        }
    }

    /**
     * Judges plausibility if it is still missing (the root, or a failed
     * assessment), applies the budget gate (not to a forced round, CTL-02) and
     * runs the first round. Returns true once the claim finished, false when
     * it has rounds left.
     */
    private fun start(c: Claim): Boolean {
        // CRED-01
        if (synchronized(lock) { c.plausibility } == null) {
            val (path, text) = synchronized(lock) { pathOf(c) to c.text }
            attempt(c, "plausibility") {
                val p = judge.plausibility(questionOf(c), path, text)
                synchronized(serviceLock) { service.setStance(c.ref, JEV, p) }
                p
            }?.let { p ->
                update { c.plausibility = p }
            }
        }
        // EXP-06 after EXP-05 (schedule()): BUDGET means "would have been expanded".
        if (!synchronized(lock) { c.forceRound } && budgetExhausted(c)) return finish(c, Status.BUDGET)
        // EXP-10: the question stopped while this claim was being judged.
        if (synchronized(lock) { !c.forceRound && c.root in diminished }) return finish(c, Status.DIMINISHING)
        update { c.status = Status.EXPLORING }
        return step(c)
    }

    /**
     * Runs one round of [c] if it has one left. Returns true once the claim
     * finished, false when it has rounds left (the caller re-queues it,
     * marked [Claim.waiting]).
     */
    private fun step(c: Claim): Boolean {
        val (sides, forcedRound, terminal) = synchronized(lock) {
            val forcedRound = c.forceRound
            val nextStatus = terminalStatus(c)
            val nextSides = if (forcedRound) SIDES else SIDES - saturatedSides(c)
            if (nextStatus == null) {
                if (forcedRound) c.saturated.clear()
                c.forceRound = false
                c.roundInFlight = true
            }
            Triple(nextSides, forcedRound, nextStatus)
        }
        if (terminal != null) return finish(c, terminal)
        val outcome = try {
            round(c, sides, forcedRound)
        } finally {
            synchronized(lock) { c.roundInFlight = false }
        }
        if (outcome != null) return finish(c, outcome)
        // Finish now if nothing is left, so a done claim needs no queue trip.
        val next = synchronized(lock) { terminalStatus(c).also { if (it == null) c.waiting = true } }
        return if (next != null) finish(c, next) else false
    }

    /**
     * Caller holds [lock]. The status that ends [c]'s expansion before its next
     * round, or null if it gets one. A forced round (CTL-02) ignores the round
     * limit, saturation and the budget.
     */
    private fun terminalStatus(c: Claim): Status? {
        val forcedRound = c.forceRound
        val nextSides = if (forcedRound) SIDES else SIDES - saturatedSides(c)
        return when {
            c.override == Override.STOP -> Status.STOPPED
            nextSides.isEmpty() -> Status.SATURATED
            !forcedRound && c.rounds >= c.roundLimit -> Status.ROUND_LIMIT
            !forcedRound && treeSize.getValue(c.root) >= config.maxClaims -> Status.BUDGET
            !forcedRound && c.root in diminished -> Status.DIMINISHING
            else -> null
        }
    }

    /**
     * Caller holds [lock]. EXP-04: a side is saturated when it holds its cap,
     * or when Jev last judged it saturated **and** it holds at least as many
     * arguments as the other side — a side that is behind is never saturated
     * by Jev alone.
     */
    private fun saturatedSides(c: Claim): Set<Side> = SIDES.filter { side ->
        atCap(c, side) || (side in c.saturated && countOf(c, side) >= countOf(c, side.opposite))
    }.toSet()

    /**
     * One round (EXP-02..05). The proposers take turns in their configured
     * order; each turn sees the claim's arguments as they stand after the
     * previous turn's triage, so a later proposer is asked for what is still
     * missing. Returns a terminal status when the round ends the expansion
     * early (all calls failed, budget), else null. A [forced] round (CTL-02)
     * ignores saturation and the budget; its new arguments are bounded by an
     * allowance of one per-side cap per side instead.
     */
    private fun round(c: Claim, sides: List<Side>, forced: Boolean): Status? {
        val counts = sortedMapOf<TriageAction, Int>()
        fun count(a: TriageAction) = counts.merge(a, 1, Int::plus)
        var dropped = 0
        val attached = mutableListOf<Claim>()
        val replaced = mutableListOf<Claim>()
        var budgetHit = false
        var asked = 0
        /** EXP-10: arguments asked for this round (the yield's denominator). */
        var requested = 0
        var failures = 0
        // CTL-02: a forced round's own allowance, shared by its turns.
        val allowance = SIDES.associateWith { capOf(c) }.toMutableMap()

        fun add(parent: Claim, side: Side, f: Fresh): Claim? {
            if (budgetHit) return null
            if (!reserve(c.root, forced)) { budgetHit = true; return null }
            return attach(parent, side, f).also { attached += it }
        }

        fun undercut(target: Claim, f: Fresh): Claim? {
            if (budgetHit) return null
            if (!reserve(c.root, forced)) { budgetHit = true; return null }
            return attachUndercut(target, f).also { attached += it }
        }

        for (p in proposers) {
            if (budgetHit) break
            val (ctx, existing) = synchronized(lock) {
                val pros = c.children.filter { it.side == Polarity.SUPPORT }
                val cons = c.children.filter { it.side == Polarity.ATTACK }
                ClaimContext(questions.getValue(c.root), pathOf(c), c.text, pros.map { it.text }, cons.map { it.text }) to
                    (pros + cons)
            }
            // EXP-04: never attach beyond the cap within a round. Both sides, since OTHER_SIDE
            // may move an argument onto a side that was not asked this round.
            val room: MutableMap<Side, Int> = if (forced) allowance else SIDES.associateWith { side ->
                capOf(c) - existing.count { it.side == side }
            }.toMutableMap()
            val turnSides = sides.filter { room.getValue(it) > 0 }
            if (turnSides.isEmpty()) continue
            // EXP-02: this proposer, every side it is asked about concurrently.
            val futures = turnSides.map { side ->
                val ask = minOf(config.argsPerCall, room.getValue(side))
                side to calls.submit<List<String>> { Usage.within(sinkFor(c.root)) { p.propose(ctx, side, ask).take(ask) } }
            }
            asked += futures.size
            requested += turnSides.sumOf { minOf(config.argsPerCall, room.getValue(it)) }
            val proposals = futures.mapNotNull { (side, f) ->
                try {
                    side to f.get()
                } catch (e: Exception) {
                    failures++
                    val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                    update { c.error = "${p.id}: $cause" }
                    null
                }
            }

            // EXP-03 step 1: exact-text duplicates (of an existing argument or of an earlier proposal).
            val byText = HashMap<String, Any>()
            existing.forEach { byText[normalize(it.text)] = it }
            val fresh = mutableListOf<Fresh>()
            for ((side, texts) in proposals) {
                for (t in texts.map(String::trim).filter(String::isNotEmpty)) {
                    when (val hit = byText[normalize(t)]) {
                        is Claim -> { mergeProposers(hit, listOf(p.id)); dropped++; count(TriageAction.DUPLICATE) }
                        is Fresh -> { dropped++; count(TriageAction.DUPLICATE) }
                        else -> Fresh(t, side, p.id).also { fresh += it; byText[normalize(t)] = it }
                    }
                }
            }
            if (fresh.isEmpty()) continue

            // EXP-03 step 2: one Jev triage request for this turn's remaining candidates.
            val verdicts = attempt(c, "triage") { judge.triage(ctx, fresh.map { Candidate(it.text, it.side) }) }
                ?.takeIf { it.size == fresh.size }
                ?: fresh.map { Triage(TriageAction.ADD) }
            val resolved = arrayOfNulls<Claim>(fresh.size)
            for ((i, f) in fresh.withIndex()) {
                val v = verdicts[i]
                val target = v.target?.let { t -> if (t < existing.size) existing.getOrNull(t) else resolved.getOrNull(t - existing.size) }
                val action = when (v.action) {
                    TriageAction.REPLACE -> when {
                        target == null -> TriageAction.ADD
                        replace(target, f) -> TriageAction.REPLACE
                        else -> TriageAction.DUPLICATE
                    }
                    TriageAction.MERGE -> when {
                        target == null -> TriageAction.ADD
                        merge(c, target, f) -> TriageAction.MERGE
                        else -> TriageAction.DUPLICATE
                    }
                    TriageAction.REFINE -> if (target == null) TriageAction.ADD else TriageAction.REFINE
                    // Only an argument (a claim with an edge) has a link to undercut.
                    TriageAction.UNDERCUT -> if (target?.edge == null) TriageAction.ADD else TriageAction.UNDERCUT
                    else -> v.action
                }
                count(action)
                // Attach every survivor that fits the budget and the cap — even if STOP arrived
                // meanwhile (CTL-03). Survivors past the cap are not attached.
                when (action) {
                    TriageAction.ADD, TriageAction.OTHER_SIDE -> {
                        val side = if (action == TriageAction.ADD) f.side else f.side.opposite
                        if (room.getValue(side) > 0) {
                            resolved[i] = add(c, side, f)?.also { room[side] = room.getValue(side) - 1 }
                        }
                    }
                    TriageAction.DUPLICATE -> {
                        dropped++
                        if (target != null) mergeProposers(target, listOf(f.proposer) + f.also)
                        resolved[i] = target
                    }
                    TriageAction.REPLACE, TriageAction.MERGE -> {
                        // replace()/merge() already rewrote the target
                        replaced += target!!
                        resolved[i] = target
                    }
                    TriageAction.REFINE -> {
                        val fits = synchronized(lock) { forced || countOf(target!!, Polarity.SUPPORT) < capOf(target) }
                        resolved[i] = if (fits) add(target!!, Polarity.SUPPORT, f) else target
                    }
                    TriageAction.UNDERCUT -> {
                        val fits = synchronized(lock) { forced || target!!.undercutters.size < capOf(target) }
                        resolved[i] = if (fits) undercut(target!!, f) else target
                    }
                    TriageAction.DROP -> Unit
                }
            }
        }
        val allProposersFailed = failures == asked && asked > 0
        val noProposerHasEverSucceeded = synchronized(lock) {
            if (!allProposersFailed && asked > 0) c.anyCallSucceeded = true
            !c.anyCallSucceeded
        }
        update {
            c.rounds++
            c.duplicatesDropped += dropped
            counts.forEach { (a, n) -> c.triage.merge(a, n, Int::plus) }
        }

        // CRED-01, CRED-02, EXP-05: every new (or reworded: REPLACE, MERGE) argument is assessed at once.
        // A rewrite may hit an argument attached in an earlier turn of this round, so assessment waits
        // for the last turn: an assessment of superseded wording must never land.
        val rewritten = replaced.distinct()
        val ready = (rewritten + attached).distinct()
        try {
            rewritten.forEach(::clearAssessment)
            assess(ready)
        } finally {
            // SPEC §3 "Exploration order": arguments compete with their parent's next
            // round immediately after assessment. Rewrite reservations are released
            // only now, so a stale task cannot explore wording with old judgments.
            // The finally also prevents an infrastructure failure from stranding a target.
            ready.forEach { n ->
                val queueNow = update {
                    if (n.rewriteInFlight) {
                        n.rewriteInFlight = false
                        n.rewriteWasQueued = false
                    }
                    n.status == Status.QUEUED
                }
                if (queueNow) schedule(n)
            }
        }
        recordYield(c, attached, counts, requested)

        // EXP-04: saturation per side, judged once every proposer had its turn
        // (a forced round's sides are re-judged too). A side at its cap needs no Jev call.
        val after = context(c)
        for (side in sides) {
            if (synchronized(lock) { atCap(c, side) }) continue
            val p = attempt(c, "saturation") { judge.saturation(after, side) } ?: continue
            update {
                if (side == Polarity.SUPPORT) c.proSaturation = p else c.conSaturation = p
                if (p >= config.saturation) c.saturated += side else c.saturated -= side
            }
        }
        return when {
            budgetHit -> Status.BUDGET
            allProposersFailed && noProposerHasEverSucceeded -> Status.FAILED
            else -> null
        }
    }

    /**
     * EXP-10: records the yield of [c]'s round that attached [attached] out of
     * [requested] asked-for arguments, triaged as [counts]: Σ (strength ×
     * relevance × quality) over the attached arguments × the share of triaged
     * proposals that were neither DUPLICATE nor DROP, per argument asked for.
     * A root round or a round that asked for nothing records no yield. When
     * the question's yields have diminished ([YieldStop.diminished]) and at
     * least one QUEUED claim can actually be halted, it stops: no new round
     * starts in it; its queued claims end DIMINISHING (a round in flight
     * finishes and attaches what it found, but records no further yield).
     */
    private fun recordYield(c: Claim, attached: List<Claim>, counts: Map<TriageAction, Int>, requested: Int) {
        if (c.parent == null || requested == 0) return
        val stopped = update {
            // The series is frozen at the stop, so it shows why the question stopped;
            // rounds that were in flight then still attach what they found.
            if (c.root in diminished) return@update null
            val value = attached.sumOf { n ->
                (n.edge?.strength ?: Config.FALLBACK_STRENGTH) * (n.relevance ?: 1.0) * (n.quality ?: 1.0)
            }
            val triaged = counts.values.sum()
            val novelty = if (triaged == 0) 1.0
            else 1.0 - ((counts[TriageAction.DUPLICATE] ?: 0) + (counts[TriageAction.DROP] ?: 0)).toDouble() / triaged
            val ys = yields.getOrPut(c.root) { mutableListOf() }
            ys += value * novelty / requested
            val stop = config.yieldStop ?: return@update null
            val size = treeSize.getValue(c.root)
            if (size >= config.maxClaims || !stop.diminished(ys, size)) return@update null
            val halted = claims.values.filter { n ->
                n.root == c.root && !n.forceRound && n.override != Override.EXPAND && !n.rewriteInFlight &&
                    (n.status == Status.QUEUED || (n.status == Status.EXPLORING && n.waiting))
            }
            // A decline observed only after the last work finished did not stop
            // the question. Record DIMINISHING only when a first round that was
            // actually queued is prevented from starting.
            if (halted.none { it.status == Status.QUEUED }) return@update null
            diminished += c.root
            halted.onEach { n ->
                n.waiting = false
                n.queueGeneration++ // its queued task, if any, falls through
                n.status = if (n.override == Override.STOP) Status.STOPPED else Status.DIMINISHING
            }
            halted to ys.size
        }
        if (stopped != null) {
            val (halted, rounds) = stopped
            System.err.println(
                "deliberate: question ${c.root.id} stopped, returns diminished " +
                    "($rounds rounds, ${halted.size} claims left unexplored)",
            )
        }
    }

    /**
     * Caller holds [lock]. SPEC §12: [root]'s spend, its projection — spent +
     * claims still to explore × mean cost per completed round, once
     * [PROJECTION_MIN_ROUNDS] rounds completed — and the per-backend details.
     */
    private fun costOf(root: CellRef, tree: List<Claim>): CostDto {
        val tallies = costs[root].orEmpty()
        val backends = (BACKEND_ORDER + tallies.keys.sorted()).distinct().mapNotNull { b ->
            val t = tallies[b] ?: return@mapNotNull null
            val info = pricing.info(b)
            BackendCostDto(
                backend = b, models = t.models, calls = t.calls,
                inputTokens = t.inputTokens, cachedInputTokens = t.cachedInputTokens, cacheWriteTokens = t.cacheWriteTokens,
                outputTokens = t.outputTokens, reasoningTokens = t.reasoningTokens,
                usd = t.usd.takeIf { t.unpricedCalls < t.calls }, unpricedCalls = t.unpricedCalls,
                rate = info.rate, rateSource = info.source, rateDate = info.date, assumed = info.assumed, note = info.note,
            )
        }
        val rounds = tree.sumOf { it.rounds }
        val spent = tallies.values.sumOf { it.usd }
        return CostDto(
            complete = root in completeCosts,
            backends = backends,
            rounds = rounds,
            queued = tree.count { it.status in ACTIVE },
            // A legacy question's earlier spend and cost-per-round denominator are unknown.
            perRoundUsd = if (root in completeCosts && rounds >= PROJECTION_MIN_ROUNDS) spent / rounds else null,
        )
    }

    private fun QuestionDto.withCost(c: CostDto): QuestionDto {
        val spent = c.backends.sumOf { it.usd ?: 0.0 }
        return copy(costUsd = spent, projectedUsd = c.perRoundUsd?.let { spent + c.queued * it }, cost = c)
    }

    /** SPEC §12: the sink bound around every call made for question [root]. */
    private fun sinkFor(root: CellRef) = UsageSink { u -> recordUsage(root, u) }

    private fun recordUsage(root: CellRef, u: CallUsage) {
        val usd = try {
            pricing.price(u)
        } catch (e: Exception) {
            null
        }
        update {
            val tallies = costs.getOrPut(root) { LinkedHashMap() }
            tallies[u.backend] = (tallies[u.backend] ?: BackendTally()).plus(u, usd)
        }
    }

    /** Caller holds [lock]. Why [root]'s tree stopped growing early, if it did (QuestionDto.stoppedBy). */
    private fun stoppedBy(root: CellRef): String? = when {
        (treeSize[root] ?: 0) >= config.maxClaims -> "budget"
        root in diminished -> "diminishing"
        else -> null
    }

    /**
     * One Jev request per argument (plausibility, strength, quality, relevance),
     * all in parallel; then reach and contribution in creation order, so an
     * argument refining another one attached in the same round sees its reach.
     */
    private fun assess(nodes: List<Claim>) {
        val futures = nodes.map { n ->
            val (question, path, text) = synchronized(lock) {
                // EXP-03 UNDERCUT: an undercutter is judged against the link it attacks.
                val p = pathOf(n).let { path -> n.undercuts?.let { path + linkOf(it) } ?: path }
                Triple(questions.getValue(n.root), p, n.text)
            }
            n to calls.submit<Assessment?> { attempt(n, "assess") { judge.assess(question, path, text, n.side!!) } }
        }
        for ((n, f) in futures) {
            val a = try {
                f.get()
            } catch (e: Exception) {
                null
            }
            val edge = synchronized(lock) { n.edge!! }
            if (a != null) synchronized(serviceLock) {
                service.setStance(n.ref, JEV, a.plausibility)
                service.setStance(edge.ref, JEV, a.strength)
            }
            update {
                if (a != null) {
                    n.plausibility = a.plausibility
                    n.relevance = a.relevance
                    n.quality = a.quality
                    edge.strength = a.strength
                }
                val reach = reachOf(n)
                n.reach = reach
                n.contribution = reach * (n.relevance ?: 1.0) * (n.quality ?: 1.0)
            }
        }
    }

    /**
     * Caller holds [lock]. EXP-05: reach decays by the edge strength; a failed
     * judgment assumes FALLBACK_STRENGTH. An undercutter's reach also decays by
     * the strength of the link it attacks: reach(parent) × strength(its edge)
     * × strength(the undercut edge).
     */
    private fun reachOf(n: Claim): Double {
        fun strength(e: Edge?) = (e?.strength ?: Config.FALLBACK_STRENGTH).coerceIn(0.0, 1.0)
        val base = (n.parent!!.reach ?: 1.0) * strength(n.edge)
        return n.undercuts?.let { base * strength(it.edge) } ?: base
    }

    /** Caller holds [lock]. The statement an undercutter of [arg] denies: that [arg] bears on its parent. */
    private fun linkOf(arg: Claim): String {
        val direction = if (arg.side == Polarity.SUPPORT) "is a reason to accept" else "is a reason to reject"
        return "The argument \"${arg.text}\" $direction the claim \"${arg.parent!!.text}\"."
    }

    private fun attach(parent: Claim, side: Side, f: Fresh): Claim {
        val (childRef, edgeRef) = synchronized(serviceLock) {
            val child = service.createClaim(f.text)
            child to service.createEdge(child, parent.ref, side)
        }
        val child = Claim(childRef, parent.root, parent, side, f.text, parent.depth + 1, f.proposer, config.maxRounds)
        val edge = Edge(edgeRef, parent.root, childRef, parent.ref, side)
        update {
            child.edge = edge
            child.alsoProposedBy += f.also
            claims[childRef] = child
            edges[edgeRef] = edge
            parent.children += child
        }
        return child
    }

    /**
     * EXP-03 UNDERCUT: [f] attacks the *edge* of argument [target], so it lowers
     * the edge's credence and with it the argument's influence. For paths and
     * context it is a claim about [target]'s parent, at [target]'s depth.
     */
    private fun attachUndercut(target: Claim, f: Fresh): Claim {
        val (targetEdge, parent) = synchronized(lock) { target.edge!! to target.parent!! }
        val (childRef, edgeRef) = synchronized(serviceLock) {
            val child = service.createClaim(f.text)
            child to service.createEdge(child, targetEdge.ref, Polarity.ATTACK)
        }
        val child = Claim(childRef, parent.root, parent, Polarity.ATTACK, f.text, parent.depth + 1, f.proposer, config.maxRounds, undercuts = target)
        val edge = Edge(edgeRef, parent.root, childRef, targetEdge.ref, Polarity.ATTACK)
        update {
            child.edge = edge
            child.alsoProposedBy += f.also
            claims[childRef] = child
            edges[edgeRef] = edge
            target.undercutters += child
        }
        return child
    }

    /** Caller holds [lock]. EXP-03 REPLACE only rewords an argument nobody has explored or undercut yet. */
    private fun replaceable(t: Claim) = t.parent != null && t.status == Status.QUEUED &&
        t.children.isEmpty() && t.undercutters.isEmpty() && t.rounds == 0

    /** Reserve an unexplored queued target and invalidate any task carrying its old priority/text. */
    private fun beginRewrite(t: Claim): RewriteReservation? = update {
        if (!replaceable(t)) return@update null
        if (t.rewriteInFlight) return@update RewriteReservation(created = false, wasQueued = t.rewriteWasQueued)
        val wasQueued = t.queueGeneration > 0
        t.rewriteInFlight = true
        t.rewriteWasQueued = wasQueued
        t.queueGeneration++
        RewriteReservation(created = true, wasQueued = wasQueued)
    }

    /** Undo a failed first rewrite reservation and restore the target's invalidated queue entry. */
    private fun cancelRewrite(t: Claim, reservation: RewriteReservation) {
        if (!reservation.created) return
        val resume = update {
            t.rewriteInFlight = false
            t.rewriteWasQueued = false
            reservation.wasQueued && t.status == Status.QUEUED
        }
        if (resume) enqueue(t)
    }

    /**
     * EXP-03 MERGE: rewrites [t] and the candidate as one sentence via the
     * [merger] and records the candidate's proposers on [t]. Returns false
     * (the caller falls back to DUPLICATE) when there is no merger, the call
     * failed (error recorded on [c]), or [t] got explored meanwhile.
     */
    private fun merge(c: Claim, t: Claim, f: Fresh): Boolean {
        val m = merger ?: return false
        val reservation = beginRewrite(t) ?: return false
        val (claim, current) = synchronized(lock) { c.text to t.text }
        val merged = try {
            Usage.within(sinkFor(c.root)) { m.merge(claim, t.side!!, current, f.text) }
        } catch (e: Exception) {
            update { c.error = "merge: $e" }
            cancelRewrite(t, reservation)
            return false
        }
        return update {
            (t.rewriteInFlight && replaceable(t)).also { ok ->
                if (ok) {
                    t.text = merged
                    t.merged = true
                    (listOf(f.proposer) + f.also).filter { it != t.proposer && it !in t.alsoProposedBy }.distinct()
                        .forEach { t.alsoProposedBy += it }
                }
            }
        }.also { if (!it) cancelRewrite(t, reservation) }
    }

    /** EXP-03 REPLACE: [t] takes the candidate's text and provenance; its old proposer is kept as `also`. */
    private fun replace(t: Claim, f: Fresh): Boolean {
        val reservation = beginRewrite(t) ?: return false
        return update {
            (t.rewriteInFlight && replaceable(t)).also { ok ->
                if (ok) {
                    val others = (listOf(t.proposer) + t.alsoProposedBy + f.also).filter { it != f.proposer }.distinct()
                    t.alsoProposedBy.clear()
                    t.alsoProposedBy += others
                    t.proposer = f.proposer
                    t.text = f.text
                }
            }
        }.also { if (!it) cancelRewrite(t, reservation) }
    }

    /** A rewritten sentence must never retain judgments made about its previous wording. */
    private fun clearAssessment(n: Claim) {
        val edge = synchronized(lock) { n.edge!! }
        update {
            n.plausibility = null
            n.relevance = null
            n.quality = null
            edge.strength = null
            val reach = reachOf(n)
            n.reach = reach
            n.contribution = reach
        }
        synchronized(serviceLock) {
            service.setStance(n.ref, JEV, null)
            service.setStance(edge.ref, JEV, null)
        }
    }

    /** EXP-03 DUPLICATE: record the extra proposers on the argument that already makes the point. */
    private fun mergeProposers(t: Claim, ids: List<String>) = update {
        ids.filter { it != t.proposer && it !in t.alsoProposedBy }.distinct().forEach { t.alsoProposedBy += it }
    }

    /**
     * Ends [c]'s expansion with [status] and returns true. Its arguments have
     * already joined the queue after their attach-time assessments.
     * EXP-06: a claim that already ran a round and then meets the budget ends
     * ROUND_LIMIT with error [Config.BUDGET_EXHAUSTED]; BUDGET is kept for a
     * claim that would have expanded but never did.
     */
    private fun finish(c: Claim, status: Status, error: String? = null): Boolean = update {
        c.waiting = false
        // A STOP that raced the last round boundary still wins (CTL-03).
        c.status = when {
            c.override == Override.STOP -> Status.STOPPED
            status == Status.BUDGET && c.rounds > 0 -> Status.ROUND_LIMIT.also { c.error = Config.BUDGET_EXHAUSTED }
            else -> status
        }
        if (error != null) c.error = error
        true
    }

    // ---------------------------------------------------------------- durability (SPEC §11)

    /**
     * One claim's engine metadata as the [store] keeps it, one field per
     * property; a property at its default is not stored. [text] is stored
     * only when a rewrite (EXP-03 REPLACE/MERGE) changed it: the structure
     * log holds the original. The `jev` stances are [plausibility] and
     * [edgeStrength]; nothing else of the credence graph is persisted.
     */
    @Serializable
    private data class ClaimRecord(
        /** The root ref of the question tree this claim belongs to. */
        val question: String? = null,
        val text: String? = null,
        val proposer: String? = null,
        val status: Status = Status.QUEUED,
        val override: Override = Override.AUTO,
        val roundLimit: Int? = null,
        val rounds: Int = 0,
        val forceRound: Boolean = false,
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
        val merged: Boolean = false,
        val error: String? = null,
        val anyCallSucceeded: Boolean = false,
        val edgeStrength: Double? = null,
    )

    /** Caller holds [lock]. */
    private fun recordOf(c: Claim) = ClaimRecord(
        question = c.root.id.toString(),
        text = c.text.takeIf { it != c.structureText }, proposer = c.proposer, status = c.status, override = c.override,
        roundLimit = c.roundLimit, rounds = c.rounds, forceRound = c.forceRound,
        plausibility = c.plausibility, relevance = c.relevance, quality = c.quality,
        reach = c.reach.takeIf { c.parent != null }, contribution = c.contribution.takeIf { c.parent != null },
        proSaturation = c.proSaturation, conSaturation = c.conSaturation, saturated = c.saturated.toList(),
        duplicatesDropped = c.duplicatesDropped, triage = c.triage.mapKeys { it.key.name },
        alsoProposedBy = c.alsoProposedBy.toList(), merged = c.merged, error = c.error,
        anyCallSucceeded = c.anyCallSucceeded, edgeStrength = c.edge?.strength,
    )

    /** EXP-10: a question's record — its non-root round yields and whether they halted queued work. */
    @Serializable
    private data class QuestionRecord(
        /** Required so a question with no non-root round yet still has its one durable record (DUR-03). */
        val yields: List<Double>,
        val diminished: Boolean = false,
        /** Missing/false identifies a record written before cost tracking existed. */
        val costComplete: Boolean = false,
    )

    /** Caller holds [lock]. */
    private fun questionFieldsOf(q: CellRef): Map<String, String> =
        RECORDS.encodeToJsonElement(
            QuestionRecord.serializer(),
            QuestionRecord(yields[q].orEmpty().toList(), q in diminished, q in completeCosts),
        ).jsonObject.mapValues { it.value.toString() } +
            // SPEC §12: one field per backend, so a call rewrites only its backend's counters.
            costs[q].orEmpty().map { (b, t) -> COST_FIELD + b to RECORDS.encodeToString(BackendTally.serializer(), t) }

    private fun fieldsOf(r: ClaimRecord): Map<String, String> =
        RECORDS.encodeToJsonElement(ClaimRecord.serializer(), r).jsonObject.mapValues { it.value.toString() }

    private fun recordFrom(fields: Map<String, String>): ClaimRecord =
        RECORDS.decodeFromJsonElement(ClaimRecord.serializer(), JsonObject(fields.mapValues { RECORDS.parseToJsonElement(it.value) }))

    /**
     * SPEC §11: rebuilds every tree from the graph's structure (claims and the
     * edges linking them, in creation order) and the [meta] records,
     * re-applies the `jev` stances (the graph recomputes every credence from
     * them), then re-queues every claim that was still active — an
     * interrupted round simply runs again. A claim whose record never reached
     * the store is rebuilt from the structure alone and queued afresh; a claim
     * the structure holds without the edge that would place it in a tree (the
     * process died between the two writes) is left out. Arguments that were
     * never assessed are assessed again before they are queued.
     */
    private fun restore(meta: Map<String, Map<String, String>>) {
        val records = meta.filterKeys { it.startsWith(CLAIM_KEY) }.entries.associate { (k, v) ->
            CellRef(UUID.fromString(k.removePrefix(CLAIM_KEY))) to recordFrom(v)
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
        val graph = service.graph()
        val attaching = graph.filter { it.info.kind == CredenceGraph.Kind.EDGE }.groupBy { it.info.source }
        synchronized(lock) {
            for (n in graph) {
                if (n.info.kind != CredenceGraph.Kind.CLAIM) continue
                val rec = records[n.ref]
                val structureText = n.info.text.orEmpty()
                val claim = if (n.info.question || rec?.question == n.ref.id.toString()) {
                    questions[n.ref] = structureText
                    Claim(n.ref, n.ref, null, null, structureText, 0, "question", config.maxRounds)
                } else run {
                    val e = attaching[n.ref]?.firstOrNull() ?: return@run null
                    val target = e.info.target!!
                    val undercut = edges[target]?.let { claims[it.source] }
                    val parent = undercut?.parent ?: claims[target] ?: return@run null
                    val side = e.info.polarity!!
                    Claim(
                        n.ref, parent.root, parent, side, structureText, parent.depth + 1,
                        "unknown", config.maxRounds, undercut,
                    ).also { child ->
                        val edge = Edge(e.ref, parent.root, n.ref, target, side)
                        child.edge = edge
                        edges[e.ref] = edge
                        if (undercut != null) undercut.undercutters += child else parent.children += child
                    }
                } ?: continue
                rec?.let { r -> apply(claim, r) }
                claims[n.ref] = claim
                treeSize.merge(claim.root, 1, Int::plus)
            }
            for ((q, r) in questionRecords) {
                if (q !in questions) continue
                yields[q] = r.yields.toMutableList()
                if (r.diminished) diminished += q
                if (r.costComplete) completeCosts += q
            }
            for ((q, tallies) in costRecords) {
                if (q in questions && tallies.isNotEmpty()) costs[q] = LinkedHashMap(tallies)
            }
        }
        val stances = synchronized(lock) {
            claims.values.mapNotNull { c -> c.plausibility?.let { c.ref to it } } +
                edges.values.mapNotNull { e -> e.strength?.let { e.ref to it } }
        }
        // The graph skips a stance a node already holds (CredenceGraph.setStance).
        synchronized(serviceLock) { stances.forEach { (ref, v) -> service.setStance(ref, JEV, v) } }
        val (unassessed, queued) = synchronized(lock) {
            claims.values.filter { it.status == Status.QUEUED }
                .partition { it.parent != null && it.plausibility == null && it.edge?.strength == null }
        }
        queued.forEach(::schedule)
        unassessed.groupBy { it.root }.values.forEach { group ->
            pending.incrementAndGet()
            calls.submit {
                try {
                    assess(group)
                    group.forEach(::schedule)
                } finally {
                    done()
                }
            }
        }
        if (claims.isNotEmpty()) onChange()
    }

    /** Caller holds [lock]. */
    private fun apply(c: Claim, r: ClaimRecord) {
        r.text?.let { c.text = it }
        r.proposer?.let { c.proposer = it }
        // SPEC §11: whatever was still active re-enters the queue; an interrupted round re-runs.
        c.status = if (r.status in ACTIVE) Status.QUEUED else r.status
        c.override = r.override
        r.roundLimit?.let { c.roundLimit = it }
        c.rounds = r.rounds
        c.forceRound = r.forceRound
        c.plausibility = r.plausibility
        c.relevance = r.relevance
        c.quality = r.quality
        if (c.parent != null) {
            c.reach = r.reach
            c.contribution = r.contribution
        }
        c.proSaturation = r.proSaturation
        c.conSaturation = r.conSaturation
        c.saturated += r.saturated
        c.duplicatesDropped = r.duplicatesDropped
        r.triage.forEach { (k, v) -> TriageAction.entries.firstOrNull { it.name == k }?.let { c.triage[it] = v } }
        c.alsoProposedBy += r.alsoProposedBy
        c.merged = r.merged
        c.error = r.error
        c.anyCallSucceeded = r.anyCallSucceeded
        c.edge?.strength = r.edgeStrength
    }

    // ---------------------------------------------------------------- helpers

    /** EXP-08: run a judge call; on failure record the error and return null. */
    private fun <T> attempt(c: Claim, what: String, call: () -> T): T? =
        try {
            Usage.within(sinkFor(c.root), call)
        } catch (e: Exception) {
            update { c.error = "jev $what: $e" }
            null
        }

    /** EXP-04: the per-side cap — `maxArgsPerSide` for the root, `maxArgsPerSideChild` below it. */
    private fun capOf(c: Claim) = if (c.parent == null) config.maxArgsPerSide else config.maxArgsPerSideChild

    /** Caller holds [lock]. How many arguments [c] holds on [side]. */
    private fun countOf(c: Claim, side: Side) = c.children.count { it.side == side }

    /** Caller holds [lock]. EXP-04: [side] of [c] already holds its cap of arguments. */
    private fun atCap(c: Claim, side: Side) = countOf(c, side) >= capOf(c)

    private fun budgetExhausted(c: Claim) = synchronized(lock) { treeSize.getValue(c.root) >= config.maxClaims }

    /** EXP-06: one claim of [root]'s budget; a forced round (CTL-02) always gets it. */
    private fun reserve(root: CellRef, forced: Boolean): Boolean = synchronized(lock) {
        val n = treeSize.getValue(root)
        (forced || n < config.maxClaims).also { if (it) treeSize[root] = n + 1 }
    }

    private fun questionOf(c: Claim) = synchronized(lock) { questions.getValue(c.root) }

    /** Caller holds [lock]. Texts from the root down to (excluding) [c]. */
    private fun pathOf(c: Claim): List<String> =
        generateSequence(c.parent) { it.parent }.map { it.text }.toList().asReversed()

    private fun context(c: Claim): ClaimContext = synchronized(lock) {
        ClaimContext(
            question = questions.getValue(c.root),
            path = pathOf(c),
            claim = c.text,
            pros = c.children.filter { it.side == Polarity.SUPPORT }.map { it.text },
            cons = c.children.filter { it.side == Polarity.ATTACK }.map { it.text },
        )
    }
}
