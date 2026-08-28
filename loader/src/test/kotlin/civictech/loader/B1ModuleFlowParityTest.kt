package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.SetApi
import civictech.cell.data.SetCellBase
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.LinkResult
import civictech.cell.link.catchUpOnLinked
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.registerPort
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Scenario **B1**, flow half — `[JAR1-SPAWN-01]` / `[JAR1-SPAWN-02]` (epic
 * computenet-051, feature computenet-051.5, task computenet-051.5.4).
 *
 * The claim: a cell spawned out of a *loaded module* through
 * [ModuleHandle.cellFactory] and linked to a **host-declared** downstream cell
 * flows identically to the same graph assembled entirely from host-classpath
 * classes — delta for delta, and wave/tag/source observation for wave/tag/source
 * observation, not merely the same final state.
 *
 * ### What makes this a parity test rather than a smoke test
 *
 * - **The host link is the point** (feature computenet-051.5's R2 warning). A
 *   module cell spawned and driven in isolation would pass with completely
 *   wrong classloader isolation; the assertion only means something because the
 *   module cell's `outlet` is linked, through the host, to [B1RecordingSink] —
 *   a cell declared *here*, on `:loader`'s own test classpath, carrying the
 *   host's `civictech.cell.*` `Propagate`/`SetDelta` types.
 * - **The comparison is against an all-host-classpath twin graph**
 *   ([HostTwinFlowSetCell]) assembled by the exact same script
 *   ([runFlowGraph]), with the exact same [CellRef]s, differing only in which
 *   classloader produced the upstream cell.
 * - **The comparator is falsifiable**, and that is asserted rather than
 *   assumed: `the parity comparator can fail` feeds it a genuinely divergent
 *   pair and requires the comparison to blow up.
 *
 * ### Why the twin is a copy rather than the fixture class
 *
 * `loader/fixtures/flow`'s jar is deliberately **not** on `:loader`'s test
 * runtime classpath — it arrives as a path in the `loader.fixture.flow` system
 * property (see loader/build.gradle.kts), which is what makes "loaded through
 * [ModuleClassLoader]" mean anything at all. So the host-classpath twin cannot
 * *be* `civictech.loader.fixture.flow.FlowSetCell`; it is a behavioural copy of
 * it below. That copy is not maintained by hand-discipline: behavioural drift
 * between the fixture and the twin fails the parity assertion loudly, which is
 * the same signal this test exists to give. Bound on that, stated so nobody
 * reads it wider than it is: the drift caught is the drift the drive script in
 * [runFlowGraph] exercises (tag algebra, add/remove/re-add, effective-only
 * remove, late-join catch-up). A fixture change outside those shapes — a new
 * port, a new emission trigger — would leave the twin stale with this test
 * still green.
 *
 * ### Wave/tag/source canonicalization
 *
 * A [civictech.cell.port.FanOutlet]'s `sourceId` is minted randomly per outlet
 * instance (`FanOutlet.sourceId`), so two independently assembled graphs can
 * never carry *equal* source UUIDs — that is true of two host-classpath graphs
 * as much as of a module/host pair, and has nothing to do with the loader. What
 * a parity claim can and must assert is *continuity*: the same number of source
 * lanes, appearing in the same order, carrying the same per-lane wave counters.
 * [canonical] replaces each `sourceId` with its lane index (order of first
 * appearance) and leaves every other observed field — wave counter,
 * `sourcePort`, `hop`, catch-up baseline flag, and the full add/del tag sets —
 * compared verbatim. Tags survive verbatim because both the fixture cell and
 * the twin mint them from `(ref, counter)` and both graphs use the same refs.
 *
 * Measured shape of what that compares (observed, not assumed): the `catchUpOnLinked`
 * baseline emission arrives with **no** [civictech.cell.MessageContext] at all, so its
 * lane/counter/`sourcePort`/`hop` are all `null` and `catchUpBaseline` is `false` on
 * every emission in this script — that field is compared but never discriminates here.
 * The three post-link emissions carry the real continuity claim: one lane, `hop = 0`,
 * the same `sourcePort`, and wave counters 3/4/5 on both sides.
 */
class B1ModuleFlowParityTest {

    private companion object {
        const val FLOW_SET_CELL = "civictech.loader.fixture.flow.FlowSetCell"
        const val FLOW_CANDIDATE_CELL = "civictech.loader.fixture.flow.FlowPromotionCandidateCell"

        /** Fixed so the module run and the twin run are assembled with identical identity. */
        val CELL_REF = CellRef(UUID.fromString("00000000-0000-0000-0000-0000000000b1"))
        val SINK_REF = CellRef(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
    }

    // ------------------------------------------------------------------
    // [JAR1-SPAWN-02] — link parity against the all-host twin
    // ------------------------------------------------------------------

    @Test
    fun `a module-spawned cell linked to a host cell flows identically to its all-host-classpath twin`() {
        val twin = runFlowGraph { ref -> HostTwinFlowSetCell(ref) }

        withClue("the twin must be a host-classpath cell — otherwise there is nothing to compare against") {
            twin.upstream.javaClass.classLoader shouldBe B1ModuleFlowParityTest::class.java.classLoader
        }

        val module = FixtureJars.withLoadedModule(FixtureJars.flow) { handle ->
            val run = runFlowGraph { ref -> handle.cellFactory(FLOW_SET_CELL).create(ref) }
            // Per-test tracing (non-vacuousness): the graph whose observations are
            // compared below really did run a class the MODULE's loader produced.
            withClue("the upstream cell must have been loaded by the module's own classloader") {
                run.upstream.javaClass.classLoader shouldBe handle.classLoader
            }
            withClue("...and therefore NOT by the host's") {
                run.upstream.javaClass.classLoader shouldNotBe B1ModuleFlowParityTest::class.java.classLoader
            }
            // The shared contract types are parent-delegated, so the module cell IS a
            // host-classpath SetApi — this cast is what the host link rides on.
            withClue("the module cell must satisfy the HOST's SetApi contract type") {
                (run.upstream is SetApi<*>) shouldBe true
            }
            run
        }

        withClue("the module graph observed a different number of emissions than the twin graph") {
            module.observations.size shouldBe twin.observations.size
        }
        withClue("the graph must actually have flowed — an empty observation list would compare equal") {
            (module.observations.size >= 4) shouldBe true
        }
        assertParity(module, twin)
    }

    // ------------------------------------------------------------------
    // Comparator self-check: it CAN fail, and it does not fail on a control
    // ------------------------------------------------------------------

    @Test
    fun `the parity comparator passes on a twin-versus-twin control and fails on a genuinely divergent pair`() {
        // Control: the same script run twice over host-classpath twins compares equal.
        // This is what rules out a comparator that is trivially failing (or trivially
        // passing on an empty stream).
        assertParity(runFlowGraph { ref -> HostTwinFlowSetCell(ref) }, runFlowGraph { ref -> HostTwinFlowSetCell(ref) })

        // Falsification: FlowPromotionCandidateCell is contract-identical to
        // FlowSetCell but uppercases every element before folding it, so its deltas
        // (and therefore its tag keys) diverge. The comparator must notice.
        val divergent = FixtureJars.withLoadedModule(FixtureJars.flow) { handle ->
            runFlowGraph { ref -> handle.cellFactory(FLOW_CANDIDATE_CELL).create(ref) }
        }
        val failure = shouldThrow<AssertionError> {
            assertParity(divergent, runFlowGraph { ref -> HostTwinFlowSetCell(ref) })
        }
        withClue("the comparator's failure must name the divergent content, not just 'not equal'") {
            failure.message!!.contains("ALPHA") shouldBe true
        }
    }

    // ------------------------------------------------------------------
    // The graph script, run identically for both sides
    // ------------------------------------------------------------------

    /** One observed emission at the host-declared downstream, with its wave metadata. */
    private data class Emission(
        val adds: Map<String, Set<Timestamp>>,
        val dels: Map<String, Set<Timestamp>>,
        val sourceId: UUID?,
        val waveCounter: Long?,
        val sourcePort: PortRef?,
        val hop: Int?,
        val catchUpBaseline: Boolean,
    )

    /** [Emission] with the outlet's random `sourceId` replaced by its lane index. */
    private data class CanonicalEmission(
        val adds: Map<String, Set<Timestamp>>,
        val dels: Map<String, Set<Timestamp>>,
        val sourceLane: Int?,
        val waveCounter: Long?,
        val sourcePort: PortRef?,
        val hop: Int?,
        val catchUpBaseline: Boolean,
    )

    private class GraphRun(val upstream: Cell, val observations: List<Emission>)

    /**
     * Assembles and drives one graph: `upstream.outlet -> sink.inlet`, both cells
     * spawned on a [ManagedHost] under a deterministic [SimulationController].
     *
     * The drive script deliberately covers four emission shapes, so parity is a
     * claim about a *stream* rather than a single delta:
     *  1. adds made **before** the link — observed only via the `catchUpOnLinked`
     *     baseline emission the link installs;
     *  2. an ordinary reactive add after the link;
     *  3. an effective remove (carries the observed tags);
     *  4. a remove of an element never added — effective-only, so it must emit
     *     nothing on either side;
     *  5. a re-add of a removed element (a second, distinct tag).
     */
    private fun runFlowGraph(make: (CellRef) -> Cell): GraphRun {
        val controller = SimulationController(seed = 1051)
        val host = ManagedHost(scheduler = controller.scheduler())

        val upstream = make(CELL_REF)
        val sink = B1RecordingSink(SINK_REF)
        host.managementInlet.call.spawn(upstream)
        host.managementInlet.call.spawn(sink)

        @Suppress("UNCHECKED_CAST")
        val ops = (upstream as SetApi<String>).inlet.call

        ops.add("alpha")
        ops.add("beta")
        controller.runToIdle()

        val link = host.managementInlet.call.connect(CELL_REF, "outlet", SINK_REF, "inlet")
        check(link !is LinkResult.Rejected) { "host refused the module->host link: $link" }
        controller.runToIdle()

        ops.add("gamma")
        ops.remove("alpha")
        ops.remove("never-added") // effective-only: must be a no-op on both sides
        ops.add("alpha")
        controller.runToIdle()

        return GraphRun(upstream, sink.seen.toList())
    }

    private fun canonical(observations: List<Emission>): List<CanonicalEmission> {
        val lanes = LinkedHashMap<UUID, Int>()
        return observations.map { e ->
            CanonicalEmission(
                adds = e.adds,
                dels = e.dels,
                sourceLane = e.sourceId?.let { lanes.getOrPut(it) { lanes.size } },
                waveCounter = e.waveCounter,
                sourcePort = e.sourcePort,
                hop = e.hop,
                catchUpBaseline = e.catchUpBaseline,
            )
        }
    }

    /**
     * [JAR1-SPAWN-02]: delta-for-delta AND wave/tag/source-for-wave/tag/source
     * equality of the two observed streams. Not final state — the whole stream,
     * in order.
     */
    private fun assertParity(module: GraphRun, twin: GraphRun) {
        withClue(
            "module-spawned graph and all-host-classpath twin graph must be observationally " +
                "identical at the host-declared downstream (deltas, tags, wave counters, " +
                "source lanes, sourcePort, hop, catch-up baseline flag)"
        ) {
            canonical(module.observations) shouldBe canonical(twin.observations)
        }
    }

    // ------------------------------------------------------------------
    // Host-classpath participants
    // ------------------------------------------------------------------

    /**
     * The **host-declared downstream** the module cell is linked to: an ordinary
     * host-classpath [Cell] whose `inlet` carries the host's own
     * `Propagate<SetDelta<String>>`. It records the payload together with the
     * [civictech.cell.MessageContext] the wave arrived under, which is what makes
     * wave/tag/source continuity observable at all.
     */
    private class B1RecordingSink(override val ref: CellRef) : Cell {
        val seen = mutableListOf<Emission>()

        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    val ctx = CurrentContext.get()
                    seen += Emission(
                        adds = value.adds,
                        dels = value.dels,
                        sourceId = ctx?.timestamp?.sourceId,
                        waveCounter = ctx?.timestamp?.counter,
                        sourcePort = ctx?.sourcePort,
                        hop = ctx?.hop,
                        catchUpBaseline = ctx?.baseline != null,
                    )
                }
            })
        }
    }

    /**
     * The all-host-classpath twin of `civictech.loader.fixture.flow.FlowSetCell`:
     * same generated base ([SetCellBase], so the identical port shape), same
     * add-wins tag algebra, same deterministic `(ref, counter)` tag minting, same
     * `catchUpOnLinked` late-join behaviour — loaded by the ordinary test
     * classloader instead of a [ModuleClassLoader].
     *
     * Kept a verbatim behavioural copy on purpose (see the class KDoc for why the
     * fixture class itself is unavailable here); drift between the two is exactly
     * what the parity assertion reports.
     */
    private class HostTwinFlowSetCell(ref: CellRef) : SetCellBase<String>(ref) {
        private val stateLock = Any()
        private val adds = mutableMapOf<String, MutableSet<Timestamp>>()
        private val dels = mutableMapOf<String, MutableSet<Timestamp>>()
        private var tagCounter = 0L

        private fun mintedTag(counter: Long): Timestamp = Timestamp(
            UUID.nameUUIDFromBytes("fixture-flow-tags:${ref.id}:${ref.instanceId}".toByteArray()),
            counter,
        )

        override fun inletHandler(): SetOps<String> = object : SetOps<String> {
            override fun add(element: String) {
                val tag = synchronized(stateLock) {
                    val minted = mintedTag(++tagCounter)
                    adds.getOrPut(element) { mutableSetOf() } += minted
                    minted
                }
                outlet.call.propagate(SetDelta(adds = mapOf(element to setOf(tag))))
            }

            override fun remove(element: String) {
                val observed = synchronized(stateLock) {
                    val seen = (adds[element] ?: emptySet()) - (dels[element] ?: emptySet())
                    if (seen.isEmpty()) return
                    dels.getOrPut(element) { mutableSetOf() } += seen
                    seen
                }
                outlet.call.propagate(SetDelta(dels = mapOf(element to observed)))
            }
        }

        init {
            outlet.catchUpOnLinked {
                synchronized(stateLock) {
                    if (adds.isEmpty() && dels.isEmpty()) null
                    else SetDelta(
                        adds = adds.mapValues { it.value.toSet() },
                        dels = dels.mapValues { it.value.toSet() },
                    )
                }
            }
        }
    }
}
