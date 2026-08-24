package civictech.testkit.dst

import civictech.cell.CellRef
import civictech.cell.durability.InMemoryJournal
import civictech.cell.durability.Journal
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.proxy.Invocation
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The rig core's own contract: the world composes the kernel triple and exposes the controller
 * unchanged ([CHA1-01]), the budget is an outcome and not an exception ([CHA1-03]), activation
 * is step-indexed ([CHA1-02]), per-fault firing is counted and inertness is visible
 * ([CHA1-24], BS-13) — and each of the six seams a sibling fault task will build on does what
 * its KDoc says.
 *
 * The seam tests are the load-bearing ones here: four fault classes are being written against
 * these signatures on sibling branches, and a seam that only *looks* sufficient is a design
 * failure in this task, not in theirs.
 */
class DstWorldTest {

    // ------------------------------------------------------------------ [CHA1-01], [CHA1-03]

    @Test
    fun `world composes controller, registry and hosts, and exposes the controller unchanged`() {
        val world = DstWorld(seed = 7)
        SelfTestGraphs.crossTalk().builder.build(world)

        assertEquals(setOf("peerA", "peerB"), world.hosts.names())
        assertEquals(2, world.hosts.all().size)
        // the kernel controller itself, not a wrapper: driving it directly still works
        val steps = world.controller.runToIdle()
        assertTrue(steps > 0, "the bare controller drains the rig's world")
    }

    /**
     * Review repair ([CHA1-04], [CHA1-30]). Everything else that checks the empty-plan claim —
     * `DstRun.execute`, `DstBaseline.run`, `DstBaseline.runToIdleSteps` — builds its graph in a
     * [DstWorld], so all three share whatever seed that world hands its controller and agree
     * with each other no matter what it is. Measured: seeding the world's controller with
     * `seed + 1` left all 30 tests green. [CHA1-04] says *the same seed*, so the pin has to
     * compare against a `SimulationController` this package did not construct.
     */
    @Test
    fun `the world's controller carries the run seed, not merely a seed`() {
        val bare = pickOrder(SimulationController(41))
        assertEquals(bare, pickOrder(DstWorld(seed = 41).controller), "DstWorld(s).controller must be SimulationController(s)")
        assertNotEquals(bare, pickOrder(SimulationController(42)), "if the pick order ignored the seed this pin would be vacuous")
    }

    /** The controller's seeded cross-host pick, as an observable sequence of scheduler indices. */
    private fun pickOrder(controller: SimulationController, hosts: Int = 3, tasks: Int = 40): List<Int> {
        val order = mutableListOf<Int>()
        val schedulers = (0 until hosts).map { controller.scheduler() }
        schedulers.forEachIndexed { index, scheduler -> repeat(tasks) { scheduler.submit(10) { order += index } } }
        while (controller.step()) Unit
        return order
    }

    @Test
    fun `budget exhaustion is a distinct outcome from a check failure`() {
        val exhausted = DstRun(SelfTestGraphs.livelock(), FaultPlan.empty(3), budget = 50).execute()
        assertEquals(DstOutcome.BUDGET_EXHAUSTED, exhausted.outcome)
        assertEquals(50, exhausted.steps)
        assertEquals(null, exhausted.failingCheck, "no verdict is claimed about a run that never quiesced")

        val failed = DstRun(
            SelfTestGraphs.crossTalk(),
            FaultPlan.empty(3),
            check = { throw AssertionError("board did not converge") },
        ).execute()
        assertEquals(DstOutcome.FAILED, failed.outcome)
        assertEquals("board did not converge", failed.failingCheck?.message)
    }

    @Test
    fun `a check that passes on a quiesced run reports PASSED`() {
        val report = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.empty(11)).execute()
        assertEquals(DstOutcome.PASSED, report.outcome)
        assertTrue(report.steps > 0)
        assertTrue(report.appliedFaults.isEmpty())
    }

    // ------------------------------------------------------------------ seam 4 + [CHA1-02]

    @Test
    fun `seam 4 - the step hook fires before every step with that step's index`() {
        val seen = mutableListOf<Int>()
        val stamps = mutableListOf<Int>()
        val graph = GraphSpec("step-hook") { world ->
            world.steps.onStep { w, step ->
                seen += step
                stamps += w.step
            }
            SelfTestGraphs.crossTalk(chains = 1, rounds = 2).builder.build(world)
        }
        val report = DstRun(graph, FaultPlan.empty(5)).execute()

        assertContentEquals((0..report.steps).toList(), seen, "one hook per step, in index order, plus the quiescence probe")
        assertEquals(seen, stamps, "world.step is the index of the step about to run")
    }

    // ------------------------------------------------------------------ seam 1

    @Test
    fun `seam 1 - edge interposers chain, drop, duplicate, and detach`() {
        val world = DstWorld(seed = 1)
        val edge = world.edges.declare("a->b", from = "peerA", to = "peerB")

        assertEquals(listOf("x"), edge.deliver("x".toByteArray()).map { String(it) }, "identity by default")

        val duplicate = edge.intercept { frame, _ -> listOf(frame, frame) }
        val tag = edge.intercept { frame, _ -> listOf(frame + '!'.code.toByte()) }
        assertEquals(
            listOf("x!", "x!"),
            edge.deliver("x".toByteArray()).map { String(it) },
            "chained in registration order: the duplicate's two frames each reach the tagger",
        )

        duplicate.close()
        assertEquals(listOf("x!"), edge.deliver("x".toByteArray()).map { String(it) })

        val drop = edge.intercept { _, _ -> emptyList() }
        assertTrue(edge.deliver("x".toByteArray()).isEmpty(), "drop is an empty result, not an exception")
        drop.close()
        tag.close()
        assertFalse(edge.intercepted)
    }

    @Test
    fun `seam 1 - an edge is one direction, so a one-way partition targets one name`() {
        val world = DstWorld(seed = 1)
        world.edges.declare("a->b")
        world.edges.declare("b->a")
        world.edges.intercept("a->b") { _, _ -> emptyList() }

        assertTrue(world.edges.deliver("a->b", "f".toByteArray()).isEmpty())
        assertEquals(1, world.edges.deliver("b->a", "f".toByteArray()).size, "the reverse direction keeps delivering")
    }

    // ------------------------------------------------------------------ seam 2

    @Test
    fun `seam 2 - crash rebuilds a host from the caller's function, keeping registry and journals`() {
        val world = DstWorld(seed = 1)
        val journal = world.journals.declare("j")
        journal.append("survivor".toByteArray())

        val generations = mutableListOf<Int>()
        val slot = world.hosts.declare("peerA") { ctx ->
            generations += ctx.generation
            assertSame(world.registry, ctx.registry, "the registry survives a crash")
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry, journalFor = { ctx.journals.view("j") })
        }
        val before = slot.host

        val after = slot.crash()

        assertEquals(listOf(0, 1), generations)
        assertEquals(1, slot.generation)
        assertNotSame(before, after, "a crashed host is discarded, not reused")
        assertSame(after, slot.host)
        assertContentEquals(
            listOf("survivor"),
            world.journals.base("j").replay().map { String(it) },
            "surviving the crash is exactly the journal's job",
        )
    }

    @Test
    fun `seam 2 - a crashed host's queued work does not come back`() {
        val world = DstWorld(seed = 1)
        var ran = 0
        val slot = world.hosts.declare("peerA") { ctx ->
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry).also {
                ctx.scheduler.submit(10) { ran++ }
            }
        }
        slot.crash() // discards generation 0's queued task, enqueues generation 1's
        world.controller.runToIdle()
        assertEquals(1, ran, "only the rebuilt host's work ran")
    }

    // ------------------------------------------------------------------ seam 3

    @Test
    fun `seam 3 - a journal decoration installed mid-run applies without re-wiring the host`() {
        val world = DstWorld(seed = 1)
        val base = InMemoryJournal()
        val view = world.journals.declare("j", base)
        // the host holds `view` forever; nothing below re-hands it a journal
        view.append("before".toByteArray())

        val swallow = world.journals.decorate("j") { inner ->
            object : Journal by inner {
                override fun append(record: ByteArray) = Unit
            }
        }
        view.append("during".toByteArray())
        assertContentEquals(listOf("before"), base.replay().map { String(it) }, "the decoration took effect on the held view")
        assertTrue(world.journals.decorated("j"))

        swallow.close()
        view.append("after".toByteArray())
        assertContentEquals(listOf("before", "after"), base.replay().map { String(it) }, "and healed when the window closed")
    }

    @Test
    fun `seam 3 - decorations compose innermost-first in registration order`() {
        val world = DstWorld(seed = 1)
        val base = InMemoryJournal()
        val view = world.journals.declare("j", base)
        world.journals.decorate("j") { inner ->
            object : Journal by inner {
                override fun append(record: ByteArray) = inner.append(record + 'i'.code.toByte())
            }
        }
        world.journals.decorate("j") { inner ->
            object : Journal by inner {
                override fun append(record: ByteArray) = inner.append(record + 'o'.code.toByte())
            }
        }
        view.append("r".toByteArray())
        assertContentEquals(listOf("roi"), base.replay().map { String(it) })
    }

    // ------------------------------------------------------------------ seam 6

    @Test
    fun `seam 6 - dead letters are captured raw, in arrival order`() {
        val world = DstWorld(seed = 1)
        val slot = world.hosts.declare("peerA") { ctx ->
            ManagedHost(scheduler = ctx.scheduler, registry = ctx.registry)
        }
        val method = Runnable::class.java.methods.single { it.name == "run" }
        slot.host.routerInlet.call.route(CellRef(UUID.randomUUID()), "inlet", Invocation.of(method, emptyArray()))
        world.controller.runToIdle()

        assertEquals(1, world.deadLetters.size, "the undeliverable route surfaced as a dead letter")
        assertTrue(world.deadLetters.single().description.isNotBlank())
    }

    // ------------------------------------------------------------------ seam 5 + [CHA1-24]

    @Test
    fun `BS-13 - per-fault firing is counted and a fault that never fired is inert (seam 5)`() {
        val fires = ScriptedFault(
            id = "fires-at-3",
            targets = listOf(FaultTarget.Host("peerA")),
            atStep = { world, step -> if (step == 3 || step == 4) world.trace.fault("fires-at-3", host = "peerA") },
        )
        val never = ScriptedFault(id = "never-fires", targets = listOf(FaultTarget.Edge("a->b")))

        val report = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.of(21, fires, never)).execute()

        val fired = report.appliedFaults.single { it.id == "fires-at-3" }
        assertEquals(2, fired.fired)
        assertContentEquals(listOf(3, 4), fired.activationSteps, "activation is recorded as step indices ([CHA1-02])")
        assertFalse(fired.inert)

        val inert = report.appliedFaults.single { it.id == "never-fires" }
        assertEquals(0, inert.fired)
        assertTrue(inert.inert, "a configured fault that never fired is flagged, not hidden")
        assertContentEquals(listOf("never-fires"), report.inertFaults.map { it.id })
        assertTrue(report.summary().contains("inert=[never-fires]"))
    }

    @Test
    fun `BS-13 - a fault scheduled past quiescence is reported inert, not quietly absent`() {
        val lateFault = ScriptedFault(
            id = "partition-at-5000",
            targets = listOf(FaultTarget.Edge("a->b")),
            atStep = { world, step ->
                if (step >= 5000) world.trace.fault("partition-at-5000", port = "a->b")
            },
        )
        val report = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.of(7, lateFault)).execute()

        assertEquals(DstOutcome.PASSED, report.outcome)
        assertTrue(report.steps < 5000, "the run quiesced long before the activation step (${report.steps})")
        val applied = report.appliedFaults.single()
        assertEquals(0, applied.fired)
        assertTrue(applied.inert, "a plan that silently tests nothing must be visible in the report")
    }

    @Test
    fun `seam 5 - a fault's firing is tagged in the trace at the step it fired`() {
        val fault = ScriptedFault(
            id = "tagger",
            atStep = { world, step -> if (step == 2) world.trace.fault("tagger", host = "peerA", port = "a->b") },
        )
        val report = DstRun(SelfTestGraphs.crossTalk(), FaultPlan.of(21, fault)).execute()
        val tagged = report.trace.single { it.faultTag == "tagger" }
        assertEquals(2, tagged.step)
        assertEquals("a->b", tagged.port)
    }

    // ------------------------------------------------------------------ [CHA1-30]

    @Test
    fun `every source of randomness derives from the one run seed, by purpose`() {
        val world = DstWorld(seed = 99)
        assertSame(world.rng("workload"), world.rng("workload"), "one stream per purpose, not a fresh replay")

        val a = DstWorld(seed = 99).rng("workload").nextInt()
        val b = DstWorld(seed = 99).rng("workload").nextInt()
        val other = DstWorld(seed = 99).rng("partition-1").nextInt()
        val differentSeed = DstWorld(seed = 100).rng("workload").nextInt()

        assertEquals(a, b, "same seed, same purpose, same stream")
        assertTrue(a != other, "different purposes are independent")
        assertTrue(a != differentSeed, "the seed is what varies a run")
    }

    // ------------------------------------------------------------------ [CHA1-06]

    @Test
    fun `a graph builder is a value with a stable id a replay artifact can name`() {
        val spec = SelfTestGraphs.crossTalk()
        try {
            GraphRegistry.register(spec)
            assertSame(spec, GraphRegistry.require(spec.id))
            assertEquals(spec.id, DstRun(spec, FaultPlan.empty(1)).execute().graphId)
        } finally {
            GraphRegistry.unregister(spec.id)
        }

        val unknown = runCatching { GraphRegistry.require("no-such-graph") }.exceptionOrNull()
        assertTrue(unknown!!.message!!.contains("no-such-graph"))
        assertTrue(unknown.message!!.contains("registered graphs"))
    }
}
