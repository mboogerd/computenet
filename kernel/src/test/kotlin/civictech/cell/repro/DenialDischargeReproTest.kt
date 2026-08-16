package civictech.cell.repro

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.Propagate
import civictech.cell.Redacted
import civictech.cell.host.DeadLetter
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.IntegrityPolicy
import civictech.cell.membrane.SignedDelta
import civictech.cell.port.Admit
import civictech.cell.port.FanInlet
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.input
import civictech.cell.port.registerPort
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * **BS-11 ([CHA2-25]) and BS-10 ([CHA2-23] as adjudicated) — "denied ⇒
 * discharged" pinned at every kernel drop point covered by this task.**
 *
 * The routing record for both lives in `doc/evidence-lane-findings.md`'s C-11
 * entry (recorded by `computenet-umx.1.1`, residual 3): the `BoundaryPolicy`
 * silent drop the KHYG epic originally filed BS-10 to reproduce as a failure
 * was fixed by SEC1 (`computenet-usd.2`, commit `ab69412`, extended by
 * `1b9653b` and this base commit `46ed020`). Per the feature's own
 * no-manufactured-failure principle (`doc/kernel-lane-findings.md`'s sibling
 * discipline, AGENTS.md), a fixed defect is reproduced **unweakened with PASS
 * as the accepted outcome** — never annotated `@ExpectedFailure`, and never
 * softened to manufacture a failure that matches the feature's 2026-08-08
 * prose. If a future change reopens the drop, this suite fails honestly and
 * the annotation is added then, not now.
 */
class DenialDischargeReproTest {

    // ------------------------------------------------------------------
    // BS-11 ([CHA2-25]): the sanitizing baseline that predates SEC1's fix —
    // the ADMIT tier and the dead-letter capture path both already treated
    // "denied/dropped ⇒ discharged" as the kernel's own standard. This
    // establishes the standard BS-10 below shows a mediated boundary now
    // meets too.
    // ------------------------------------------------------------------

    /** Contract carrying both exclusive kinds in one invocation, deliberately — BS-11 asks for "one invocation carrying Owned/Leased". */
    interface ExclusivePairApi {
        fun accept(owned: Owned<String>, leased: Leased<String>)
    }

    /**
     * `kernel/src/test/kotlin/civictech/cell/port/AdmitDischargeTest.kt`
     * already pins that a dropped `Owned` is discharged
     * (`a dropped Owned-carrying invocation is discharged, not leaked`) and
     * that `unackedDrops` counts a *missing-ack* edge case. What it does
     * **not** cover, and what this test adds rather than duplicates:
     * `Leased` release in the same invocation, and — the literal wording of
     * BS-11 — that "the drop is counted in the tier's own accounting", i.e.
     * `Admit`'s `onDrop` hook (`InletPolicy.kt:73`, `:111`), which
     * `AdmitDischargeTest` never installs.
     */
    @Test
    fun `BS-11 an ADMIT-tier drop discharges the Owned and the Leased it carries, and the tier's own onDrop accounting counts it`() {
        var drops = 0
        val admit = Admit(admits = { false }, onDrop = { drops++ })
        val inlet = FanInlet(ExclusivePairApi::class.java)
        inlet.install(admit)

        var outstanding = 1
        val leased = Leased("leased-secret") { outstanding -= 1 }
        val owned = Owned("owned-secret")

        inlet.call.accept(owned, leased)

        // Discharged, not leaked: a second take()/release() is use-after-move,
        // which only fires if the first discharge really happened.
        assertThrows<IllegalStateException> { owned.take() }
        outstanding shouldBe 0
        assertThrows<IllegalStateException> { leased.release() }

        // The tier's own accounting counted the drop — the part
        // AdmitDischargeTest never exercises.
        drops shouldBe 1
    }

    /** Organelle whose single inlet throws on every delivery, so routing to it always dead-letters. */
    private class ThrowingExclusiveCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet by input<ExclusivePairApi>()

        init {
            inlet.serve(
                object : ExclusivePairApi {
                    override fun accept(owned: Owned<String>, leased: Leased<String>) {
                        throw IllegalStateException("boom")
                    }
                },
            )
        }
    }

    /**
     * The dead-letter capture path (`civictech.cell.host.DeadLetters.sanitizeForDeadLetter`)
     * is the other sanitizer BS-11 names. No existing test in the repository
     * routes a *fault* (as opposed to a boundary denial) carrying a live
     * `Owned`/`Leased` through it — the closest neighbors
     * (`LifecycleAndDeadLetterTest`'s throwing-cell tests,
     * `MediateProxyIntegrityTest`'s BS-5 pair) exercise plain payloads or a
     * boundary-denial capture respectively, not this fault-path capture. This
     * is the genuinely uncovered half of BS-11.
     */
    @Test
    fun `BS-11 a dead-lettered invocation discharges the Owned and the Leased it carries, counted in deadLetterCount`() {
        val controller = SimulationController(seed = 21)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )

        val cell = ThrowingExclusiveCell()
        host.managementInlet.call.spawn(cell)
        controller.runToIdle()

        var outstanding = 1
        val leased = Leased("leased-secret") { outstanding -= 1 }
        val owned = Owned("owned-secret")
        val acceptMethod = ExclusivePairApi::class.java.methods.single { it.name == "accept" }

        // Routed through the ordinary hosted-delivery path (enqueueHostedInvocation
        // -> the attention-staged dispatch -> ManagedHost.deliver), not
        // routerInlet's raw management shortcut: that shortcut (`invocation.invoke()`
        // via `enqueue()`) never builds/attaches a HostedPortInvocation to the
        // resulting dead letter, so it cannot exercise sanitizeForDeadLetter's
        // per-argument capture at all — confirmed empirically, not assumed.
        host.enqueueHostedInvocation(
            HostedPortInvocation(
                cellRef = cell.ref,
                portName = "inlet",
                type = HostedPortInvocation.Type.PORT_API,
                invocation = Invocation.of(acceptMethod, arrayOf(owned, leased)),
            ),
        )
        controller.runToIdle()

        letters.size shouldBe 1
        val letter = letters.single()
        letter.cause!!.message shouldBe "boom"

        // Sanitized, never a live exclusive handle: Owned -> Frozen, Leased -> Redacted.
        val captured = letter.invocation!!.invocation.args
        captured.size shouldBe 2
        captured[0].shouldBeInstanceOf<Frozen<*>>().value shouldBe "owned-secret"
        captured[1].shouldBeInstanceOf<Redacted>()

        // Discharged on the originals, not just copied into the dead letter.
        assertThrows<IllegalStateException> { owned.take() }
        outstanding shouldBe 0
        assertThrows<IllegalStateException> { leased.release() }

        // The drop is counted in this tier's own accounting too.
        host.supervisionAccounting().deadLetters shouldBe 1L
    }

    // ------------------------------------------------------------------
    // BS-10 ([CHA2-23] as adjudicated): the boundary-denial reproduction,
    // written unweakened. PASS is the accepted outcome post-usd.2 — this
    // pins SEC1's fix from CHA2's side.
    // ------------------------------------------------------------------

    private val peer = PeerId("replica-a")

    private fun badlySigned(payload: Any, counter: Long) = SignedDelta(
        payload = payload,
        mintingPeer = peer,
        counter = counter,
        signature = "not-${peer.name}".toByteArray(),
    )

    /** Organelle behind a mediated, signature-required exposure; the Api is untyped so it serves an Owned payload directly. */
    private class ExclusiveInletOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val received = mutableListOf<Any>()
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Any>>())

        init {
            inlet.serve(
                object : Propagate<Any> {
                    override fun propagate(value: Any) {
                        received += value
                    }
                },
            )
        }
    }

    private class BoundaryDenialMembrane(
        val organelle: ExclusiveInletOrganelle = ExclusiveInletOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = mediate(
            "exposedInlet",
            "inlet",
            organelle.inlet,
            BoundaryPolicy(integrity = IntegrityPolicy.RequireSigned),
        )
    }

    /**
     * `kernel/src/test/kotlin/civictech/cell/membrane/MediateProxyIntegrityTest.kt`
     * already asserts this **identical shape** — a hosted, mediated
     * `CompositeCell` boundary whose `BoundaryPolicy` denies an inbound
     * invocation carrying an `Owned` (and, separately, a `Leased`), with the
     * refused payload frozen/released and dead-lettered
     * (`BS-5 an integrity refusal freezes an Owned carried inside the
     * SignedDelta envelope exactly once`, and its `Leased` twin
     * immediately below it). Landed by `ab69412`
     * (`computenet-usd.2`/`computenet-usd.2.1`).
     *
     * This test is CHA2's own pin of that same fact, from the evidence
     * lane's side, rather than a rewrite of SEC1's suite: the two lanes stay
     * independently verifiable. To honor "cite instead of duplicating"
     * ([CHA2-23]'s adjudicated form), it deliberately does **not** re-derive
     * every angle `MediateProxyIntegrityTest` already covers — the `Leased`
     * variant, the unattached-sink variant, and the no-sink-at-all variant
     * all stay cited there, not repeated here. This test carries only the
     * one assertion BS-10 itself names: an `Owned` refused at a hosted,
     * mediated boundary is discharged and dead-lettered, not dropped.
     */
    @Test
    fun `BS-10 a hosted, mediated boundary's BoundaryPolicy denial discharges and dead-letters an inbound Owned`() {
        val controller = SimulationController(seed = 22)
        val host = ManagedHost(scheduler = controller.scheduler())
        val letters = mutableListOf<DeadLetter>()
        host.deadLetterOutlet.subscribe(
            Use.fixed(
                object : Propagate<DeadLetter> {
                    override fun propagate(value: DeadLetter) {
                        letters += value
                    }
                },
                PortRef.generate(),
            ),
        )

        val membrane = BoundaryDenialMembrane()
        host.managementInlet.call.spawn(membrane)
        controller.runToIdle()

        val owned = Owned("owned-secret")
        val propagateMethod = Propagate::class.java.methods.single { it.name == "propagate" }
        propagateMethod.invoke(membrane.exposedInlet.call, badlySigned(owned, counter = 1))
        controller.runToIdle()

        // Never delivered — the whole point of a denial.
        membrane.organelle.received shouldBe emptyList()
        membrane.boundaryDenials["exposedInlet"]!!.denialCount shouldBe 1L

        // Dead-lettered in sanitized (Frozen) form, not as a live Owned.
        letters.size shouldBe 1
        val captured = letters.single().invocation!!.invocation.args
        captured.size shouldBe 1
        captured.single().shouldBeInstanceOf<Frozen<*>>().value shouldBe "owned-secret"

        // Discharged, not dropped: a second take() is use-after-move.
        assertThrows<IllegalStateException> { owned.take() }
    }
}
