package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.graph.graph
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.mapSet
import civictech.cell.data.op.JoinSetCell
import civictech.cell.data.op.SemiJoinCell

/**
 * CP-A3 (spec 20/22 §Completeness over silent or stuck edges, G-40): the
 * absorbing operator suite mints `Progress` absorb-acks for the waves it
 * consumes without emitting, so a downstream glitch-free join settles its
 * frontier over a mid-pipeline filter/join/antijoin/groupBy exactly as over a
 * live delta arm.
 */
class GlitchFreeOperatorSuiteTest {

    private val mapDeltaApi = @Suppress("UNCHECKED_CAST")
        (Propagate::class.java as Class<Propagate<MapDelta<String, Long>>>)
    private val setDeltaApi = @Suppress("UNCHECKED_CAST")
        (Propagate::class.java as Class<Propagate<SetDelta<String>>>)

    private fun cust(e: String) = e.first().toString()
    private fun amt(e: String) = e[1].toString().toLong()

    interface MapDeltaInlet {
        val inlet: Use<Propagate<MapDelta<String, Long>>>
    }

    interface MapDeltaOutlet {
        val outlet: Subscribe<Propagate<MapDelta<String, Long>>>
    }

    interface SetInlet {
        val inlet: Use<SetOps<String>>
    }

    // ---------------------------------------------------------------------
    // Part 1: the full M11 chain, observed through a glitch-free wrapper.
    // ---------------------------------------------------------------------

    @Test
    fun `the M11 suite observed glitch-free equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())

            val refs = mutableMapOf<String, CellRef>()
            graph(host.managementInlet) {
                val a = spawn("writerA") { SetCell<String>() }
                val b = spawn("writerB") { SetCell<String>() }
                val union = spawn("union") { UnionSetCell<String>() }
                val mapped = spawn("mapped") { FlatMapSetCell(f = { e: String -> listOf(e.lowercase()) }) }
                val cats = spawn("cats") { SetCell<String>() }
                val blocked = spawn("blocked") { SetCell<String>() }
                val join = spawn("join") {
                    JoinSetCell(
                        leftKey = { e: String -> cust(e) },
                        rightKey = { c: String -> cust(c) },
                        combine = { e: String, c: String -> e + c.drop(1) },
                    )
                }
                val anti = spawn("anti") {
                    SemiJoinCell(leftKey = { e: String -> cust(e) }, rightKey = { k: String -> k }, negated = true)
                }
                val sum = spawn("sum") { GroupByCell(keyFn = ::cust, aggregator = Aggregators.sumOf(::amt)) }
                // the glitch-free observer sits directly on the groupBy output
                val gf = spawn("gf") { GlitchFreeCell(mapDeltaApi) }
                a linkTo union
                b linkTo union
                union linkTo mapped
                connect(mapped, "outlet", join, "left")
                connect(cats, "outlet", join, "right")
                connect(join, "outlet", anti, "left")
                connect(blocked, "outlet", anti, "right")
                connect(anti, "outlet", sum, "inlet")
                connect(sum, "outlet", gf, "inlet")
                listOf(a, b, cats, blocked, sum, gf).forEach { refs[it.name] = it.ref }
            }

            val flushed = mutableListOf<MapDelta<String, Long>>()
            host.lookup<MapDeltaOutlet>(refs.getValue("gf"))!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Long>> {
                    override fun propagate(value: MapDelta<String, Long>) {
                        flushed += value
                    }
                }, PortRef.generate()),
            )

            val writerA = host.lookup<SetInlet>(refs.getValue("writerA"))!!.inlet.call
            val writerB = host.lookup<SetInlet>(refs.getValue("writerB"))!!.inlet.call
            val cats = host.lookup<SetInlet>(refs.getValue("cats"))!!.inlet.call
            val blocked = host.lookup<SetInlet>(refs.getValue("blocked"))!!.inlet.call

            val heldCats = mutableSetOf("aP", "bQ", "cR")
            heldCats.forEach { cats.add(it) }

            val rnd = Random(seed)
            val rowDomain = listOf("A3", "a3", "a5", "B2", "b7", "c2", "C4")
            val blockDomain = listOf("a", "b", "c")
            val held = listOf(mutableSetOf<String>(), mutableSetOf())
            val heldBlocked = mutableSetOf<String>()
            val writers = listOf(writerA, writerB)

            repeat(60) {
                when (rnd.nextInt(10)) {
                    in 0..6 -> {
                        val w = rnd.nextInt(2)
                        val e = rowDomain[rnd.nextInt(rowDomain.size)]
                        if (rnd.nextInt(10) < 6 || e !in held[w]) {
                            writers[w].add(e); held[w] += e
                        } else {
                            writers[w].remove(e); held[w] -= e
                        }
                    }
                    in 7..8 -> {
                        val k = blockDomain[rnd.nextInt(blockDomain.size)]
                        if (k in heldBlocked) {
                            blocked.remove(k); heldBlocked -= k
                        } else {
                            blocked.add(k); heldBlocked += k
                        }
                    }
                    else -> {
                        val c = "aP"
                        if (c in heldCats) {
                            cats.remove(c); heldCats -= c
                        } else {
                            cats.add(c); heldCats += c
                        }
                    }
                }
                if (rnd.nextInt(5) == 0) controller.runToIdle()
            }
            controller.runToIdle()

            // batch recompute over final state (same shape as the M11 exit test)
            val rows = held.flatten().map { it.lowercase() }.toSet()
            val joined = rows.flatMap { row -> heldCats.filter { cust(it) == cust(row) }.map { c -> row + c.drop(1) } }
            val kept = joined.filter { cust(it) !in heldBlocked }
            val batchSums = kept.groupBy(::cust).mapValues { (_, es) -> es.sumOf(::amt) }

            assertEquals(batchSums, mapFold(flushed), "glitch-free observer diverged from batch on seed $seed")
        }
    }

    // ---------------------------------------------------------------------
    // Part 2: the absorb-ack is load-bearing at a fan-in — a real absorbing
    // arm settles the join's frontier; a non-acking twin strands the wave.
    // ---------------------------------------------------------------------

    /** A filter that forwards passing elements but NEVER acks a swallowed wave (the CP-A3 control). */
    private class NonAckingFilter(
        private val predicate: (String) -> Boolean,
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>))
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.onEach { d ->
                val passed = SetDelta(d.adds.filterKeys(predicate), d.dels.filterKeys(predicate))
                if (passed.adds.isNotEmpty() || passed.dels.isNotEmpty()) outlet.call.propagate(passed)
                // deliberately no absorb-ack
            }
        }
    }

    /**
     * Runs the fan-in diamond: one source feeds a passing arm (identity mapSet,
     * always emits) and an absorbing arm (a filter for "a…"); both reconverge
     * on a glitch-free join. The final write is a non-"a" element — a real add
     * on the pass arm, silently absorbed on the filter arm — so the join can
     * only complete that wave if the filter's absorb-ack crossed. Returns the
     * membership the glitch-free observer settled on.
     */
    private fun runFanIn(seed: Long, acking: Boolean): Set<String> {
        val controller = SimulationController(seed)
        val hostSrc = ManagedHost(scheduler = controller.scheduler())
        val hostMap = ManagedHost(scheduler = controller.scheduler())
        val hostFilter = ManagedHost(scheduler = controller.scheduler())
        val hostJoin = ManagedHost(scheduler = controller.scheduler())

        val source = SetCell<String>()
        val pass = FlatMapSetCell<String, String>(f = { listOf(it) }) // identity mapSet
        val filter: Cell = if (acking) FilterCell<String>(predicate = { it.startsWith("a") })
        else NonAckingFilter(predicate = { it.startsWith("a") })
        val gf = GlitchFreeCell(setDeltaApi)
        val observed = mutableListOf<SetDelta<String>>()
        val observer = object : Cell {
            override val ref = CellRef(UUID.randomUUID())
            @Suppress("UNCHECKED_CAST")
            val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<SetDelta<String>>>))

            init {
                inlet.onEach { observed += it }
            }
        }

        hostSrc.managementInlet.call.spawn(source)
        hostMap.managementInlet.call.spawn(pass)
        hostFilter.managementInlet.call.spawn(filter)
        hostJoin.managementInlet.call.spawn(gf)
        hostJoin.managementInlet.call.spawn(observer)
        controller.runToIdle()

        // source fans to both arms through their own hosts (independent scheduling)
        val passInlet = hostMap.lookup<DeltaInletProxy>(pass.ref)!!.inlet.call
        val filterInlet = hostFilter.lookup<DeltaInletProxy>(filter.ref)!!.inlet.call
        source.outlet.subscribe(Use.fixed(passInlet, PortRef.generate()))
        source.outlet.subscribe(Use.fixed(filterInlet, PortRef.generate()))

        // both arms link into the glitch-free join (fires EdgeOpen), delivery routed over hostJoin
        val passOutlet = outletOf(pass)
        val filterOutlet = outletOf(filter)
        (passOutlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>))
        (filterOutlet.linkTo(gf.inlet as LinkFrom<Propagate<SetDelta<String>>>))
        val routedGf = hostJoin.lookup<DeltaInletProxy>(gf.ref)!!.inlet.call
        passOutlet.unsubscribe(gf.inlet.ref)
        passOutlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
        filterOutlet.unsubscribe(gf.inlet.ref)
        filterOutlet.subscribe(Use.fixed(routedGf, gf.inlet.ref))
        gf.outlet.subscribe(Use.fixed(observer.inlet.call, observer.inlet.ref))
        controller.runToIdle()

        val srcApi = hostSrc.lookup<SetInlet>(source.ref)!!.inlet.call
        val rnd = Random(seed)
        // interleaved passing writes, then a final non-"a" add absorbed by the filter arm
        val passing = listOf("a1", "a2", "a4")
        passing.forEach { e ->
            srcApi.add(e)
            repeat(rnd.nextInt(4)) { controller.step() }
        }
        srcApi.add("z9") // final wave: real on the pass arm, absorbed on the filter arm
        controller.runToIdle()

        return tagFold(observed)
    }

    @Suppress("UNCHECKED_CAST")
    private fun outletOf(cell: Cell): FanOutlet<Propagate<SetDelta<String>>> =
        civictech.cell.port.PortRegistry.of(cell)["outlet"] as FanOutlet<Propagate<SetDelta<String>>>

    @Test
    fun `an absorbing arm's Progress ack settles the fan-in frontier on every seed`() {
        for (seed in 0L until 100L) {
            val settled = runFanIn(seed, acking = true)
            assertTrue("z9" in settled, "the absorbed final wave never settled on seed $seed (settled=$settled)")
            assertEquals(setOf("a1", "a2", "a4", "z9"), settled, "unexpected membership on seed $seed")
        }
    }

    @Test
    fun `control - without the absorb-ack the final wave stalls at the fan-in`() {
        for (seed in 0L until 20L) {
            val settled = runFanIn(seed, acking = false)
            // the filter arm never acks 'z9'; the join holds that wave forever
            assertFalse("z9" in settled, "expected a stall on seed $seed but z9 settled (settled=$settled)")
        }
    }
}
