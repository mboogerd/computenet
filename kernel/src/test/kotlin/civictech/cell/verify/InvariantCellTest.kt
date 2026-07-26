package civictech.cell.verify

import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import io.kotest.assertions.throwables.shouldThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.CounterDelta
import civictech.cell.data.op.CountCell

class InvariantCellTest {

    private fun collectViolations(invariant: InvariantCell<*, *>): MutableList<Violation> {
        val violations = mutableListOf<Violation>()
        invariant.violations.subscribe(Use.fixed(object : Propagate<Violation> {
            override fun propagate(value: Violation) {
                violations += value
            }
        }, PortRef.generate()))
        return violations
    }

    @Test
    fun `a folding invariant flags the observation that breaks it`() {
        val nonNegative = InvariantCell<CounterDelta, Long>(
            "non-negative total", 0L,
            fold = { total, delta -> total + delta.amount },
            check = { total, _ -> if (total < 0) "total went negative: $total" else null },
        )
        val violations = collectViolations(nonNegative)

        nonNegative.inlet.call.propagate(CounterDelta(2))
        nonNegative.inlet.call.propagate(CounterDelta(-2))
        assertTrue(violations.isEmpty())

        nonNegative.inlet.call.propagate(CounterDelta(-1))
        assertEquals(1, violations.size)
        assertEquals("non-negative total", violations[0].invariant)
        assertEquals(CounterDelta(-1), violations[0].observed)
    }

    @Test
    fun `attaching an invariant to a live pipeline is just linking`() {
        val set = SetCell<String>()
        val count = CountCell<String>()
        @Suppress("UNCHECKED_CAST")
        set.outlet.linkTo(count.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        val bounded = InvariantCell.observing<CounterDelta>("count stays under 3") {
            if (it.amount > 2) "jump of ${it.amount}" else null
        }
        val violations = collectViolations(bounded)
        @Suppress("UNCHECKED_CAST")
        count.outlet.linkTo(bounded.inlet as LinkFrom<Propagate<CounterDelta>>)

        set.inlet.call.add("a")
        set.inlet.call.add("b")
        assertTrue(violations.isEmpty()) // increments of 1 each

        // a late-linked invariant receives catch-up like any subscriber:
        val lateBound = InvariantCell.observing<CounterDelta>("no bulk catch-up") {
            if (it.amount > 1) "saw bulk delta of ${it.amount}" else null
        }
        val lateViolations = collectViolations(lateBound)
        @Suppress("UNCHECKED_CAST")
        count.outlet.linkTo(lateBound.inlet as LinkFrom<Propagate<CounterDelta>>)
        assertEquals(1, lateViolations.size) // catch-up delta of 2 — proves it flows
    }

    @Test
    fun `checkInvariants adapter fails a run whose invariant is violated`() {
        val controller = SimulationController(seed = 3)
        val host = ManagedHost(scheduler = controller.scheduler())
        val set = SetCell<String>().also { host.managementInlet.call.spawn(it) }
        val count = CountCell<String>().also { host.managementInlet.call.spawn(it) }
        @Suppress("UNCHECKED_CAST")
        set.outlet.linkTo(count.inlet as LinkFrom<Propagate<SetDelta<String>>>)

        val nonNegative = InvariantCell<CounterDelta, Long>(
            "non-negative count", 0L,
            fold = { total, delta -> total + delta.amount },
            check = { total, _ -> if (total < 0) "count went negative: $total" else null },
        )
        @Suppress("UNCHECKED_CAST")
        count.outlet.linkTo(nonNegative.inlet as LinkFrom<Propagate<CounterDelta>>)

        // a clean run passes
        checkInvariants(controller, listOf(nonNegative), clue = "seed 3:") {
            set.inlet.call.add("a")
            set.inlet.call.remove("a")
        }

        // a violated run fails the test — inject a rogue negative delta
        shouldThrow<AssertionError> {
            checkInvariants(controller, listOf(nonNegative), clue = "seed 3:") {
                nonNegative.inlet.call.propagate(CounterDelta(-5))
            }
        }
    }
}
