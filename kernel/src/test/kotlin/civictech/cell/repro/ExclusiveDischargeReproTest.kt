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
 * **C-11 reach: all four reproductions pinned; no standing expected failures remain.**
 *
 * The adjudication these four reproductions consume is
 * `doc/evidence-lane-findings.md` -> "C-11 — shadow suppression drops exclusives",
 * recorded by `computenet-umx.1.1` and **re-verified against the code by this task** at base
 * commit `0169fd7`, not inherited:
 *
 * - the discharging suppression proxy is **landed** — `Shadow.spawn`/`suppress`/
 *   `suppressionProxy` (`evolve/Evolution.kt:62,69,84,88`) and `Proxy.discharging`
 *   (`proxy/Proxy.kt:101`). BS-7 and BS-12 pin it and **pass**;
 * - residual 1 (BS-8) **was** real and is now fixed by `computenet-ulss` (93 I-6 / I-8):
 *   `Proxy.discharge` had `Owned`/`Leased`/`Map`/`Iterable`/`Array` branches and no case for
 *   an arbitrary payload object, and `ContractProcessor.carriesExclusive` recursed through
 *   type *arguments* only, so a field-nested exclusive was not even marked. Both halves
 *   widened; BS-8 keeps its body, loses its `@ExpectedFailure`, and is the acceptance test;
 * - residual 2 (BS-9) **was** real and is now fixed by `computenet-3jv2` (93 I-17 /
 *   G-32): `Shadow.spawn` cut suppression at `cell is Effectful` and nothing in `kernel/`
 *   read the `ContractDescriptor.effect` bit the decision names as the boundary. Suppression
 *   now serves every effect-carrying contract, with the cell marker kept as the coarse
 *   fallback; BS-9 keeps its body, loses its `@ExpectedFailure`, and is the acceptance test.
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
    // BS-8 ([CHA2-21]) — nested-exclusive escape. FIXED by computenet-ulss;
    // this is now the acceptance test for that fix, not an expected failure.
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
     * **`computenet-ulss` fixed the divergence and this body is unchanged**: the
     * `@ExpectedFailure(signature = "CHA2-BS-8")` annotation is gone (`[CHA2-44]` — an
     * expected failure that starts passing must be un-annotated, not deleted), and the same
     * assertions now pin the fix. Its method name still says "escapes" because that is the
     * reproduction id BS-8 and the findings entry refer to; what it asserts is that the
     * escape no longer happens.
     *
     * The escape had two cooperating layers and this test observes the composite outcome,
     * which is the one that matters: **the payload must not stay live**. The mechanism
     * assertions that were always true are outside the signature block; the two that were
     * the divergence are inside it, and [withSignature] stays so a regression still fails
     * with the recorded token rather than an anonymous assertion.
     */
    @Test
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
            // The behavioural half first, deliberately: it is the defect, and it only passes
            // once *both* layers are fixed. Widening only the KSP scan marks pushNested
            // exclusive but hands the envelope to a `discharge` with no branch for it;
            // widening only `discharge` never gets called because the method is not marked.
            // So this assertion is the acceptance test for computenet-ulss, and the
            // structural one below is its explanation.
            assertThrows<IllegalStateException> { envelope.payload.take() }

            // The KSP half (`ContractProcessor.carriesExclusive`, widened to walk a payload
            // class's declared properties): the method carrying a field-nested exclusive is
            // marked exclusive, which is what makes the discharging proxy walk its args.
            descriptor.methods.single { it.name == "pushNested" }.exclusive shouldBe true
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
