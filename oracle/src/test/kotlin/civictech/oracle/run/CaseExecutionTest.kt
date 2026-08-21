package civictech.oracle.run

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.consistency.GlitchFreeCell
import civictech.cell.consistency.WaveFrontier
import civictech.cell.data.delta.SetDelta
import civictech.cell.graph.CellFactory
import civictech.cell.graph.ConnectStep
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.SpawnStep
import civictech.cell.port.FanInlet
import civictech.cell.port.PolicyTier
import civictech.cell.port.registerPort
import civictech.oracle.bind.CoreOperators
import civictech.oracle.bind.OperatorCatalog
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * computenet-vpiz — what [CaseExecution.assemble] does at a **host cut**: it bridges the edge
 * for real, so the cut is invisible to a wave-frontier join.
 *
 * ## The equality this file exists to pin
 *
 * `[22-GF-03]`: "WHERE a frontier edge is bridged across a host boundary, a glitch-free join
 * SHALL compute an identical completeness condition and preserve the diamond guarantee across
 * the cut." A frontier's completeness condition ranges over its inlet's **edge set**, which is
 * folded from per-link `EdgeOpen`/`EdgeClose` — and watermarks, `Progress(thru)` absorb-acks
 * and stall markers all travel per-link. So "identical completeness condition" is, concretely,
 * *the same links on the same inlet either side of the cut*, and that is what
 * [`a cross-host connect registers the same target-side link a same-host connect does`] and
 * [`an ALIGN-tier join computes the same completeness condition across the cut as in-process`]
 * assert, on graphs that differ only in the host ordinal the sink is placed on.
 *
 * ## What this replaced
 *
 * computenet-g25w measured, and computenet-xj0v re-derived, that a cross-host `ConnectStep` used
 * to be issued as a bare `Propagate` handle resolved from the `LocationRegistry` and wrapped in
 * `Use.fixed`, on the SOURCE host only — so the target inlet registered **0** links against a
 * same-host connect's **1**. The data plane was unaffected, which is exactly why the gap was
 * invisible until something read link identity; a frontier there would have folded its
 * completeness condition over an edge set missing its only arm. computenet-xj0v shipped a named
 * refusal at assemble time as an interim tripwire. computenet-vpiz replaces it with the bridged
 * link pair the refusal stood in for (`civictech.cell.wire`'s `bridgeTo`/`bridgeFrom`, which are
 * in `:kernel` and so available here — `[ORA1-API-04]` bars `:wire`, a different module and a
 * different package), and this file's refusal tests became the positive equality above.
 *
 * Nothing the generator can draw exercises the frontier path today: no registered catalog
 * operator carries `GlitchFree` or installs an ALIGN policy. Hence the hand-built [AlignedJoin]
 * here — the smallest cell that puts an ALIGN-tier policy on the far side of the cut.
 */
class CaseExecutionTest {

    private val writer = WriterId("w")
    private val source = SourceId("s")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
    }

    @AfterEach
    fun resetCatalog() {
        OperatorCatalog.reset()
    }

    private fun setFactory() = OperatorCatalog.entry(CoreOperators.Ids.SET)!!.kernel

    // ------------------------------------------------------------------ the two sink cells

    /**
     * A sink with an ordinary [FanInlet] — **no** policy of any tier, so it is the instrument
     * for the bare link measurement and for the policy-free control.
     */
    private class Recorder(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<SetDelta<Any?>>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Any?>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<Any?>> {
                override fun propagate(value: SetDelta<Any?>) {
                    received += value
                }
            })
        }
    }

    /**
     * The same sink with a [WaveFrontier] installed on its inlet — the general opt-in form
     * ("any plain cell can opt into the same completeness gate", [GlitchFreeCell]'s KDoc),
     * deliberately used here in preference to [GlitchFreeCell] itself so the equality is pinned
     * to the **policy tier** rather than to the sugar class. [alignedSugarJoinIsBridgedToo]
     * covers the sugar class as well.
     */
    private class AlignedJoin(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<SetDelta<Any?>>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<Any?>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<Any?>> {
                override fun propagate(value: SetDelta<Any?>) {
                    received += value
                }
            })
            inlet.install(WaveFrontier(GlitchFreeCell.WaveMode.WAIT))
        }
    }

    // ------------------------------------------------------------------ the case under test

    /**
     * `set(src) -> sink`, with `sink` built by [sinkFactory] and placed on host [sinkHost]
     * (`src` always on `0`), so `sinkHost = 0` and `sinkHost = 1` are the same graph either
     * side of a host cut — the only knob that separates them.
     *
     * `sink` is deliberately absent from [CaseTopology.nodes]: [CaseExecution.assemble] reads
     * that list only to bind **sources** and to resolve **terminals** through the catalog, and
     * this case has no terminal and drives only `src`. Declaring `sink` there would require a
     * catalog id for a cell no catalog registers — which is the whole premise of this bead.
     */
    private fun caseWith(sinkFactory: CellFactory, sinkHost: Int) = GeneratedCase(
        seed = 1L,
        topology = CaseTopology(
            nodes = listOf(TopologyNode("src", CoreOperators.Ids.SET, emptyList(), source)),
            terminals = emptyList(),
            placement = mapOf("src" to 0, "sink" to sinkHost),
        ),
        spec = GraphSpec(
            listOf(
                SpawnStep("src", setFactory()),
                SpawnStep("sink", sinkFactory),
                ConnectStep("src", "outlet", "sink", "inlet"),
            ),
        ),
        script = CaseScript(
            listOf(
                CaseStep.Op(source, ScriptEvent.Add(writer, "ab")),
                CaseStep.Op(source, ScriptEvent.Add(writer, "cd")),
                CaseStep.Op(source, ScriptEvent.Remove(writer, "ab")),
            ),
        ),
        removeAudit = emptyList(),
    )

    /** Drives [case]'s script straight at [assembly]'s sources, then drains [world] fully. */
    private fun drive(case: GeneratedCase, world: SimWorld, assembly: CaseExecution.CaseAssembly) {
        case.script.steps.filterIsInstance<CaseStep.Op>().forEach { step ->
            val bound = assembly.graph.sources.getValue(step.source)
            when (val event = step.event) {
                is ScriptEvent.Add -> bound.add(event.element)
                is ScriptEvent.Remove -> bound.remove(event.element)
                else -> Unit
            }
        }
        while (world.controller.step()) { /* drain */ }
    }

    // ------------------------------------------------------------------ the measurement

    /**
     * The core of `[22-GF-03]` at its cheapest: the same graph either side of the cut registers
     * the **same** target-side link count, on otherwise identical cells, with an identical data
     * plane. Before computenet-vpiz this test read `shouldBeEmpty()` on the cross-host side —
     * 1 same-host, 0 across the cut — which is precisely the truncated edge set the bridge
     * removes.
     */
    @Test
    fun `a cross-host connect registers the same target-side link a same-host connect does`() {
        lateinit var coHosted: Recorder
        val sameHostWorld = SimWorld(seed = 1L)
        val sameHostCase = caseWith({ ref -> Recorder(ref).also { coHosted = it } }, sinkHost = 0)
        val sameHostAssembly = CaseExecution.assemble(sameHostCase, sameHostWorld)

        lateinit var remote: Recorder
        val crossHostWorld = SimWorld(seed = 1L)
        val crossHostCase = caseWith({ ref -> Recorder(ref).also { remote = it } }, sinkHost = 1)
        val crossHostAssembly = CaseExecution.assemble(crossHostCase, crossHostWorld)

        withClue("a same-host ConnectStep registers a link the target inlet can see") {
            coHosted.inlet.linking.links.size shouldBe 1
        }
        withClue(
            "and so does the cross-host one, now that it is wired as a bridged link pair " +
                "(bridgeTo on the source outlet, bridgeFrom on the target inlet): the arm has " +
                "link identity, so EdgeOpen/EdgeClose, per-source watermarks and Progress(thru) " +
                "absorb-acks have something to travel on [22-GF-03]",
        ) {
            remote.inlet.linking.links.size shouldBe coHosted.inlet.linking.links.size
        }

        drive(sameHostCase, sameHostWorld, sameHostAssembly)
        drive(crossHostCase, crossHostWorld, crossHostAssembly)

        withClue("non-vacuity: the co-hosted sink actually received the script's deltas") {
            coHosted.received.shouldNotBeEmpty()
            coHosted.received.map(::shapeOf) shouldBe listOf(
                setOf("ab") to emptySet(),
                setOf("cd") to emptySet(),
                emptySet<Any?>() to setOf("ab"),
            )
        }
        withClue("and the data plane is unchanged by the bridge — same deltas, same order") {
            remote.received.map(::shapeOf) shouldBe coHosted.received.map(::shapeOf)
        }
    }

    /**
     * A [SetDelta] reduced to the elements it adds and deletes. The two worlds here are
     * separate `SimWorld`s, so their `SetCell`s mint different `Timestamp.sourceId`s for the
     * identical script; comparing whole deltas would compare those UUIDs and never the
     * property under test.
     */
    private fun shapeOf(delta: SetDelta<Any?>): Pair<Set<Any?>, Set<Any?>> =
        delta.adds.keys.toSet() to delta.dels.keys.toSet()

    // -------------------------------------------------- the [22-GF-03] equality, across the cut

    /**
     * The reproduction this bead exists for, in its positive form: the same graph, with the sink
     * carrying an ALIGN-tier policy, once co-hosted with its upstream and once across the cut.
     * The frontier's completeness condition ranges over its inlet's edge set, so "identical
     * completeness condition" is the same edge set — and the bridged arm is in it.
     *
     * Under computenet-xj0v the cross-host half of this threw `IllegalStateException` at
     * assemble time (the interim refusal); before that it assembled with the frontier folding
     * over an edge set of size 0.
     */
    @Test
    fun `an ALIGN-tier join computes the same completeness condition across the cut as in-process`() {
        lateinit var coHosted: AlignedJoin
        val sameHostWorld = SimWorld(seed = 1L)
        val sameHostCase = caseWith({ ref -> AlignedJoin(ref).also { coHosted = it } }, sinkHost = 0)
        val sameHostAssembly = CaseExecution.assemble(sameHostCase, sameHostWorld)

        lateinit var bridged: AlignedJoin
        val crossHostWorld = SimWorld(seed = 1L)
        val crossHostCase = caseWith({ ref -> AlignedJoin(ref).also { bridged = it } }, sinkHost = 1)
        val crossHostAssembly = CaseExecution.assemble(crossHostCase, crossHostWorld)

        withClue("non-vacuity: both sinks really do carry the ALIGN-tier policy [22-GF-03] is about") {
            coHosted.inlet.hasPolicy(PolicyTier.ALIGN) shouldBe true
            bridged.inlet.hasPolicy(PolicyTier.ALIGN) shouldBe true
        }
        withClue("the in-process frontier waits on exactly the one arm feeding it") {
            coHosted.inlet.linking.links.size shouldBe 1
        }
        withClue(
            "and so does the bridged one: the completeness condition is computed over an edge " +
                "set of the same size, INCLUDING the cross-host arm [22-GF-03]",
        ) {
            bridged.inlet.linking.links.size shouldBe coHosted.inlet.linking.links.size
        }

        drive(sameHostCase, sameHostWorld, sameHostAssembly)
        drive(crossHostCase, crossHostWorld, crossHostAssembly)

        withClue("non-vacuity: the WAIT-mode frontier released the waves in-process") {
            coHosted.received.shouldNotBeEmpty()
        }
        withClue(
            "and released the same waves, in the same order, across the cut — a frontier folding " +
                "over a truncated edge set would have held them forever",
        ) {
            bridged.received.map(::shapeOf) shouldBe coHosted.received.map(::shapeOf)
        }
    }

    /** The sugar cell (`GlitchFreeCell`) gets the same bridged arm — it installs the same policy. */
    @Test
    fun alignedSugarJoinIsBridgedToo() {
        lateinit var sugar: GlitchFreeCell<*>
        val world = SimWorld(seed = 1L)
        val case = caseWith(
            { ref -> GlitchFreeCell(Propagate::class.java, ref).also { sugar = it } },
            sinkHost = 1,
        )

        CaseExecution.assemble(case, world)

        withClue("non-vacuity: the sugar cell carries the ALIGN-tier policy") {
            sugar.inlet.hasPolicy(PolicyTier.ALIGN) shouldBe true
        }
        withClue("and its inlet sees the bridged arm across the cut") {
            sugar.inlet.linking.links.size shouldBe 1
        }
    }

    /**
     * The in-process baseline the equality above is measured against, standing on its own: the
     * ALIGN-tier fan-in co-hosted with its upstream assembles, links, and runs. It is what makes
     * "the same completeness condition" a comparison rather than a coincidence — a bridge that
     * registered two links, or none, would still have to differ from this.
     */
    @Test
    fun `the same ALIGN-tier fan-in links and runs when it shares a host with its upstream`() {
        lateinit var join: AlignedJoin
        val world = SimWorld(seed = 1L)
        val case = caseWith({ ref -> AlignedJoin(ref).also { join = it } }, sinkHost = 0)

        val assembly = CaseExecution.assemble(case, world)

        withClue("a same-host connect into the frontier registers the arm it must wait for") {
            join.inlet.linking.links.size shouldBe 1
        }
        drive(case, world, assembly)
        withClue("and the wave still reaches the join, so the control is not vacuous") {
            join.received.shouldNotBeEmpty()
        }
    }

    /**
     * The complementary control: a cross-host connect into an inlet with **no** ALIGN policy
     * still delivers. Every case the generator can draw today lands here, so this is what keeps
     * the bridged pair from silently breaking the ordinary multi-host data path — the bridged
     * link carries protocol frames, and payloads still ride the registry-resolved handle.
     */
    @Test
    fun `a cross-host connect into a policy-free inlet is still allowed`() {
        lateinit var remote: Recorder
        val world = SimWorld(seed = 1L)
        val case = caseWith({ ref -> Recorder(ref).also { remote = it } }, sinkHost = 1)

        val assembly = CaseExecution.assemble(case, world)
        drive(case, world, assembly)

        remote.received.shouldNotBeEmpty()
    }
}
