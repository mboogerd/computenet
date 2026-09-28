package civictech.deliberate

import civictech.cell.CellRef
import civictech.deliberate.ExplorationPolicy.Companion.FINISHED
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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
 * a task's priority is the claim's value of information (model C:
 * |d root / d claim| × 4·p·(1 − p), the sensitivity read from the graph's
 * sensitivity cells when a worker takes its next task, so the order follows
 * the dataflow as it stands then; the root is 1) times
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
    /**
     * Model A: asked once per question root before its first round; READINGS or
     * POSITIONS turn the root FRAMED and each item into a root-like claim of its
     * own. Without one, every question is explored as asked.
     */
    private val framer: Framer? = null,
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
        /** SPEC §3 "Exploration order": a claim's next round is queued at its value of information × roundDecay^(rounds run). */
        val roundDecay: Double = 0.5,
        /**
         * Not a stop rule since model C, and not settable from the command line:
         * an explicit bound on the expanded depth that tests use to keep a fake-driven
         * tree small. Unbounded by default.
         */
        val maxDepth: Int = Int.MAX_VALUE,
        /** EXP-06: the hard per-question cost cap, in claims. It stands beside the value-of-information stop. */
        val maxClaims: Int = 180,
        val workers: Int = 8,
        /**
         * Model C: a node whose value of information ([ExplorationPolicy.voiOf]) is
         * below this gets no further round (DIMINISHING), so a question stops once
         * the largest value of information over its remaining nodes is below it
         * (`--voi-eps`). 0 disables the stop.
         */
        val voiEpsilon: Double = DEFAULT_VOI_EPSILON,
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
            require(voiEpsilon >= 0.0) { "voiEpsilon must not be negative" }
        }

        companion object {
            const val DEFAULT_SATURATION = 0.22
            /**
             * Model C's ε. A starting value, not a calibrated one: a scratch model review
             * (2026-09-27, not in the repo) found about half of the explored claims could
             * not move the root by 0.01.
             */
            const val DEFAULT_VOI_EPSILON = 0.01
            /** EXP-10: the recent-yield window QuestionDto reports (the yield stop itself is gone). */
            const val YIELD_WINDOW = 8
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

    private class Task(val claim: Claim, val seq: Long, val generation: Long)

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
    private val paused = state.paused

    private val pending = AtomicInteger()
    private val idle = Object()
    /**
     * The ready tasks. Not a priority heap: model C's priorities move with the
     * sensitivity cells, so [take] ranks the tasks when a worker asks ([currentPriority]).
     */
    private val queue = ArrayList<Task>()
    private val seq = AtomicLong()
    @Volatile private var closed = false
    private val calls: ExecutorService = Executors.newVirtualThreadPerTaskExecutor()

    private val writer: RecordWriter?
    private val persister: ScheduledExecutorService? = store?.let {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "deliberate-persist").apply { isDaemon = true } }
    }

    private companion object {
        const val JEV = "jev"
    }

    /** What [rounds] may do to the engine: its state, its locks, its queue and the credence graph. */
    private val host = object : RoundHost {
        override val state get() = this@DeliberationEngine.state
        override fun <T> locked(block: () -> T): T = synchronized(lock, block)
        override fun <T> update(block: () -> T): T = this@DeliberationEngine.update(block)
        override fun <T> attempt(c: Claim, what: String, call: () -> T): T? = this@DeliberationEngine.attempt(c, what, call)
        override fun sinkFor(root: CellRef) = this@DeliberationEngine.sinkFor(root)
        override fun enqueue(c: Claim) = this@DeliberationEngine.enqueue(c)
        override fun schedule(c: Claim) = this@DeliberationEngine.schedule(c)
        override fun createArgument(text: String, parent: CellRef, side: Side): Pair<CellRef, CellRef> =
            synchronized(serviceLock) {
                val child = service.createClaim(text)
                child to service.createEdge(child, parent, side)
            }
        override fun setStances(vararg stances: Pair<CellRef, Double?>) = synchronized(serviceLock) {
            stances.forEach { (ref, v) -> service.setStance(ref, JEV, v) }
        }
    }
    private val rounds = RoundProtocol(host, policy, judge, proposers, merger, calls)
    /** Started last, once everything a worker touches exists. */
    private val workerThreads: List<Thread> = List(config.workers) { i ->
        Thread.ofPlatform().daemon().name("deliberate-worker-$i").start(::work)
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
                        rounds.assess(ready)
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
                take()
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

    /**
     * SPEC §3 "Exploration order": waits for a ready task and takes the one of
     * highest [currentPriority] (ties: the earliest queued).
     */
    private fun take(): Task {
        while (true) {
            val ready = synchronized(queue) {
                while (queue.isEmpty()) (queue as Object).wait()
                queue.toList()
            }
            val best = synchronized(lock) {
                ready.maxWith(compareBy<Task> { currentPriority(it) }.thenByDescending { it.seq })
            }
            synchronized(queue) { if (queue.remove(best)) return best }
        }
    }

    /**
     * Caller holds [lock]. A task's priority now ([ExplorationPolicy.priorityOf] at
     * the claim's current sensitivity); a stale task ranks first, so it is discarded at once.
     */
    private fun currentPriority(t: Task): Double =
        if (t.generation != t.claim.queueGeneration) Double.MAX_VALUE else policy.priorityOf(viewOf(t.claim))

    /** Caller holds [lock]. What [ExplorationPolicy] sees of [c]'s question. */
    private fun questionView(c: Claim) = state.questionView(c.root)

    /** Caller holds [lock]. What [ExplorationPolicy] sees of [c], with its current sensitivity (model C). */
    private fun viewOf(c: Claim) = c.view(service.sensitivityOf(c.ref))

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
        val generation = synchronized(lock) {
            c.queueGeneration++
            if (held(c)) {
                // CTL-05: withheld until the question resumes; a task already queued goes stale.
                c.parked = true
                return
            }
            c.queueGeneration
        }
        pending.incrementAndGet()
        synchronized(queue) {
            queue += Task(c, seq.getAndIncrement(), generation)
            (queue as Object).notifyAll()
        }
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
            policy.scheduleGate(viewOf(c), questionView(c))
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
     * assessment), applies the budget and value-of-information gates (not to a forced
     * round, CTL-02) and runs the first round. Returns true once the claim
     * finished, false when it has rounds left.
     */
    private fun start(c: Claim): Boolean {
        if (parkIfHeld(c)) {
            onChange()
            return false
        }
        // Model A: a framed root is never explored itself (an EXPAND on it runs no round).
        if (synchronized(lock) { c.framing.let { it != null && it.mode != FramingMode.NONE } }) {
            update { c.forceRound = false }
            return finish(c, Status.FRAMED)
        }
        if (frame(c)) return true
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
        // Preserve the original observations at this race boundary: EXPAND can arrive
        // between the force read and the budget read, or between budget and EXP-10.
        val forcedAtBudgetGate = synchronized(lock) { c.forceRound }
        if (!forcedAtBudgetGate) {
            val budgetGate = synchronized(lock) {
                policy.startBudgetGate(forceRound = false, treeSize = treeSize.getValue(c.root))
            }
            if (budgetGate != null) return finish(c, budgetGate)
        }
        // Model C: the value of information is re-read now; it may have fallen since the claim was queued.
        val voiGate = synchronized(lock) { policy.startVoiGate(viewOf(c)) }
        if (voiGate != null) return finish(c, voiGate)
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
            val view = viewOf(c)
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
            rounds.run(c, sides, forcedRound)
        } finally {
            synchronized(lock) { c.roundInFlight = false }
        }
        if (outcome != null) return finish(c, outcome)
        // Finish now if nothing is left, so a done claim needs no queue trip.
        val next = synchronized(lock) {
            policy.terminalStatus(viewOf(c), questionView(c)).also { if (it == null) c.waiting = true }
        }
        return if (next != null) finish(c, next) else false
    }

    /**
     * Model A: asks the [framer] how question root [c] should be explored, once,
     * before its first round. READINGS/POSITIONS with at least 2 items frame the
     * root in the graph, create one root-like claim per item, finish the root
     * FRAMED and schedule the items; returns true then. NONE, or a failed call
     * (the root carries a "framing: …" error), returns false: explored as asked.
     */
    private fun frame(c: Claim): Boolean {
        val f = framer ?: return false
        val text = synchronized(lock) {
            // Only a question root: its readings/positions are roots of the same tree and are never framed.
            if (c.ref != c.root || c.parent != null || c.isLink || c.rounds != 0 || c.children.isNotEmpty() || c.framing != null) return false
            c.text
        }
        val framing = tryCall(c, "framing") { f.frame(text) }
        if (framing == null) {
            // computenet-3iu1k: tryCall already recorded the error; remember the
            // outcome too (as NONE) so a re-entry — pause/resume, restart, or a
            // budget-gate-then-EXPAND — does not ask the framer again.
            update { c.framing = Framing.NONE }
            return false
        }
        if (framing.mode == FramingMode.NONE || framing.items.size < 2) {
            update { c.framing = Framing.NONE }
            return false
        }
        val refs = try {
            synchronized(serviceLock) {
                service.frame(c.ref, CredenceGraph.IssueMode.valueOf(framing.mode.name), framing.items)
            }
        } catch (e: Exception) {
            // computenet-mnzog: a READINGS/POSITIONS answer that fails to write into the
            // graph (service.frame threw) is a failed call the same way a null tryCall
            // result is (computenet-3iu1k): remember it as NONE so a re-entry before
            // round 1 — a pause landing during the root's plausibility call, a
            // budget-gate-then-EXPAND, or a restart before round 1 — does not bill a
            // second Framer call.
            update { c.error = "framing: $e"; c.framing = Framing.NONE }
            return false
        }
        val proposer = if (framing.mode == FramingMode.READINGS) Claim.READING else Claim.POSITION
        val positions = synchronized(lock) {
            c.framing = framing
            refs.zip(framing.items).map { (ref, item) ->
                Claim(ref, c.root, null, null, item, 0, proposer, config.maxRounds).also { claims[ref] = it }
            }.also { treeSize.merge(c.root, it.size, Int::plus) }
        }
        finish(c, Status.FRAMED)
        positions.forEach(::schedule)
        return true
    }

    // ---------------------------------------------------------------- cost (SPEC §12)

    /** SPEC §12: the sink bound around every call made for question [root]. */
    private fun sinkFor(root: CellRef) = UsageSink { u -> recordUsage(root, u) }

    private fun recordUsage(root: CellRef, u: CallUsage) {
        val usd = ledger.price(u)
        update { ledger.record(root, u, usd) }
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
    private fun <T> attempt(c: Claim, what: String, call: () -> T): T? = tryCall(c, "jev $what", call)

    /** EXP-08: run a call billed to [c]'s question; on failure record "[label]: error" and return null. */
    private fun <T> tryCall(c: Claim, label: String, call: () -> T): T? =
        try {
            Usage.within(sinkFor(c.root), call)
        } catch (e: Exception) {
            update { c.error = "$label: $e" }
            null
        }

    private fun questionOf(c: Claim) = synchronized(lock) { questions.getValue(c.root) }
}
