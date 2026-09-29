package civictech.cell.consistency

import civictech.cell.BoundarySeam
import civictech.cell.BudgetOutcome
import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.CurrentContext
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.RecordingLedger
import civictech.cell.Timestamp
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.host.CellError
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.AuthLevel
import civictech.cell.link.CurrentPeer
import civictech.cell.link.LinkResult
import civictech.cell.link.PeerId
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.onEach
import civictech.cell.port.FanInlet
import civictech.cell.port.FanOutlet
import civictech.cell.port.LinkFrom
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.port.registerPort
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BS-05, re-scoped (`computenet-2zasa.1`, feature `computenet-2zasa`, decision
 * `2zasa-D1`; `[ECO1-DEN-07]`, `[ECO1-DEN-09]`, `[ECO1-DEN-10]`): a remote
 * principal's budget running dry **partway through a wave** a downstream
 * glitch-free join is holding never starves that join. The wave completes
 * carrying BOTH arms' contributions, with no rescue, no `GlitchViolation` and
 * no RESTART; the link the principal formed while funded keeps delivering;
 * and only NEW claims by that principal are refused.
 *
 * **Why this is the stronger outcome, not the terminal-stall one BS-05 was
 * first written against.** BS-05's original form — "a budget-refused Link
 * re-authorization on an edge the join is waiting on is classified as a
 * terminal stall" — has no mechanism on this tree (observed at `195d92ad`):
 *
 * - The `[SEC1-27]` terminal-stall rescue is `MediateProxy`'s **delta**
 *   refusal path only: `MediateProxy.invoke` → `onRefused` →
 *   `CompositeCell.stallDeniedEdges` (bound in `mediate` as
 *   `onRefused = { context -> stallDeniedEdges(exposedPorts(), context) }`),
 *   plus `mediateOutlet`'s disclosure suppression. `BoundaryDenialWaveCompletenessTest`
 *   pins it. No `LinkResult.Rejected` reaches it.
 * - The three landed budget sites (ECO1 F3, `computenet-5o1rf`) are all
 *   metadata-plane: `ManagedHost.internalApi.spawn` (HOST_ADMISSION),
 *   `CompositeCell.asProtocolFilter`'s Attention arm (PROTOCOL_AUTHORITY, only
 *   `id == Protocols.Attention && message is Attention` under a non-null
 *   `CurrentPeer.stamp()`), and `CompositeCell.linkBudgetPolicy`
 *   (LINK_AUTHORITY, a `LinkPolicy` in `linking.policies`, reached only by
 *   `LinkSupport.reject`/`reauthorize`). None runs on a delta delivery.
 * - A held link is never re-evaluated (`66m-D7`, `[ECO1-DEN-09]`): nothing
 *   walks `linking.policies` after `register` except
 *   `Evolution.reauthorizeRebinds` (promotion PRECHECK — a refusal throws
 *   `PromotionAborted` and the incumbent keeps serving, and it is uncharged
 *   until `computenet-8aboz`). Reconnect re-fires `LinkSupport.fireLinked`
 *   (`Peering.chainOnReannounce`) with no policy walk.
 *
 * So `[ECO1-DEN-07]`'s antecedent ("a denial on an edge a join is waiting on")
 * cannot arise from a budget refusal, and this file asserts what does hold:
 * the poisoned wave completes with both contributions. The premise that no
 * `BudgetClaim` is constructed on a delta path is pinned structurally by
 * `civictech.cell.architecture.BudgetDenialRoutingRatchetTest` (sibling task
 * `computenet-2zasa.4`); a charged promotion-rebind refusal, and whatever
 * obligation it creates, is `computenet-8aboz`'s.
 *
 * **Where the charged ports are (which `asProtocolFilter` installation is
 * real).** `asProtocolFilter` is installed only by `mediateOutlet` — `mediate`
 * (inlets) installs no protocol filter — and `linkBudgetPolicy` only by
 * `mediate`. So the membrane arm exposes BOTH: a `mediate`d inlet the source
 * links into under q's stamp (the Link charge), and a `mediateOutlet` with an
 * Attention `protocolAuthority` that feeds the join (the Attention charge):
 *
 * ```
 *   source ──> "B" LabelArm ────────────────────────────────────────> GlitchFreeCell(WAIT) ──> observer
 *          └─(connect under q)─> [exposedInlet ─> relay ─> exposedOutlet(Attention budget)] ──┘
 * ```
 *
 * B is subscribed first so the source's broadcast reaches it first and the
 * join holds each wave waiting on M (the SEC1 fixture's reasoning). The
 * mid-wave refusals are driven synchronously from the test thread after
 * `source.emit(2)` and before the drain; the interleaving inside the wave is
 * not asserted, only that both refusals are recorded before the wave releases.
 *
 * **Mutation evidence** (`.claude/skills/work/references/evidence.md`,
 * "Mutation checks"; `CompositeCell.kt` is outside this task's claim, so each
 * mutation was applied, run, and restored, never committed):
 *
 * - (a) `linkBudgetPolicy`'s body replaced with `LinkPolicy { null }` — the
 *   second-connect assertion (`Rejected`) and the LINK_AUTHORITY counts
 *   redden.
 * - (b) `asProtocolFilter`'s Attention `Refused` branch `return@filter null`
 *   replaced with `throw IllegalStateException("mutated")` — see the
 *   `MUTATION (b)` note in the test body for which assertion reddens.
 *
 * SimulationController caution (`computenet-2zasa` AMENDS comment): a
 * `CurrentPeer` stamp set on the stepping thread is visible to every task
 * stepped there, so every stamped block below is synchronous and the drain
 * always runs OUTSIDE it.
 */
class BudgetDenialWaveCompletenessTest {

    private val q = PeerId("principal-q")

    /** One observation at the far side of the join: the released value and the wave it rode. */
    private data class Obs(val value: String, val ts: Timestamp)

    /** Mints a fresh wave per emission (`originate`), fanning to both arms. */
    private class SourceCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<Int>>())

        fun emit(n: Int) = outlet.originate { propagate(n) }
    }

    /** The unbudgeted arm: reactive `Int -> "B:n"`, preserving the incoming wave. */
    private class LabelArm(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("B:$n") }
        }
    }

    /** The organelle behind the budgeted membrane: `Int -> "M:n"`. */
    private class RelayOrganelle(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell {
        val inlet = registerPort("inlet", FanInlet.create<Propagate<Int>>())
        val outlet = registerPort("outlet", FanOutlet.create<Propagate<String>>())

        init {
            inlet.onEach { n -> outlet.call.propagate("M:$n") }
        }
    }

    /**
     * The budgeted arm. `exposedInlet` is `mediate`d (so `linkBudgetPolicy`
     * charges a remote link into it); `exposedOutlet` is `mediateOutlet`ed
     * with an Attention `protocolAuthority` (so `asProtocolFilter`'s budget
     * arm charges a remote Attention frame on it). Property names equal the
     * exposure names (G-17).
     */
    private class BudgetedArmMembrane(
        val organelle: RelayOrganelle = RelayOrganelle(),
    ) : CompositeCell() {
        val exposedInlet = mediate("exposedInlet", "inlet", organelle.inlet)
        val exposedOutlet = mediateOutlet(
            "exposedOutlet",
            "outlet",
            organelle.outlet,
            BoundaryPolicy(
                protocolAuthority = mapOf(Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW)),
            ),
        )
    }

    /**
     * The fixture. `SimWorld` builds its host with no `budget`, so the host is
     * built here directly and drained by [runToIdle], `SimWorld.runToIdle`'s
     * budgeted loop: quiescence is asserted under a step budget, never slept.
     */
    private class Fixture(seed: Long) {
        val exhausted = AtomicBoolean(false)
        val ledger = RecordingLedger(
            "host",
            refuse = {
                if (exhausted.get()) BudgetOutcome.Refused(DenialReason.BUDGET_EXHAUSTED, "host", shortfall = 1) else null
            },
        )
        val controller = SimulationController(seed)
        val host = ManagedHost(scheduler = controller.scheduler(), registry = LocationRegistry(), budget = ledger)

        val obs = CopyOnWriteArrayList<Obs>()
        val violations = CopyOnWriteArrayList<CellError>()
        val letters = CopyOnWriteArrayList<DeadLetter>()
        val attentionSeen = CopyOnWriteArrayList<Attention>()

        val source = SourceCell()
        val secondSource = SourceCell()
        val labelArm = LabelArm()
        val membrane = BudgetedArmMembrane()
        val join = GlitchFreeCell(
            @Suppress("UNCHECKED_CAST") (Propagate::class.java as Class<Propagate<String>>),
            mode = GlitchFreeCell.WaveMode.WAIT,
        )
        val membraneRef: CellRef

        val inletSink get() = membrane.boundaryDenials["exposedInlet"]!!
        val outletSink get() = membrane.boundaryDenials["exposedOutlet"]!!

        /** A stand-in bridged edge the Attention frame arrives over, as `BudgetProtocolSeamTest.Rig` builds it. */
        private val attentionEdge = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(membrane.ref),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(membrane.ref, "exposedOutlet"),
        )

        init {
            host.deadLetterOutlet.subscribe(sink<DeadLetter> { letters += it })
            join.outlet.subscribe(sink<String> { obs += Obs(it, CurrentContext.get()!!.timestamp) })
            join.errorOutlet.subscribe(sink<CellError> { violations += it })

            host.managementInlet.call.spawn(source)
            host.managementInlet.call.spawn(secondSource)
            host.managementInlet.call.spawn(labelArm)
            membraneRef = host.managementInlet.call.spawn(membrane)
            host.managementInlet.call.spawn(join)
            runToIdle()

            ProtocolSupport.of(membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
                attentionSeen += message as Attention
            }
        }

        fun runToIdle(budget: Int = 200_000): Int {
            var steps = 0
            while (controller.step()) {
                check(++steps < budget) { "no quiescence within $budget steps" }
            }
            return steps
        }

        /** A remote link request from [from] into the membrane, under q's stamp. Synchronous: no step runs stamped. */
        fun connectAs(peer: PeerId, from: SourceCell): LinkResult =
            CurrentPeer.with(peer, AuthLevel.Authenticated) {
                host.managementInlet.call.connect(from.ref, "outlet", membraneRef, "exposedInlet")
            }

        /** A remote Attention assertion on the membrane's charged outlet, under [peer]'s stamp. */
        fun assertAttentionAs(peer: PeerId, attention: Attention) {
            CurrentPeer.with(peer, AuthLevel.Authenticated) {
                ProtocolSupport.of(membrane.exposedOutlet).deliver(Protocols.Attention, attentionEdge, attention)
            }
        }

        fun joinFrom(outlet: FanOutlet<Propagate<String>>) {
            @Suppress("UNCHECKED_CAST")
            (outlet.linkTo(join.inlet as LinkFrom<Propagate<String>>) is LinkResult.Connected).shouldBeTrue()
        }

        fun byWave(): Map<Long, Set<String>> =
            obs.groupBy({ it.ts.counter }, { it.value }).mapValues { it.value.toSet() }

        private fun <T> sink(block: (T) -> Unit): Use<Propagate<T>> = Use.fixed(
            object : Propagate<T> {
                override fun propagate(value: T) = block(value)
            },
            PortRef.generate(),
        )
    }

    @Test
    fun `BS-05 mid-wave budget exhaustion never starves the join - the held link keeps delivering and only new claims are refused`() {
        val f = Fixture(seed = 51)

        // Arm B first (see the header), then the budgeted arm, formed REMOTELY
        // by q while funded: one admitted Link charge, keyed by q's request.
        f.source.outlet.subscribe(Use.fixed(f.labelArm.inlet.call, f.labelArm.inlet.ref))
        f.connectAs(q, f.source).shouldBeInstanceOf<LinkResult.Connected>()
        f.joinFrom(f.labelArm.outlet)
        f.joinFrom(f.membrane.exposedOutlet)
        f.runToIdle()

        f.ledger.charges.map { it.claimClass } shouldContainExactly listOf(ClaimClass.Link)
        f.ledger.charges.single().stamp.id shouldBe q
        f.ledger.balance(q, ClaimClass.Link) shouldBe 9L

        // Wave 1 crosses both arms: the join is really joining, and a delta on
        // the held link constructs no claim.
        f.source.emit(1)
        f.runToIdle()
        f.byWave()[1L] shouldBe setOf("B:1", "M:1")
        f.inletSink.denialCount shouldBe 0L
        f.outletSink.denialCount shouldBe 0L
        f.violations.shouldBeEmpty()
        f.ledger.charges.size shouldBe 1

        // q's budget runs dry. Wave 2 is emitted, and BEFORE the drain — while
        // the join is holding wave 2 — q's Attention frame and a second link
        // request are both refused.
        f.exhausted.set(true)
        f.source.emit(2)
        f.assertAttentionAs(q, Attention(AttentionBand.HIGH.level, version = 2))
        val second = f.connectAs(q, f.secondSource).shouldBeInstanceOf<LinkResult.Rejected>()
        second.reason shouldStartWith "budget refused link"
        val wave2Steps = f.runToIdle()

        // THE assertions: the poisoned wave completes with BOTH contributions —
        // no rescue was needed, so none fired.
        f.byWave()[2L] shouldBe setOf("B:2", "M:2")
        // MUTATION (b) — see the report on computenet-2zasa.1 for which of
        // these reddened when the Attention refusal threw instead of dropping.
        f.violations.shouldBeEmpty()
        f.host.supervisionAccounting().restarts shouldBe 0L
        (wave2Steps < 200_000).shouldBeTrue()
        f.attentionSeen.shouldBeEmpty()

        // Each refusal accounted exactly once, on its own exposure's sink.
        f.outletSink.denialCount shouldBe 1L
        f.inletSink.denialCount shouldBe 1L
        f.letters.map { it.denial!!.seam } shouldContainExactly
            listOf(BoundarySeam.PROTOCOL_AUTHORITY, BoundarySeam.LINK_AUTHORITY)
        f.letters.map { it.denial!!.exposure } shouldContainExactly listOf("exposedOutlet", "exposedInlet")
        f.letters.map { it.denial!!.principal }.distinct() shouldBe listOf(q)
        f.letters.map { it.denial!!.reason }.distinct() shouldBe listOf(DenialReason.BUDGET_EXHAUSTED)
        // The ledger saw the setup Link charge plus the two refused claims, nothing else.
        f.ledger.charges.map { it.claimClass } shouldContainExactly
            listOf(ClaimClass.Link, ClaimClass.Attention, ClaimClass.Link)
        f.membrane.exposedInlet.linking.links.size shouldBe 1

        // [ECO1-DEN-09]: WHILE q is exhausted, the link q formed while funded
        // keeps delivering, and the ledger records no re-evaluation of it.
        f.source.emit(3)
        f.runToIdle()
        f.byWave()[3L] shouldBe setOf("B:3", "M:3")

        val sourceId = f.obs.first().ts.sourceId
        f.obs.map { it.ts.counter }.distinct() shouldBe listOf(1L, 2L, 3L)
        f.obs.map { it.ts.sourceId }.distinct() shouldBe listOf(sourceId)
        f.ledger.charges.size shouldBe 3
        f.outletSink.denialCount shouldBe 1L
        f.inletSink.denialCount shouldBe 1L
        f.violations.shouldBeEmpty()
        f.host.supervisionAccounting().restarts shouldBe 0L
    }
}
