package civictech.cell.data

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

/**
 * The G-23 claim: two views merging the same tagged delta streams converge to
 * identical membership regardless of arrival interleaving — invariant-checked
 * over many seeds, with a control proving that untagged arrival-order
 * application (the pre-tag semantics) diverges under the same interleavings.
 */
class SetConvergenceTest {

    /** Pre-tag semantics (control): apply element adds/dels in arrival order. */
    private fun naiveFold(deltas: List<SetDelta<String>>): Set<String> {
        val present = mutableSetOf<String>()
        deltas.forEach { d ->
            present += d.adds.keys
            present -= d.dels.keys
        }
        return present
    }

    private data class Run(
        val unionViews: List<List<SetDelta<String>>>,
        val naiveViews: List<List<SetDelta<String>>>,
        val expected: Set<String>,
    )

    private fun runInterleaved(seed: Long, ops: Int): Run {
        val controller = SimulationController(seed)
        val viewHosts = listOf(ManagedHost(scheduler = controller.scheduler()), ManagedHost(scheduler = controller.scheduler()))

        val writers = listOf(SetCell<String>(), SetCell<String>())
        val unions = viewHosts.map { host ->
            UnionSetCell<String>().also { host.managementInlet.call.spawn(it) }
        }
        val naiveCollectors = viewHosts.map { host ->
            CollectorCell().also { host.managementInlet.call.spawn(it) }
        }

        // every writer streams to both unions and both naive collectors, routed
        // through the view host's queue so drain order interleaves arrivals
        writers.forEach { writer ->
            viewHosts.forEachIndexed { i, host ->
                val routedUnion = host.lookup<DeltaInletProxy>(unions[i].ref)!!.inlet.call
                val routedNaive = host.lookup<DeltaInletProxy>(naiveCollectors[i].ref)!!.inlet.call
                writer.outlet.subscribe(Use.fixed(routedUnion, PortRef.generate()))
                writer.outlet.subscribe(Use.fixed(routedNaive, PortRef.generate()))
            }
        }

        val unionBuffers = unions.map { union ->
            mutableListOf<SetDelta<String>>().also { buffer ->
                union.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
                    override fun propagate(value: SetDelta<String>) {
                        buffer += value
                    }
                }, PortRef.generate()))
            }
        }

        val rnd = Random(seed)
        val domain = listOf("x", "y", "z")
        val writerState = writers.map { mutableSetOf<String>() }
        repeat(ops) {
            val w = rnd.nextInt(writers.size)
            val element = domain[rnd.nextInt(domain.size)]
            if (rnd.nextInt(10) < 7 || element !in writerState[w]) {
                writers[w].inlet.call.add(element)
                writerState[w] += element
            } else {
                writers[w].inlet.call.remove(element)
                writerState[w] -= element
            }
            repeat(rnd.nextInt(4)) { controller.step() } // partial, seed-randomized draining
        }
        controller.runToIdle()

        return Run(
            unionViews = unionBuffers,
            naiveViews = naiveCollectors.map { it.arrivals },
            expected = writerState.flatten().toSet(),
        )
    }

    @Test
    fun `tagged views converge to the writers' membership on every seed`() {
        for (seed in 0L until 200L) {
            val run = runInterleaved(seed, ops = 40)
            run.unionViews.forEach { tagFold(it) shouldBe run.expected }
        }
    }

    @Test
    fun `control - arrival-order views diverge on at least one seed`() {
        var diverged = 0
        for (seed in 0L until 50L) {
            val run = runInterleaved(seed, ops = 40)
            val memberships = run.naiveViews.map { naiveFold(it) }
            if (memberships.toSet().size > 1 || memberships.any { it != run.expected }) diverged++
        }
        // if this fails the harness interleaving is too weak to expose order bias
        (diverged > 0).shouldBeTrue()
    }
}
