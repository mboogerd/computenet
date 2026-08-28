package civictech.loader

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.data.delta.SetDelta
import civictech.cell.evolve.Promotion
import civictech.cell.evolve.Shadow
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.membrane.TrafficLightCell
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRegistry
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.nature.ContractRegistry
import civictech.nature.ModuleRegistration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Scenario **B14** of epic computenet-051 (JAR1), feature computenet-051.5, task
 * computenet-051.5.5 — `[JAR1-SPAWN-04]` and its interaction with
 * `[JAR1-UNL-02]`.
 *
 * Given a host-classpath incumbent (`civictech.cell.data.SetCell<String>`) with a
 * live downstream subscriber behind a traffic-light gate, and a candidate class
 * supplied by a **loaded module** (`fixture.flow`'s `FlowPromotionCandidateCell`,
 * resolved through [ModuleHandle.cellFactory] and therefore defined by that
 * module's own [ModuleClassLoader]), when the candidate is spawned and promoted
 * via `civictech.cell.evolve.Promotion.promote`, then:
 *
 *  - the four-phase swap completes (PRECHECK → PREPARE → COMMIT → RETIRE) and the
 *    incumbent is despawned;
 *  - downstream observes **no torn, duplicate or missing delta across the swap**,
 *    including the invocations parked in the gate's buffered window while it was
 *    red;
 *  - while the promoted candidate is live, `ModuleLoader.unload` is **refused**
 *    naming the live count, the module stays [ModuleState.REGISTERED] and its
 *    registrations stay resolvable;
 *  - after the candidate is despawned, the unload succeeds.
 *
 * **Test/dispute-only.** `Promotion`, `ModuleLoader` and the kernel are inputs
 * exercised here, never modified by this task.
 *
 * ## How the buffered window is made observable, deterministically
 *
 * `Promotion.promote` reds the gate, commits and greens it in one synchronous
 * call, so in a single-threaded simulation nothing can *arrive* between those two
 * points. Rather than race a second thread (which would make the assertion a
 * timing assertion — forbidden), the rig enters the same state a hair early: it
 * reds the traffic gate itself and parks [IN_FLIGHT] in the gate's `ParkQueue`
 * before calling `promote`. `promote`'s own `setRed` is then the no-op
 * `TrafficLightCell` defines it to be for an already-red gate, and its COMMIT and
 * `setGreen` see exactly the buffered window PREPARE exists to create: the parked
 * invocations replay downstream **after** the incumbent has been dropped from the
 * gate and the downstream `Use` rebound, i.e. across the swap.
 *
 * ## Why the assertions below are not vacuous
 *
 * Two independent self-checks, because a promotion test that silently promoted
 * nothing — or a glitch assertion that could not fail — would look identical to
 * a passing one:
 *
 *  1. **The candidate really is the module's class.** Every arm asserts
 *     `candidate.javaClass.classLoader === handle.classLoader`, so a candidate
 *     accidentally resolved through the host's own loader fails here rather than
 *     passing as "a module cell was promoted".
 *  2. **The glitch assertion can fail.** `control - the promotion's own gate is
 *     what discharges the buffered window` runs the identical sequence with the
 *     promotion handed a gate that is *not* the one in the traffic path. The
 *     parked window is then never greened, downstream loses every delta from the
 *     swap onward, and [Rig.assertGlitchFree] — the same predicate — throws.
 */
class B14ModulePromotionTest {

    private companion object {
        const val CANDIDATE_FQN = "civictech.loader.fixture.flow.FlowPromotionCandidateCell"

        /** Emitted before the swap and fully drained: the incumbent serves these. */
        val BEFORE = listOf("e1", "e2", "e3")

        /** Parked in the gate's buffered window when the swap runs. */
        val IN_FLIGHT = listOf("e4", "e5")

        /** Emitted after the swap has completed. */
        val AFTER = listOf("e6", "e7")
    }

    /**
     * The gate's data face as seen through the host queue — calls on this proxy
     * are enqueued on the host rather than delivered synchronously, so the
     * pre-swap and post-swap traffic crosses a real host hop.
     */
    interface GateProxy {
        val dataInlet: Use<SetOps<String>>
    }

    /** The live downstream subscriber: records every delta it observes, in order. */
    class ViewCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val observed = mutableListOf<SetDelta<String>>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<SetDelta<String>>>())

        init {
            inlet.serve(object : Propagate<SetDelta<String>> {
                override fun propagate(value: SetDelta<String>) {
                    observed += value
                }
            })
        }
    }

    /**
     * The B14 rig. [gateInTrafficPath] is the one knob: `true` is the real
     * arrangement (the promotion drives the very gate the traffic crosses),
     * `false` the control (the promotion drives a decoy, so nothing discharges
     * the buffered window).
     */
    private class Rig(
        val handle: ModuleHandle,
        gateInTrafficPath: Boolean = true,
    ) {
        val controller = SimulationController(seed = 14)
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

        val logicalId: UUID = UUID.randomUUID()
        val incumbent = SetCell<String>(CellRef(logicalId, instanceId = 0))

        /** The gate the traffic actually crosses. */
        val trafficGate = TrafficLightCell.create<SetOps<String>>()

        /** The gate handed to [Promotion.promote] — the traffic one, or a decoy. */
        val promotionGate = if (gateInTrafficPath) trafficGate else TrafficLightCell.create<SetOps<String>>()

        val view = ViewCell()

        /**
         * The module-supplied candidate: resolved through the module's declared
         * `CellDescriptor` table and constructed by that module's own classloader
         * `[JAR1-SPAWN-01]`.
         */
        val candidate: Cell = handle.cellFactory(CANDIDATE_FQN).create(CellRef(logicalId, instanceId = 1))

        /** Every element handed to the graph, in the order downstream must observe it. */
        private val emitted = mutableListOf<String>()

        private val api: SetOps<String>

        init {
            listOf(trafficGate, promotionGate, incumbent, view)
                .distinct()
                .forEach { host.managementInlet.call.spawn(it) }
            // Shadow-spawn the candidate exactly as kernel's ShadowPromotionTest
            // does: it sees the same live inputs before the swap, effects
            // suppressed. This is also the publish a ModuleLoader.track tracker
            // attributes — hence the tracker must already be attached.
            Shadow.spawn(host, candidate)
            controller.runToIdle()

            trafficGate.dataOutlet.subscribe(incumbent.inlet)
            @Suppress("UNCHECKED_CAST")
            trafficGate.dataOutlet.subscribe(PortRegistry.of(candidate)["inlet"] as Use<SetOps<String>>)
            incumbent.outlet.subscribe(view.inlet)
            trafficGate.controlInlet.call.setGreen() // traffic lights start red
            promotionGate.controlInlet.call.setGreen()

            api = (HostedCellProxy.create(trafficGate.ref, host, GateProxy::class.java) as GateProxy).dataInlet.call
        }

        /** Enqueue an add per element on the host queue; delivered on the next [drain]. */
        fun emit(elements: List<String>) {
            elements.forEach {
                emitted += it
                api.add(it)
            }
        }

        /**
         * Red the traffic gate and park [elements] in its buffered window — the
         * state `Promotion.promote`'s PREPARE establishes, entered one step early
         * so a single-threaded test can observe the window rather than race it.
         */
        fun parkAcrossSwap(elements: List<String>) {
            trafficGate.controlInlet.call.setRed()
            elements.forEach {
                emitted += it
                trafficGate.dataInlet.call.add(it)
            }
        }

        fun drain() {
            controller.runToIdle()
        }

        fun promote() {
            Promotion.promote(
                host, promotionGate, incumbent, candidate, "outlet",
                downstream = listOf(view.inlet),
            )
        }

        /**
         * Glitch-freedom across the swap, asserted rather than assumed:
         * downstream must have observed **exactly one** add-delta per element
         * handed to the graph, in that order, carrying no removals — nothing
         * torn (each delta names one element), nothing duplicated, nothing lost.
         *
         * The element key is compared case-insensitively on purpose. The
         * candidate's entire observable difference is that it upper-cases
         * (`FlowPromotionCandidateCell.normalize`), so *case* says which instance
         * served a wave while *identity* says nothing was lost. That the case
         * pattern really does change is a separate claim, asserted by
         * [assertSwapObserved], so this predicate cannot quietly absorb "the swap
         * never happened".
         */
        fun assertGlitchFree() {
            withClue("one add-delta per element, in order — nothing torn, duplicated or missing") {
                view.observed.map { it.adds.keys.singleOrNull()?.lowercase() } shouldBe emitted.toList()
            }
            withClue("a promotion swap must not manufacture removals downstream") {
                view.observed.all { it.dels.isEmpty() } shouldBe true
            }
        }

        /**
         * The swap is visible in the stream: a non-empty run of incumbent waves
         * (lower case) followed by a non-empty run of module-candidate waves
         * (upper case), never interleaved.
         */
        fun assertSwapObserved() {
            val servedByCandidate = view.observed.map { delta ->
                val key = delta.adds.keys.single()
                key == key.uppercase()
            }
            withClue("expected incumbent waves then candidate waves, never interleaved: $servedByCandidate") {
                servedByCandidate shouldBe servedByCandidate.sorted()
                servedByCandidate shouldContain false // the incumbent served part of the stream
                servedByCandidate shouldContain true // and the module candidate served the rest
            }
        }

        /** The elements downstream actually observed, case-folded back to what was emitted. */
        fun observedElements(): List<String> =
            view.observed.mapNotNull { it.adds.keys.singleOrNull()?.lowercase() }
    }

    /**
     * Load `fixture.flow`, hand [body] the loader and the handle, and clean up the
     * process-global registries afterwards whether or not [body] unloaded.
     *
     * Not [FixtureJars.withLoadedModule], because these tests call
     * [ModuleLoader.unload] themselves and that bracket would unregister twice.
     */
    private fun withFlowModule(body: (ModuleLoader, ModuleHandle) -> Unit) {
        val loader = FixtureJars.loaderAccepting(FixtureJars.flow)
        val handle = loader.load(FixtureJars.flow)
        try {
            body(loader, handle)
        } finally {
            if (handle.state == ModuleState.REGISTERED) {
                ModuleRegistration.unregister(handle.id)
                handle.classLoader.close()
            }
        }
    }

    // ------------------------------------------------------------------
    // B14 — [JAR1-SPAWN-04]: the promotion itself
    // ------------------------------------------------------------------

    @Test
    fun `a module-supplied candidate promotes over a host-classpath incumbent, glitch-free`() {
        withFlowModule { _, handle ->
            val rig = Rig(handle)

            withClue("[JAR1-SPAWN-01]: the candidate must be the MODULE's class, not a host-loaded twin") {
                (rig.candidate.javaClass.classLoader === handle.classLoader) shouldBe true
                rig.candidate.javaClass.name shouldBe CANDIDATE_FQN
            }

            rig.emit(BEFORE)
            rig.drain()

            rig.parkAcrossSwap(IN_FLIGHT)
            rig.promote() // PRECHECK -> PREPARE -> COMMIT -> RETIRE, no throw
            rig.drain()

            rig.emit(AFTER)
            rig.drain()

            withClue("RETIRE despawned the incumbent") {
                rig.registry.locate(rig.incumbent.ref) shouldBe null
            }
            withClue("the promoted candidate is live on the host") {
                rig.registry.locate(rig.candidate.ref).shouldNotBeNull()
            }
            rig.assertGlitchFree()
            rig.assertSwapObserved()
            withClue("the parked window was replayed to the candidate, not to the retired incumbent") {
                rig.observedElements() shouldBe (BEFORE + IN_FLIGHT + AFTER)
            }
        }
    }

    /**
     * Non-vacuousness control for [Rig.assertGlitchFree] — the same predicate,
     * observed FAILING when the promotion is not the thing driving the gate the
     * traffic crosses.
     *
     * The sequence is byte-for-byte the arm above; only the gate handed to
     * `Promotion.promote` differs. PREPARE then reds a membrane no traffic passes
     * and `setGreen` discharges an empty buffer, so the real gate stays red and
     * the parked window — and everything after it — never reaches downstream.
     */
    @Test
    fun `control - the promotion's own gate is what discharges the buffered window`() {
        withFlowModule { _, handle ->
            val rig = Rig(handle, gateInTrafficPath = false)

            (rig.candidate.javaClass.classLoader === handle.classLoader) shouldBe true

            rig.emit(BEFORE)
            rig.drain()
            rig.parkAcrossSwap(IN_FLIGHT)
            rig.promote()
            rig.drain()
            rig.emit(AFTER)
            rig.drain()

            val seen = rig.observedElements()
            withClue("the control loses the parked window and everything after it: $seen") {
                seen shouldBe BEFORE
                (IN_FLIGHT + AFTER).forEach { seen shouldNotContain it }
            }
            withClue("so the glitch-free predicate the promoted arm relies on demonstrably fails here") {
                shouldThrow<AssertionError> { rig.assertGlitchFree() }
            }
        }
    }

    // ------------------------------------------------------------------
    // B14 — [JAR1-UNL-02]: unload refused while the promoted candidate is live
    // ------------------------------------------------------------------

    @Test
    fun `unload is refused while the promoted module candidate is live, and succeeds after despawn`() {
        withFlowModule { loader, handle ->
            val registry = LocationRegistry()
            val controller = SimulationController(seed = 14)
            val host = ManagedHost(scheduler = controller.scheduler(), registry = registry)

            // Attribution is attach-time-forward only (ModuleLoader.track's KDoc),
            // so the tracker attaches BEFORE the candidate is spawned. Attaching
            // afterwards would report a live count of zero and the refusal below
            // would never fire — a vacuous test that looks like a passing one.
            val tracker = loader.track(registry)
            try {
                val logicalId = UUID.randomUUID()
                val incumbent = SetCell<String>(CellRef(logicalId, instanceId = 0))
                val gate = TrafficLightCell.create<SetOps<String>>()
                val view = ViewCell()
                val candidate = handle.cellFactory(CANDIDATE_FQN)
                    .create(CellRef(logicalId, instanceId = 1))

                (candidate.javaClass.classLoader === handle.classLoader) shouldBe true

                listOf(gate, incumbent, view).forEach { host.managementInlet.call.spawn(it) }
                Shadow.spawn(host, candidate)
                controller.runToIdle()

                gate.dataOutlet.subscribe(incumbent.inlet)
                @Suppress("UNCHECKED_CAST")
                gate.dataOutlet.subscribe(PortRegistry.of(candidate)["inlet"] as Use<SetOps<String>>)
                incumbent.outlet.subscribe(view.inlet)
                gate.controlInlet.call.setGreen()

                Promotion.promote(
                    host, gate, incumbent, candidate, "outlet",
                    downstream = listOf(view.inlet),
                )
                controller.runToIdle()

                withClue("the promoted candidate is the module's one live cell") {
                    handle.liveInstances shouldBe 1
                }

                val candidateClass = handle.classLoader.loadClass(CANDIDATE_FQN)
                ContractRegistry.cellDescriptor(candidateClass).shouldNotBeNull()

                val refusal = shouldThrow<ModuleUnloadRefusedException> { loader.unload(handle) }
                refusal.id shouldBe handle.id
                refusal.liveInstances shouldBe 1
                refusal.message.shouldNotBeNull() shouldContain "1 cell(s)"

                withClue("[JAR1-UNL-02]: a refused unload leaves the module exactly as it was") {
                    handle.state shouldBe ModuleState.REGISTERED
                    loader.loaded() shouldContain handle
                    ContractRegistry.cellDescriptor(candidateClass) shouldNotBe null
                }
                withClue("and the promoted candidate keeps serving traffic after the refusal") {
                    val before = view.observed.size
                    gate.dataInlet.call.add("after-refusal")
                    controller.runToIdle()
                    view.observed.size shouldBe before + 1
                    view.observed.last().adds.keys.single() shouldBe "AFTER-REFUSAL"
                }

                host.managementInlet.call.despawn(candidate.ref)
                controller.runToIdle()
                handle.liveInstances shouldBe 0

                loader.unload(handle)
                handle.state shouldBe ModuleState.CLOSED
                loader.loaded() shouldNotContain handle
            } finally {
                tracker.close()
            }
        }
    }
}
