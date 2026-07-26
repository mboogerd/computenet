package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.tagFold
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.UnionSetCell
import civictech.cell.data.op.CountCell
import civictech.cell.data.op.IntersectSetCell

class SetAlgebraGraphsTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetOutletProxy {
        val outlet: Subscribe<Propagate<SetDelta<String>>>
    }

    interface CounterOutletProxy {
        val outlet: Subscribe<Propagate<CounterDelta>>
    }

    private fun deltasOf(host: ManagedHost, ref: CellRef): MutableList<SetDelta<String>> {
        val deltas = mutableListOf<SetDelta<String>>()
        host.lookup<SetOutletProxy>(ref)!!.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                deltas += value
            }
        }, PortRef.generate()))
        return deltas
    }

    private fun countsOf(host: ManagedHost, ref: CellRef): MutableList<CounterDelta> {
        val counts = mutableListOf<CounterDelta>()
        host.lookup<CounterOutletProxy>(ref)!!.outlet.subscribe(Use.fixed(object : Propagate<CounterDelta> {
            override fun propagate(value: CounterDelta) {
                counts += value
            }
        }, PortRef.generate()))
        return counts
    }

    private fun inletOf(host: ManagedHost, ref: CellRef): SetOps<String> =
        host.lookup<SetInletProxy>(ref)!!.inlet.call

    private fun GraphSpec.handles(): Set<String> = steps.filterIsInstance<SpawnStep>().map { it.handle }.toSet()
    private fun GraphSpec.connects(): Set<ConnectStep> = steps.filterIsInstance<ConnectStep>().toSet()

    // ---- structural equality (headline acceptance) -------------------------

    @Test
    fun `intersect then count builds the same cell graph as the hand-wired version`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())

        val viaCombinators = graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val votes = spawn("votes") { ref -> SetCell<String>(ref = ref) }
            items.intersect<String>("wanted", votes).count<String>("count")
        }

        val handWired = graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val votes = spawn("votes") { ref -> SetCell<String>(ref = ref) }
            val wanted = spawn("wanted") { ref -> IntersectSetCell<String>(ref = ref) }
            val count = spawn("count") { ref -> CountCell<String>(ref = ref) }
            connect(items, "outlet", wanted, "left")
            connect(votes, "outlet", wanted, "right")
            connect(wanted, "outlet", count, "inlet")
        }

        // same nodes (spawn handles) and same links (connect steps)
        assertEquals(handWired.handles(), viaCombinators.handles())
        assertEquals(handWired.connects(), viaCombinators.connects())
    }

    // ---- graphs-as-data round-trip -----------------------------------------

    @Test
    fun `the combinator GraphSpec serializes and replays onto a fresh host`() {
        val controller = SimulationController(seed = 2)
        val hostA = ManagedHost(scheduler = controller.scheduler())

        val spec = graph(hostA.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val votes = spawn("votes") { ref -> SetCell<String>(ref = ref) }
            items.intersect<String>("wanted", votes).count<String>("count")
        }

        val bytes = ByteArrayOutputStream()
            .also { ObjectOutputStream(it).use { out -> out.writeObject(spec) } }
            .toByteArray()
        val revived = ObjectInputStream(ByteArrayInputStream(bytes)).readObject() as GraphSpec
        // topology data survives verbatim (factories serialize but don't compare equal)
        revived.connects() shouldBe spec.connects()

        val hostB = ManagedHost(scheduler = controller.scheduler())
        val refs = revived.applyTo(hostB.managementInlet)
        refs.keys shouldBe setOf("items", "votes", "wanted", "count")

        val counts = countsOf(hostB, refs.getValue("count"))
        val items = inletOf(hostB, refs.getValue("items"))
        val votes = inletOf(hostB, refs.getValue("votes"))

        items.add("apple")
        votes.add("apple") // apple now in both → enters ∩ → count 1
        items.add("pear")  // only on one side → not in ∩
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 1
    }

    // ---- live behavior through the combinators ------------------------------

    @Test
    fun `filter forwards passing elements and absorbs the rest`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())
        var itemsRef: CellRef? = null
        var produceRef: CellRef? = null
        graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            // 'a'..'m' pass, 'n'..'z' are absorbed
            val produce = items.filter<String>("produce") { it.first().lowercaseChar() in 'a'..'m' }
            itemsRef = items.ref
            produceRef = produce.ref
        }
        val out = deltasOf(host, produceRef!!)
        val items = inletOf(host, itemsRef!!)

        items.add("apple")   // passes
        items.add("melon")   // passes
        items.add("pear")    // absorbed (p)
        items.add("tomato")  // absorbed (t)
        controller.runToIdle()
        assertEquals(setOf("apple", "melon"), tagFold(out))

        items.remove("apple") // leaves cleanly
        controller.runToIdle()
        assertEquals(setOf("melon"), tagFold(out))
    }

    @Test
    fun `intersect tracks membership across concurrent add and remove`() {
        val controller = SimulationController(seed = 4)
        val host = ManagedHost(scheduler = controller.scheduler())
        var itemsRef: CellRef? = null
        var votesRef: CellRef? = null
        var wantedRef: CellRef? = null
        graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val votes = spawn("votes") { ref -> SetCell<String>(ref = ref) }
            val wanted = items.intersect<String>("wanted", votes)
            itemsRef = items.ref; votesRef = votes.ref; wantedRef = wanted.ref
        }
        val out = deltasOf(host, wantedRef!!)
        val items = inletOf(host, itemsRef!!)
        val votes = inletOf(host, votesRef!!)

        items.add("apple"); items.add("pear")
        votes.add("apple") // apple in both, pear only in items
        controller.runToIdle()
        assertEquals(setOf("apple"), tagFold(out))

        votes.add("pear")  // pear now enters ∩
        items.remove("apple") // apple leaves ∩ concurrently
        controller.runToIdle()
        assertEquals(setOf("pear"), tagFold(out))
    }

    @Test
    fun `union dedups across two sources`() {
        val controller = SimulationController(seed = 5)
        val host = ManagedHost(scheduler = controller.scheduler())
        var leftRef: CellRef? = null
        var rightRef: CellRef? = null
        var mergedRef: CellRef? = null
        var countRef: CellRef? = null
        graph(host.managementInlet) {
            val l = spawn("l") { ref -> SetCell<String>(ref = ref) }
            val r = spawn("r") { ref -> SetCell<String>(ref = ref) }
            val merged = l.union<String>("merged", r)
            val count = merged.count<String>("count")
            leftRef = l.ref; rightRef = r.ref; mergedRef = merged.ref; countRef = count.ref
        }
        val out = deltasOf(host, mergedRef!!)
        val counts = countsOf(host, countRef!!)
        val l = inletOf(host, leftRef!!)
        val r = inletOf(host, rightRef!!)

        l.add("apple")
        r.add("apple") // same element from both sides → union dedups membership
        r.add("pear")
        controller.runToIdle()

        assertEquals(setOf("apple", "pear"), tagFold(out))
        counts.sumOf { it.amount } shouldBe 2 // distinct membership, not 3
    }

    // ---- adopt --------------------------------------------------------------

    @Test
    fun `adopt wraps an app-spawned cell without re-spawning it`() {
        val controller = SimulationController(seed = 6)
        val host = ManagedHost(scheduler = controller.scheduler())

        // app owns and spawns the union itself, outside the DSL
        val appUnion = UnionSetCell<String>()
        val appUnionRef = host.managementInlet.call.spawn(appUnion)

        var adoptedRef: CellRef? = null
        var srcRef: CellRef? = null
        var countRef: CellRef? = null
        val spec = graph(host.managementInlet) {
            val src = spawn("src") { ref -> SetCell<String>(ref = ref) }
            val adopted = adopt(appUnion)
            src linkTo adopted            // src.outlet → union.inlet
            val count = adopted.count<String>("n")
            adoptedRef = adopted.ref; srcRef = src.ref; countRef = count.ref
        }

        // adopt reuses the app's ref, does not mint a new instance
        assertEquals(appUnionRef, adoptedRef)
        assertEquals(appUnion.ref, adoptedRef)
        // and records NO spawn step for the adopted cell (no double-spawn) —
        // only "src" and the "n" count spawn, never the adopted union
        assertTrue(spec.steps.filterIsInstance<SpawnStep>().none { it.handle.startsWith("adopted-") })
        assertEquals(setOf("src", "n"), spec.steps.filterIsInstance<SpawnStep>().map { it.handle }.toSet())

        val counts = countsOf(host, countRef!!)
        val src = inletOf(host, srcRef!!)
        src.add("apple")
        src.add("pear")
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2 // the adopted union is live and wired
    }

    // ---- chaining -----------------------------------------------------------

    @Test
    fun `filter then count chains correctly`() {
        val controller = SimulationController(seed = 7)
        val host = ManagedHost(scheduler = controller.scheduler())
        var itemsRef: CellRef? = null
        var countRef: CellRef? = null
        graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val count = items
                .filter<String>("produce") { it.first().lowercaseChar() in 'a'..'m' }
                .count<String>("produceCount")
            itemsRef = items.ref; countRef = count.ref
        }
        val counts = countsOf(host, countRef!!)
        val items = inletOf(host, itemsRef!!)

        items.add("apple")  // counts
        items.add("melon")  // counts
        items.add("pear")   // filtered out
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 2
    }

    @Test
    fun `intersect then filter then count chains correctly`() {
        val controller = SimulationController(seed = 8)
        val host = ManagedHost(scheduler = controller.scheduler())
        var itemsRef: CellRef? = null
        var votesRef: CellRef? = null
        var countRef: CellRef? = null
        graph(host.managementInlet) {
            val items = spawn("items") { ref -> SetCell<String>(ref = ref) }
            val votes = spawn("votes") { ref -> SetCell<String>(ref = ref) }
            val count = items
                .intersect<String>("wanted", votes)
                .filter<String>("wantedProduce") { it.first().lowercaseChar() in 'a'..'m' }
                .count<String>("n")
            itemsRef = items.ref; votesRef = votes.ref; countRef = count.ref
        }
        val counts = countsOf(host, countRef!!)
        val items = inletOf(host, itemsRef!!)
        val votes = inletOf(host, votesRef!!)

        items.add("apple"); items.add("tomato")
        votes.add("apple"); votes.add("tomato") // both in ∩; tomato filtered out ('t')
        controller.runToIdle()

        counts.sumOf { it.amount } shouldBe 1 // only apple survives ∩ ∧ produce-filter
    }
}
