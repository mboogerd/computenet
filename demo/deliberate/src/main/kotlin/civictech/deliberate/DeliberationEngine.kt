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
 * proposer calls out on a separate call executor. All engine metadata sits
 * behind [lock], held only for short reads/writes and never across a
 * Judge/Proposer call. Agora structure mutations go through [serviceLock]
 * because `AgoraService.createClaim`/`createEdge` are single-writer.
 */
class DeliberationEngine(
    private val service: AgoraService,
    private val judge: Judge,
    private val proposers: List<Proposer>,
    private val config: Config = Config(),
    private val onChange: () -> Unit = {},
) : AutoCloseable {

    data class Config(
        val argsPerCall: Int = 2,
        val maxRounds: Int = 3,
        val saturation: Double = 0.7,
        val relevance: Double = 0.5,
        val maxDepth: Int = 3,
        val maxClaims: Int = 60,
        val workers: Int = 8,
    )

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
        var proSaturation: Double? = null
        var conSaturation: Double? = null
        var rounds = 0
        var duplicatesDropped = 0
        var error: String? = null
        val saturated = mutableSetOf<Side>()
        /** CTL-02: the next round ignores saturation (a forced re-run). */
        var forceRound = false
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
                Override.STOP -> { if (c.status == Status.QUEUED) c.status = Status.STOPPED; false }
                // CTL-04: back through the normal gates.
                Override.AUTO -> (c.status == Status.STOPPED).also { if (it) c.status = Status.QUEUED }
                // CTL-02: a finished claim runs again with one extra round, saturation ignored for it.
                Override.EXPAND -> (c.status in FINISHED).also {
                    if (it) {
                        c.roundLimit = if (c.rounds == 0) config.maxRounds else c.rounds + 1
                        c.forceRound = true
                        c.status = Status.QUEUED
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
                        proposer = c.proposer, plausibility = c.plausibility, relevance = c.relevance,
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
    }

    /** Runs gates and rounds; returns the children to enqueue. */
    private fun expand(c: Claim): List<Claim> {
        // CRED-01
        if (synchronized(lock) { c.plausibility } == null) {
            val path = synchronized(lock) { pathOf(c) }
            attempt(c, "plausibility") { judge.plausibility(questionOf(c), path, c.text) }?.let { p ->
                service.setStance(c.ref, "jev", p)
                update { c.plausibility = p }
            }
        }
        // Gates (EXP-05, EXP-06; CTL-02 skips depth/relevance).
        val forced = synchronized(lock) { c.override == Override.EXPAND }
        val isRoot = c.parent == null
        if (!isRoot && !forced && c.depth > config.maxDepth) return finish(c, Status.DEPTH_LIMIT)
        if (budgetExhausted(c)) return finish(c, Status.BUDGET)
        if (!isRoot && !forced) {
            val r = attempt(c, "relevance") { judge.relevance(context(c)) }
            if (r != null) {
                update { c.relevance = r }
                if (r < config.relevance) return finish(c, Status.PRUNED)
            }
        }
        update { c.status = Status.EXPLORING }

        val created = mutableListOf<Claim>()
        while (true) {
            val (sides, stop, limit) = synchronized(lock) {
                val s = if (c.forceRound) SIDES else SIDES - c.saturated
                Triple(s, c.override == Override.STOP, c.rounds >= c.roundLimit)
            }
            when {
                stop -> return finish(c, Status.STOPPED, created)
                sides.isEmpty() -> return finish(c, Status.SATURATED, created)
                limit -> return finish(c, Status.ROUND_LIMIT, created)
                budgetExhausted(c) -> return finish(c, Status.BUDGET, created)
            }
            val outcome = round(c, sides, created)
            if (outcome != null) return finish(c, outcome, created)
        }
    }

    /**
     * One round (EXP-02..04). Returns a terminal status when the round ends
     * the expansion early (all calls failed, budget), else null.
     */
    private fun round(c: Claim, sides: List<Side>, created: MutableList<Claim>): Status? {
        val ctx = context(c)
        // EXP-02: every proposer × side, concurrently.
        val futures = sides.flatMap { side ->
            proposers.map { p ->
                Triple(side, p, calls.submit<List<String>> { p.propose(ctx, side, config.argsPerCall).take(config.argsPerCall) })
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
        if (failures == futures.size && futures.isNotEmpty()) {
            val never = synchronized(lock) { c.rounds++; !c.anyCallSucceeded }
            return if (never) Status.FAILED else Status.ROUND_LIMIT
        }
        synchronized(lock) { c.anyCallSucceeded = true }

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
            val survivors = if (existing.isEmpty() || fresh.isEmpty()) fresh else {
                val verdict = attempt(c, "duplicates") { judge.duplicates(c.text, side, existing, fresh.map { it.first }) }
                if (verdict == null) fresh else fresh.filterIndexed { i, _ -> verdict.getOrNull(i) == null }
                    .also { dropped += fresh.size - it.size }
            }
            if (dropped > 0) update { c.duplicatesDropped += dropped }
            // Attach every survivor that fits the budget — even if STOP arrived meanwhile (CTL-03).
            for ((text, pid) in survivors) {
                if (!reserve(c.root)) { budgetHit = true; break }
                created += attach(c, side, text, pid)
            }
        }
        update { c.rounds++ }

        // EXP-04: saturation per side (a forced round's sides are re-judged too).
        val wasForced = synchronized(lock) { c.forceRound.also { c.forceRound = false } }
        if (wasForced) synchronized(lock) { c.saturated.clear() }
        val after = context(c)
        for (side in sides) {
            val p = attempt(c, "saturation") { judge.saturation(after, side) } ?: continue
            update {
                if (side == Polarity.SUPPORT) c.proSaturation = p else c.conSaturation = p
                if (p >= config.saturation) c.saturated += side
            }
        }
        return if (budgetHit) Status.BUDGET else null
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
        attempt(parent, "relationStrength") { judge.relationStrength(questionOf(parent), parent.text, text, side) }?.let { s ->
            service.setStance(edgeRef, "jev", s)
            update { edge.strength = s }
        }
        return child
    }

    private fun finish(c: Claim, status: Status, created: List<Claim> = emptyList()): List<Claim> {
        update {
            // A STOP that raced the last round boundary still wins (CTL-03).
            c.status = if (c.override == Override.STOP && status != Status.FAILED) Status.STOPPED else status
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
