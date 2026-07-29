package civictech.inspect

import civictech.cell.CellRef
import civictech.cell.Timestamp
import civictech.cell.port.PortRef
import java.util.UUID

/**
 * V3-BE — the **wave-health heuristic**: the inspector's answer to "a wave left
 * and I cannot say it ever landed".
 *
 * ### Why this is a heuristic, stated once, here
 *
 * `doc/spec/20-dataflow-semantics/22-consistency.md` §"Completeness over silent
 * or stuck edges" decides the real rule ([22-LIVE-01]) and closes with
 * **⚠ GAP (G-40)**: a glitch-free join cannot distinguish an effective-only-
 * silent arm from a dead one, so wave completeness blocks forever on absorbing,
 * suspended, restarting or dead-lettered frontier edges. Closing that needs
 * per-source per-edge watermarks, metadata-plane `Progress` absorb-acks emitted
 * at a defined quiescence boundary, and typed `Stall(reason, recoverable)`
 * markers with per-edge WAIT | DEGRADE | RE-SCOPE policies — kernel work that
 * belongs with `civictech.cell.verify`, and is explicitly out of this plan's
 * scope.
 *
 * What the inspector can honestly offer meanwhile is a **diagnostic**, computed
 * from outside the graph on data it already holds: "this looks stuck — go
 * look." Every row it produces carries [WaveHealthRow.heuristic] `= true` and
 * opens its description with the word "heuristic", in the data and not only in
 * the UI. No row asserts that a wave *is* lost, that a cell *is* stuck, or that
 * glitch-freedom *is* violated.
 *
 * ### The two inputs — both already held, neither newly subscribed (P2/P6)
 *
 * 1. **Last wave per tapped outlet.** [FlowCollector] installs one
 *    payload-agnostic `FanOutlet.observe` attachment per producing outlet, and
 *    has always recorded the last `MessageContext` it was handed.
 *    [FlowCollector.tapReadings] hands that over as a value snapshot.
 * 2. **Per-observed-cell frontier stamps.** [StampedView] records the ambient
 *    wave position on every *effective* change of a cell a client explicitly
 *    observed; [Observations.frontierOf] reads it without touching the
 *    observation's idle deadline.
 *
 * Nothing here calls `Observations.start`, installs a tap, extends an
 * observation's lifetime, reads state from an unobserved cell, or issues a
 * `StateRequest` pull — a pull reply mints waves from the producing outlet's
 * own counter, so an instrument that pulled would perturb the very wave plane
 * it is diagnosing. Evaluation runs on the inspector's single existing
 * scheduler thread as one more `Tick`; there is no new thread and nothing new
 * on the data path.
 *
 * ### Comparability: waves are per-source, and epochs die
 *
 * `Timestamp` is `(sourceId, counter)` and **two different `sourceId`s are
 * incomparable** — there is no ordering between them (spec 20/22 §MessageContext
 * rule 1, G-20). Because emission is the context-stamping point and transparent
 * flow preserves the timestamp (rule 2), an upstream outlet's wave and a
 * downstream observed cell's frontier *are* comparable exactly when they carry
 * the same `sourceId`, and never otherwise. A supervision RESTART mints a fresh
 * per-outlet `sourceId` (glossary §Generation), so this class resets **all**
 * tracking for a site the moment its observed `sourceId` changes and never
 * subtracts across epochs.
 *
 * Vocabulary, per the glossary's frontier/watermark disambiguation: this class
 * compares a **watermark** (a per-source counter high-water position, what an
 * outlet's `Timestamp.counter` is) against a **frontier** in the fold sense
 * (what [StampedView.frontier] records for the sink's last effective change).
 * The row field names follow the shapes they carry, not the senses: `wave` is
 * the counter-sense value and `frontier` the fold-sense one.
 *
 * ### The false-positive guards
 *
 * A lagging frontier is *correct behaviour* in more cases than it is a fault. A
 * row raised for any of these is a defect, not a tuning question:
 *
 * 1. **Fresh epochs** — see above; [resetSite].
 * 2. **A null frontier is not a lag.** A freshly opened observation reports
 *    `frontier: null` by design (its state arrived as a catch-up baseline, and
 *    a baseline is deliberately not a wave position — spec 20/21 §Pull, 93
 *    I-24). At least one non-null frontier is required before a cell is
 *    eligible at all: see [subjectsOf].
 * 3. **A baseline or re-baseline emission is not "the upstream wave"** —
 *    [FlowCollector.liveWaveOf] refuses both.
 * 4. **Absorption is legitimate and common.** [StampedView.frontier] advances
 *    only on an *effective* change: a de-duplicated add, a no-op update, an
 *    absorbed delta acknowledged rather than propagated all leave the frontier
 *    honestly behind an outlet that did emit. This is the single largest source
 *    of honest lag and the reason this class is heuristic. The only defence
 *    available without kernel watermarks is a conservative
 *    [LAG_THRESHOLD_WAVES] plus [LAG_GRACE_MS]: absorption runs shorter than 32
 *    consecutive waves, or shorter than five seconds, cannot raise a row.
 *    Longer ones still can, and that residual is documented rather than
 *    papered over.
 * 5. **Filtering and aggregating operators drop waves by construction.** Same
 *    defence, and it is a real one for this shape: the lag of a filter is
 *    bounded by its longest *consecutive* drop run, not by its drop rate — a
 *    filter passing one delta in four keeps the delta near four however busy
 *    its source is, and never approaches 32.
 * 6. **A suspended or drained cell is intentionally not propagating** —
 *    [isCold] skips it (and, more conservatively than the ticket asks, a cold
 *    *producer* too: its last wave is frozen, so any lag against it is an
 *    artefact of the suspension the activity feed already reports).
 * 7. **Independent sources are allowed to be silent.**
 *    `22-consistency.md` [22-LIVE-01]: completeness is per-source and
 *    over-alignment across independent sources is forbidden. **No row is ever
 *    derived from one source's silence relative to another's activity**: every
 *    row's substance — `wave`, `frontier`, `lagWaves` — is a single-source
 *    comparison, refused outright when the two stamps disagree on `sourceId`.
 *    The one thing cross-site data is used for is [activityTick], a *liveness
 *    precondition* that suppresses rows on a quiescent process; it can only
 *    ever stop a row from opening, never cause one.
 *
 * ### Bounds
 *
 * At most [WAVE_HEALTH_MAX_OPEN] rows are open at once; forcing an eviction
 * emits that row's `cleared` event, because a client must never be left holding
 * a row the server has forgotten. Tracking state is keyed by (edge, cell) pairs
 * that are live subjects *this tick* and is pruned to that set on every pass, so
 * nothing accumulates for edges or observations that have gone away.
 */
internal class WaveHealth(
    /** The tapped outlets and their last live waves — [FlowCollector.tapReadings]. */
    private val sites: () -> List<TapReading>,
    /** The cells a client has explicitly asked to observe — `Observations.openRefs`. */
    private val observed: () -> Set<CellRef>,
    /** [Observations.frontierOf] — a read that never touches the idle deadline. */
    private val frontierOf: (CellRef) -> Timestamp?,
    /** [Heat.isCold] for one ref: suspended, drained, or not locally hosted. */
    private val isCold: (CellRef) -> Boolean,
    /** Non-blocking sink for one row — the `error.waveHealth` emission point. */
    private val onRow: (WaveHealthRow) -> Unit,
    private val maxOpen: Int = WAVE_HEALTH_MAX_OPEN,
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    /**
     * Guards every field below. [evaluate] runs on the inspector's scheduler
     * thread and [openRows] from an HTTP thread serving `GET /errors`, so the
     * open set is read under the same monitor it is written under. Nothing that
     * can block or enter a host runs beneath it: [onRow] is called *outside*
     * it, from [evaluate]'s tail.
     */
    private val lock = Any()

    /** Open rows by id, in the order they opened — eviction takes the oldest. */
    private val open = LinkedHashMap<String, WaveHealthRow>()

    /** When each open row's condition was last re-confirmed, for [ROW_TTL_MS]. */
    private val confirmedAtMs = HashMap<String, Long>()

    private val lagTracking = HashMap<SubjectKey, Tracking>()
    private val stallTracking = HashMap<SubjectKey, Tracking>()

    /** Last observed `sourceId` per tapped site — the epoch-change trip wire (guard 1). */
    private val siteEpoch = HashMap<PortRef, UUID>()

    /** Last observed wave position per tapped site, for [activityTick]. */
    private var siteWaves = HashMap<PortRef, Timestamp>()

    /**
     * A monotone count of evaluations in which *some* tapped site's wave
     * position moved — the process-liveness proxy that keeps a genuinely idle
     * graph silent (guard 7's second half). A condition may only mature into a
     * row if this has advanced since the condition began, so "nothing has
     * happened anywhere for the whole grace window" can never produce one.
     * Deliberately derived from the wave positions the sites already publish
     * rather than from a new per-message counter: a monotone emission count
     * would be one more atomic add on the data path, which P2 forbids.
     */
    private var activityTick = 0L

    /** `GET /api/inspect/errors` — the currently open rows, oldest first. */
    fun openRows(): List<WaveHealthRow> = synchronized(lock) { open.values.toList() }

    /**
     * One evaluation pass — the `"waveHealth"` `Tick`, scheduled after
     * `"flowSample"` so `tickAll()` stays a faithful synchronous stand-in for
     * the scheduled order.
     *
     * Reads, decides and mutates under [lock]; publishes afterwards, so a slow
     * SSE consumer can never delay a `GET /errors` or the next tick.
     */
    fun evaluate() {
        val emissions = synchronized(lock) { evaluateLocked(clock()) }
        emissions.forEach(onRow)
    }

    /** A stopped inspector forgets its diagnostics; the stream is going away with them. */
    override fun close() = synchronized(lock) {
        open.clear()
        confirmedAtMs.clear()
        lagTracking.clear()
        stallTracking.clear()
        siteEpoch.clear()
        siteWaves = HashMap()
    }

    // --------------------------------------------------------------- evaluation

    private fun evaluateLocked(now: Long): List<WaveHealthRow> {
        val readings = sites()
        val emissions = ArrayList<WaveHealthRow>()

        noteActivity(readings)
        val epochChanged = resetChangedEpochs(readings, emissions)

        // Every live tapped edge and the cell it feeds — the authority for "is
        // this row's edge still bound, and still to the same cell".
        val boundEdges = HashMap<UUID, CellRef>()
        readings.forEach { reading -> reading.edges.forEach { (id, cell) -> boundEdges[id] = cell } }

        val observedNow = observed()
        val subjects = subjectsOf(readings, observedNow)
        val subjectsByKey = subjects.associateBy { it.key }

        val confirmed = HashSet<String>()
        subjects.forEach { subject ->
            evaluateLag(subject, now, confirmed, emissions)
            evaluateStall(subject, now, confirmed, emissions)
        }

        // Tracking exists only for live subjects: an edge that unbound, an
        // observation that was released and a cell that went cold all drop
        // their partial progress rather than resuming it minutes later.
        lagTracking.keys.retainAll(subjectsByKey.keys)
        stallTracking.keys.retainAll(subjectsByKey.keys)

        reconcileOpenRows(now, confirmed, subjectsByKey, boundEdges, observedNow, epochChanged, emissions)
        return emissions
    }

    /**
     * The liveness proxy (see [activityTick]). A site whose wave position moved
     * since the previous pass — including a site seen for the first time — is
     * activity; a site *disappearing* is not.
     */
    private fun noteActivity(readings: List<TapReading>) {
        val current = HashMap<PortRef, Timestamp>()
        var moved = false
        readings.forEach { reading ->
            val wave = reading.lastWave ?: return@forEach
            current[reading.producer] = wave
            if (siteWaves[reading.producer] != wave) moved = true
        }
        siteWaves = current
        if (moved) activityTick++
    }

    /**
     * Guard 1, the hard one: **never subtract across epochs**. A supervision
     * restart mints a fresh `sourceId` per outlet, so the moment a site's
     * observed source changes, every comparison that referenced the dead epoch
     * is meaningless — its tracking is dropped and any row it had opened is
     * cleared. Returns the edges whose site's epoch changed on this pass, so
     * [reconcileOpenRows] clears their rows even though the fresh epoch may
     * already look "lagging" against a frontier still stamped with the dead one.
     */
    private fun resetChangedEpochs(readings: List<TapReading>, emissions: MutableList<WaveHealthRow>): Set<UUID> {
        val changed = HashSet<UUID>()
        readings.forEach { reading ->
            val source = reading.lastWave?.sourceId ?: return@forEach
            val prior = siteEpoch.put(reading.producer, source)
            if (prior == null || prior == source) return@forEach
            reading.edges.forEach { (id, _) -> changed += id }
        }
        if (changed.isEmpty()) return changed
        lagTracking.keys.removeAll { it.edge in changed }
        stallTracking.keys.removeAll { it.edge in changed }
        return changed
    }

    /**
     * The eligible (edge, observed cell) pairs, with every structural guard
     * applied. Everything refused here is refused *silently and permanently for
     * this pass* — a subject that cannot be compared honestly is not a subject.
     */
    private fun subjectsOf(readings: List<TapReading>, observedNow: Set<CellRef>): List<Subject> {
        val subjects = ArrayList<Subject>()
        readings.forEach { reading ->
            // guard 3: a baseline/re-baseline emission carries no usable wave position
            val wave = reading.lastWave ?: return@forEach
            // guard 6, producer half: a suspended or drained producer is
            // intentionally not emitting, so its frozen last wave is not evidence
            val producerCell = reading.producer.cell
            if (producerCell != null && isCold(producerCell)) return@forEach
            reading.edges.forEach inner@{ (edgeId, consumer) ->
                // P6: only cells a client explicitly asked to observe are ever subjects
                if (consumer !in observedNow) return@inner
                // guard 6, consumer half
                if (isCold(consumer)) return@inner
                // guard 2: a null frontier is not a lag, it is an unstamped fold
                val frontier = frontierOf(consumer) ?: return@inner
                // guards 1 and 7: two sourceIds are incomparable — no ordering
                // exists between them, and one source's silence is never
                // evidence about another's
                if (frontier.sourceId != wave.sourceId) return@inner
                subjects += Subject(edgeId, consumer, wave, frontier)
            }
        }
        return subjects
    }

    /**
     * **(a) `frontierLag`** — an observed cell whose frontier trails an upstream
     * tapped outlet's last observed wave by more than [LAG_THRESHOLD_WAVES],
     * continuously for longer than [LAG_GRACE_MS], while the process is
     * observably live.
     *
     * A delta at or below the threshold does not merely fail to open a row: it
     * *resets* the clock, so "continuously" means what it says and a filter
     * whose drop runs stay short can never accumulate its way to a row.
     */
    private fun evaluateLag(
        subject: Subject,
        now: Long,
        confirmed: MutableSet<String>,
        emissions: MutableList<WaveHealthRow>,
    ) {
        val lag = subject.wave.counter - subject.frontier.counter
        if (lag < LAG_THRESHOLD_WAVES) {
            lagTracking.remove(subject.key)
            return
        }
        val tracking = lagTracking[subject.key]
        if (tracking == null || tracking.source != subject.wave.sourceId) {
            lagTracking[subject.key] = Tracking(subject.wave.sourceId, subject.wave.counter, now, activityTick)
            return
        }
        val heldMs = now - tracking.sinceMs
        if (heldMs < LAG_GRACE_MS) return
        // guard 7's liveness half: nothing anywhere has moved since this
        // condition began, so the graph is quiescent rather than stuck
        if (activityTick <= tracking.sinceActivity) return
        raise(
            kind = WaveHealthRow.FRONTIER_LAG,
            subject = subject,
            wave = subject.wave,
            lagWaves = lag,
            heldMs = heldMs,
            now = now,
            description = "heuristic: observed frontier trails this edge's last wave by $lag waves " +
                "for ${seconds(heldMs)}s — absorption, filtering and aggregation all look like this",
            confirmed = confirmed,
            emissions = emissions,
        )
    }

    /**
     * **(b) `stalledWave`** — a tapped edge carried wave *W* toward an observed
     * cell and that cell's frontier never reached *W* within [STALL_WINDOW_MS],
     * while the process was observably live.
     *
     * *W* is **pinned** when tracking starts and never re-taken, or the window
     * would restart on every fresh emission and never mature. The frontier
     * reaching *W* resolves the condition; the next pass may pin a later wave.
     */
    private fun evaluateStall(
        subject: Subject,
        now: Long,
        confirmed: MutableSet<String>,
        emissions: MutableList<WaveHealthRow>,
    ) {
        val tracking = stallTracking[subject.key]
        if (tracking == null || tracking.source != subject.wave.sourceId) {
            if (subject.wave.counter > subject.frontier.counter) {
                stallTracking[subject.key] = Tracking(subject.wave.sourceId, subject.wave.counter, now, activityTick)
            } else {
                stallTracking.remove(subject.key)
            }
            return
        }
        if (subject.frontier.counter >= tracking.counter) {
            // the pinned wave landed: resolved, and re-armed on the next pass
            stallTracking.remove(subject.key)
            return
        }
        val heldMs = now - tracking.sinceMs
        if (heldMs < STALL_WINDOW_MS) return
        if (activityTick <= tracking.sinceActivity) return
        val stalled = Timestamp(tracking.source, tracking.counter)
        raise(
            kind = WaveHealthRow.STALLED_WAVE,
            subject = subject,
            wave = stalled,
            lagWaves = tracking.counter - subject.frontier.counter,
            heldMs = heldMs,
            now = now,
            description = "heuristic: wave ${tracking.counter} observed on this edge has not reached the " +
                "observed frontier after ${seconds(heldMs)}s of graph activity — it may have been " +
                "absorbed rather than lost",
            confirmed = confirmed,
            emissions = emissions,
        )
    }

    /**
     * Open a row, or re-confirm and possibly update one already open. An update
     * is published only when something a client would render actually changed —
     * `heldMs` grows on every tick by construction and is not on its own a
     * reason to re-send a row.
     */
    private fun raise(
        kind: String,
        subject: Subject,
        wave: Timestamp,
        lagWaves: Long,
        heldMs: Long,
        now: Long,
        description: String,
        confirmed: MutableSet<String>,
        emissions: MutableList<WaveHealthRow>,
    ) {
        val id = "$kind:${subject.edge}:${InspectorServer.encodeRef(subject.ref)}"
        confirmed += id
        val row = WaveHealthRow(
            id = id,
            kind = kind,
            state = WaveHealthRow.OPEN,
            ref = InspectorServer.encodeRef(subject.ref),
            edge = subject.edge.toString(),
            wave = stampOf(wave),
            frontier = stampOf(subject.frontier),
            lagWaves = lagWaves,
            heldMs = heldMs,
            atMs = now,
            description = description,
        )
        val prior = open.put(id, row)
        confirmedAtMs[id] = now
        when {
            prior == null -> {
                evict(now, keep = id, emissions = emissions)
                emissions += row
            }

            prior.wave != row.wave || prior.lagWaves != row.lagWaves || prior.frontier != row.frontier ->
                emissions += row
        }
    }

    /**
     * Decide, for every currently open row, whether it survives this pass.
     *
     * The order below is the ticket's list of clearing causes, and each is
     * checked against live state rather than being waited out:
     * the tapped edge unbound (or now feeds a different cell); the cell's
     * observation was released, or the cell was despawned or reported cold; the
     * site's source epoch changed; the condition was re-derived and no longer
     * holds. [ROW_TTL_MS] is the backstop for the remaining case — the subject
     * is neither gone nor re-derivable, which happens when a site's last
     * observed emission is a baseline and its wave position is momentarily
     * unreadable — and, more importantly, for any path not enumerated here at
     * all. That is what makes "rows always clear" structural rather than a
     * consequence of this list being complete.
     */
    private fun reconcileOpenRows(
        now: Long,
        confirmed: Set<String>,
        subjectsByKey: Map<SubjectKey, Subject>,
        boundEdges: Map<UUID, CellRef>,
        observedNow: Set<CellRef>,
        epochChanged: Set<UUID>,
        emissions: MutableList<WaveHealthRow>,
    ) {
        open.keys.toList().forEach { id ->
            val row = open[id] ?: return@forEach
            val key = keyOf(row) ?: return@forEach clearRow(id, now, emissions)
            val gone = boundEdges[key.edge] != key.ref ||
                key.ref !in observedNow ||
                isCold(key.ref) ||
                key.edge in epochChanged
            when {
                gone -> clearRow(id, now, emissions)
                id in confirmed -> Unit
                key in subjectsByKey -> clearRow(id, now, emissions)
                now - (confirmedAtMs[id] ?: now) >= ROW_TTL_MS -> clearRow(id, now, emissions)
            }
        }
    }

    /**
     * Retire [id]: one `state: "cleared"` event carrying the row's last known
     * fields, so a client can render *what* resolved rather than only that
     * something did.
     */
    private fun clearRow(id: String, now: Long, emissions: MutableList<WaveHealthRow>) {
        val row = open.remove(id) ?: return
        confirmedAtMs.remove(id)
        keyOf(row)?.let { key ->
            lagTracking.remove(key)
            stallTracking.remove(key)
        }
        emissions += row.copy(state = WaveHealthRow.CLEARED, atMs = now)
    }

    /**
     * Enforce [maxOpen]. The oldest open row that is not the one just opened is
     * evicted, **and its `cleared` event is emitted** — the cap is the server's
     * problem, and a client left holding a row the server has forgotten would
     * make it the client's.
     */
    private fun evict(now: Long, keep: String, emissions: MutableList<WaveHealthRow>) {
        while (open.size > maxOpen) {
            val oldest = open.keys.firstOrNull { it != keep } ?: return
            clearRow(oldest, now, emissions)
        }
    }

    private fun keyOf(row: WaveHealthRow): SubjectKey? {
        val edge = runCatching { UUID.fromString(row.edge) }.getOrNull() ?: return null
        val ref = InspectorServer.decodeRef(row.ref) ?: return null
        return SubjectKey(edge, ref)
    }

    /** One eligible comparison for one pass: a tapped edge, an observed cell, and their two stamps. */
    private class Subject(
        val edge: UUID,
        val ref: CellRef,
        val wave: Timestamp,
        val frontier: Timestamp,
    ) {
        val key = SubjectKey(edge, ref)
    }

    /** The identity a condition is tracked and a row is keyed by. */
    private data class SubjectKey(val edge: UUID, val ref: CellRef)

    /**
     * One condition's continuous run. [source] is carried so a fresh epoch is
     * detected here too, not only at [resetChangedEpochs]; [counter] is the
     * pinned wave `stalledWave` is waiting for (and, for `frontierLag`, merely
     * the wave the run started at).
     */
    private class Tracking(
        val source: UUID,
        val counter: Long,
        val sinceMs: Long,
        /** [activityTick] when the run began — the liveness gate's baseline. */
        val sinceActivity: Long,
    )

    internal companion object {
        /**
         * Minimum same-source counter delta before `frontierLag` is even
         * considered. Sized against guards 4 and 5 rather than against any
         * observed fault: a filter's lag is bounded by its longest *consecutive*
         * drop run, and an absorbing fold's by its longest run of ineffective
         * deltas, so a threshold of 32 excludes every operator that lets
         * something through at least every 32 waves. Deliberately conservative
         * — a false positive in a diagnostic labelled "heuristic" is still a lie
         * a user has to chase.
         */
        const val LAG_THRESHOLD_WAVES = 32L

        /**
         * How long the delta must hold *continuously* before a row opens. The
         * second half of the guard-4/5 defence: a burst that briefly outruns a
         * downstream and then settles never reaches this, and neither does a
         * quiescent moment inside an otherwise healthy graph.
         */
        const val LAG_GRACE_MS = 5_000L

        /**
         * `stalledWave`'s window for the observed frontier to reach a pinned
         * wave. Twice [LAG_GRACE_MS]: this condition needs no magnitude at all
         * (one un-landed wave is enough), so its only protection against slow
         * but healthy propagation is time.
         */
        const val STALL_WINDOW_MS = 10_000L

        /**
         * An open row not re-confirmed for this long clears itself. The backstop
         * that makes "rows always clear" true for paths [reconcileOpenRows] does
         * not enumerate.
         */
        const val ROW_TTL_MS = 30_000L

        /**
         * Cap on simultaneously open rows — the same bound and the same
         * oldest-evicted discipline as the error lane's ring buffers, applied to
         * an open set rather than to a history.
         */
        const val WAVE_HEALTH_MAX_OPEN = 200

        private fun stampOf(stamp: Timestamp) = WaveStamp(stamp.sourceId.toString(), stamp.counter)

        /** One decimal place, locale-independent — these strings go on the wire. */
        private fun seconds(ms: Long): String = "${ms / 1000}.${(ms % 1000) / 100}"
    }
}
