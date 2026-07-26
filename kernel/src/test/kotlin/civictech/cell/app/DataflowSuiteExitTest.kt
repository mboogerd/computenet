package civictech.cell.app

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.data.Aggregators
import civictech.cell.data.FlatMapSetCell
import civictech.cell.data.GroupByCell
import civictech.cell.data.JoinSetCell
import civictech.cell.Propagate
import civictech.cell.data.SemiJoinCell
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.UnionSetCell
import civictech.cell.data.mapFold
import civictech.cell.graph.graph
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.MapDelta

/**
 * M11 exit test: the full operations suite composed end-to-end across two
 * hosts — writers → union → mapSet (many-to-one) → equi-join → antijoin →
 * groupBy(sum) + groupBy(topK) — equals a batch recompute over final writer
 * state on every seed, with a mid-run snapshot/restore and a late joiner.
 */
class DataflowSuiteExitTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetOutletProxy {
        val outlet: Subscribe<Propagate<SetDelta<String>>>
    }

    interface JoinLeftProxy {
        val left: Use<Propagate<SetDelta<String>>>
    }

    interface SumOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<String, Long>>>
    }

    interface TopOutletProxy {
        val outlet: Subscribe<Propagate<MapDelta<String, List<Long>>>>
    }

    private fun cust(e: String) = e.first().toString()
    private fun amt(e: String) = e[1].toString().toLong()

    class MapDeltaCollectorCell(
        val arrivals: MutableList<MapDelta<String, Long>> = mutableListOf(),
        override val ref: CellRef = CellRef(UUID.randomUUID()),
    ) : Cell {
        @Suppress("UNCHECKED_CAST")
        val inlet = registerPort("inlet", FanInlet(Propagate::class.java as Class<Propagate<MapDelta<String, Long>>>))

        init {
            inlet.serve(object : Propagate<MapDelta<String, Long>> {
                override fun propagate(value: MapDelta<String, Long>) {
                    arrivals += value
                }
            })
        }
    }

    @Test
    fun `pipeline across hosts equals batch recompute on every seed`() {
        var controlDiverged = false

        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val sourceHost = ManagedHost(scheduler = controller.scheduler())
            val viewHost = ManagedHost(scheduler = controller.scheduler())

            // source host: writers → union → lowercase normalization (many-to-one)
            val sourceRefs = mutableMapOf<String, CellRef>()
            graph(sourceHost.managementInlet) {
                val a = spawn("writerA") { SetCell<String>() }
                val b = spawn("writerB") { SetCell<String>() }
                val union = spawn("union") { UnionSetCell<String>() }
                val mapped = spawn("mapped") { FlatMapSetCell(f = { e: String -> listOf(e.lowercase()) }) }
                a linkTo union
                b linkTo union
                union linkTo mapped
                listOf(a, b, union, mapped).forEach { sourceRefs[it.name] = it.ref }
            }

            // view host: join with categories → exclude blocked → aggregates
            val sumCell = GroupByCell(keyFn = ::cust, aggregator = Aggregators.sumOf(::amt))
            val viewRefs = mutableMapOf<String, CellRef>()
            graph(viewHost.managementInlet) {
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
                val sum = spawn("sum") { sumCell }
                val top = spawn("top") {
                    GroupByCell(keyFn = ::cust, aggregator = Aggregators.topK(2, ::amt))
                }
                connect(cats, "outlet", join, "right")
                connect(join, "outlet", anti, "left")
                connect(blocked, "outlet", anti, "right")
                connect(anti, "outlet", sum, "inlet")
                connect(anti, "outlet", top, "inlet")
                listOf(cats, blocked, join, anti, sum, top).forEach { viewRefs[it.name] = it.ref }
            }

            // bridge: source host's mapped outlet → view host's join.left, via the hosts' queues
            val joinLeft = viewHost.lookup<JoinLeftProxy>(viewRefs.getValue("join"))!!.left
            sourceHost.lookup<SetOutletProxy>(sourceRefs.getValue("mapped"))!!.outlet.subscribe(
                Use.fixed(object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        joinLeft.call.propagate(value)
                    }
                }, PortRef.generate())
            )

            val sums = mutableListOf<MapDelta<String, Long>>()
            viewHost.lookup<SumOutletProxy>(viewRefs.getValue("sum"))!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, Long>> {
                    override fun propagate(value: MapDelta<String, Long>) {
                        sums += value
                    }
                }, PortRef.generate())
            )
            val tops = mutableListOf<MapDelta<String, List<Long>>>()
            viewHost.lookup<TopOutletProxy>(viewRefs.getValue("top"))!!.outlet.subscribe(
                Use.fixed(object : Propagate<MapDelta<String, List<Long>>> {
                    override fun propagate(value: MapDelta<String, List<Long>>) {
                        tops += value
                    }
                }, PortRef.generate())
            )
            // control: a retraction-blind fold of the same stream must diverge somewhere
            val naiveSums = mutableMapOf<String, Long>()
            viewHost.lookup<SetOutletProxy>(viewRefs.getValue("anti"))!!.outlet.subscribe(
                Use.fixed(object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        value.adds.keys.forEach { naiveSums.merge(cust(it), amt(it), Long::plus) }
                    }
                }, PortRef.generate())
            )

            val writerA = sourceHost.lookup<SetInletProxy>(sourceRefs.getValue("writerA"))!!.inlet.call
            val writerB = sourceHost.lookup<SetInletProxy>(sourceRefs.getValue("writerB"))!!.inlet.call
            val cats = viewHost.lookup<SetInletProxy>(viewRefs.getValue("cats"))!!.inlet.call
            val blocked = viewHost.lookup<SetInletProxy>(viewRefs.getValue("blocked"))!!.inlet.call

            val heldCats = mutableSetOf("aP", "bQ", "cR")
            heldCats.forEach { cats.add(it) }

            val rnd = Random(seed)
            val rowDomain = listOf("A3", "a3", "a5", "B2", "b7", "c2", "C4")
            val blockDomain = listOf("a", "b", "c")
            val held = listOf(mutableSetOf<String>(), mutableSetOf())
            val heldBlocked = mutableSetOf<String>()
            val writers = listOf(writerA, writerB)

            repeat(60) { step ->
                when (rnd.nextInt(10)) {
                    in 0..6 -> { // row churn
                        val w = rnd.nextInt(2)
                        val e = rowDomain[rnd.nextInt(rowDomain.size)]
                        if (rnd.nextInt(10) < 6 || e !in held[w]) {
                            writers[w].add(e); held[w] += e
                        } else {
                            writers[w].remove(e); held[w] -= e
                        }
                    }
                    in 7..8 -> { // block-list churn
                        val k = blockDomain[rnd.nextInt(blockDomain.size)]
                        if (k in heldBlocked) {
                            blocked.remove(k); heldBlocked -= k
                        } else {
                            blocked.add(k); heldBlocked += k
                        }
                    }
                    else -> { // category flap: exercises join re-entry
                        val c = "aP"
                        if (c in heldCats) {
                            cats.remove(c); heldCats -= c
                        } else {
                            cats.add(c); heldCats += c
                        }
                    }
                }
                if (rnd.nextInt(5) == 0) controller.runToIdle()

                // mid-run snapshot/restore: a restored twin serves identical catch-up
                if (step == 30) {
                    controller.runToIdle()
                    val restored = GroupByCell(keyFn = ::cust, aggregator = Aggregators.sumOf(::amt))
                    restored.restore(sumCell.snapshot())
                    val fromRestored = MapDeltaCollectorCell()
                    val fromLive = MapDeltaCollectorCell()
                    restored.outlet.linkTo(fromRestored.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)
                    sumCell.outlet.linkTo(fromLive.inlet as LinkFrom<Propagate<MapDelta<String, Long>>>)
                    assertEquals(
                        mapFold(fromLive.arrivals), mapFold(fromRestored.arrivals),
                        "restored snapshot diverged from live cell on seed $seed",
                    )
                }
            }
            controller.runToIdle()

            // batch recompute over final writer/category/block state
            val rows = held.flatten().map { it.lowercase() }.toSet()
            val joined = rows.flatMap { row ->
                heldCats.filter { cust(it) == cust(row) }.map { c -> row + c.drop(1) }
            }
            val kept = joined.filter { cust(it) !in heldBlocked }
            val batchSums = kept.groupBy(::cust).mapValues { (_, es) -> es.sumOf(::amt) }
            val batchTops = kept.groupBy(::cust).mapValues { (_, es) ->
                es.map(::amt).sortedDescending().take(2)
            }

            assertEquals(batchSums, mapFold(sums), "grouped sums diverged from batch on seed $seed")
            assertEquals(batchTops, mapFold(tops), "topK diverged from batch on seed $seed")
            if (naiveSums.filterValues { it != 0L } != batchSums) controlDiverged = true

            // late joiner: hosted catch-up serves the full aggregate state
            val late = MapDeltaCollectorCell()
            val lateRef = viewHost.managementInlet.call.spawn(late)
            viewHost.managementInlet.call.connect(viewRefs.getValue("sum"), "outlet", lateRef, "inlet")
            controller.runToIdle()
            assertEquals(batchSums, mapFold(late.arrivals), "late joiner catch-up diverged on seed $seed")
        }

        assertTrue(controlDiverged, "control failed to reproduce the retraction-blind divergence")
    }
}
