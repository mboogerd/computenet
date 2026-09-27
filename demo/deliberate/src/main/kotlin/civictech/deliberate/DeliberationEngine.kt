package civictech.deliberate

import civictech.agora.cell.Polarity
import civictech.cell.CellRef
import civictech.deliberate.ExplorationPolicy.Companion.FINISHED
import civictech.deliberate.ExplorationPolicy.Companion.SIDES
import civictech.deliberate.ExplorationPolicy.Companion.opposite
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
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
 * counters ([CostLedger]) that are part of the question's durable record.
 *
 * Structure: this class owns the threads, the locks and the round protocol.
 * The exploration rules — gates, saturation, priority, reach, contribution,
 * yield — are pure functions in [ExplorationPolicy], asked with immutable
 * views taken under [lock]; the durability codec is [EngineRecords]; the
 * UI's view is [GraphProjection]; the per-claim metadata is [Claim] and
 * [EngineState].
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
        val maxDepth: Int = 5,
        val maxClaims: Int = 180,
        val workers: Int = 8,
        /** EXP-10: the per-question diminishing-returns stop; null disables it (yields are still recorded). */
        val yieldStop: YieldStop? = YieldStop(),
        /**
         * SPEC §3 "Links as claims": links compete in the queue like claims. Off, a
         * link is never explored automatically (it ends PRUNED); EXPAND still explores it.
         */
        val exploreLinks: Boolean = true,
        /**
         * SPEC §11 DUR-06 (`--start-paused`): every question restored at boot starts
         * paused (CTL-05), so nothing runs until the human resumes one. Questions asked
         * afterwards run normally.
         */
        val startPaused: Boolean = false,
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

    /** A proposal that survived exact-text dedupe; [also] are the other proposers of the same text. */
    private class Fresh(val text: String, val side: Side, val proposer: String) {
        val also = mutableListOf<String>()
    }

    private class Task(val claim: Claim, val priority: Double, val seq: Long, val generation: Long)

    private data class RewriteReservation(val created: Boolean, val wasQueued: Boolean)

    /** One round's working state (EXP-02..05, EXP-10), shared by its proposer turns. */
    private class Round(val claim: Claim, val sides: List<Side>, val forced: Boolean, cap: Int) {
        val counts = sortedMapOf<TriageAction, Int>()
        var dropped = 0
        val attached = mutableListOf<Claim>()
        val replaced = mutableListOf<Claim>()
        var budgetHit = false
        var asked = 0
        /** EXP-10: arguments asked for this round (the yield's denominator). */
        var requested = 0
        var failures = 0
        /** CTL-02: a forced round's own allowance, shared by its turns. */
        val allowance = SIDES.associateWith { cap }.toMutableMap()

        fun count(a: TriageAction) {
            counts.merge(a, 1, Int::plus)
        }
    }

    private val lock = Any()
    private val serviceLock = Any()
    private val policy = ExplorationPolicy(config)
    /** Guarded by [lock], like every [state] collection. */
    private val ledger = CostLedger(pricing)
    private val projection = GraphProjection(policy, ledger)
    private val state = EngineState()
    private val claims = state.claims
    private val edges = state.edges
    private val questions = state.questions
    private val treeSize = state.treeSize
    private val yields = state.yields
    private val diminished = state.diminished
    private val paused = state.paused

    private val pending = AtomicInteger()
    private val idle = Object()
    private val queue = PriorityBlockingQueue<Task>(64, compareByDescending<Task> { it.priority }.thenBy { it.seq })
    private val seq = AtomicLong()
    @Volatile private var closed = false
    private val calls: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
    private val workerThreads: List<Thread> = List(config.workers) { i ->
        Thread.ofPlatform().daemon().name("deliberate-worker-$i").start(::work)
    }

    private val writer: RecordWriter?
    private val persister: ScheduledExecutorService? = store?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deliberate-persist").apply { isDaemon = true } }
    }

    private companion object {
        fun normalize(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ").trimEnd('.', '!', '?', ';')
        const val JEV = "jev"
    }

    init {
        if (store != null) {
            val restored = store.load()
            // DUR-02 writes only changed records. Seed the write-behind cache
            // from the fold we just restored, so a quiet restart does not
            // append every question and claim to the host journal again.
            writer = RecordWriter(store, restored)
            restore(restored)
            persister!!.scheduleWithFixedDelay({
                try {
                    persistNow()
                } catch (e: Exception) {
                    System.err.println("deliberate: persisting metadata failed: $e")
                }
            }, persistEveryMs, persistEveryMs, TimeUnit.MILLISECONDS)
        } else {
            writer = null
        }
    }

    // ---------------------------------------------------------------- API

    /** EXP-01: create the root claim and start expanding it; returns at once. */
    fun ask(question: String): CellRef {
        val ref = synchronized(serviceLock) { service.createClaim(question, question = true) }
        val root = Claim(ref, ref, null, null, question, 0, Claim.QUESTION, config.maxRounds)
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
        var assessBeforeSchedule: Claim? = null
        val toSchedule: List<Claim> = synchronized(lock) {
            val c = requireNotNull(claims[ref]) { "unknown claim ${ref.id}" }
            c.override = mode
            // STOP cancels queued work; AUTO returns to ordinary gates. Neither keeps an earlier forced round.
            if (mode != Override.EXPAND) c.forceRound = false
            val scheduled = when (mode) {
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
            if (c.needsAssessment && (c in scheduled || enqueueAgain === c)) {
                c.needsAssessment = false
                c.parked = false
                assessBeforeSchedule = c
                enqueueAgain = null
                emptyList()
            } else scheduled
        }
        onChange()
        if (assessBeforeSchedule != null) {
            assessThenSchedule(listOf(assessBeforeSchedule!!))
        } else {
            toSchedule.forEach(::schedule)
            enqueueAgain?.let(::enqueue)
        }
    }

    /**
     * CTL-05: pauses or resumes question [root]. A paused question starts no new
     * round — a round in flight finishes and attaches what it found (as CTL-03),
     * and its queued claims and links stay QUEUED (a waiting claim stays
     * EXPLORING) without being dequeued. Only a forced round (CTL-02 EXPAND) still
     * runs in it: the human asked for that one. Resuming re-schedules everything
     * the pause withheld through the normal gates. Durable (the question record).
     */
    fun setPaused(root: CellRef, paused: Boolean) {
        val withheld = synchronized(lock) {
            require(root in questions) { "unknown question ${root.id}" }
            if (paused) {
                this.paused += root
                null
            } else if (this.paused.remove(root)) {
                claims.values.filter { it.root == root && it.parked }.onEach { it.parked = false }
            } else null
        }
        onChange()
        withheld?.let(::resume)
    }

    /**
     * CTL-05: re-schedules work a pause withheld, exactly as a restart would
     * ([restore]): unassessed arguments are assessed first, then queued with their links.
     */
    private fun resume(withheld: List<Claim>) {
        val (unassessed, rest) = synchronized(lock) {
            withheld.partition { it.needsAssessment && it.status == Status.QUEUED }
                .also { (u, _) -> u.forEach { it.needsAssessment = false } }
        }
        rest.forEach { c ->
            val waiting = synchronized(lock) { c.status == Status.EXPLORING && c.waiting }
            if (waiting) enqueue(c) else schedule(c)
        }
        assessThenSchedule(unassessed)
    }

    /** Assesses [nodes] (unassessed arguments) per question, then schedules them and their links. */
    private fun assessThenSchedule(nodes: List<Claim>) {
        nodes.groupBy { it.root }.values.forEach { group ->
            pending.incrementAndGet()
            calls.submit {
                try {
                    // Resume and pause can race: authorization is checked in the
                    // task that is about to start the Jev calls, not only where
                    // the task was submitted.
                    val ready = synchronized(lock) {
                        if (held(group.first())) {
                            group.forEach { it.needsAssessment = true; it.parked = true }
                            emptyList()
                        } else group
                    }
                    if (ready.isNotEmpty()) {
                        assess(ready)
                        ready.flatMap { listOfNotNull(it, it.link) }.forEach(::schedule)
                    }
                } finally {
                    done()
                }
            }
        }
    }

    /** The UI's view (SPEC §6), projected by [GraphProjection]. It only reads. */
    fun snapshot(): GraphDto {
        val graph = service.graph()
        val layers = service.layers
        return synchronized(lock) { projection.project(graph, layers, state) }
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
        val w = writer ?: return
        val current = synchronized(lock) { EngineRecords.current(state, ledger) }
        w.write(current)
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

    /** Caller holds [lock]. What [ExplorationPolicy] sees of [c]'s question. */
    private fun questionView(c: Claim) = state.questionView(c.root)

    /** Caller holds [lock]. CTL-05: [c]'s question is paused and its next round is not a forced one (CTL-02). */
    private fun held(c: Claim) = policy.held(c.view(), questionView(c))

    /**
     * Stops work that was dequeued just before its question paused. The task
     * has not started a round yet, so a judging claim returns to QUEUED; a
     * claim between rounds remains EXPLORING and waiting, as CTL-05 specifies.
     */
    private fun parkIfHeld(c: Claim): Boolean = synchronized(lock) {
        if (!held(c)) return@synchronized false
        c.parked = true
        when (c.status) {
            Status.JUDGING -> c.status = Status.QUEUED
            Status.EXPLORING -> c.waiting = true
            else -> Unit
        }
        true
    }

    private fun enqueue(c: Claim) {
        if (closed) return
        val (priority, generation) = synchronized(lock) {
            c.queueGeneration++
            if (held(c)) {
                // CTL-05: withheld until the question resumes; a task already queued goes stale.
                c.parked = true
                return
            }
            policy.priorityOf(c.view()) to c.queueGeneration
        }
        pending.incrementAndGet()
        queue.add(Task(c, priority, seq.getAndIncrement(), generation))
    }

    /**
     * Queues a QUEUED claim for its first round unless a gate that needs no
     * judgment ends it first ([ExplorationPolicy.scheduleGate]). In a paused
     * question (CTL-05) it stays QUEUED, gates unapplied, until the question resumes.
     */
    private fun schedule(c: Claim) {
        val gate = synchronized(lock) {
            if (c.status != Status.QUEUED) return
            if (held(c)) {
                c.parked = true
                return
            }
            policy.scheduleGate(c.view(), questionView(c))
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
                // CTL-05: queued before its question paused; it waits, unstarted, for the resume.
                (c.status == Status.QUEUED || (c.status == Status.EXPLORING && c.waiting)) && held(c) -> {
                    c.parked = true
                    null
                }
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
     * assessment), applies the budget and diminishing gates (not to a forced
     * round, CTL-02) and runs the first round. Returns true once the claim
     * finished, false when it has rounds left.
     */
    private fun start(c: Claim): Boolean {
        if (parkIfHeld(c)) {
            onChange()
            return false
        }
        // CRED-01 (a link's stance is its argument's CRED-02 strength, judged at attach time)
        if (synchronized(lock) { !c.isLink && c.plausibility == null }) {
            val text = synchronized(lock) { c.text }
            attempt(c, "plausibility") {
                val p = judge.plausibility(questionOf(c), text)
                synchronized(serviceLock) { service.setStance(c.ref, JEV, p) }
                p
            }?.let { p ->
                update { c.plausibility = p }
            }
        }
        // A pause may have arrived while the plausibility call was in flight.
        // It may finish, but it must not lead into a new round.
        if (parkIfHeld(c)) {
            onChange()
            return false
        }
        // EXP-06 after EXP-05 (schedule()): BUDGET means "would have been expanded";
        // EXP-10: the question stopped while this claim was being judged.
        val gate = synchronized(lock) { policy.startGate(c.view(), questionView(c)) }
        if (gate != null) return finish(c, gate)
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
            if (held(c)) {
                c.parked = true
                c.waiting = true
                return false
            }
            val view = c.view()
            val forcedRound = c.forceRound
            val nextStatus = policy.terminalStatus(view, questionView(c))
            val nextSides = policy.nextSides(view)
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
        val next = synchronized(lock) {
            policy.terminalStatus(c.view(), questionView(c)).also { if (it == null) c.waiting = true }
        }
        return if (next != null) finish(c, next) else false
    }

    // ---------------------------------------------------------------- one round (EXP-02..05)

    /**
     * One round (EXP-02..05). The proposers take turns in their configured
     * order ([proposeTurn]); each turn sees the claim's arguments as they
     * stand after the previous turn's triage, so a later proposer is asked
     * for what is still missing. Returns a terminal status when the round
     * ends the expansion early (all calls failed, budget), else null
     * ([closeRound]). A [forced] round (CTL-02) ignores saturation and the
     * budget; its new arguments are bounded by an allowance of one per-side
     * cap per side instead.
     */
    private fun round(c: Claim, sides: List<Side>, forced: Boolean): Status? {
        val r = Round(c, sides, forced, capOf(c))
        for (p in proposers) {
            if (r.budgetHit) break
            proposeTurn(r, p)
        }
        return closeRound(r)
    }

    /** EXP-02, EXP-03: one proposer's turn — its calls, exact-text dedupe, one triage request, every verdict applied. */
    private fun proposeTurn(r: Round, p: Proposer) {
        val c = r.claim
        val (ctx, existing) = synchronized(lock) {
            val pros = c.children.filter { it.side == Polarity.SUPPORT }
            val cons = c.children.filter { it.side == Polarity.ATTACK }
            state.contextOf(c) to (pros + cons)
        }
        // EXP-04: never attach beyond the cap within a round. Both sides, since OTHER_SIDE
        // may move an argument onto a side that was not asked this round.
        val room: MutableMap<Side, Int> = if (r.forced) r.allowance else SIDES.associateWith { side ->
            capOf(c) - existing.count { it.side == side }
        }.toMutableMap()
        val turnSides = r.sides.filter { room.getValue(it) > 0 }
        if (turnSides.isEmpty()) return
        val fresh = dedupe(r, p, existing, propose(r, p, ctx, turnSides, room))
        if (fresh.isEmpty()) return
        // EXP-03 step 2: one Jev triage request for this turn's remaining candidates.
        val verdicts = attempt(c, "triage") { judge.triage(ctx, fresh.map { Candidate(it.text, it.side) }) }
            ?.takeIf { it.size == fresh.size }
            ?: fresh.map { Triage(TriageAction.ADD) }
        val resolved = arrayOfNulls<Claim>(fresh.size)
        for ((i, f) in fresh.withIndex()) {
            val v = verdicts[i]
            val target = v.target?.let { t -> if (t < existing.size) existing.getOrNull(t) else resolved.getOrNull(t - existing.size) }
            val action = resolveAction(c, v.action, target, f)
            r.count(action)
            resolved[i] = applyVerdict(r, action, f, target, room)
        }
    }

    /** EXP-02: [p] asked about every side in [turnSides] concurrently; a failed call records its error on the claim. */
    private fun propose(r: Round, p: Proposer, ctx: ClaimContext, turnSides: List<Side>, room: Map<Side, Int>): List<Pair<Side, List<String>>> {
        val c = r.claim
        val futures = turnSides.map { side ->
            val ask = minOf(config.argsPerCall, room.getValue(side))
            side to calls.submit<List<String>> { Usage.within(sinkFor(c.root)) { p.propose(ctx, side, ask).take(ask) } }
        }
        r.asked += futures.size
        r.requested += turnSides.sumOf { minOf(config.argsPerCall, room.getValue(it)) }
        return futures.mapNotNull { (side, f) ->
            try {
                side to f.get()
            } catch (e: Exception) {
                r.failures++
                val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
                update { c.error = "${p.id}: $cause" }
                null
            }
        }
    }

    /** EXP-03 step 1: drops exact-text duplicates (of an existing argument or of an earlier proposal). */
    private fun dedupe(r: Round, p: Proposer, existing: List<Claim>, proposals: List<Pair<Side, List<String>>>): List<Fresh> {
        val byText = HashMap<String, Any>()
        existing.forEach { byText[normalize(it.text)] = it }
        val fresh = mutableListOf<Fresh>()
        for ((side, texts) in proposals) {
            for (t in texts.map(String::trim).filter(String::isNotEmpty)) {
                when (val hit = byText[normalize(t)]) {
                    is Claim -> { mergeProposers(hit, listOf(p.id)); r.dropped++; r.count(TriageAction.DUPLICATE) }
                    is Fresh -> { r.dropped++; r.count(TriageAction.DUPLICATE) }
                    else -> Fresh(t, side, p.id).also { fresh += it; byText[normalize(t)] = it }
                }
            }
        }
        return fresh
    }

    /**
     * EXP-03: what a triage verdict [action] on [f] comes to. A verdict that
     * needs a missing [target] is an ADD; REPLACE and MERGE rewrite [target]
     * here, and fall back to DUPLICATE when it cannot be rewritten.
     */
    private fun resolveAction(c: Claim, action: TriageAction, target: Claim?, f: Fresh): TriageAction = when (action) {
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
        TriageAction.UNDERCUT -> if (target?.link == null) TriageAction.ADD else TriageAction.UNDERCUT
        else -> action
    }

    /**
     * EXP-03: applies a resolved [action] on [f]; returns the argument it now
     * stands for (a later candidate may target it), or null. Every survivor
     * that fits the budget and the cap is attached — even if STOP arrived
     * meanwhile (CTL-03). Survivors past the cap are not attached.
     */
    private fun applyVerdict(r: Round, action: TriageAction, f: Fresh, target: Claim?, room: MutableMap<Side, Int>): Claim? {
        val c = r.claim
        return when (action) {
            TriageAction.ADD, TriageAction.OTHER_SIDE -> if (action == TriageAction.OTHER_SIDE && c.isLink) {
                // A counter-argument generated while probing a link is not evidence that
                // the link holds or fails. Preserve it, but put it where it belongs:
                // against the parent claim (live-run correction to EXP-03/LINK-05).
                val parent = c.parent!!
                val fits = synchronized(lock) { fits(parent, Polarity.ATTACK, r.forced) }
                if (fits && room.getValue(f.side) > 0) {
                    add(r, parent, Polarity.ATTACK, f)?.also { room[f.side] = room.getValue(f.side) - 1 }
                } else null
            } else {
                val side = if (action == TriageAction.ADD) f.side else f.side.opposite
                if (room.getValue(side) > 0) add(r, c, side, f)?.also { room[side] = room.getValue(side) - 1 } else null
            }
            TriageAction.DUPLICATE -> {
                r.dropped++
                if (target != null) mergeProposers(target, listOf(f.proposer) + f.also)
                target
            }
            TriageAction.REPLACE, TriageAction.MERGE -> {
                // resolveAction() already rewrote the target
                r.replaced += target!!
                target
            }
            TriageAction.REFINE -> {
                val fits = synchronized(lock) { fits(target!!, Polarity.SUPPORT, r.forced) }
                if (fits) add(r, target!!, Polarity.SUPPORT, f) else target
            }
            // EXP-03 UNDERCUT: attached to the target's link, as one of its con arguments.
            TriageAction.UNDERCUT -> {
                val (link, fits) = synchronized(lock) { target!!.link!!.let { l -> l to fits(l, Polarity.ATTACK, r.forced) } }
                if (fits) add(r, link, Polarity.ATTACK, f) else target
            }
            TriageAction.DROP -> null
        }
    }

    /** Attaches [f] on [side] of [parent] if [r]'s question has budget left (EXP-06); else marks the round budget-hit. */
    private fun add(r: Round, parent: Claim, side: Side, f: Fresh): Claim? {
        if (r.budgetHit) return null
        if (!reserve(r.claim.root, r.forced)) {
            r.budgetHit = true
            return null
        }
        return attach(parent, side, f).also { r.attached += it }
    }

    /**
     * Ends a round once every proposer had its turn: counts it, assesses and
     * queues its new and reworded arguments, records its yield (EXP-10) and
     * re-judges saturation (EXP-04). Returns the status that ends the claim, if any.
     */
    private fun closeRound(r: Round): Status? {
        val c = r.claim
        val allProposersFailed = r.failures == r.asked && r.asked > 0
        val noProposerHasEverSucceeded = synchronized(lock) {
            if (!allProposersFailed && r.asked > 0) c.anyCallSucceeded = true
            !c.anyCallSucceeded
        }
        update {
            c.rounds++
            c.duplicatesDropped += r.dropped
            r.counts.forEach { (a, n) -> c.triage.merge(a, n, Int::plus) }
        }
        assessAndQueue(r.attached, r.replaced)
        recordYield(c, r.attached, r.counts, r.requested)
        judgeSaturation(c, r.sides)
        return when {
            r.budgetHit -> Status.BUDGET
            allProposersFailed && noProposerHasEverSucceeded -> Status.FAILED
            else -> null
        }
    }

    /**
     * CRED-01, CRED-02, EXP-05: every new (or reworded: REPLACE, MERGE) argument is assessed at once.
     * A rewrite may hit an argument attached in an earlier turn of this round, so assessment waits
     * for the last turn: an assessment of superseded wording must never land.
     */
    private fun assessAndQueue(attached: List<Claim>, replaced: List<Claim>) {
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
            // A link joins the queue with its argument (SPEC §3 "Links as claims"), once the
            // argument's strength — the link's stance and uncertainty — is known.
            ready.flatMap { listOfNotNull(it, it.link) }.forEach { n ->
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
    }

    /**
     * EXP-04: saturation per side, judged once every proposer had its turn
     * (a forced round's sides are re-judged too). A side at its cap needs no Jev call.
     */
    private fun judgeSaturation(c: Claim, sides: List<Side>) {
        val after = synchronized(lock) { state.contextOf(c) }
        for (side in sides) {
            if (synchronized(lock) { policy.atCap(c.view(), side) }) continue
            val p = attempt(c, "saturation") { judge.saturation(after, side) } ?: continue
            update {
                if (side == Polarity.SUPPORT) c.proSaturation = p else c.conSaturation = p
                if (policy.jevSaturates(p)) c.saturated += side else c.saturated -= side
            }
        }
    }

    /**
     * EXP-10: records the yield ([ExplorationPolicy.roundYield]) of [c]'s round
     * that attached [attached] out of [requested] asked-for arguments, triaged
     * as [counts]. When the question's yields have diminished and at least one
     * QUEUED claim can actually be halted, it stops: no new round starts in it;
     * its queued claims end DIMINISHING (a round in flight finishes and
     * attaches what it found, but records no further yield).
     */
    private fun recordYield(c: Claim, attached: List<Claim>, counts: Map<TriageAction, Int>, requested: Int) {
        if (!policy.recordsYield(isRoot = c.parent == null, requested = requested)) return
        val stopped = update {
            // The series is frozen at the stop, so it shows why the question stopped;
            // rounds that were in flight then still attach what they found.
            if (c.root in diminished) return@update null
            val ys = yields.getOrPut(c.root) { mutableListOf() }
            ys += policy.roundYield(attached.map { AttachedValue(it.edge?.strength, it.relevance, it.quality) }, counts, requested)
            if (!policy.yieldsDiminished(ys, treeSize.getValue(c.root))) return@update null
            val halted = claims.values.filter { n -> n.root == c.root && policy.haltable(n.view()) }
            if (!policy.yieldStopHalts(halted.map { it.view() })) return@update null
            diminished += c.root
            halted.onEach { n ->
                n.waiting = false
                n.queueGeneration++ // its queued task, if any, falls through
                n.status = policy.haltedStatus(n.view())
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

    // ---------------------------------------------------------------- cost (SPEC §12)

    /** SPEC §12: the sink bound around every call made for question [root]. */
    private fun sinkFor(root: CellRef) = UsageSink { u -> recordUsage(root, u) }

    private fun recordUsage(root: CellRef, u: CallUsage) {
        val usd = ledger.price(u)
        update { ledger.record(root, u, usd) }
    }

    // ---------------------------------------------------------------- attaching and assessing

    /**
     * One Jev request per argument (plausibility, strength, quality, relevance),
     * all in parallel; then reach and contribution in creation order, so an
     * argument refining another one attached in the same round sees its reach.
     */
    private fun assess(nodes: List<Claim>) {
        val futures = nodes.map { n ->
            val (question, path, text) = update {
                n.assessing = true
                // An argument about a link (an undercutter, a link supporter) is judged against the
                // link's text: the link is the last entry of its path (SPEC §3 "Links as claims").
                Triple(questions.getValue(n.root), n.path(), n.text)
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
                n.assessing = false
                if (a != null) {
                    n.plausibility = a.plausibility
                    n.relevance = a.relevance
                    n.quality = a.quality
                    edge.strength = a.strength
                }
                val reach = policy.reachOf(n.parent!!.reach, edge.strength)
                n.reach = reach
                n.contribution = policy.contribution(reach, n.relevance, n.quality)
                updateLink(n)
            }
        }
    }

    /** Caller holds [lock]. SPEC §3 "Links as claims": [arg]'s link takes its reach and [ExplorationPolicy.linkContribution]. */
    private fun updateLink(arg: Claim) {
        val l = arg.link ?: return
        l.reach = arg.reach
        l.contribution = policy.linkContribution(arg.contribution, arg.reach, arg.edge?.strength)
    }

    /**
     * Attaches [f] as an argument on [side] of [parent] — a claim, or a link
     * (then its edge targets the link's edge: SPEC §3 "Links as claims") —
     * together with the argument's own link.
     */
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
            state.linkFor(child, config.maxRounds)
        }
        return child
    }

    // ---------------------------------------------------------------- rewrites (EXP-03 REPLACE, MERGE)

    /**
     * Caller holds [lock]. EXP-03 REPLACE only rewords an argument nobody has
     * explored yet — neither the argument nor its link (whose text quotes it).
     */
    private fun replaceable(t: Claim) = t.parent != null && t.status == Status.QUEUED &&
        t.children.isEmpty() && t.rounds == 0 &&
        t.link.let { l -> l == null || (l.children.isEmpty() && l.rounds == 0 && l.status !in setOf(Status.JUDGING, Status.EXPLORING)) }

    /** Reserve an unexplored queued target and invalidate any task carrying its old priority/text. */
    private fun beginRewrite(t: Claim): RewriteReservation? = update {
        if (!replaceable(t)) return@update null
        if (t.rewriteInFlight) return@update RewriteReservation(created = false, wasQueued = t.rewriteWasQueued)
        val wasQueued = t.queueGeneration > 0
        t.rewriteInFlight = true
        t.rewriteWasQueued = wasQueued
        t.queueGeneration++
        // Its link quotes the old wording: hold it too until the rewrite is assessed.
        t.link?.let { l ->
            l.rewriteInFlight = true
            l.queueGeneration++
        }
        RewriteReservation(created = true, wasQueued = wasQueued)
    }

    /** Undo a failed first rewrite reservation and restore the target's invalidated queue entry. */
    private fun cancelRewrite(t: Claim, reservation: RewriteReservation) {
        if (!reservation.created) return
        val (resume, link) = update {
            t.rewriteInFlight = false
            t.rewriteWasQueued = false
            val l = t.link?.also { it.rewriteInFlight = false }
            // Its link had been queued only once the argument was assessed.
            (reservation.wasQueued && t.status == Status.QUEUED) to l?.takeIf { t.contribution != null && it.status == Status.QUEUED }
        }
        if (resume) enqueue(t)
        link?.let(::schedule)
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
                    t.link?.text = linkText(t)
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
                    t.link?.text = linkText(t)
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
            val reach = policy.reachOf(n.parent!!.reach, edge.strength)
            n.reach = reach
            n.contribution = reach
            updateLink(n)
            // The link is re-gated with the new wording's judgments (replaceable() kept it unexplored).
            n.link?.let { l -> if (l.status in FINISHED && l.override != Override.STOP) l.status = Status.QUEUED }
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

    /** Ends [c]'s expansion with [status] as [ExplorationPolicy.finish] rules and returns true. */
    private fun finish(c: Claim, status: Status, error: String? = null): Boolean = update {
        c.waiting = false
        val end = policy.finish(c.view(), status)
        c.status = end.status
        end.error?.let { c.error = it }
        if (error != null) c.error = error
        true
    }

    // ---------------------------------------------------------------- durability (SPEC §11)

    /**
     * SPEC §11: rebuilds every tree from the graph's structure and the [meta]
     * records ([EngineRecords.rebuild]), re-applies the `jev` stances (the
     * graph recomputes every credence from them), then re-queues every claim
     * that was still active — an interrupted round simply runs again. A claim
     * whose record never reached the store is queued afresh. Arguments that
     * were never assessed are assessed again before they are queued.
     */
    private fun restore(meta: Map<String, Map<String, String>>) {
        val graph = service.graph()
        synchronized(lock) { EngineRecords.rebuild(graph, meta, state, ledger, config.maxRounds, config.startPaused) }
        val stances = synchronized(lock) {
            claims.values.mapNotNull { c -> c.plausibility?.let { c.ref to it } } +
                edges.values.mapNotNull { e -> e.strength?.let { e.ref to it } }
        }
        // The graph skips a stance a node already holds (CredenceGraph.setStance).
        synchronized(serviceLock) { stances.forEach { (ref, v) -> service.setStance(ref, JEV, v) } }
        val (unassessed, queued) = synchronized(lock) {
            claims.values.filter { it.status == Status.QUEUED && !it.isLink }
                .partition { it.parent != null && it.plausibility == null && it.edge?.strength == null }
        }
        // CTL-05: in a paused question even the assessment waits for the resume (it is spend too).
        val (deferred, assessNow) = synchronized(lock) { unassessed.partition { it.root in paused } }
        synchronized(lock) { deferred.forEach { it.needsAssessment = true; it.parked = true } }
        // A link is queued with its argument: after it, and only once the argument is assessed.
        queued.flatMap { listOfNotNull(it, it.link) }.forEach(::schedule)
        synchronized(lock) {
            claims.values.filter { it.isLink && it.status == Status.QUEUED && it.argument!!.status != Status.QUEUED }
        }.forEach(::schedule)
        assessThenSchedule(assessNow)
        if (claims.isNotEmpty()) onChange()
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

    /** EXP-04: the per-side cap of [c] ([ExplorationPolicy.capOf]). */
    private fun capOf(c: Claim) = policy.capOf(isRoot = c.parent == null)

    /** Caller holds [lock]. Whether one more argument fits on [side] of [c] ([ExplorationPolicy.fits]). */
    private fun fits(c: Claim, side: Side, forced: Boolean) = policy.fits(c.view(), side, forced)

    /** EXP-06: one claim of [root]'s budget; a forced round (CTL-02) always gets it. */
    private fun reserve(root: CellRef, forced: Boolean): Boolean = synchronized(lock) {
        val n = treeSize.getValue(root)
        policy.mayReserve(n, forced).also { if (it) treeSize[root] = n + 1 }
    }

    private fun questionOf(c: Claim) = synchronized(lock) { questions.getValue(c.root) }
}
