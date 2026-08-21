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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * computenet-xj0v — what [CaseExecution.assemble] does at a **host cut**, and the one shape it
 * now refuses there.
 *
 * ## The measurement this rests on, re-established here
 *
 * [`the cross-host connect registers no target-side link, while the same-host connect does`]
 * re-derives computenet-g25w's finding against *this* branch's `CaseExecution` rather than
 * taking it on trust: a `ConnectStep` whose two ends share a host produces **1** link the
 * target inlet can see; the same step across a cut produces **0**, because the cross-host
 * branch issues a bare `Propagate` handle resolved from the `LocationRegistry` and wrapped in
 * `Use.fixed`, on the SOURCE host only. The data plane is unaffected — same deltas, same
 * order — which is exactly why the gap is invisible until something reads link identity.
 *
 * ## Why that gap is a defect for [22-GF-03], and what is done about it
 *
 * Every frontier/completeness bookkeeping is keyed by link identity: `WaveFrontier`'s edge set
 * is folded from per-link `EdgeOpen`/`EdgeClose`, and watermarks, `Progress(thru)` absorb-acks
 * and stall markers all travel per-link. A wave-frontier join (an inlet carrying an ALIGN-tier
 * policy — `PolicyTier.ALIGN`, the kernel's own criterion in
 * `civictech.cell.host.hasFrontierPolicy`) fed across such a cut would therefore compute its
 * completeness condition over an edge set **missing the cross-host arm**, which is the opposite
 * of what [22-GF-03] requires.
 *
 * The bead left two routes open, and **both are reachable from here** — the bead's phrase
 * "`:wire`'s `WireEdgeLink`" is misleading: `WireEdgeLink`, `bridgeTo`/`bridgeFrom`,
 * `BridgeEgressCell`/`BridgeIngressCell` and `WireCodec` are all in **`:kernel`**
 * (`civictech.cell.wire`, not `:wire`'s `civictech.wire`), which `:oracle` depends on, so
 * `[ORA1-API-04]` does not bar them;
 * `kernel/src/test/kotlin/civictech/cell/consistency/GlitchFreeBridgedDiamondTest.kt` builds
 * this very shape across two `ManagedHost`s with no `:wire` dependency. Bridging every
 * cross-host edge for real changes the harness's cross-host model, so it is filed as follow-up
 * work; what is done **here** is the other route — **refuse at assemble time**, naming the
 * handle, the inlet and the policy tier — so the limit is a loud, named refusal instead of a
 * silently truncated edge set while that work is outstanding.
 *
 * Nothing in the corpus can build that shape today: no registered catalog operator carries
 * `GlitchFree` or installs an ALIGN policy. The refusal is a tripwire for the moment one does —
 * hence the hand-built [AlignedJoin] here, which is the smallest cell that trips it.
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
     * A sink with an ordinary [FanInlet] — **no** policy of any tier, so it is the cell the
     * refusal must NOT fire on, and the instrument for the link measurement.
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
     * deliberately used here in preference to [GlitchFreeCell] itself so the refusal is pinned
     * to the **policy tier** rather than to the sugar class. [alignedSugarJoinIsRefusedToo]
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
     * computenet-g25w's finding, re-derived on this branch: 1 target-side link same-host, 0
     * across the cut, on otherwise identical cells — and an identical data plane either way.
     */
    @Test
    fun `the cross-host connect registers no target-side link, while the same-host connect does`() {
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
            "the cross-host ConnectStep does not: a bare Propagate handle wrapped in Use.fixed " +
                "and issued on the source host leaves NO link identity on the target side, so " +
                "no EdgeOpen and no per-inlink frontier bookkeeping can happen there",
        ) {
            remote.inlet.linking.links.shouldBeEmpty()
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
        withClue("the data plane is unaffected by the missing link — same deltas, same order") {
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

    // ------------------------------------------------------------------ the refusal

    /**
     * The reproduction this bead exists for: the same graph, with the sink carrying an
     * ALIGN-tier policy, placed across the cut. Before the fix it assembled happily and the
     * frontier's edge set silently omitted the only arm feeding it.
     */
    @Test
    fun `a cross-host connect into an ALIGN-tier inlet is refused at assemble time`() {
        lateinit var join: AlignedJoin
        val world = SimWorld(seed = 1L)
        val case = caseWith({ ref -> AlignedJoin(ref).also { join = it } }, sinkHost = 1)

        val failure = assertThrows<IllegalStateException> { CaseExecution.assemble(case, world) }

        withClue("the message names the handle, the inlet and the policy tier [22-GF-03]") {
            failure.message.shouldContain("sink")
            failure.message.shouldContain("inlet")
            failure.message.shouldContain("ALIGN")
        }
        withClue("non-vacuity: the sink really did carry the policy the refusal names") {
            join.inlet.hasPolicy(civictech.cell.port.PolicyTier.ALIGN) shouldBe true
        }
    }

    /** The sugar cell (`GlitchFreeCell`) trips the same refusal — it installs the same policy. */
    @Test
    fun alignedSugarJoinIsRefusedToo() {
        val world = SimWorld(seed = 1L)
        val case = caseWith(
            { ref -> GlitchFreeCell(Propagate::class.java, ref) },
            sinkHost = 1,
        )

        val failure = assertThrows<IllegalStateException> { CaseExecution.assemble(case, world) }
        failure.message.shouldContain("ALIGN")
    }

    /**
     * The refusal is scoped to the **cut**, not to the policy: the identical ALIGN-tier fan-in
     * co-hosted with its upstream assembles, links, and runs. Without this control the fix
     * would be indistinguishable from one that banned frontier joins outright.
     */
    @Test
    fun `the same ALIGN-tier fan-in is not refused when it shares a host with its upstream`() {
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
     * The complementary control: a cross-host connect into an inlet with **no** ALIGN policy is
     * untouched. This is what keeps the fix from becoming a ban on multi-host cases — every
     * case the generator can draw today lands here.
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
