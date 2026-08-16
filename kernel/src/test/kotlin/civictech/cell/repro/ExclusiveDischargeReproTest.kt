package civictech.cell.repro

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Leased
import civictech.cell.Owned
import civictech.cell.evolve.Effectful
import civictech.cell.evolve.Shadow
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.port.FanInlet
import civictech.cell.port.registerPort
import civictech.cell.proxy.Proxy
import civictech.nature.ContractRegistry
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/**
 * **C-11 reach: BS-7 and BS-12 pinned, BS-8 and BS-9 standing as expected failures.**
 *
 * The adjudication these four reproductions consume is
 * `doc/evidence-lane-findings.md` -> "C-11 — shadow suppression drops exclusives",
 * recorded by `computenet-umx.1.1` and **re-verified against the code by this task** at base
 * commit `0169fd7`, not inherited:
 *
 * - the discharging suppression proxy is **landed** — `Shadow.spawn`/`suppress`/
 *   `suppressionProxy` (`evolve/Evolution.kt:62,69,84,88`) and `Proxy.discharging`
 *   (`proxy/Proxy.kt:101`). BS-7 and BS-12 pin it and **pass**;
 * - residual 1 (BS-8) is still real: `Proxy.discharge`'s `when` (`proxy/Proxy.kt:123-134`)
 *   has `Owned`/`Leased`/`Map`/`Iterable`/`Array` branches and no case for an arbitrary
 *   payload object; `ContractProcessor.carriesExclusive` (`ContractProcessor.kt:70-73`)
 *   recurses through type *arguments* only, so a field-nested exclusive is not even marked;
 * - residual 2 (BS-9) is still real: `Evolution.kt:64` is literally
 *   `if (cell is Effectful) suppress(cell)`, and nothing in `kernel/` reads the
 *   `ContractDescriptor.effect` bit that G-32/93 I-17 decided should be the cut.
 *
 * ## Detection, and the `[CHA2-26]` deviation
 *
 * `[CHA2-26]` asks for detection "through the rig's own check" — CHA1's exclusive-payload
 * accounting (`[CHA1-53]`). That rig does not exist on `main` (no `civictech.testkit.dst`),
 * as the findings entry's "CHA1's rig does not exist" section adjudicates, so every
 * assertion here observes the payload directly instead: a second `take()`/`release()`
 * throwing `IllegalStateException` is the evidence that the first discharge really happened,
 * and a lease's own `returnToPool` callback counts releases. Same weak form
 * `computenet-umx.1.5` used. When the rig lands it should adopt these tests rather than
 * re-derive them.
 *
 * ## Why the two failing tests are annotated and not softened
 *
 * `@ExpectedFailure` (`computenet-umx.1.2`) runs the body on every build and **fails the
 * build if it passes** (`[CHA2-44]`). So an annotation here is a falsifiable claim that the
 * divergence is real today, not a way to keep a red test quiet — and, in the other
 * direction, nothing here is weakened to manufacture a failure the plan predicted (BS-13,
 * the discipline `computenet-umx.1.5` applied to BS-10).
 */
class ExclusiveDischargeReproTest {

    private companion object {
        const val BS8_NESTED_EXCLUSIVE_ESCAPES = "CHA2-BS-8"
        const val BS9_UNSUPPRESSED_NON_EFFECTFUL_SHADOW = "CHA2-BS-9"
        const val FINDINGS = "doc/evidence-lane-findings.md#c-11--shadow-suppression-drops-exclusives"
    }

    // ------------------------------------------------------------------
    // BS-7 ([CHA2-20], the C-11 half of [CHA2-02]) — the landed core, pinned.
    // ------------------------------------------------------------------

    private class DirectExclusiveSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful {
        val ownedInlet = registerPort("ownedInlet", FanInlet.create<ReproOwnedPush>())
        val leasedInlet = registerPort("leasedInlet", FanInlet.create<ReproLeasedPush>())

        /** Incremented only if the cell's *own* handler runs — shadow suppression must keep this at 0. */
        var effects = 0

        init {
            ownedInlet.serve(
                object : ReproOwnedPush {
                    override fun push(value: Owned<String>) {
                        value.take()
                        effects++
                    }
                },
            )
            leasedInlet.serve(
                object : ReproLeasedPush {
                    override fun push(value: Leased<String>) {
                        value.release()
                        effects++
                    }
                },
            )
        }
    }

    /**
     * The landed exit test for this behaviour is
     * `kernel/src/test/kotlin/civictech/cell/evolve/ShadowOwnershipTest.kt` (W1.2), and this
     * is deliberately **not** a rewrite of it: it is the evidence lane's own pin of the same
     * fact, under its own fixtures, so the fixing lane's suite and the reproduction suite
     * stay independently verifiable — the citation discipline `computenet-umx.1.5` applied
     * to `MediateProxyIntegrityTest`. What it adds beyond the exit test is the *exactly
     * once* half stated as such: the lease's `returnToPool` counts one release and no more,
     * and the second `take()`/`release()` is what proves the first one happened.
     */
    @Test
    fun `BS-7 a shadowed Effectful cell discharges Owned and Leased exactly once and never runs its own handler`() {
        val controller = SimulationController(seed = 31)
        val host = ManagedHost(scheduler = controller.scheduler())
        val sink = DirectExclusiveSink()

        Shadow.spawn(host, sink)
        controller.runToIdle()

        var releases = 0
        val owned = Owned("owned-payload")
        val leased = Leased("leased-payload") { releases++ }

        sink.ownedInlet.call.push(owned)
        sink.leasedInlet.call.push(leased)

        // The suppressed sink's own effect never ran.
        sink.effects shouldBe 0

        // ...yet both obligations were discharged, exactly once each.
        releases shouldBe 1
        assertThrows<IllegalStateException> { owned.take() }
        assertThrows<IllegalStateException> { leased.release() }
    }

    // ------------------------------------------------------------------
    // BS-8 ([CHA2-21]) — nested-exclusive escape. EXPECTED FAILURE.
    // Owner: computenet-ulss.
    // ------------------------------------------------------------------

    private class NestedExclusiveSink(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, Effectful {
        val inlet = registerPort("inlet", FanInlet.create<ReproNestedExclusivePush>())
        var effects = 0

        init {
            inlet.serve(
                object : ReproNestedExclusivePush {
                    override fun pushDirect(value: Owned<String>) {
                        value.take()
                        effects++
                    }

                    override fun pushNested(envelope: OwnedEnvelope) {
                        envelope.payload.take()
                        effects++
                    }
                },
            )
        }
    }

    /**
     * KSP accepted the nested-exclusive contract shape (feature §9 risk 6 did not
     * materialize — no `DISPUTES.md` fallback needed), so the reproduction is written as a
     * real behavioural test rather than a filed dispute.
     *
     * The escape has two cooperating layers and this test observes the composite outcome,
     * which is the one that matters: **the payload stays live**. The mechanism assertions
     * that stay true after a fix are outside the signature block; the two that *are* the
     * divergence are inside it.
     */
    @Test
    @ExpectedFailure(
        signature = BS8_NESTED_EXCLUSIVE_ESCAPES,
        reason = "an Owned nested in a plain data-class parameter crosses a shadow-suppressed " +
            "discharging proxy undischarged: carriesExclusive walks type arguments only, and " +
            "Proxy.discharge has no branch for an arbitrary payload object",
        owner = "computenet-ulss",
        filedAs = FINDINGS,
    )
    fun `BS-8 an Owned nested in a data-class parameter escapes a shadow-suppressed discharging proxy`() {
        val controller = SimulationController(seed = 32)
        val host = ManagedHost(scheduler = controller.scheduler())
        val sink = NestedExclusiveSink()

        Shadow.spawn(host, sink)
        controller.runToIdle()

        // Mechanism, not divergence: the contract has a directly-exclusive method, so
        // suppressionProxy (Evolution.kt:88-93) selected Proxy.discharging for the whole
        // contract. The nested payload below therefore crosses a *discharging* sink — the
        // literal shape BS-8 names — and not a plain NoOp that was never going to discharge.
        val descriptor = requireNotNull(ContractRegistry.descriptor(ReproNestedExclusivePush::class.java))
        descriptor.methods.single { it.name == "pushDirect" }.exclusive shouldBe true

        val envelope = OwnedEnvelope("nested", Owned("owned-payload"))
        sink.inlet.call.pushNested(envelope)

        // Also mechanism: suppression is genuinely in place, so the sink's own handler did
        // not run. Without this, "the payload is still live" would have a second, innocent
        // explanation (the cell was never suppressed at all) — that is BS-9's defect, not
        // this one.
        sink.effects shouldBe 0

        withSignature(BS8_NESTED_EXCLUSIVE_ESCAPES) {
            // The KSP half: the method carrying a field-nested exclusive is not marked.
            descriptor.methods.single { it.name == "pushNested" }.exclusive shouldBe true

            // The behavioural half, and the one that actually matters: the suppressed sink
            // believes it discharged, and the Owned is still takeable — leaked, with no
            // dead letter and no accounting anywhere.
            assertThrows<IllegalStateException> { envelope.payload.take() }
        }
    }

    // ------------------------------------------------------------------
    // BS-9 ([CHA2-22]) — unsuppressed non-Effectful shadow. EXPECTED FAILURE.
    // Owner: computenet-3jv2.
    // ------------------------------------------------------------------

    /** Serves an `@Contract(effect = true)` inlet, and deliberately does **not** implement `Effectful`. */
    private class ContractEffectCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<ReproEffectApi>())

        /** Stands in for a world-touching side effect: a write, a notification, an actuator call. */
        val fired = mutableListOf<String>()

        init {
            inlet.serve(
                object : ReproEffectApi {
                    override fun fire(id: String) {
                        fired += id
                    }
                },
            )
        }
    }

    @Test
    @ExpectedFailure(
        signature = BS9_UNSUPPRESSED_NON_EFFECTFUL_SHADOW,
        reason = "Shadow.spawn's guard is `if (cell is Effectful)`, so a non-Effectful cell serving " +
            "an @Contract(effect = true) inlet is shadowed with no suppression at all and acts on " +
            "the world a second time; the decided cut (93 I-17 / G-32) is the contract bit, which " +
            "kernel reads nowhere",
        owner = "computenet-3jv2",
        filedAs = FINDINGS,
    )
    fun `BS-9 a non-Effectful cell serving an effect-carrying contract is shadowed without suppression`() {
        val controller = SimulationController(seed = 33)
        val host = ManagedHost(scheduler = controller.scheduler())
        val cell = ContractEffectCell()

        // Mechanism, not divergence: the contract really is declared effect-carrying, and the
        // generated descriptor really carries the bit. If this ever stops holding, the
        // reproduction has lost its subject and must fail loudly rather than "as expected".
        requireNotNull(ContractRegistry.descriptor(ReproEffectApi::class.java)).effect shouldBe true

        Shadow.spawn(host, cell)
        controller.runToIdle()

        cell.inlet.call.fire("effect-1")

        withSignature(BS9_UNSUPPRESSED_NON_EFFECTFUL_SHADOW) {
            // Shadow mode exists precisely so a candidate does not act on the world twice
            // (Evolution.kt:43-50). Here it acted.
            cell.fired shouldBe emptyList()
        }
    }

    // ------------------------------------------------------------------
    // BS-12 ([CHA2-24]) — missing descriptor is loud, pinned.
    // ------------------------------------------------------------------

    /**
     * `Proxy.discharging` opens with `requireNotNull(ContractRegistry.descriptor(clazz))`
     * (`proxy/Proxy.kt:102-104`). The failure mode this pins out is the tempting one: falling
     * back to `Proxy.noop` (`:96`) when the descriptor is missing, which would turn every
     * exclusive crossing an undescribed contract into a silent leak — the exact class of bug
     * BS-8 above documents, but repo-wide and undetectable.
     *
     * "Names the class" is asserted literally, because a bare `IllegalArgumentException` from
     * inside a suppression proxy is close to undiagnosable in a real host log.
     */
    @Test
    fun `BS-12 a discharging proxy over a contract with no generated descriptor fails loudly and names the class`() {
        ContractRegistry.descriptor(ReproUndescribedApi::class.java) shouldBe null

        val failure = assertThrows<IllegalArgumentException> {
            Proxy.discharging(ReproUndescribedApi::class.java)
        }

        failure.message!! shouldContain ReproUndescribedApi::class.java.name
        failure.message!! shouldContain "discharging proxy requires a generated contract descriptor"
    }
}
