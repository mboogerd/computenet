package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.tagFold
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.oracle.forEachBatchFoldSeed
import civictech.cell.port.PortRef
import civictech.cell.port.Subscribe
import civictech.cell.port.Use
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
import civictech.oracle.run.SetTerminalFold
import civictech.oracle.run.asScriptSource
import civictech.testkit.SimWorld
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.*
import civictech.cell.data.delta.SetDelta

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
        // ORA1 §DIFF-11 migration (computenet-4ru.12.4): the same graph-DSL leftJoin over two
        // SetCell sources and the same batch-fold property, now a DifferentialRunner.check
        // caller. DifferentialRunner drives the interleaving and the settling itself, so the
        // original's own SimulationController/runToIdle bookkeeping is no longer needed here.
        val sourceL = SourceId("l")
        val sourceR = SourceId("r")
        val writerL = WriterId("l")
        val writerR = WriterId("r")

        fun buildGraph(world: SimWorld): CaseGraph {
            val (built, _) = graphOf(world.host.managementInlet) {
                val l = spawn("l") { SetCell<String>() }
                val r = spawn("r") { SetCell<String>() }
                val joined = leftJoin<String, String, String, String>(
                    "joined", l, r,
                    leftKey = { it.first().toString() },
                    rightKey = { it.first().toString() },
                    combine = { e, d -> "$e|${d ?: "-"}" },
                )
                Triple(l.cell, r.cell, joined.ref)
            }
            val (lCell, rCell, joinedRef) = built

            val joinedFold = SetTerminalFold<String>()
            val mgmt = world.host.managementInlet.call
            mgmt.spawn(joinedFold)
            mgmt.connect(joinedRef, "outlet", joinedFold.ref, "inlet")

            return CaseGraph(
                terminals = mapOf("joined" to joinedFold),
                sources = mapOf(
                    sourceL to lCell.inlet.call.asScriptSource(),
                    sourceR to rCell.inlet.call.asScriptSource(),
                ),
            )
        }

        forEachBatchFoldSeed { seed ->
            val rnd = Random(seed)
            val leftDomain = listOf("ax", "ay", "bx", "cx")
            val rightDomain = listOf("a1", "a2", "b1", "c1")
            val heldLeft = mutableSetOf<String>()
            val heldRight = mutableSetOf<String>()
            val leftEvents = mutableListOf<ScriptEvent>()
            val rightEvents = mutableListOf<ScriptEvent>()
            repeat(40) {
                if (rnd.nextBoolean()) {
                    val e = leftDomain[rnd.nextInt(leftDomain.size)]
                    if (rnd.nextInt(10) < 6 || e !in heldLeft) {
                        leftEvents += ScriptEvent.Add(writerL, e); heldLeft += e
                    } else {
                        leftEvents += ScriptEvent.Remove(writerL, e); heldLeft -= e
                    }
                } else {
                    val e = rightDomain[rnd.nextInt(rightDomain.size)]
                    if (rnd.nextInt(10) < 6 || e !in heldRight) {
                        rightEvents += ScriptEvent.Add(writerR, e); heldRight += e
                    } else {
                        rightEvents += ScriptEvent.Remove(writerR, e); heldRight -= e
                    }
                }
            }
            val script = Script(listOf(SourceScript(sourceL, leftEvents), SourceScript(sourceR, rightEvents)))

            val reference = Reference { s ->
                val liveLeft = Membership.live(s.slice(sourceL)).map { it as String }.toSet()
                val liveRight = Membership.live(s.slice(sourceR)).map { it as String }.toSet()
                val batch = liveLeft.flatMap { a ->
                    val matches = liveRight.filter { key(it) == key(a) }
                    if (matches.isEmpty()) listOf("$a|-") else matches.map { b -> "$a|$b" }
                }.toSet()
                mapOf("joined" to ModelState.SetState(batch))
            }

            DifferentialRunner.check(
                seed = seed,
                caseMarker = "left join: l,r -> leftJoin(key=first char) -> joined",
                script = script,
                reference = reference,
                buildGraph = ::buildGraph,
            )
        }
    }
}
