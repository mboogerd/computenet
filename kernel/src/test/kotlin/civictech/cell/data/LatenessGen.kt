package civictech.cell.data

import civictech.cell.Timestamp
import java.io.Serializable
import java.util.Random
import java.util.UUID

/**
 * KE4.6 (computenet-fh1fo.1): the seeded lateness generator and its
 * cell-independent oracle, shared by `LatenessHarnessTest`'s B6 (batch
 * equivalence, `[24-WL-10]`) and B7 (window-count bound, `[24-WL-19]`) arms,
 * and — computenet-fh1fo.2 — the join-family domain those arms use for the
 * `JoinSetCell` shape (`LatenessOracle.remaining`/`joinBatch`, [OracleJoinKey]).
 *
 * **Independence (fh1fo-D3).** Nothing here imports a `civictech.cell.data.op`
 * type: the oracle's floor, late set and batch are re-derived from spec 24
 * §Lateness and waterlines directly — never by consulting `GroupByCell` or
 * `WaterlineCell` — so a harness mismatch is evidence against the cells, not a
 * second copy of them agreeing with itself.
 */

/**
 * One generated element: unique per [seq], so the tag algebra never merges two
 * writers' elements, and [t] is the explicit event-time attribute `[24-WL-01]`
 * reads (through [EvTime]), never an arrival order.
 */
data class Ev(val writer: Int, val seq: Int, val t: Long) : Serializable

/** `[24-WL-01]`: the event-time extractor as a named `Serializable` value. */
object EvTime : (Ev) -> Long, Serializable {
    override fun invoke(e: Ev): Long = e.t
    private fun readResolve(): Any = EvTime
}

/**
 * The generation envelope. Event time is a global base counter that advances
 * by 1 per emitted add, jittered uniformly in `[-disorder, +disorder]`; waves
 * are assigned to [writers] round-robin, each carrying `1..maxBatch` adds
 * (one wave = one `originate`, so a wave can be partially late-dropped). With
 * probability [violationRate] an add is instead placed far below its writer's
 * current maximum — a deliberate breach of the author's `[24-WL-07]` promise
 * (see [Envelope.violationDepth]). With probability [retractRate] a wave also
 * carries a del of one earlier emitted add (dels are never late, `[24-WL-08]`;
 * the oracle folds them by liveness).
 */
data class Envelope(
    val writers: Int,
    val window: Long,
    val lateness: Long,
    val disorder: Long,
    val violationRate: Double,
    val length: Int,
    val maxBatch: Int = 3,
    val retractRate: Double = 0.05,
) {
    init {
        require(writers > 0 && window > 0 && lateness >= 0 && disorder >= 0 && maxBatch > 0 && length > 0)
    }

    /**
     * The round-robin source lag, in event-time units: every writer emits
     * once every [writers] waves, and those waves carry at most
     * `writers * maxBatch` adds, so at any wave boundary each writer's last
     * add sits on a base at most this far behind the global base.
     */
    val sourceLag: Long get() = writers.toLong() * maxBatch

    /**
     * How far below its writer's current maximum a violating add is placed
     * (plus `0 until window` more). `lateness + 2*disorder + sourceLag + 1`:
     * once every writer has contributed, the floor is at least
     * `base - sourceLag - disorder - lateness` while the writer's maximum is at
     * most `base + disorder`, so an add this deep is strictly below the floor
     * — a breach that is actually dropped, not merely declared.
     */
    val violationDepth: Long get() = lateness + 2 * disorder + sourceLag + 1
}

/** One wave from one writer: the adds and dels its single `originate` carries. */
data class Wave(val writer: Int, val adds: Map<Ev, Set<Timestamp>>, val dels: Map<Ev, Set<Timestamp>>)

/** The generated stream for one seed. Deterministic in (envelope, seed). */
class LatenessGen(val env: Envelope, val seed: Long) {

    val waves: List<Wave> = generate()

    private fun generate(): List<Wave> {
        val rng = Random(seed)
        val out = mutableListOf<Wave>()
        val writerMax = arrayOfNulls<Long>(env.writers)
        val pool = mutableListOf<Pair<Ev, Set<Timestamp>>>() // adds a later del may target
        var base = BASE0
        var seq = 0
        var wave = 0
        while (seq < env.length) {
            val w = wave % env.writers
            val n = minOf(1 + rng.nextInt(env.maxBatch), env.length - seq)
            val adds = LinkedHashMap<Ev, Set<Timestamp>>()
            repeat(n) {
                val jitter = if (env.disorder == 0L) 0L else rng.nextLong(-env.disorder, env.disorder + 1)
                val wm = writerMax[w]
                val t = if (wm != null && rng.nextDouble() < env.violationRate) {
                    wm - env.violationDepth - rng.nextLong(env.window)
                } else base + jitter
                base++
                val e = Ev(w, seq, t)
                val tags = setOf(Timestamp(UUID(seed, seq.toLong()), seq.toLong() + 1))
                adds[e] = tags
                writerMax[w] = maxOf(wm ?: t, t)
                seq++
            }
            // a del targets an add of an EARLIER wave only, never one in this wave
            val dels = if (pool.isNotEmpty() && rng.nextDouble() < env.retractRate) {
                val (e, tags) = pool.removeAt(rng.nextInt(pool.size))
                mapOf(e to tags)
            } else emptyMap()
            adds.forEach { (e, tags) -> pool += e to tags }
            out += Wave(w, adds, dels)
            wave++
        }
        return out
    }

    private companion object {
        /** Keeps every event time — violations included — well clear of zero. */
        const val BASE0 = 1_000_000L
    }
}

/**
 * The independent oracle (fh1fo-D3), fed the same waves in the same order.
 *
 * Per wave, mirroring the harness wiring (fh1fo-D2, waterline arm attached
 * first): FIRST fold the wave's adds into the per-writer maxima and the floor,
 * THEN classify each add as late iff `t < floor` — strict, `[24-WL-07]`.
 *
 * The floor is `[24-WL-02]`/`[24-WL-20]` exactly: the running maximum over
 * waves of the candidate `min over contributing sources of (maxSeen −
 * lateness)`, `null` (the identity) before the first contribution. It is
 * never `min(maxima) − lateness` alone — a source joining below the floor
 * leaves the floor where it is (epic computenet-lxo, DECISION 2026-09-27 00:25).
 * Sources here are writers: each writer is one outlet, hence one wave
 * `sourceId`, and nothing retires.
 */
class LatenessOracle(
    private val lateness: Long,
    private val window: Long,
    /**
     * Whether a del emitted by writer `emitter` reaches the state holding
     * row `e`. The identity for a single-inlet cell (every writer feeds the
     * one inlet). The join shape (computenet-fh1fo.2) routes each writer to
     * ONE side, so a del of the other side's row lands on a side that never
     * held it and is a no-op — this predicate says so.
     */
    private val delReaches: (emitter: Int, e: Ev) -> Boolean = { _, _ -> true },
) {
    private val maxima = HashMap<Int, Long>()

    /** Current floor; `null` is the identity. */
    var floor: Long? = null
        private set

    /** The floor after each wave, in wave order. */
    val floors = mutableListOf<Long?>()

    /** Every add classified late, tags verbatim. */
    val lateSet = LinkedHashMap<Ev, Set<Timestamp>>()

    /** Admitted adds still live (not yet deleted by liveness). Never evicted: the batch side restricts instead. */
    val live = LinkedHashMap<Ev, Set<Timestamp>>()

    /** Every admitted add, tags verbatim, in arrival order — deleted or not (the join arm's density source). */
    val admitted = LinkedHashMap<Ev, Set<Timestamp>>()

    /** The generator's own per-window ADMITTED count (dels not subtracted) — B7's density ceiling. */
    val admittedPerWindow = HashMap<Long, Int>()

    fun apply(wave: Wave) {
        // 1. maxima, candidate, floor — before the wave's own classification
        for (e in wave.adds.keys) maxima.merge(wave.writer, e.t, ::maxOf)
        if (maxima.isNotEmpty()) {
            val candidate = maxima.values.min() - lateness
            val f = floor
            if (f == null || candidate > f) floor = candidate
        }
        // 2. classify adds against the floor after this wave's rise
        val f = floor
        for ((e, tags) in wave.adds) {
            if (f != null && e.t < f) {
                lateSet[e] = tags
            } else {
                live[e] = tags
                admitted[e] = tags
                admittedPerWindow.merge(windowOf(e.t), 1, Int::plus)
            }
        }
        // 3. dels fold iff their tag is live ([24-WL-08]); a del of a late add is a no-op
        for ((e, tags) in wave.dels) if (delReaches(wave.writer, e) && live[e] == tags) live.remove(e)
        floors += floor
    }

    fun windowOf(t: Long): Long = Math.floorDiv(t, window) * window

    /** A tumbling window's exclusive end — `keyTime` for `[24-WL-06]`. */
    fun keyTime(k: Long): Long = k + window

    /** True iff window [k] has been passed by the final floor ([24-WL-06]). */
    fun passed(k: Long): Boolean = floor.let { it != null && keyTime(k) <= it }

    /** Count per window over the late-filtered, del-folded input — unrestricted. */
    fun unrestrictedBatch(): Map<Long, Long> =
        live.keys.groupingBy { windowOf(it.t) }.eachCount().mapValues { it.value.toLong() }

    /**
     * `[24-WL-10]` for a window-keyed cell: recompute in batch over the
     * late-filtered input, then drop every window the final floor has passed
     * (`keyTime(k) <= finalFloor`).
     */
    fun restrictedBatch(): Map<Long, Long> = unrestrictedBatch().filterKeys { !passed(it) }

    fun maxDensityPerWindow(): Int = admittedPerWindow.values.maxOrNull() ?: 0

    // ---- the join-family domain (computenet-fh1fo.2, [24-WL-10] join clause, [24-WL-16])

    /**
     * True iff row [e] survives the final floor: the join family's eviction
     * unit is the row, evicted exactly when `t < floor` (strict — the same
     * threshold as the late drop, `[24-WL-16]`). No exclusives, so nothing is
     * refused and every such row is gone.
     */
    fun survives(e: Ev): Boolean = floor.let { f -> f == null || !(e.t < f) }

    /** The live rows left after the final floor's row eviction: late-filtered, del-folded, then `t < finalFloor` removed. */
    fun remaining(): Map<Ev, Set<Timestamp>> = live.filterKeys(::survives)

    /**
     * `[24-WL-10]`'s join-family batch: the equi-join over [remaining] rows,
     * split into sides by [isLeft], matched by [key]. Whole state — no
     * output-side filter ("the two sides then need no further filter").
     */
    fun <K> joinBatch(isLeft: (Ev) -> Boolean, key: (Ev) -> K): Set<Pair<Ev, Ev>> {
        val rows = remaining().keys
        val right = rows.filterNot(isLeft).groupBy(key)
        return rows.filter(isLeft).flatMapTo(LinkedHashSet()) { l -> (right[key(l)] ?: emptyList()).map { r -> l to r } }
    }

    companion object {
        fun run(gen: LatenessGen, delReaches: (Int, Ev) -> Boolean = { _, _ -> true }): LatenessOracle =
            LatenessOracle(gen.env.lateness, gen.env.window, delReaches).apply { gen.waves.forEach(::apply) }
    }
}

/**
 * The join shape's key (computenet-fh1fo.2), the oracle's copy: a windowed
 * equi-join — same tumbling window of [window] AND same `seq % alphabet`. A
 * small [alphabet] makes several rows per window share a key, so pairs are
 * actually minted. Derived from `seq`, not drawn from the generator's `Random`,
 * so adding the join arm leaves every group-by stream byte-identical.
 */
class OracleJoinKey(private val window: Long, private val alphabet: Int) : (Ev) -> Pair<Long, Int>, Serializable {
    override fun invoke(e: Ev): Pair<Long, Int> = Math.floorDiv(e.t, window) * window to e.seq % alphabet
}
