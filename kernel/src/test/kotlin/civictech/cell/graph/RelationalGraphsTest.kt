package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.data.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetDelta
import civictech.cell.data.SetOps
import civictech.cell.data.tagFold
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*

class RelationalGraphsTest {

    interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    interface SetOutletProxy {
        val outlet: Subscribe<Propagate<SetDelta<String>>>
    }

    private fun key(e: String) = e.first().toString()

    private fun deltasOf(host: ManagedHost, ref: CellRef): MutableList<SetDelta<String>> {
        val deltas = mutableListOf<SetDelta<String>>()
        host.lookup<SetOutletProxy>(ref)!!.outlet.subscribe(Use.fixed(object : Propagate<SetDelta<String>> {
            override fun propagate(value: SetDelta<String>) {
                deltas += value
            }
        }, PortRef.generate()))
        return deltas
    }

    @Test
    fun `left join null-completes unmatched rows and upgrades them on match`() {
        val controller = SimulationController(seed = 1)
        val host = ManagedHost(scheduler = controller.scheduler())
        val handles = mutableMapOf<String, CellRef>()
        graph(host.managementInlet) {
            val emp = spawn("emp") { SetCell<String>() }
            val dept = spawn("dept") { SetCell<String>() }
            val joined = leftJoin<String, String, String, String>(
                "joined", emp, dept,
                leftKey = { it.first().toString() },
                rightKey = { it.first().toString() },
                combine = { e, d -> "$e|${d ?: "-"}" },
            )
            listOf(emp, dept, joined).forEach { handles[it.name] = it.ref }
        }
        val out = deltasOf(host, handles.getValue("joined"))
        val emp = host.lookup<SetInletProxy>(handles.getValue("emp"))!!.inlet.call
        val dept = host.lookup<SetInletProxy>(handles.getValue("dept"))!!.inlet.call

        emp.add("ax")
        controller.runToIdle()
        assertEquals(setOf("ax|-"), tagFold(out))

        dept.add("a1") // ax upgrades from null-completed to matched
        emp.add("bx")  // stays null-completed
        controller.runToIdle()
        assertEquals(setOf("ax|a1", "bx|-"), tagFold(out))

        dept.remove("a1") // downgrade back — re-entry rides fresh minted tags
        controller.runToIdle()
        assertEquals(setOf("ax|-", "bx|-"), tagFold(out))
    }

    @Test
    fun `full join completes both sides`() {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val handles = mutableMapOf<String, CellRef>()
        graph(host.managementInlet) {
            val l = spawn("l") { SetCell<String>() }
            val r = spawn("r") { SetCell<String>() }
            val joined = fullJoin<String, String, String, String>(
                "joined", l, r,
                leftKey = { it.first().toString() },
                rightKey = { it.first().toString() },
                combine = { a, b -> "${a ?: "-"}|${b ?: "-"}" },
            )
            listOf(l, r, joined).forEach { handles[it.name] = it.ref }
        }
        val out = deltasOf(host, handles.getValue("joined"))
        val l = host.lookup<SetInletProxy>(handles.getValue("l"))!!.inlet.call
        val r = host.lookup<SetInletProxy>(handles.getValue("r"))!!.inlet.call

        l.add("ax"); l.add("bx"); r.add("a1"); r.add("c1")
        controller.runToIdle()
        assertEquals(setOf("ax|a1", "bx|-", "-|c1"), tagFold(out))
    }

    @Test
    fun `left join - incremental result equals batch recompute on every seed`() {
        for (seed in 0L until 100L) {
            val controller = SimulationController(seed)
            val host = ManagedHost(scheduler = controller.scheduler())
            val handles = mutableMapOf<String, CellRef>()
            graph(host.managementInlet) {
                val l = spawn("l") { SetCell<String>() }
                val r = spawn("r") { SetCell<String>() }
                val joined = leftJoin<String, String, String, String>(
                    "joined", l, r,
                    leftKey = { it.first().toString() },
                    rightKey = { it.first().toString() },
                    combine = { e, d -> "$e|${d ?: "-"}" },
                )
                listOf(l, r, joined).forEach { handles[it.name] = it.ref }
            }
            val out = deltasOf(host, handles.getValue("joined"))
            val l = host.lookup<SetInletProxy>(handles.getValue("l"))!!.inlet.call
            val r = host.lookup<SetInletProxy>(handles.getValue("r"))!!.inlet.call

            val rnd = Random(seed)
            val leftDomain = listOf("ax", "ay", "bx", "cx")
            val rightDomain = listOf("a1", "a2", "b1", "c1")
            val heldLeft = mutableSetOf<String>()
            val heldRight = mutableSetOf<String>()
            repeat(40) {
                if (rnd.nextBoolean()) {
                    val e = leftDomain[rnd.nextInt(leftDomain.size)]
                    if (rnd.nextInt(10) < 6 || e !in heldLeft) {
                        l.add(e); heldLeft += e
                    } else {
                        l.remove(e); heldLeft -= e
                    }
                } else {
                    val e = rightDomain[rnd.nextInt(rightDomain.size)]
                    if (rnd.nextInt(10) < 6 || e !in heldRight) {
                        r.add(e); heldRight += e
                    } else {
                        r.remove(e); heldRight -= e
                    }
                }
                if (rnd.nextInt(4) == 0) controller.runToIdle() // interleave settling
            }
            controller.runToIdle()

            val batch = heldLeft.flatMap { a ->
                val matches = heldRight.filter { key(it) == key(a) }
                if (matches.isEmpty()) listOf("$a|-") else matches.map { b -> "$a|$b" }
            }.toSet()
            assertEquals(batch, tagFold(out), "left join diverged from batch on seed $seed")
        }
    }
}
