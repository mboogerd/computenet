package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

/**
 * Grows one deliberation tree per question (SPEC §3–§5) on top of an
 * [AgoraService]: proposers generate arguments, the [judge] steers the
 * exploration and supplies the `jev` stances, agora propagates credence.
 *
 * Threading: claims are expanded by `config.workers` threads pulling from one
 * FIFO queue (so trees grow roughly level by level); each round fans its
 * proposer calls out on a separate call executor. The app-wide bound on
 * concurrent CLI processes (EXP-07) is not the engine's: it is the one
 * [ProcessGate] the CLI proposers share. All engine metadata sits behind [lock], held only for short reads/writes and never across a
 * Judge/Proposer call. Agora mutations go through [serviceLock], preserving
 * the service's single-writer mutation model while worker threads expand.
 */
class DeliberationEngine(
    private val service: AgoraService,
    private val judge: Judge,
    private val proposers: List<Proposer>,
    private val config: Config = Config(),
    private val onChange: () -> Unit = {},
) : AutoCloseable {

    /** Knobs of SPEC §3; the Jev thresholds' defaults come from `CALIBRATION.md` (SPEC §10). */
    data class Config(
        val argsPerCall: Int = 1,
        val maxRounds: Int = 3,
        /** EXP-04: a side whose Jev saturation (1 − p(missing)) reaches this gets no more proposals. */
        val saturation: Double = DEFAULT_SATURATION,
        /** EXP-04: a side holding this many arguments is saturated regardless of Jev. */
        val maxArgsPerSide: Int = 6,
        /** EXP-05: a non-root claim is expanded only when relevance × reach reaches this. */
        val minInfluence: Double = DEFAULT_MIN_INFLUENCE,
        val maxDepth: Int = 3,
        val maxClaims: Int = 60,
        val workers: Int = 8,
    ) {
        init {
            require(workers > 0) { "workers must be positive" }
            require(maxArgsPerSide > 0) { "maxArgsPerSide must be positive" }
        }

        companion object {
            const val DEFAULT_SATURATION = 0.22
            const val DEFAULT_MIN_INFLUENCE = 0.35
            /**
             * EXP-05: reach assumes this edge strength when the CRED-02 judgment
             * failed — middling, so one failure neither prunes nor frees a subtree.
             */
            const val FALLBACK_STRENGTH = 0.5
        }
    }

    private class Claim(
        val ref: CellRef,
        val root: CellRef,
        val parent: Claim?,
        val side: Side?,
        val text: String,
        val depth: Int,
        val proposer: String,
        var roundLimit: Int,
    ) {
        var status = Status.QUEUED
        var override = Override.AUTO
        var plausibility: Double? = null
        var relevance: Double? = null
        /** EXP-05: 1 for the root; parent reach × edge strength once the edge is judged. */
        var reach: Double? = if (parent == null) 1.0 else null
        var proSaturation: Double? = null
        var conSaturation: Double? = null
        var rounds = 0
        var duplicatesDropped = 0
        var error: String? = null
        val saturated = mutableSetOf<Side>()
        /** CTL-02: the next round ignores saturation (a forced re-run). */
        var forceRound = false
        var roundInFlight = false
        var anyCallSucceeded = false
        val children = mutableListOf<Claim>()
    }

    private class Edge(val ref: CellRef, val root: CellRef, val source: CellRef, val target: CellRef, val side: Side) {
        var strength: Double? = null
    }

    private val lock = Any()
    private val serviceLock = Any()
    private val claims = LinkedHashMap<CellRef, Claim>()
    private val edges = LinkedHashMap<CellRef, Edge>()
    private val questions = LinkedHashMap<CellRef, String>()
    private val treeSize = HashMap<CellRef, Int>()

    private val pending = AtomicInteger()
    private val idle = Object()
    private val workers: ExecutorService = ThreadPoolExecutor(
        config.workers, config.workers, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(),
    ) { r -> Thread(r, "deliberate-worker").apply { isDaemon = true } }
    private val calls: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private companion object {
        val FINISHED = setOf(
            Status.SATURATED, Status.ROUND_LIMIT, Status.PRUNED, Status.DEPTH_LIMIT,
            Status.BUDGET, Status.STOPPED, Status.FAILED,
        )
        val ACTIVE = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
        val SIDES = listOf(Polarity.SUPPORT, Polarity.ATTACK)
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';')
    }

    // ---------------------------------------------------------------- API

    /** EXP-01: create the root claim and start expanding it; returns at once. */
    fun ask(question: String): CellRef {
        val ref = synchronized(serviceLock) { service.createClaim(question) }
        val root = Claim(ref, ref, null, null, question, 0, "question", config.maxRounds)
        synchronized(lock) {
            claims[ref] = root
            questions[ref] = question
            treeSize[ref] = 1
        }
        onChange()
        enqueue(root)
        return ref
    }

    /** CTL-01..04. */
    fun setOverride(ref: CellRef, mode: Override) {
        val requeue = synchronized(lock) {
            val c = requireNotNull(claims[ref]) { "unknown claim ${ref.id}" }
            c.override = mode
            when (mode) {
                // CTL-03: queued work is cancelled at once; a running claim stops at its next round boundary.
                Override.STOP -> {
                    if (c.status !in setOf(Status.JUDGING, Status.EXPLORING)) c.status = Status.STOPPED
                    false
                }
                // CTL-04: back through the normal gates.
                Override.AUTO -> (c.status == Status.STOPPED).also { if (it) c.status = Status.QUEUED }
                // CTL-02: a finished claim runs again with one extra round, saturation ignored for it.
                Override.EXPAND -> (c.status in FINISHED && c.status != Status.BUDGET).also { finished ->
                    if (finished) {
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + 1)
                        c.forceRound = true
                        c.status = Status.QUEUED
                    } else if (c.status == Status.JUDGING || c.status == Status.EXPLORING) {
                        // If a round is already in flight, the override belongs to the next round;
                        // do not let completion of this one consume the human's request.
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + if (c.roundInFlight) 2 else 1)
                        c.forceRound = true
                    }
                }
            }
        }
        onChange()
        if (requeue) enqueue(claimOf(ref))
    }

    fun snapshot(): GraphDto {
        val graph = service.graph()
        return synchronized(lock) {
            val nodes = graph.mapNotNull { n ->
                claims[n.ref]?.let { c ->
                    NodeDto(
                        ref = c.ref.id.toString(), kind = "CLAIM", credence = n.credence, root = c.root.id.toString(),
                        text = c.text, depth = c.depth, status = c.status, override = c.override,
                        proposer = c.proposer, plausibility = c.plausibility, relevance = c.relevance, reach = c.reach,
                        proSaturation = c.proSaturation, conSaturation = c.conSaturation, rounds = c.rounds,
                        duplicatesDropped = c.duplicatesDropped, error = c.error,
                    )
                } ?: edges[n.ref]?.let { e ->
                    NodeDto(
                        ref = e.ref.id.toString(), kind = "EDGE", credence = n.credence, root = e.root.id.toString(),
                        polarity = e.side.name, source = e.source.id.toString(), target = e.target.id.toString(),
                        strength = e.strength,
                    )
                }
            }
            val qs = questions.map { (root, text) ->
                val tree = claims.values.filter { it.root == root }
                QuestionDto(root.id.toString(), text, tree.size, tree.any { it.status in ACTIVE })
            }
            GraphDto(qs, nodes)
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

    override fun close() {
        workers.shutdownNow()
        calls.shutdownNow()
    }

    // ---------------------------------------------------------------- scheduling

    private fun claimOf(ref: CellRef): Claim = synchronized(lock) { claims.getValue(ref) }

    private fun enqueue(c: Claim) {
        pending.incrementAndGet()
        try {
            workers.execute {
                try {
                    process(c)
                } finally {
                    done()
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            done() // closed
        }
    }

    private fun done() {
        if (pending.decrementAndGet() == 0) synchronized(idle) { idle.notifyAll() }
    }

    private inline fun <T> update(block: () -> T): T {
        val r = synchronized(lock, block)
        onChange()
        return r
    }

    private fun process(c: Claim) {
        // Claim ownership: only a QUEUED claim is taken; stale/cancelled tasks fall through.
        val taken = synchronized(lock) {
            (c.status == Status.QUEUED).also { if (it) c.status = Status.JUDGING }
        }
        if (!taken) return
        onChange()
        try {
            val children = expand(c)
            children.forEach(::enqueue)
        } catch (t: Throwable) {
            // EXP-08: never let an exception kill a worker or leave a claim stuck.
            update { c.status = Status.FAILED; c.error = t.toString() }
        }
        // An EXPAND racing the terminal transition either queued itself in
        // setOverride(), or left forceRound behind while this invocation was
        // still active. The latter must not be lost at the finish boundary.
        val forcedRequeue = synchronized(lock) {
            (c.override == Override.EXPAND && c.forceRound && c.status in FINISHED && c.status != Status.BUDGET)
                .also { if (it) c.status = Status.QUEUED }
        }
        if (forcedRequeue) {
            onChange()
            enqueue(c)
        }
    }

    /** Runs gates and rounds; returns the children to enqueue. */
    private fun expand(c: Claim): List<Claim> {
        // CRED-01
        if (synchronized(lock) { c.plausibility } == null) {
            val path = synchronized(lock) { pathOf(c) }
            attempt(c, "plausibility") {
                val p = judge.plausibility(questionOf(c), path, c.text)
                synchronized(serviceLock) { service.setStance(c.ref, "jev", p) }
                p
            }?.let { p ->
                update { c.plausibility = p }
            }
        }
        // Gates (EXP-05, EXP-06; CTL-02 skips depth/relevance).
        val forced = synchronized(lock) { c.override == Override.EXPAND }
        val isRoot = c.parent == null
        if (!isRoot && !forced && c.depth > config.maxDepth) return finish(c, Status.DEPTH_LIMIT)
        if (!isRoot && !forced) {
            // EXP-05: influence = relevance × reach. attach() always sets reach before the
            // child is enqueued; if relevance itself cannot be judged, the gate falls back to
            // reach alone (the upper bound of the influence).
            val reach = synchronized(lock) { c.reach } ?: Config.FALLBACK_STRENGTH
            val r = attempt(c, "relevance") { judge.relevance(context(c)) }
            if (r != null) update { c.relevance = r }
            if ((r ?: 1.0) * reach < config.minInfluence) return finish(c, Status.PRUNED)
        }
        // EXP-06 after EXP-05: BUDGET means "would have been expanded", so a claim the
        // influence gate rejects reads PRUNED even once the budget is spent.
        if (budgetExhausted(c)) return finish(c, Status.BUDGET)
        update { c.status = Status.EXPLORING }

        val created = mutableListOf<Claim>()
        while (true) {
            val (sides, forcedRound, terminal) = synchronized(lock) {
                val forcedRound = c.forceRound
                // EXP-04: a side at maxArgsPerSide is saturated (checked here too, so a
                // re-queued claim whose side filled up earlier asks no proposer for it).
                if (!forcedRound) SIDES.filter { atCap(c, it) }.forEach { c.saturated += it }
                val nextSides = if (forcedRound) SIDES else SIDES - c.saturated
                val nextStatus = when {
                    c.override == Override.STOP -> Status.STOPPED
                    nextSides.isEmpty() -> Status.SATURATED
                    !forcedRound && c.rounds >= c.roundLimit -> Status.ROUND_LIMIT
                    treeSize.getValue(c.root) >= config.maxClaims -> Status.BUDGET
                    else -> null
                }
                if (nextStatus == null) {
                    if (forcedRound) c.saturated.clear()
                    c.forceRound = false
                    c.roundInFlight = true
                }
                Triple(nextSides, forcedRound, nextStatus)
            }
            if (terminal != null) return finish(c, terminal, created)
            val outcome = try {
                round(c, sides, forcedRound, created)
            } finally {
                synchronized(lock) { c.roundInFlight = false }
            }
            if (outcome != null) return finish(c, outcome, created)
        }
    }

    /**
     * One round (EXP-02..04). Returns a terminal status when the round ends
     * the expansion early (all calls failed, budget), else null. A [forced]
     * round (CTL-02) ignores saturation, including the per-side cap.
     */
    private fun round(c: Claim, sides: List<Side>, forced: Boolean, created: MutableList<Claim>): Status? {
        val ctx = context(c)
        // EXP-04: never attach beyond maxArgsPerSide within a round.
        val room = sides.associateWith { side ->
            if (forced) Int.MAX_VALUE else synchronized(lock) { config.maxArgsPerSide - c.children.count { it.side == side } }
        }
        // EXP-02: every proposer × side, concurrently.
        val futures = sides.flatMap { side ->
            val ask = minOf(config.argsPerCall, room.getValue(side))
            proposers.map { p ->
                Triple(side, p, calls.submit<List<String>> { p.propose(ctx, side, ask).take(ask) })
            }
        }
        var failures = 0
        val candidates = futures.mapNotNull { (side, p, f) ->
            try {
                Triple(side, p.id, f.get())
            } catch (e: Exception) {
                failures++
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                update { c.error = "${p.id}: $cause" }
                null
            }
        }
        val allProposersFailed = failures == futures.size && futures.isNotEmpty()
        val noProposerHasEverSucceeded = synchronized(lock) {
            if (!allProposersFailed) c.anyCallSucceeded = true
            !c.anyCallSucceeded
        }

        // EXP-03: dedupe within the round, then against existing siblings via the judge.
        var budgetHit = false
        for (side in sides) {
            val existing = synchronized(lock) { c.children.filter { it.side == side }.map { it.text } }
            val seen = existing.map(::normalize).toMutableSet()
            var dropped = 0
            val fresh = mutableListOf<Pair<String, String>>() // text, proposer
            candidates.filter { it.first == side }.forEach { (_, pid, texts) ->
                texts.map(String::trim).filter(String::isNotEmpty).forEach { t ->
                    if (seen.add(normalize(t))) fresh += t to pid else dropped++
                }
            }
            val survivors = if (fresh.isEmpty()) fresh else {
                val verdict = attempt(c, "duplicates") { judge.duplicates(c.text, side, existing, fresh.map { it.first }) }
                if (verdict == null) fresh else fresh.filterIndexed { i, _ -> verdict.getOrNull(i) == null }
                    .also { dropped += fresh.size - it.size }
            }
            if (dropped > 0) update { c.duplicatesDropped += dropped }
            // Attach every survivor that fits the budget and the side's cap — even if STOP
            // arrived meanwhile (CTL-03). Survivors past the cap are not attached.
            for ((text, pid) in survivors.take(room.getValue(side))) {
                if (!reserve(c.root)) { budgetHit = true; break }
                created += attach(c, side, text, pid)
            }
        }
        update { c.rounds++ }

        // EXP-04: saturation per side (a forced round's sides are re-judged too).
        val after = context(c)
        for (side in sides) {
            if (synchronized(lock) { atCap(c, side) }) {
                update { c.saturated += side } // the cap decides; no Jev call needed
                continue
            }
            val p = attempt(c, "saturation") { judge.saturation(after, side) } ?: continue
            update {
                if (side == Polarity.SUPPORT) c.proSaturation = p else c.conSaturation = p
                if (p >= config.saturation) c.saturated += side
            }
        }
        return when {
            budgetHit -> Status.BUDGET
            allProposersFailed && noProposerHasEverSucceeded -> Status.FAILED
            else -> null
        }
    }

    private fun attach(parent: Claim, side: Side, text: String, proposerId: String): Claim {
        val (childRef, edgeRef) = synchronized(serviceLock) {
            val child = service.createClaim(text)
            child to service.createEdge(child, parent.ref, side)
        }
        val child = Claim(childRef, parent.root, parent, side, text, parent.depth + 1, proposerId, config.maxRounds)
        val edge = Edge(edgeRef, parent.root, childRef, parent.ref, side)
        update {
            claims[childRef] = child
            edges[edgeRef] = edge
            parent.children += child
        }
        // CRED-02
        val s = attempt(child, "relationStrength") {
            val s = judge.relationStrength(questionOf(parent), parent.text, text, side)
            synchronized(serviceLock) { service.setStance(edgeRef, "jev", s) }
            s
        }
        // EXP-05: reach decays by the edge strength; a failed judgment assumes FALLBACK_STRENGTH
        // (the error is already recorded on the child by attempt()).
        update {
            if (s != null) edge.strength = s
            child.reach = (parent.reach ?: 1.0) * (s ?: Config.FALLBACK_STRENGTH).coerceIn(0.0, 1.0)
        }
        return child
    }

    private fun finish(c: Claim, status: Status, created: List<Claim> = emptyList()): List<Claim> {
        update {
            // A STOP that raced the last round boundary still wins (CTL-03).
            c.status = if (c.override == Override.STOP) Status.STOPPED else status
        }
        return created
    }

    // ---------------------------------------------------------------- helpers

    /** EXP-08: run a judge call; on failure record the error and return null. */
    private fun <T> attempt(c: Claim, what: String, call: () -> T): T? =
        try {
            call()
        } catch (e: Exception) {
            update { c.error = "jev $what: $e" }
            null
        }

    /** Caller holds [lock]. EXP-04: [side] of [c] already holds maxArgsPerSide arguments. */
    private fun atCap(c: Claim, side: Side) = c.children.count { it.side == side } >= config.maxArgsPerSide

    private fun budgetExhausted(c: Claim) = synchronized(lock) { treeSize.getValue(c.root) >= config.maxClaims }

    private fun reserve(root: CellRef): Boolean = synchronized(lock) {
        val n = treeSize.getValue(root)
        (n < config.maxClaims).also { if (it) treeSize[root] = n + 1 }
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
