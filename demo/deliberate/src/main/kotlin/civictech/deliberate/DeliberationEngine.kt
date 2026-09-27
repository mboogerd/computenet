package civictech.deliberate

import civictech.agora.AgoraService
import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow
import kotlin.time.Duration

/**
 * Grows one deliberation tree per question (SPEC §3–§5) on top of an
 * [AgoraService]: proposers generate arguments, the [judge] steers the
 * exploration and supplies the `jev` stances, agora propagates credence.
 *
 * Threading: claims are expanded by `config.workers` threads pulling from one
 * priority queue (SPEC §3 "Exploration order"). One queue task runs one round:
 * a task's priority is the claim's contribution (reach × relevance × quality,
 * judged by Jev when the argument was attached; the root is 1) times
 * `roundDecay` per round the claim already ran, so a claim with rounds left
 * re-enters the queue behind stronger fresh work. An argument enters the queue
 * as soon as its attach-time assessment completes. Each round fans its proposer
 * calls and the assessments of its new arguments out on a separate call executor. The
 * app-wide bound on concurrent CLI processes (EXP-07) is not the engine's: it
 * is the one [ProcessGate] the CLI proposers share. All engine metadata sits
 * behind [lock], held only for short reads/writes and never across a
 * Judge/Proposer call. Agora mutations go through [serviceLock], preserving
 * the service's single-writer mutation model while worker threads expand.
 */
class DeliberationEngine(
    private val service: AgoraService,
    private val judge: Judge,
    private val proposers: List<Proposer>,
    private val config: Config = Config(),
    /** EXP-03 MERGE; without one, a MERGE verdict is handled as DUPLICATE. */
    private val merger: Merger? = null,
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
        val maxClaims: Int = 60,
        val workers: Int = 8,
    ) {
        init {
            require(workers > 0) { "workers must be positive" }
            require(maxArgsPerSide > 0) { "maxArgsPerSide must be positive" }
            require(maxArgsPerSideChild > 0) { "maxArgsPerSideChild must be positive" }
            require(roundDecay in 0.0..1.0) { "roundDecay must be in [0,1]" }
        }

        companion object {
            const val DEFAULT_SATURATION = 0.22
            const val DEFAULT_MIN_INFLUENCE = 0.35
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

    private class Claim(
        val ref: CellRef,
        val root: CellRef,
        val parent: Claim?,
        val side: Side?,
        /** EXP-03 REPLACE may swap it while the claim is still unexplored (agora's text is immutable). */
        var text: String,
        val depth: Int,
        var proposer: String,
        var roundLimit: Int,
    ) {
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
        val saturated = mutableSetOf<Side>()
        /** CTL-02: the next round ignores saturation (a forced re-run). */
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
        val children = mutableListOf<Claim>()
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

    private val pending = AtomicInteger()
    private val idle = Object()
    private val queue = PriorityBlockingQueue<Task>(64, compareByDescending<Task> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    @Volatile private var closed = false
    private val calls: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val workerThreads: List<Thread> = List(config.workers) { i ->
        Thread.ofPlatform().daemon().name("deliberate-worker-$i").start(::work)
    }

    private companion object {
        val FINISHED = setOf(
            Status.SATURATED, Status.ROUND_LIMIT, Status.PRUNED, Status.DEPTH_LIMIT,
            Status.BUDGET, Status.STOPPED, Status.FAILED,
        )
        val ACTIVE = setOf(Status.QUEUED, Status.JUDGING, Status.EXPLORING)
        val SIDES = listOf(Polarity.SUPPORT, Polarity.ATTACK)
        val Side.opposite get() = if (this == Polarity.SUPPORT) Polarity.ATTACK else Polarity.SUPPORT
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
                // CTL-02: a finished claim runs again with one extra round, saturation ignored for it.
                Override.EXPAND -> when {
                    c.status in FINISHED && c.status != Status.BUDGET -> {
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + 1)
                        c.forceRound = true
                        c.status = Status.QUEUED
                        listOf(c)
                    }
                    c.status == Status.QUEUED -> {
                        // Jump the queue: the stale task finds the claim taken and falls through.
                        enqueueAgain = c
                        emptyList()
                    }
                    c.status == Status.JUDGING || c.status == Status.EXPLORING -> {
                        // If a round is already in flight, the override belongs to the next round;
                        // do not let completion of this one consume the human's request.
                        c.roundLimit = maxOf(c.roundLimit, c.rounds + if (c.roundInFlight) 2 else 1)
                        c.forceRound = true
                        if (c.waiting) enqueueAgain = c
                        emptyList()
                    }
                    else -> emptyList()
                }
            }
        }
        onChange()
        toSchedule.forEach(::schedule)
        enqueueAgain?.let(::enqueue)
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
                        quality = c.quality, contribution = c.contribution,
                        proSaturation = c.proSaturation, conSaturation = c.conSaturation, rounds = c.rounds,
                        duplicatesDropped = c.duplicatesDropped, error = c.error,
                        alsoProposedBy = c.alsoProposedBy.toList().ifEmpty { null },
                        merged = c.merged.takeIf { it },
                        triage = c.triage.mapKeys { it.key.name }.ifEmpty { null },
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
        closed = true
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
     * irrelevant or poorly constructed argument is never explored.
     */
    private fun schedule(c: Claim) {
        val gate = synchronized(lock) {
            if (c.status != Status.QUEUED) return
            if (c.parent == null || c.override == Override.EXPAND) null
            else when {
                c.depth > config.maxDepth -> Status.DEPTH_LIMIT
                contributionOf(c) < config.minInfluence -> Status.PRUNED
                else -> null
            }
        }
        if (gate == null) enqueue(c) else finish(c, gate).forEach(::schedule)
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
        val children = try {
            if (continuing) step(c) else start(c)
        } catch (t: Throwable) {
            // EXP-08: never let an exception kill a worker or leave a claim stuck.
            finish(c, Status.FAILED, error = t.toString())
        }
        if (children == null) {
            // SPEC §3 "Exploration order": rounds left, so back into the queue at the decayed priority.
            enqueue(c)
            return
        }
        children.forEach(::schedule)
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

    /**
     * Judges plausibility if it is still missing (the root, or a failed
     * assessment), applies the budget gate and runs the first round. Returns
     * the children to queue once the claim finished, or null when it has
     * rounds left.
     */
    private fun start(c: Claim): List<Claim>? {
        // CRED-01
        if (synchronized(lock) { c.plausibility } == null) {
            val (path, text) = synchronized(lock) { pathOf(c) to c.text }
            attempt(c, "plausibility") {
                val p = judge.plausibility(questionOf(c), path, text)
                synchronized(serviceLock) { service.setStance(c.ref, "jev", p) }
                p
            }?.let { p ->
                update { c.plausibility = p }
            }
        }
        // EXP-06 after EXP-05 (schedule()): BUDGET means "would have been expanded".
        if (budgetExhausted(c)) return finish(c, Status.BUDGET)
        update { c.status = Status.EXPLORING }
        return step(c)
    }

    /**
     * Runs one round of [c] if it has one left. Returns the children to
     * queue once the claim finished, or null when it has rounds left (the
     * caller re-queues it, marked [Claim.waiting]).
     */
    private fun step(c: Claim): List<Claim>? {
        val (sides, forcedRound, terminal) = synchronized(lock) {
            val forcedRound = c.forceRound
            val nextStatus = terminalStatus(c)
            val nextSides = if (forcedRound) SIDES else SIDES - c.saturated
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
        // Finish now if nothing is left, so a done claim releases its children without a queue trip.
        val next = synchronized(lock) { terminalStatus(c).also { if (it == null) c.waiting = true } }
        return if (next != null) finish(c, next) else null
    }

    /**
     * Caller holds [lock]. The status that ends [c]'s expansion before its next
     * round, or null if it gets one. EXP-04: a side at its cap is marked
     * saturated here too, so a re-queued claim whose side filled up earlier asks
     * no proposer for it.
     */
    private fun terminalStatus(c: Claim): Status? {
        val forcedRound = c.forceRound
        if (!forcedRound) SIDES.filter { atCap(c, it) }.forEach { c.saturated += it }
        val nextSides = if (forcedRound) SIDES else SIDES - c.saturated
        return when {
            c.override == Override.STOP -> Status.STOPPED
            nextSides.isEmpty() -> Status.SATURATED
            !forcedRound && c.rounds >= c.roundLimit -> Status.ROUND_LIMIT
            treeSize.getValue(c.root) >= config.maxClaims -> Status.BUDGET
            else -> null
        }
    }

    /**
     * One round (EXP-02..05). Returns a terminal status when the round ends
     * the expansion early (all calls failed, budget), else null. A [forced]
     * round (CTL-02) ignores saturation, including the per-side cap.
     */
    private fun round(c: Claim, sides: List<Side>, forced: Boolean): Status? {
        val (ctx, existing) = synchronized(lock) {
            val pros = c.children.filter { it.side == Polarity.SUPPORT }
            val cons = c.children.filter { it.side == Polarity.ATTACK }
            ClaimContext(questions.getValue(c.root), pathOf(c), c.text, pros.map { it.text }, cons.map { it.text }) to
                (pros + cons)
        }
        // EXP-04: never attach beyond the cap within a round. Both sides, since OTHER_SIDE
        // may move an argument onto a side that was not asked this round.
        val room = SIDES.associateWith { side ->
            if (forced) Int.MAX_VALUE else capOf(c) - existing.count { it.side == side }
        }.toMutableMap()
        // EXP-02: every proposer × side, concurrently.
        val futures = sides.flatMap { side ->
            val ask = minOf(config.argsPerCall, room.getValue(side))
            proposers.map { p ->
                Triple(side, p, calls.submit<List<String>> { p.propose(ctx, side, ask).take(ask) })
            }
        }
        var failures = 0
        val proposals = futures.mapNotNull { (side, p, f) ->
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

        // EXP-03 step 1: exact-text duplicates (of an existing argument or of an earlier proposal).
        val counts = sortedMapOf<TriageAction, Int>()
        fun count(a: TriageAction) = counts.merge(a, 1, Int::plus)
        var dropped = 0
        val byText = HashMap<String, Any>()
        existing.forEach { byText[normalize(it.text)] = it }
        val fresh = mutableListOf<Fresh>()
        for ((side, pid, texts) in proposals) {
            for (t in texts.map(String::trim).filter(String::isNotEmpty)) {
                when (val hit = byText[normalize(t)]) {
                    is Claim -> { mergeProposers(hit, listOf(pid)); dropped++; count(TriageAction.DUPLICATE) }
                    is Fresh -> { if (pid != hit.proposer && pid !in hit.also) hit.also += pid; dropped++; count(TriageAction.DUPLICATE) }
                    else -> Fresh(t, side, pid).also { fresh += it; byText[normalize(t)] = it }
                }
            }
        }

        // EXP-03 step 2: one Jev triage request for every remaining candidate of the round.
        val verdicts = if (fresh.isEmpty()) emptyList() else
            attempt(c, "triage") { judge.triage(ctx, fresh.map { Candidate(it.text, it.side) }) }
                ?.takeIf { it.size == fresh.size }
                ?: fresh.map { Triage(TriageAction.ADD) }
        val resolved = arrayOfNulls<Claim>(fresh.size)
        val attached = mutableListOf<Claim>()
        val replaced = mutableListOf<Claim>()
        var budgetHit = false
        fun add(parent: Claim, side: Side, f: Fresh): Claim? {
            if (budgetHit) return null
            if (!reserve(c.root)) { budgetHit = true; return null }
            return attach(parent, side, f).also { attached += it }
        }
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
                TriageAction.REPLACE -> {
                    replaced += target!!
                    resolved[i] = target
                }
                TriageAction.MERGE -> {
                    // merge() already rewrote the target
                    replaced += target!!
                    resolved[i] = target
                }
                TriageAction.REFINE -> {
                    val fits = synchronized(lock) { forced || target!!.children.count { it.side == Polarity.SUPPORT } < capOf(target) }
                    resolved[i] = if (fits) add(target!!, Polarity.SUPPORT, f) else target
                }
                TriageAction.DROP -> Unit
            }
        }
        update {
            c.rounds++
            c.duplicatesDropped += dropped
            counts.forEach { (a, n) -> c.triage.merge(a, n, Int::plus) }
        }

        // CRED-01, CRED-02, EXP-05: every new (or reworded: REPLACE, MERGE) argument is assessed at once.
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

    /**
     * One Jev request per argument (plausibility, strength, quality, relevance),
     * all in parallel; then reach and contribution in creation order, so an
     * argument refining another one attached in the same round sees its reach.
     */
    private fun assess(nodes: List<Claim>) {
        val question = nodes.firstOrNull()?.let(::questionOf) ?: return
        val futures = nodes.map { n ->
            val (path, text) = synchronized(lock) { pathOf(n) to n.text }
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
                service.setStance(n.ref, "jev", a.plausibility)
                service.setStance(edge.ref, "jev", a.strength)
            }
            update {
                if (a != null) {
                    n.plausibility = a.plausibility
                    n.relevance = a.relevance
                    n.quality = a.quality
                    edge.strength = a.strength
                }
                // EXP-05: reach decays by the edge strength; a failed judgment assumes
                // FALLBACK_STRENGTH and relevance/quality fall back to 1 (the upper bound).
                val reach = (n.parent!!.reach ?: 1.0) * (edge.strength ?: Config.FALLBACK_STRENGTH).coerceIn(0.0, 1.0)
                n.reach = reach
                n.contribution = reach * (n.relevance ?: 1.0) * (n.quality ?: 1.0)
            }
        }
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

    /** Caller holds [lock]. EXP-03 REPLACE only rewords an argument nobody has explored yet. */
    private fun replaceable(t: Claim) = t.parent != null && t.status == Status.QUEUED && t.children.isEmpty() && t.rounds == 0

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
            m.merge(claim, t.side!!, current, f.text)
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
            val reach = (n.parent!!.reach ?: 1.0) * Config.FALLBACK_STRENGTH
            n.reach = reach
            n.contribution = reach
        }
        synchronized(serviceLock) {
            service.setStance(n.ref, "jev", null)
            service.setStance(edge.ref, "jev", null)
        }
    }

    /** EXP-03 DUPLICATE: record the extra proposers on the argument that already makes the point. */
    private fun mergeProposers(t: Claim, ids: List<String>) = update {
        ids.filter { it != t.proposer && it !in t.alsoProposedBy }.distinct().forEach { t.alsoProposedBy += it }
    }

    /**
     * Ends [c]'s expansion with [status]. Children have already joined the
     * queue after their attach-time assessments.
     * EXP-06: a claim that already ran a round and then meets the budget ends
     * ROUND_LIMIT with error [Config.BUDGET_EXHAUSTED]; BUDGET is kept for a
     * claim that would have expanded but never did.
     */
    private fun finish(c: Claim, status: Status, error: String? = null): List<Claim> = update {
        c.waiting = false
        // A STOP that raced the last round boundary still wins (CTL-03).
        c.status = when {
            c.override == Override.STOP -> Status.STOPPED
            status == Status.BUDGET && c.rounds > 0 -> Status.ROUND_LIMIT.also { c.error = Config.BUDGET_EXHAUSTED }
            else -> status
        }
        if (error != null) c.error = error
        emptyList()
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

    /** EXP-04: the per-side cap — `maxArgsPerSide` for the root, `maxArgsPerSideChild` below it. */
    private fun capOf(c: Claim) = if (c.parent == null) config.maxArgsPerSide else config.maxArgsPerSideChild

    /** Caller holds [lock]. EXP-04: [side] of [c] already holds its cap of arguments. */
    private fun atCap(c: Claim, side: Side) = c.children.count { it.side == side } >= capOf(c)

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
