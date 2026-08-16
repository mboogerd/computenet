package civictech.cell.consistency

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.CurrentContext
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.host.CellError
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.DisclosurePolicy
import civictech.cell.membrane.IntegrityPolicy
import civictech.cell.membrane.ProjectionId
import civictech.cell.membrane.ProjectionRegistry
import civictech.cell.membrane.SignedDelta
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.testkit.SimWorld
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * BS-12 / `[SEC1-27]` (`computenet-usd.3.1`, feature `computenet-usd.3`): a
 * mid-wave `BoundaryPolicy` denial reaches the landed I-18 "edge that will not
 * deliver" classification, so a two-edge glitch-free join with one denied arm
 * **reaches completeness** instead of waiting forever.
 *
 * The defect this file guards is a liveness one, and it is invisible to every
 * denial-accounting test that exists: `computenet-usd.1`/`.2` proved a refusal
 * is counted, sanitized and discharged, and a refusal that does all three and
 * then simply `return null`s still starves whatever downstream frontier was
 * waiting on the contribution it dropped. So the load-bearing assertion in
 * every test below is that the **poisoned wave itself releases** — asserted
 * before any later wave is emitted, because a later wave's monotone watermark
 * would retroactively complete it anyway (`GlitchFreeStallTest`'s "a later
 * wave's watermark retroactively completes an earlier wave an edge silently
 * absorbed") and would make the whole file vacuous.
 *
 * Shape, both variants (the M2 fork-join diamond, one arm denied):
 *
 * ```
 *   source ──> "B" mapper ─────────────────────────────> GlitchFreeCell ──> observer
 *          └─> membrane (BoundaryPolicy denies wave 2) ──┘
 * ```
 *
 * Both arms descend from one source outlet, so both carry the same
 * `Timestamp(source, n)`: the join is genuinely waiting on the denied arm for
 * exactly the wave the denial poisoned. Determinism comes from
 * [SimWorld]'s budgeted `runToIdle` — quiescence is asserted under a step
 * budget, never a wall-clock sleep — and every assertion below is a semantic
 * outcome (which waves released, carrying which contributions), never a
 * scheduling order.
 *
 * Rule 4 of the feature is guarded alongside, in both variants: a denial mints
 * no wave and advances none, and leaves source/tag continuity untouched — the
 * observed waves keep one source id and an unbroken counter sequence across
 * the denial, and no `SupervisionPolicy` RESTART fires (`[SEC1-29]`, BS-14,
 * 93 I-28 §6 / G-20).
 */
class BoundaryDenialWaveCompletenessTest {

    private val peer = PeerId("replica-a")

    /** One observation at the far side of the join: the released value and the wave it rode. */
    private data class Obs(val value: String, val ts: Timestamp)

    // ------------------------------------------------------------------
    // Graph pieces
    // ------------------------------------------------------------------

    /** Mints a fresh wave per emission (`originate`), fanning to both arms. */
    private class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())

        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    /** The undenied arm: reactive `Int -> "B:n"`, preserving the incoming wave. */
    private class LabelArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("B:$n") }
        }
    }

    /**
     * The organelle behind the denying membrane: forwards what it is given.
     * Typed `Propagate<Any>` because the integrity variant hands it the
     * *unwrapped* payload of a [SignedDelta] while the disclosure variant hands
     * it an `Int` to label; the contract erases either way.
     */
    private class RelayOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Any>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { value -> outlet.call.propagate("M:$value") }
        }
    }

    /** Seam 3 `PORT_API` **inbound**: the arm crosses a `RequireSigned` boundary. */
    private class SignedArmMembrane(
        val organelle: RelayOrganelle = RelayOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = mediate(
            "exposedInlet",
            "inlet",
            organelle.inlet,
            BoundaryPolicy(integrity = IntegrityPolicy.RequireSigned),
        )
        val exposedOutlet = flatten("exposedOutlet", "outlet", organelle.outlet)
    }

    /** Seam 3 `PORT_API` **outbound**: the arm's emissions cross a projecting boundary. */
    private class ProjectingArmMembrane(
        projection: ProjectionId,
        val organelle: RelayOrganelle = RelayOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = flatten("exposedInlet", "inlet", organelle.inlet)
        val exposedOutlet = mediateOutlet(
            "exposedOutlet",
            "outlet",
            organelle.outlet,
            BoundaryPolicy(disclosure = DisclosurePolicy.Project(projection)),
        )
    }

    /**
     * The fixture: one host, one source, the `B` arm, a denying arm, and the
     * glitch-free join whose completeness is under test.
     *
     * The `B` arm is subscribed **first** on purpose: the source's broadcast is
     * in subscription order, so when the denial fires the join is already
     * holding the poisoned wave, waiting on the denied edge. That is the exact
     * BS-12 situation — a downstream wave waiting on an input a denial removed.
     */
    private class Fixture(seed: Long) {
        val world = SimWorld(seed = seed)
        val obs = mutableListOf<Obs>()
        val violations = mutableListOf<CellError>()
        val source = SourceCell()
        val labelArm = LabelArm()
        val join = GlitchFreeCell(
            @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>),
            mode = GlitchFreeCell.WaveMode.WAIT,
        )

        init {
            join.outlet.subscribe(
                Use.fixed(
                    object : Propagate<String> {
                        override fun propagate(value: String) {
                            obs += Obs(value, CurrentContext.get()!!.timestamp)
                        }
                    },
                    PortRef.generate(),
                ),
            )
            join.errorOutlet.subscribe(
                Use.fixed(
                    object : Propagate<CellError> {
                        override fun propagate(value: CellError) {
                            violations += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
        }

        /** Links [outlet] into the join, asserting the edge really opened. */
        fun joinFrom(outlet: FanOutlet<Propagate<String>>) {
            @Suppress("UNCHECKED_CAST")
            (outlet.linkTo(join.inlet as LinkFrom<Propagate<String>>) is LinkResult.Connected).shouldBeTrue()
        }

        /** Waves released at the observer, in arrival order, grouped by wave counter. */
        fun byWave(): Map<Long, List<String>> =
            obs.groupBy({ it.ts.counter }, { it.value })
    }

    // ------------------------------------------------------------------
    // BS-12, integrity variant ([SEC1-27]): a RequireSigned arm refuses
    // mid-wave and the join still completes.
    // ------------------------------------------------------------------

    @Test
    fun `BS-12 a two-edge join whose RequireSigned arm refuses mid-wave reaches completeness`() {
        val fixture = Fixture(seed = 31)
        val membrane = SignedArmMembrane()

        fixture.world.host.managementInlet.call.spawn(fixture.source)
        fixture.world.host.managementInlet.call.spawn(fixture.labelArm)
        fixture.world.host.managementInlet.call.spawn(membrane)
        fixture.world.host.managementInlet.call.spawn(fixture.join)
        fixture.world.runToIdle()

        // Arm B first (see [Fixture]); the signing arm second, so the join is
        // already holding the wave when the boundary refuses.
        fixture.source.outlet.subscribe(Use.fixed(fixture.labelArm.inlet.call, fixture.labelArm.inlet.ref))
        val signingArm = signingArm(membrane, poisonedWave = 2)
        fixture.source.outlet.subscribe(Use.fixed(signingArm, PortRef.generate()))
        fixture.joinFrom(fixture.labelArm.outlet)
        fixture.joinFrom(membrane.exposedOutlet)
        fixture.world.runToIdle()

        val sink = membrane.boundaryDenials["exposedInlet"]!!

        // Wave 1 crosses both arms untouched: the join is really joining.
        fixture.source.emit(1)
        fixture.world.runToIdle()
        fixture.byWave()[1L]!!.toSet() shouldBe setOf("B:1", "M:1")
        sink.denialCount shouldBe 0L
        fixture.violations.shouldBeEmpty()

        // Wave 2's delta arrives on the mediated arm unsigned and is refused
        // mid-wave. THE assertion: the wave the join was waiting on releases,
        // carrying the surviving arm's contribution alone — no later wave has
        // been emitted, so nothing but the denial's own classification can
        // have completed it.
        fixture.source.emit(2)
        fixture.world.runToIdle()

        fixture.byWave()[2L] shouldBe listOf("B:2")
        sink.denialCount shouldBe 1L

        // The rescue is recorded honestly, naming the wave it advanced past.
        val sourceId = fixture.obs.first().ts.sourceId
        val violation = fixture.violations.single()
        violation.cause.shouldBeInstanceOf<GlitchViolation>()
        violation.cause.message!! shouldContain "DEAD_LETTERED"
        violation.cause.message!! shouldContain Timestamp(sourceId, 2).toString()

        // [SEC1-29] / BS-14: a denial is not a fault.
        fixture.world.host.supervisionAccounting().restarts shouldBe 0L

        // The edge stays OPEN — RE-SCOPE advanced past one wave, it did not
        // close the arm. A subsequent signed delta joins normally, and the
        // source's counter sequence is unbroken across the denial: the denial
        // minted no wave and re-based no source (I-28 §6, G-20).
        fixture.source.emit(3)
        fixture.world.runToIdle()

        fixture.byWave()[3L]!!.toSet() shouldBe setOf("B:3", "M:3")
        fixture.obs.map { it.ts.counter }.distinct() shouldBe listOf(1L, 2L, 3L)
        fixture.obs.map { it.ts.sourceId }.distinct() shouldBe listOf(sourceId)
        sink.denialCount shouldBe 1L
        fixture.violations.size shouldBe 1
        fixture.world.host.supervisionAccounting().restarts shouldBe 0L
    }

    /**
     * The signing arm as the source sees it: every wave but [poisonedWave] is
     * enveloped as a valid [SignedDelta] with a strictly increasing counter;
     * [poisonedWave] arrives as a bare payload, which `RequireSigned` refuses
     * as `UNSIGNED`.
     */
    private fun signingArm(membrane: SignedArmMembrane, poisonedWave: Int): Propagate<Int> {
        @Suppress("UNCHECKED_CAST")
        val inbound = membrane.exposedInlet.call as Propagate<Any>
        return object : Propagate<Int> {
            override fun propagate(value: Int) {
                if (value == poisonedWave) {
                    inbound.propagate("$value-unsigned")
                } else {
                    inbound.propagate(
                        SignedDelta(
                            payload = value,
                            mintingPeer = peer,
                            counter = value.toLong(),
                            signature = peer.name.toByteArray(),
                        ),
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // BS-12, disclosure variant: the denying arm is an exposed outlet whose
    // projection suppresses one emission.
    // ------------------------------------------------------------------

    @Test
    fun `BS-12 a two-edge join whose projecting arm suppresses an emission mid-wave reaches completeness`() {
        val projection = ProjectionId("boundary-denial-wave-completeness-${UUID.randomUUID()}")
        // Projects away exactly the poisoned wave's delta; everything else
        // crosses unchanged, so the arm stays a live, delivering edge.
        ProjectionRegistry.register(projection) { delta -> if (delta == "M:2") null else delta }

        val fixture = Fixture(seed = 32)
        val membrane = ProjectingArmMembrane(projection)

        fixture.world.host.managementInlet.call.spawn(fixture.source)
        fixture.world.host.managementInlet.call.spawn(fixture.labelArm)
        fixture.world.host.managementInlet.call.spawn(membrane)
        fixture.world.host.managementInlet.call.spawn(fixture.join)
        fixture.world.runToIdle()

        fixture.source.outlet.subscribe(Use.fixed(fixture.labelArm.inlet.call, fixture.labelArm.inlet.ref))
        @Suppress("UNCHECKED_CAST")
        val inbound = membrane.exposedInlet.call as Propagate<Int>
        fixture.source.outlet.subscribe(Use.fixed(inbound, PortRef.generate()))
        fixture.joinFrom(fixture.labelArm.outlet)
        fixture.joinFrom(membrane.exposedOutlet)
        fixture.world.runToIdle()

        val sink = membrane.boundaryDenials["exposedOutlet"]!!

        fixture.source.emit(1)
        fixture.world.runToIdle()
        fixture.byWave()[1L]!!.toSet() shouldBe setOf("B:1", "M:1")
        sink.denialCount shouldBe 0L
        fixture.violations.shouldBeEmpty()

        // Wave 2 is projected away at the boundary — the emission "did not
        // happen at all" for the subscriber. Same load-bearing assertion:
        // the wave releases with the surviving arm alone, before any later
        // wave exists to retroactively complete it.
        fixture.source.emit(2)
        fixture.world.runToIdle()

        fixture.byWave()[2L] shouldBe listOf("B:2")
        sink.denialCount shouldBe 1L

        val sourceId = fixture.obs.first().ts.sourceId
        val violation = fixture.violations.single()
        violation.cause.shouldBeInstanceOf<GlitchViolation>()
        violation.cause.message!! shouldContain "DEAD_LETTERED"
        violation.cause.message!! shouldContain Timestamp(sourceId, 2).toString()

        fixture.world.host.supervisionAccounting().restarts shouldBe 0L

        fixture.source.emit(3)
        fixture.world.runToIdle()

        fixture.byWave()[3L]!!.toSet() shouldBe setOf("B:3", "M:3")
        fixture.obs.map { it.ts.counter }.distinct() shouldBe listOf(1L, 2L, 3L)
        fixture.obs.map { it.ts.sourceId }.distinct() shouldBe listOf(sourceId)
        sink.denialCount shouldBe 1L
        fixture.violations.size shouldBe 1
        fixture.world.host.supervisionAccounting().restarts shouldBe 0L
    }
}
