package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.op.GroupByCell
import civictech.cell.link.LinkResult
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.io.Serializable
import java.util.UUID

/**
 * One writer: one outlet, so one wave `sourceId` (`[22-SRC-01]`). [emit] is one
 * [FanOutlet.originate] — a fresh `(sourceId, counter)` fanned, as the SAME
 * `SetDelta`, to every link in attachment order (the
 * `GroupByEvictionGlitchFreeTest` shape).
 */
internal class LatenessSource<E>(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
    @Suppress("UNCHECKED_CAST")
    val outlet = registerPort(
        "outlet",
        FanOutlet(Propagate::class.java as Class<Propagate<SetDelta<E>>>, PortRef.generate()),
    )

    fun emit(delta: SetDelta<E>) = outlet.originate { propagate(delta) }
}

/**
 * A test-side interposing relay: one inlet, one outlet, forwarding
 * `transform(d)` for every delivered `d` (`null` = swallow). With the identity
 * it is transparent; the harness's controls (fh1fo-D4) give it a
 * fault-injecting transform instead of giving any production cell a fault
 * flag. Generic so the join shape (task computenet-fh1fo.2) can relay a
 * `SetDelta` the same way.
 *
 * **The `evictWithoutRetract` transform** ([evictWithoutRetract]) strips from
 * a `MapDelta` every removal of a window `k` with `keyTime(k) <= floor()`,
 * `floor()` read at delivery time. That predicate isolates eviction emissions:
 * `GroupByCell` sets its floor before it emits an eviction's removals, so
 * every eviction removal satisfies it; and a data-driven group death of such a
 * `k` cannot occur after the rise, because the window's members were evicted
 * by that rise and a later del of them finds no live tag and is a no-op
 * (`[24-WL-09]`) — while an add for them is below the floor and late-dropped
 * (`[24-WL-07]`). (A window refused under `[24-WL-17]` could still die by data
 * after the rise; the harness carries no exclusives, so none is refused.)
 */
internal class Relay<D : Any>(
    private val transform: (D) -> D?,
    override val ref: CellRef = CellRef(UUID.randomUUID()),
) : Cell {
    @Suppress("UNCHECKED_CAST")
    val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<D>>))

    @Suppress("UNCHECKED_CAST")
    val outlet = registerPort("outlet", FanOutlet(Propagate::class.java as Class<Propagate<D>>, PortRef.generate()))

    init {
        inlet.serve(object : Propagate<D> {
            override fun propagate(value: D) {
                transform(value)?.let { outlet.call.propagate(it) }
            }
        })
    }

    companion object {
        /** The B3 control's transform (`[KE4-31]`): drop eviction retractions, keep everything else. */
        fun <K, V> evictWithoutRetract(keyTime: (K) -> Long, floor: () -> Long?): (MapDelta<K, V>) -> MapDelta<K, V>? = { d ->
            val f = floor()
            val kept = if (f == null) d.removals else d.removals.filterTo(mutableSetOf()) { keyTime(it) > f }
            if (d.puts.isEmpty() && kept.isEmpty()) null else MapDelta(d.puts, kept)
        }
    }
}

/**
 * KE4.6 (computenet-fh1fo.1) — the seeded lateness harness over the tumbling
 * [GroupByCell]: B6 batch equivalence under `[24-WL-10]`'s window-keyed
 * restriction (`[KE4-10]`, group-by half), B7's window-count bound
 * (`[24-WL-19]`, `[KE4-11]`, group-by half), and the B3 evict-without-retract
 * control made red-capable (`[KE4-31]`).
 *
 * **Shape (fh1fo-D1/D2).** `writers` [LatenessSource]s fan in directly to
 * `WaterlineCell.inlet` and `GroupByCell.inlet`; `WaterlineCell.outlet ->
 * GroupByCell.waterline`. Every source links the waterline arm FIRST, so a
 * wave's own floor rise (and the eviction it triggers) precedes its fold —
 * `FanOutlet` fans to consumers in attachment order (`consumerOrder`, observed
 * at 7fcf6216), and [LatenessOracle] mirrors that order. Synchronous
 * in-process links, no host: a wave is quiescent when [LatenessSource.emit]
 * returns.
 *
 * Subscriber A is linked (through a [Relay], identity unless the control is
 * selected) before the first wave; the late joiner links at idle and folds its
 * catch-up (`[21-CATCHUP-02]`).
 */
class LatenessHarnessTest {

    /** Window key → the window's start, via the kernel's own assigner (the cell side). */
    private class WindowOf(window: Long) : (Ev) -> Long, Serializable {
        private val assign = Windows.tumbling(window)
        override fun invoke(e: Ev): Long = assign(e.t)
    }

    private class Rig(val env: Envelope, evictWithoutRetract: Boolean) {
        val keyTime: (Long) -> Long = { k -> k + env.window }
        val cell = GroupByCell(
            keyFn = WindowOf(env.window),
            aggregator = Aggregators.count<Ev>(),
            lateness = Windows.Lateness(EvTime, env.lateness),
            keyTime = keyTime,
        )
        val wc = WaterlineCell(lateness = Windows.Lateness(EvTime, env.lateness))
        val sources = List(env.writers) { LatenessSource<Ev>() }
        val relay = Relay<MapDelta<Long, Long>>(
            if (evictWithoutRetract) Relay.evictWithoutRetract(keyTime) { cell.floor() } else { d -> d },
        )
        val seenA: MutableList<MapDelta<Long, Long>>
        val late: MutableList<SetDelta<Ev>>

        init {
            link(wc.outlet, cell.waterline)
            sources.forEach { s ->
                link(s.outlet, wc.inlet) // fh1fo-D2: the waterline arm first
                link(s.outlet, cell.inlet)
            }
            link(cell.outlet, relay.inlet)
            seenA = collect(relay.outlet)
            late = collect(cell.late)
        }

        fun emit(wave: Wave) = sources[wave.writer].emit(SetDelta(adds = wave.adds, dels = wave.dels))
    }

    /** What one seed left behind at idle, for the B6/B7 checks. */
    private class Outcome(val rig: Rig, val oracle: LatenessOracle, val cellFloors: List<Long?>)

    private fun drive(env: Envelope, seed: Long, evictWithoutRetract: Boolean = false): Outcome {
        val gen = LatenessGen(env, seed)
        val oracle = LatenessOracle(env.lateness, env.window)
        val rig = Rig(env, evictWithoutRetract)
        val floors = ArrayList<Long?>(gen.waves.size)
        gen.waves.forEach { w ->
            rig.emit(w)
            oracle.apply(w)
            floors += rig.cell.floor()
        }
        return Outcome(rig, oracle, floors)
    }

    /**
     * B6 checks 1-4 on one seed at idle. Every message names the seed; the
     * per-wave floor check also names the wave.
     */
    private fun checkB6(env: Envelope, seed: Long, evictWithoutRetract: Boolean = false): Outcome {
        val out = drive(env, seed, evictWithoutRetract)
        val (rig, oracle) = out.rig to out.oracle
        val cell = rig.cell
        val where = "seed=$seed env=$env"
        val finalFloor = oracle.floor

        // 1. [24-WL-10]/[24-WL-05]: A's fold == late joiner's catch-up == the restricted batch
        val batch = oracle.restrictedBatch()
        withClue("$where: early subscriber A's fold vs the [24-WL-10] batch") { mapFold(rig.seenA) shouldBe batch }
        withClue("$where: late joiner's catch-up fold vs the [24-WL-10] batch") { lateJoinFold(cell) shouldBe batch }

        // 2. [24-WL-07]: the late outlet carries exactly the oracle's late set, tags verbatim
        val lateMerged = rig.late.fold(SetDelta<Ev>()) { acc, d -> acc.merge(d) }
        withClue("$where: tagFold(late) vs oracle lateSet") { tagFold(rig.late) shouldBe oracle.lateSet.keys }
        withClue("$where: late tags verbatim") {
            lateMerged.adds shouldBe oracle.lateSet
            lateMerged.dels shouldBe emptyMap()
        }
        withClue("$where: droppedBelowFloor") { cell.droppedBelowFloor shouldBe oracle.lateSet.size.toLong() }

        // 3. [24-WL-02]/[24-WL-03]: the floor equals the oracle's after every wave, and never falls
        withClue("$where: final floor") { cell.floor() shouldBe finalFloor }
        var prev: Long? = null
        out.cellFloors.forEachIndexed { i, f ->
            withClue("$where wave=$i: floor vs oracle") { f shouldBe oracle.floors[i] }
            if (prev != null) {
                withClue("$where wave=$i: floor monotone ($prev -> $f)") { (f != null && f >= prev!!).shouldBeTrue() }
            }
            prev = f
        }

        // 4. [24-WL-19] structural half: no passed window in groups or contents, none refused
        withClue("$where: passed windows still in groups") {
            groupKeys(cell).filter { oracle.passed(it) }.shouldBeEmpty()
        }
        withClue("$where: passed windows still in contents()") {
            cell.contents().adds.keys.filter { oracle.passed(oracle.windowOf(it.t)) }.shouldBeEmpty()
        }
        withClue("$where: refusedWindows") { cell.refusedWindows() shouldBe emptyMap() }
        return out
    }

    // ------------------------------------------------------------------ B6

    @Test
    fun `B6 - no violations - every seed equals the restricted batch and nothing is late`() {
        // lateness >= 2*disorder + maxBatch: a wave's lowest add sits at most
        // maxBatch + disorder below the global base while the floor sits at most
        // disorder - lateness above it, so no add can land under the floor.
        val env = NO_VIOLATION.copy(length = 500)
        var evictedSomething = 0
        for (seed in SEEDS) {
            val out = checkB6(env, seed)
            withClue("seed=$seed: no-violation envelope must drop nothing") {
                out.oracle.lateSet.keys.shouldBeEmpty()
                out.rig.cell.droppedBelowFloor shouldBe 0L
            }
            if (out.oracle.unrestrictedBatch().keys.any { out.oracle.passed(it) }) evictedSomething++
        }
        // non-vacuity of the restriction: eviction really happened
        evictedSomething shouldBe SEEDS.count()
    }

    @Test
    fun `B6 - with violations - every seed equals the restricted batch and late drops are exact`() {
        val env = VIOLATION.copy(length = 500)
        var seedsThatDropped = 0
        for (seed in SEEDS) {
            val out = checkB6(env, seed)
            if (out.oracle.lateSet.isNotEmpty()) seedsThatDropped++
        }
        // the arm is not vacuous: late drops happened (on most seeds, and at least one)
        seedsThatDropped shouldBeGreaterThan SEEDS.count() / 2
    }

    // ------------------------------------------------------------------ B7

    /**
     * `[24-WL-19]`'s live-window bound for the tumbling shape, from the
     * envelope alone. Derivation, at idle after the last wave with global base
     * `B` (one past the last add's base), every writer having contributed and
     * no violations:
     * - each writer's last add is on a base `>= B - sourceLag` (it emitted in
     *   one of the last `writers` waves, which hold `<= writers * maxBatch`
     *   adds), so its maximum is `>= B - sourceLag - disorder`, the final
     *   candidate is `>= B - sourceLag - disorder - lateness`, and so is the
     *   floor `F` (the running max of candidates);
     * - a live window `k` was not passed, `k + window > F`, and holds an add
     *   with `t <= B - 1 + disorder`, so `k` is a multiple of `window` in
     *   `(F - window, B - 1 + disorder]`;
     * - that interval is `< lateness + 2*disorder + sourceLag + window` long,
     *   so it holds at most `(lateness + 2*disorder + sourceLag + window) /
     *   window + 1` multiples of `window`.
     * The bound is on windows, not elements (`[24-WL-19]`).
     */
    private fun liveWindowBound(env: Envelope): Int =
        ((env.lateness + 2 * env.disorder + env.sourceLag + env.window) / env.window + 1).toInt()

    @Test
    fun `B7 - live windows stay within the envelope's bound at 1k and at 10k`() {
        val bound = liveWindowBound(NO_VIOLATION)
        // non-vacuous: a bound that admits the whole 10k stream is no bound
        bound.toLong() shouldBeLessThan 10_000L / NO_VIOLATION.window / 4

        for (seed in B7_SEEDS) {
            val live = IntArray(2)
            listOf(1_000, 10_000).forEachIndexed { i, length ->
                val env = NO_VIOLATION.copy(length = length)
                val out = drive(env, seed)
                val where = "seed=$seed length=$length bound=$bound"
                val windows = groupKeys(out.rig.cell).size
                live[i] = windows
                withClue("$where: live windows") { windows shouldBeLessThanOrEqual bound }
                withClue("$where: nothing late, so eviction alone bounds the state") {
                    out.oracle.lateSet.keys.shouldBeEmpty()
                }
                // elements: only live windows x the generator's own admitted density — no element bound claimed
                val density = out.oracle.maxDensityPerWindow()
                withClue("$where: held elements vs live windows x density $density") {
                    out.rig.cell.contents().adds.size shouldBeLessThanOrEqual windows * density
                }
            }
            // the 10k run holds no more beyond the bound than the 1k run (both within it)
            withClue("seed=$seed: 10k live windows ${live[1]} vs 1k ${live[0]}, bound $bound") {
                (live[1] <= bound && live[0] <= bound).shouldBeTrue()
            }
        }
    }

    // ------------------------------------------------------- B3 control

    @Test
    fun `B3 control - evict without retract diverges the two subscribers`() {
        val env = NO_VIOLATION.copy(length = 500)
        // precondition: a seed whose final floor passed a window still holding live admitted elements
        val seed = SEEDS.first { s ->
            val oracle = LatenessOracle.run(LatenessGen(env, s))
            oracle.unrestrictedBatch().keys.any { oracle.passed(it) }
        }
        val oracle = LatenessOracle.run(LatenessGen(env, seed))
        oracle.unrestrictedBatch().keys.filter { oracle.passed(it) } shouldNotBe emptyList<Long>()

        // the same seed is green without the control ...
        checkB6(env, seed)
        // ... and red with it: A keeps the evicted windows the late joiner never sees
        val failure = shouldThrow<AssertionError> { checkB6(env, seed, evictWithoutRetract = true) }
        (failure.message ?: "").contains("seed=$seed").shouldBeTrue()
    }

    // ------------------------------------------------------------ support

    private companion object {
        val SEEDS = 0L until 100L
        val B7_SEEDS = 0L until 100L

        /** `violationRate = 0`, `lateness = 2*disorder + maxBatch` — see the first B6 test. */
        val NO_VIOLATION = Envelope(writers = 3, window = 10, lateness = 7, disorder = 2, violationRate = 0.0, length = 500)

        /** The design's example envelope (3 writers, disorder 3, lateness 2) plus deliberate breaches. */
        val VIOLATION = Envelope(writers = 3, window = 10, lateness = 2, disorder = 3, violationRate = 0.05, length = 500)

        @Suppress("UNCHECKED_CAST")
        fun <T> link(outlet: FanOutlet<Propagate<T>>, inlet: FanInlet<Propagate<T>>) {
            (outlet.linkTo(inlet as LinkFrom<Propagate<T>>) is LinkResult.Connected).shouldBeTrue()
        }

        fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
            val collected = mutableListOf<T>()
            outlet.subscribe(Use.fixed(object : Propagate<T> {
                override fun propagate(value: T) {
                    collected += value
                }
            }, PortRef.generate()))
            return collected
        }

        /** The keys [cell] holds in `groups`, read through its snapshot. */
        @Suppress("UNCHECKED_CAST")
        fun groupKeys(cell: GroupByCell<*, Long, *, *>): Set<Long> =
            ((cell.snapshot() as List<*>)[1] as Map<Long, *>).keys

        /** Link a fresh subscriber to [cell] now and return the fold of what it caught up with. */
        fun lateJoinFold(cell: GroupByCell<Ev, Long, Long, Long>): Map<Long, Long> {
            val arrivals = mutableListOf<MapDelta<Long, Long>>()
            val joiner = object : Cell {
                override val ref = CellRef(UUID.randomUUID())

                @Suppress("UNCHECKED_CAST")
                val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<Long, Long>>>))

                init {
                    inlet.serve(object : Propagate<MapDelta<Long, Long>> {
                        override fun propagate(value: MapDelta<Long, Long>) {
                            arrivals += value
                        }
                    })
                }
            }
            @Suppress("UNCHECKED_CAST")
            (cell.outlet.linkTo(joiner.inlet as LinkFrom<Propagate<MapDelta<Long, Long>>>) is LinkResult.Connected).shouldBeTrue()
            return mapFold(arrivals)
        }
    }
}
