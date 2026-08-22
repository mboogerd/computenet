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
import civictech.oracle.bind.TaggedOperators
import civictech.oracle.gen.CaseScript
import civictech.oracle.gen.CaseStep
import civictech.oracle.gen.CaseTopology
import civictech.oracle.gen.GeneratedCase
import civictech.oracle.gen.TerminalSpec
import civictech.oracle.gen.TopologyNode
import civictech.oracle.model.ModelState
import civictech.oracle.model.ScriptEvent
import civictech.oracle.model.SourceId
import civictech.oracle.model.WriterId
import civictech.testkit.SimWorld
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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
 *
 * ## The second thing this file pins: the tagged wiring (computenet-6v7y)
 *
 * [CaseExecution] is also where a *tagged* case meets the runner, and until computenet-6v7y it
 * did not: [CaseExecution.scriptSourceFor] had no `orMap` branch (so binding an OR-map source
 * threw, naming the id) and `foldFor` dispatched on [civictech.oracle.model.ElementShape] alone
 * (so an OR-map terminal, whose declared output shape is `MapOf`, resolved to
 * [MapTerminalFold] — an **arrival-order** fold over a stream that carries
 * `TaggedMapDelta`s). Every piece of the chain existed and none of them met, so no generated
 * OR-map case executed end to end. The `orMap …` tests below are that wiring's pins; they live
 * here rather than in `GeneratedCaseExecutionTest` because both dispatches are
 * [CaseExecution]'s, and the fold-identity assertion has to read the assembly this class
 * already builds by hand.
 */
class CaseExecutionTest {

    private val writer = WriterId("w")
    private val source = SourceId("s")

    @BeforeEach
    fun registerCatalog() {
        CoreOperators.registerAll()
        // The tagged half of the vocabulary, for the orMap wiring tests below. Registering it
        // for every test is harmless — the two objects share no id — and `resetCatalog` drops
        // both.
        TaggedOperators.registerAll()
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

    // -------------------------------------------------- the tagged wiring, computenet-6v7y

    /**
     * `orMap(src) -> terminal` — the smallest *generated-shaped* case whose source is a tagged
     * one. Hand-constructed for the same reason every case in `GeneratedCaseExecutionTest` is:
     * invoking `CaseGenerator` here would make this a test of the generator's current draws
     * rather than of the wiring, and it uses the catalog's own registered factory so the kernel
     * half and the model half cannot drift.
     */
    private fun orMapCase(script: CaseScript, seed: Long = 91L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(TopologyNode("om", TaggedOperators.Ids.OR_MAP, emptyList(), source)),
            terminals = listOf(TerminalSpec("tagged", "om")),
            placement = mapOf("om" to 0),
        ),
        spec = GraphSpec(listOf(SpawnStep("om", OperatorCatalog.entry(TaggedOperators.Ids.OR_MAP)!!.kernel))),
        script = script,
        removeAudit = emptyList(),
    )

    /**
     * `put(k1,v1) put(k2,v2) put(k1,v9) removeKey(k2)` — a re-put (`[24-TMAP-03]`: the fresh dot
     * wins) and a reset-remove (`[24-TMAP-04]`), so the answer is `{k1: v9}` and is not the
     * answer an empty or put-only script would give.
     */
    private fun orMapScript() = CaseScript(
        listOf(
            CaseStep.Op(source, ScriptEvent.Put(writer, "k1", "v1")),
            CaseStep.Op(source, ScriptEvent.Put(writer, "k2", "v2")),
            CaseStep.Op(source, ScriptEvent.Put(writer, "k1", "v9")),
            CaseStep.Op(source, ScriptEvent.RemoveKey(writer, "k2")),
            CaseStep.Barrier,
        ),
    )

    /**
     * The end-to-end half of computenet-6v7y: a case naming an `orMap` source runs through
     * [DifferentialRunner] and agrees with the catalog-resolved reference.
     *
     * Against the unfixed wiring this failed at `CaseExecution.scriptSourceFor`, which had no
     * `orMap` branch and threw naming the id — the source could not be bound at all, so nothing
     * downstream of it ever ran.
     *
     * The barrier reading is what makes the `Success` non-vacuous: two empty states agree too.
     */
    @Test
    fun `orMap - a generated case naming a tagged source executes end to end`() {
        var observed: Map<String, ModelState>? = null

        DifferentialRunner.run(orMapCase(orMapScript())) { observed = it } shouldBe RunOutcome.Success

        withClue(
            "non-vacuity: the terminal really holds the OR-map's reading — the re-put's dot won " +
                "at k1 [24-TMAP-03] and the reset-remove took k2 [24-TMAP-04]",
        ) {
            observed shouldBe mapOf("tagged" to ModelState.MapState(mapOf("k1" to "v9")))
        }
    }

    /**
     * The dispatch half: an `orMap` terminal resolves to [TaggedMapTerminalFold], **not** to
     * [MapTerminalFold].
     *
     * This is asserted on the fold's identity rather than only on the run's verdict because the
     * two folds are not interchangeable and the difference is invisible in a single-writer
     * final state: [MapTerminalFold] folds by *arrival order* through
     * [civictech.cell.data.view.MapView], while a tagged map's per-key value is decided by the
     * `(counter, sourceId)` order over the key's live dots. Substituting the arrival-order fold
     * is `[ORA2-CTL-01]`'s control, and it must not become reachable here by accident — which is
     * exactly what dispatching on [civictech.oracle.model.ElementShape] alone did, since `orMap`
     * declares a `MapOf` output shape like every untagged map operator.
     *
     * The `MapTerminalFold` clause is the regression pin the acceptance criterion asks for: a
     * `foldFor` that fell back to the shape-only branch would satisfy the `MapOf` reading and
     * fail here.
     */
    @Test
    fun `orMap - the terminal folds through the tagged fold, not the arrival-order map fold`() {
        val case = orMapCase(orMapScript())
        val world = SimWorld(seed = case.seed)

        val assembly = CaseExecution.assemble(case, world)

        val fold = assembly.graph.terminals.getValue("tagged")
        withClue("the tagged terminal resolves to the dot-algebra fold") {
            fold.shouldBeInstanceOf<TaggedMapTerminalFold<*, *>>()
        }
        withClue("and specifically NOT to the arrival-order MapView fold [ORA2-CTL-01]") {
            (fold is MapTerminalFold<*, *>) shouldBe false
        }
        withClue(
            "and the source really was bound — scriptSourceFor resolved orMap rather than " +
                "refusing it by name",
        ) {
            assembly.graph.sources.keys shouldBe setOf(source)
        }
    }

    /**
     * The tagged fold is fed by the same [civictech.cell.data.delta.TaggedMapDelta] stream the
     * kernel cell emits, and reads it through the dot algebra: driving the script straight at
     * the bound source and draining leaves the fold holding the OR-map's own answer.
     *
     * Distinct from the end-to-end test above in what it can fail on — no reference model, no
     * runner, no barrier — so a regression in the *binding* (a source bound to the wrong ops
     * surface) is separable from a regression in the *comparison*.
     */
    @Test
    fun `orMap - the bound source drives the kernel cell and the fold reads its dots`() {
        val case = orMapCase(orMapScript())
        val world = SimWorld(seed = case.seed)
        val assembly = CaseExecution.assemble(case, world)
        val bound = assembly.graph.sources.getValue(source)

        case.script.steps.filterIsInstance<CaseStep.Op>().forEach { step ->
            when (val event = step.event) {
                is ScriptEvent.Put -> bound.put(event.key, event.element)
                is ScriptEvent.RemoveKey -> bound.removeKey(event.key)
                else -> Unit
            }
        }
        while (world.controller.step()) { /* drain */ }

        assembly.graph.terminals.getValue("tagged").current() shouldBe
            ModelState.MapState(mapOf("k1" to "v9"))
    }

    // -------------------------------------------------- the pnCounter dispatch, computenet-f5zo

    /**
     * `pnCounter(src) -> terminal` — hand-constructed for the same reason [orMapCase] is: this
     * shape (a terminal linked directly onto a `pnCounter` source) is not one `GraphGenerator`
     * ever draws (`CoreOperators.registerAll`'s KDoc: no registered operator consumes a bare
     * `Scalar`, so `pnCounter` can never appear inside a generated case), but [CaseExecution]
     * does not know that — `foldFor` dispatches on the catalog entry's declared shape for any
     * topology handed to it, generated or not. Constructing it directly is what exercises that
     * dispatch in isolation from the generator's own reachability limit.
     */
    private fun pnCounterCase(script: CaseScript, seed: Long = 91L) = GeneratedCase(
        seed = seed,
        topology = CaseTopology(
            nodes = listOf(TopologyNode("pn", CoreOperators.Ids.PN_COUNTER, emptyList(), source)),
            terminals = listOf(TerminalSpec("total", "pn")),
            placement = mapOf("pn" to 0),
        ),
        spec = GraphSpec(listOf(SpawnStep("pn", OperatorCatalog.entry(CoreOperators.Ids.PN_COUNTER)!!.kernel))),
        script = script,
        removeAudit = emptyList(),
    )

    /** `increment(5) increment(3) decrement(2)` — net total 6, not the 0 an empty script gives. */
    private fun pnCounterScript() = CaseScript(
        listOf(
            CaseStep.Op(source, ScriptEvent.Increment(writer, 5L)),
            CaseStep.Op(source, ScriptEvent.Increment(writer, 3L)),
            CaseStep.Op(source, ScriptEvent.Decrement(writer, 2L)),
            CaseStep.Barrier,
        ),
    )

    /**
     * The dispatch half of computenet-f5zo: a `pnCounter` terminal resolves to
     * [PnCounterTerminalFold], **not** to [ScalarTerminalFold].
     *
     * `pnCounter` declares a bare [civictech.oracle.model.ElementShape.Scalar] output shape,
     * same as `counter` — but `PnCounterCell.outlet` carries a
     * [civictech.cell.data.delta.PnCounterDelta], not a `CounterDelta`, so the shape-only
     * dispatch [foldFor] uses for the untagged families resolves it to the wrong fold, whose
     * inlet cannot even accept the stream. Asserted on fold identity, exactly like the `orMap`
     * pin above, for the same reason: a `foldFor` that fell back to the shape-only branch would
     * satisfy the `Scalar` reading and fail here.
     *
     * Measured against the unfixed dispatch (review of computenet-f5zo, the `PN_COUNTER` branch
     * of `foldFor` deleted): this test fails here, on its own assertion —
     * `AssertionError: the pnCounter terminal resolves to the pointwise-max fold /
     * ScalarTerminalFold ... is of type ScalarTerminalFold but expected PnCounterTerminalFold`.
     * `assemble` completes; the wrong fold is simply linked.
     */
    @Test
    fun `pnCounter - the terminal folds through the pn-counter fold, not the summing scalar fold`() {
        val case = pnCounterCase(pnCounterScript())
        val world = SimWorld(seed = case.seed)

        val assembly = CaseExecution.assemble(case, world)

        val fold = assembly.graph.terminals.getValue("total")
        withClue("the pnCounter terminal resolves to the pointwise-max fold") {
            fold.shouldBeInstanceOf<PnCounterTerminalFold>()
        }
        withClue("and specifically NOT to the summing ScalarTerminalFold") {
            (fold is ScalarTerminalFold) shouldBe false
        }
    }

    /**
     * The end-to-end half: a case naming a `pnCounter` terminal runs through
     * [DifferentialRunner] and agrees with the catalog-resolved reference.
     *
     * Against the unfixed wiring, `foldFor` resolved the terminal to [ScalarTerminalFold], whose
     * inlet is `FanInlet<Propagate<CounterDelta>>` — but `PnCounterCell.outlet` emits
     * `Propagate<PnCounterDelta>`, and `PnCounterDelta` is not a `CounterDelta`. Connecting the
     * two raises a `ClassCastException` per delta at the fold's inlet, which the fan-in port
     * dead-letters rather than propagating. `assemble` does **not** throw and the run is not
     * aborted: `DifferentialRunner` returns `RunOutcome.DeadLetterFailure` carrying one
     * `DeadLetter` per script event — measured on review, three of them, each
     * `java.lang.ClassCastException: class civictech.cell.data.delta.PnCounterDelta cannot be
     * cast to class civictech.cell.data.delta.CounterDelta`. That outcome, not an exception, is
     * what the `shouldBe RunOutcome.Success` below discriminates.
     *
     * **The expected value pins the merge semantics, not only the delta type.** A
     * `PnCounterDelta` carries each source's *cumulative* total, so the three events emit
     * `incs={s:5}`, `incs={s:8}`, `decs={s:2}`. Pointwise max reads 8 − 2 = 6; a fold that
     * *summed* those arrivals the way [ScalarTerminalFold] sums `CounterDelta.amount` would read
     * 5 + 8 − 2 = 11. Measured on review by mutating [PnCounterTerminalFold]'s merge to
     * pointwise addition while leaving its type and the dispatch above intact: this test fails
     * (`WavePrefixViolation`, `observed=ScalarState(13)` against prefixes `{1=5, 2=8}`) while the
     * identity assertion above still passes. So a differently-typed-but-still-summing
     * substitution is caught here, and caught by the runner's agreement with the reference
     * model rather than only by the literal below.
     */
    @Test
    fun `pnCounter - a generated case naming a pnCounter terminal executes end to end`() {
        var observed: Map<String, ModelState>? = null

        DifferentialRunner.run(pnCounterCase(pnCounterScript())) { observed = it } shouldBe RunOutcome.Success

        withClue("non-vacuity: net total is 5 + 3 - 2 = 6, not the 0 an empty script gives") {
            observed shouldBe mapOf("total" to ModelState.ScalarState(6L))
        }
    }
}
