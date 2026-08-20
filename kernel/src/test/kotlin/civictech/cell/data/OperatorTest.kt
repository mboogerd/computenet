package civictech.cell.data

import civictech.cell.Propagate
import civictech.cell.Timestamp
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
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.FilterCell
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.FlatMapSetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.mapSet
import civictech.cell.data.op.IntersectSetCell
import civictech.cell.data.op.JoinCell
import civictech.cell.oracle.forEachBatchFoldSeed
import civictech.oracle.model.Membership
import civictech.oracle.model.ModelState
import civictech.oracle.model.Script
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.SourceScript
import civictech.oracle.model.WriterId
import civictech.oracle.run.CaseGraph
import civictech.oracle.run.DifferentialRunner
import civictech.oracle.run.Reference
import civictech.oracle.run.ScalarTerminalFold
import civictech.oracle.run.SetTerminalFold
import civictech.oracle.run.asScriptSource
import civictech.testkit.SimWorld

class OperatorTest {

    private fun tag(counter: Long) = Timestamp(UUID(0, counter), counter)

    private fun <T : Any> collect(outlet: Subscribe<Propagate<T>>): MutableList<T> {
        val collected = mutableListOf<T>()
        outlet.subscribe(Use.fixed(object : Propagate<T> {
            override fun propagate(value: T) {
                collected += value
            }
        }, PortRef.generate()))
        return collected
    }

    @Test
    fun `FilterCell passes matching elements with tags intact and absorbs the rest`() {
        val filter = FilterCell<String> { it.startsWith("a") }
        val out = collect(filter.outlet)

        val t1 = tag(1); val t2 = tag(2)
        filter.inlet.call.propagate(SetDelta(adds = mapOf("apple" to setOf(t1), "banana" to setOf(t2))))
        filter.inlet.call.propagate(SetDelta(dels = mapOf("apple" to setOf(t1))))

        assertEquals(2, out.size)
        assertEquals(mapOf("apple" to setOf(t1)), out[0].adds)
        assertEquals(mapOf("apple" to setOf(t1)), out[1].dels)
    }

    @Test
    fun `CountCell emits membership-size changes only`() {
        val count = CountCell<String>()
        val out = collect(count.outlet)

        val t1 = tag(1); val t2 = tag(2); val t3 = tag(3)
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(t1))))
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(t2), "y" to setOf(t3)))) // x already live
        count.inlet.call.propagate(SetDelta(dels = mapOf("x" to setOf(t1)))) // x still live via t2

        assertEquals(listOf(CounterDelta(1), CounterDelta(1)), out)

        count.inlet.call.propagate(SetDelta(dels = mapOf("x" to setOf(t2))))
        assertEquals(CounterDelta(-1), out.last())
    }

    @Test
    fun `IntersectSetCell tracks entry and exit of both-sided elements`() {
        val intersect = IntersectSetCell<String>()
        val out = collect(intersect.outlet)

        val t1 = tag(1); val t2 = tag(2)
        intersect.left.call.propagate(SetDelta(adds = mapOf("x" to setOf(t1))))
        assertTrue(out.isEmpty()) // only left

        intersect.right.call.propagate(SetDelta(adds = mapOf("x" to setOf(t2))))
        // computenet-vvre: entry advertises ONE freshly minted, cell-owned tag,
        // never the borrowed input tags — 21 §Tag hygiene. (This assertion used
        // to demand `setOf(t1, t2)`, which is the defect: borrowing t1 lets a
        // reconvergent UnionSetCell mistake this cell's exit for the retraction
        // of the direct edge's own contribution — IntersectDiamondTagTest.)
        val minted = out.single().adds.getValue("x").single()
        assertEquals(setOf("x"), out.single().adds.keys)
        assertTrue(minted !in setOf(t1, t2), "output tag must not be borrowed from either input")

        intersect.left.call.propagate(SetDelta(dels = mapOf("x" to setOf(t1))))
        // exit deletes exactly what entry minted, so downstream membership dies
        assertEquals(mapOf("x" to setOf(minted)), out[1].dels)
    }

    @Test
    fun `JoinCell joins on both-sided keys and retracts on either removal`() {
        val join = JoinCell<String, Int, String>()
        val out = collect(join.outlet)

        join.left.call.propagate(MapDelta(mapOf("k" to 1), emptySet()))
        assertTrue(out.isEmpty())

        join.right.call.propagate(MapDelta(mapOf("k" to "one"), emptySet()))
        assertEquals(mapOf("k" to (1 to "one")), out.single().puts)

        join.left.call.propagate(MapDelta(mapOf("k" to 2), emptySet())) // refresh
        assertEquals(mapOf("k" to (2 to "one")), out[1].puts)

        join.right.call.propagate(MapDelta(emptyMap(), setOf("k")))
        assertEquals(setOf("k"), out[2].removals)
    }

    @Test
    fun `operators serve catch-up to late-linking subscribers`() {
        val count = CountCell<String>()
        count.inlet.call.propagate(SetDelta(adds = mapOf("x" to setOf(tag(1)), "y" to setOf(tag(2)))))

        val late = mutableListOf<CounterDelta>()
        val lateCollector = CollectorCounter(late)
        count.outlet.linkTo(lateCollector.inlet as LinkFrom<Propagate<CounterDelta>>)

        assertEquals(listOf(CounterDelta(2)), late)
    }

    class CollectorCounter(private val arrivals: MutableList<CounterDelta>) {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<CounterDelta>>())

        init {
            inlet.serve(object : Propagate<CounterDelta> {
                override fun propagate(value: CounterDelta) {
                    arrivals += value
                }
            })
        }
    }

    @Test
    fun `pipeline - incremental result equals batch recompute on every seed`() {
        // [ORA1-DIFF-11] migration (computenet-4ru.12.4): the same two-writer union-filter-count
        // pipeline and the same batch-fold property, now run through DifferentialRunner.check
        // instead of a hand-rolled held-set recompute. Both the filter-set terminal and the
        // count scalar terminal survive as separate CaseGraph terminals, matching the original's
        // two assertions.
        val sourceA = SourceId("w0")
        val sourceB = SourceId("w1")
        val writerA = WriterId("w0")
        val writerB = WriterId("w1")
        val predicate: (String) -> Boolean = { it.hashCode() % 2 == 0 }

        fun buildGraph(world: SimWorld): CaseGraph {
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()
            val filter = FilterCell<String>(predicate = predicate)
            val count = CountCell<String>()
            val filteredFold = SetTerminalFold<String>()
            val countFold = ScalarTerminalFold()

            val mgmt = world.host.managementInlet.call
            writers.forEach { mgmt.spawn(it) }
            mgmt.spawn(union)
            mgmt.spawn(filter)
            mgmt.spawn(count)
            mgmt.spawn(filteredFold)
            mgmt.spawn(countFold)
            writers.forEach { mgmt.connect(it.ref, "outlet", union.ref, "inlet") }
            mgmt.connect(union.ref, "outlet", filter.ref, "inlet")
            mgmt.connect(filter.ref, "outlet", count.ref, "inlet")
            mgmt.connect(filter.ref, "outlet", filteredFold.ref, "inlet")
            mgmt.connect(count.ref, "outlet", countFold.ref, "inlet")

            return CaseGraph(
                terminals = mapOf("filtered" to filteredFold, "count" to countFold),
                sources = mapOf(
                    sourceA to writers[0].inlet.call.asScriptSource(),
                    sourceB to writers[1].inlet.call.asScriptSource(),
                ),
            )
        }

        forEachBatchFoldSeed { seed ->
            val rnd = Random(seed)
            val domain = ('a'..'f').map { it.toString() }
            val writerIds = listOf(writerA, writerB)
            val held = listOf(mutableSetOf<String>(), mutableSetOf<String>())
            val events = listOf(mutableListOf<ScriptEvent>(), mutableListOf<ScriptEvent>())
            repeat(60) {
                val w = rnd.nextInt(2)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    events[w] += ScriptEvent.Add(writerIds[w], element)
                    held[w] += element
                } else {
                    events[w] += ScriptEvent.Remove(writerIds[w], element)
                    held[w] -= element
                }
            }
            val script = Script(listOf(SourceScript(sourceA, events[0]), SourceScript(sourceB, events[1])))

            // batch recompute over the writers' final observed-remove membership
            val reference = Reference { s ->
                val batch = (Membership.live(s.slice(sourceA)) + Membership.live(s.slice(sourceB)))
                    .map { it as String }
                    .filter(predicate)
                    .toSet()
                mapOf(
                    "filtered" to ModelState.SetState(batch),
                    "count" to ModelState.ScalarState(batch.size.toLong()),
                )
            }

            DifferentialRunner.check(
                seed = seed,
                caseMarker = "pipeline: writer0,writer1 -> union -> filter(even hash) -> {filtered, count}",
                script = script,
                reference = reference,
                buildGraph = ::buildGraph,
            )
        }
    }

    @Test
    fun `FlatMapSetCell unions tags when inputs collide on one output`() {
        val flat = mapSet<String, String> { it.first().toString() }
        val out = collect(flat.outlet)

        val t1 = tag(1); val t2 = tag(2)
        flat.inlet.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1), "ay" to setOf(t2))))
        assertEquals(mapOf("a" to setOf(t1, t2)), out.single().adds)

        // partial-preimage removal: "a" must stay live via "ay"'s tag
        flat.inlet.call.propagate(SetDelta(dels = mapOf("ax" to setOf(t1))))
        assertEquals(mapOf("a" to setOf(t1)), out[1].dels)
        assertEquals(setOf("a"), tagFold(out))
    }

    @Test
    fun `FlatMapSetCell expands elements and serves collision-safe catch-up`() {
        val flat = FlatMapSetCell<String, String>(f = { listOf(it, it.first().toString()) })
        val t1 = tag(1); val t2 = tag(2)
        flat.inlet.call.propagate(SetDelta(adds = mapOf("ax" to setOf(t1), "ay" to setOf(t2))))

        val late = CollectorCell()
        flat.outlet.linkTo(late.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        assertEquals(setOf("ax", "ay", "a"), tagFold(late.arrivals))
        assertEquals(setOf(t1, t2), late.arrivals.single().adds["a"]) // catch-up folded collided tags
    }

    @Test
    fun `control - last-wins remap diverges where fold-with-union converges`() {
        // the failure class FlatMapSetCell.remap guards against: two inputs
        // collide on one output within a single delta; a mapKeys-style remap
        // keeps only the last entry, dropping the other preimage's liveness
        val f = { _: String -> "y" }
        val t1 = tag(1); val t2 = tag(2)
        val adds = mapOf("a1" to setOf(t1), "a2" to setOf(t2))
        val delA2 = mapOf("a2" to setOf(t2))

        val naive = listOf(
            SetDelta(adds = adds.mapKeys { f(it.key) }),  // {y: {t2}} — t1 silently dropped
            SetDelta(dels = delA2.mapKeys { f(it.key) }), // y dies downstream
        )
        assertEquals(emptySet<String>(), tagFold(naive), "control failed to reproduce the divergence")

        val flat = mapSet<String, String>(f)
        val out = collect(flat.outlet)
        flat.inlet.call.propagate(SetDelta(adds = adds))
        flat.inlet.call.propagate(SetDelta(dels = delA2))
        assertEquals(setOf("y"), tagFold(out), "y must survive via a1's tag") // batch: f({a1}) = {y}
    }

    @Test
    fun `flatMap pipeline - incremental result equals batch recompute on every seed`() {
        // [ORA1-DIFF-11] migration (computenet-4ru.12.4): same two-writer union-flatMap
        // pipeline and the same batch-fold property, now a DifferentialRunner.check caller.
        val sourceA = SourceId("w0")
        val sourceB = SourceId("w1")
        val writerA = WriterId("w0")
        val writerB = WriterId("w1")
        val expand = { s: String -> listOf(s, s.first().toString()) } // expansion + heavy collision

        fun buildGraph(world: SimWorld): CaseGraph {
            val writers = listOf(SetCell<String>(), SetCell<String>())
            val union = UnionSetCell<String>()
            val flat = FlatMapSetCell(f = expand)
            val mappedFold = SetTerminalFold<String>()

            val mgmt = world.host.managementInlet.call
            writers.forEach { mgmt.spawn(it) }
            mgmt.spawn(union)
            mgmt.spawn(flat)
            mgmt.spawn(mappedFold)
            writers.forEach { mgmt.connect(it.ref, "outlet", union.ref, "inlet") }
            mgmt.connect(union.ref, "outlet", flat.ref, "inlet")
            mgmt.connect(flat.ref, "outlet", mappedFold.ref, "inlet")

            return CaseGraph(
                terminals = mapOf("mapped" to mappedFold),
                sources = mapOf(
                    sourceA to writers[0].inlet.call.asScriptSource(),
                    sourceB to writers[1].inlet.call.asScriptSource(),
                ),
            )
        }

        forEachBatchFoldSeed { seed ->
            val rnd = Random(seed)
            val domain = listOf("ax", "ay", "bx", "by", "cx", "cy")
            val writerIds = listOf(writerA, writerB)
            val held = listOf(mutableSetOf<String>(), mutableSetOf<String>())
            val events = listOf(mutableListOf<ScriptEvent>(), mutableListOf<ScriptEvent>())
            repeat(60) {
                val w = rnd.nextInt(2)
                val element = domain[rnd.nextInt(domain.size)]
                if (rnd.nextInt(10) < 6 || element !in held[w]) {
                    events[w] += ScriptEvent.Add(writerIds[w], element)
                    held[w] += element
                } else {
                    events[w] += ScriptEvent.Remove(writerIds[w], element)
                    held[w] -= element
                }
            }
            val script = Script(listOf(SourceScript(sourceA, events[0]), SourceScript(sourceB, events[1])))

            val reference = Reference { s ->
                val batch = (Membership.live(s.slice(sourceA)) + Membership.live(s.slice(sourceB)))
                    .map { it as String }
                    .flatMap(expand)
                    .toSet()
                mapOf("mapped" to ModelState.SetState(batch))
            }

            DifferentialRunner.check(
                seed = seed,
                caseMarker = "flatMap pipeline: writer0,writer1 -> union -> flatMap(expand) -> mapped",
                script = script,
                reference = reference,
                buildGraph = ::buildGraph,
            )
        }
    }
}
