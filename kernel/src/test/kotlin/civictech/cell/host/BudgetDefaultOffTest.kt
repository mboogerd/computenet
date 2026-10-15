package civictech.cell.host

import civictech.cell.BoundarySeam
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.RecordingLedger
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetCell
import civictech.cell.link.CurrentPeer
import civictech.cell.link.PeerId
import civictech.cell.membrane.BoundaryPolicy
import civictech.cell.membrane.CompositeCell
import civictech.cell.membrane.ProtocolAuthority
import civictech.cell.port.PortRef
import civictech.cell.port.Use
import civictech.cell.protocol.ProtocolSupport
import civictech.cell.protocol.Protocols
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.Peering
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** One mediated Attention outlet with a ceiling, matching the existing POL-06 fixtures. */
private class AttentionMembrane(
    val organelle: SetCell<String> = SetCell(),
) : CompositeCell() {
    val exposedOutlet = mediateOutlet(
        "exposedOutlet",
        "outlet",
        organelle.outlet,
        policy = BoundaryPolicy(
            protocolAuthority = mapOf(
                Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW),
            ),
        ),
    )
}

/**
 * [ECO1-POL-06] / [ECO1-BUD-04] structural default-off proof.
 *
 * This overlaps the existing `BudgetProtocolSeamTest` test named "ECO1-POL-06 - an
 * all-Unlimited host attaches no ledger, and a remote assertion is only clamped",
 * `BudgetProtocolSeamTest` test named "ECO1-POL-06 - with no ledger attached a remote
 * link connects and nothing is recorded",
 * `BudgetHierarchyWalkTest` test named "with every scope Unlimited nothing is attached,
 * and with any real budget the hierarchy ledger is", and `BridgeBoundaryPolicyTest`
 * test named "the ceiling clamps a bridge-arriving assertion and stays a no-op for a
 * local one". Its increment is the literal default constructor and the loopback path
 * under one name: the default-built host and child attach no budget, while a real
 * loopback remote assertion is clamped without a budget denial. The refusing-ledger
 * control proves that the same loopback frame reaches the protocol seam.
 */
class BudgetDefaultOffTest {

    private val budgetField = CompositeCell::class.java.getDeclaredField("budget").apply {
        isAccessible = true
    }

    @Test
    fun `a host built with no budget argument has no ledger on its chain or a child`() {
        val controller = SimulationController(seed = 1)
        val root = ManagedHost(scheduler = controller.scheduler())
        val child = ManagedHost(scheduler = controller.scheduler())

        root.managementInlet.call.spawn(child)
        controller.runToIdle()

        root.anyLedgerOnChain() shouldBe false
        child.anyLedgerOnChain() shouldBe false
    }

    @Test
    fun `a membrane spawned on a default host has no budget attached stamped or unstamped`() {
        val controller = SimulationController(seed = 2)
        val host = ManagedHost(scheduler = controller.scheduler())
        val unstamped = AttentionMembrane()
        val stamped = AttentionMembrane()

        host.managementInlet.call.spawn(unstamped)
        CurrentPeer.with(PeerId("q")) {
            host.managementInlet.call.spawn(stamped)
        }
        controller.runToIdle()

        budgetField.get(unstamped) shouldBe null
        budgetField.get(stamped) shouldBe null
    }

    @Test
    fun `over loopback a remote assertion on a default host is clamped and records no budget denial`() {
        val run = LoopbackRun(seed = 3)
        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(run.membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        run.assertAttentionFromQ(Attention(AttentionBand.HIGH.level, version = 7))

        observed shouldContainExactly listOf(Attention(AttentionBand.LOW.level, version = 7))
        run.hostP.boundaryDenialCount() shouldBe 0L
        run.hostQ.boundaryDenialCount() shouldBe 0L
        run.deadLetters.mapNotNull { it.denial }
            .none { it.reason in BUDGET_DENIAL_REASONS } shouldBe true
    }

    @Test
    fun `the same loopback rig with a refusing ledger records exactly one budget denial`() {
        val ledger = RecordingLedger(
            "p",
            refuse = {
                BudgetOutcome.Refused(
                    DenialReason.BUDGET_EXHAUSTED,
                    "p",
                    shortfall = 1,
                )
            },
        )
        val run = LoopbackRun(seed = 4, budget = ledger)
        val observed = mutableListOf<Attention>()
        ProtocolSupport.of(run.membrane.exposedOutlet).handle(Protocols.Attention) { _, message ->
            observed += message as Attention
        }

        run.assertAttentionFromQ(Attention(AttentionBand.HIGH.level, version = 7))

        observed.shouldBeEmpty()
        ledger.charges.size shouldBe 1
        run.hostP.boundaryDenialCount() shouldBe 1L
        run.hostQ.boundaryDenialCount() shouldBe 0L
        val denial = run.deadLetters.single().denial!!
        denial.seam shouldBe BoundarySeam.PROTOCOL_AUTHORITY
        denial.reason shouldBe DenialReason.BUDGET_EXHAUSTED
    }

    private class LoopbackRun(
        seed: Long,
        budget: BudgetLedger? = null,
    ) {
        val controller = SimulationController(seed)

        val registryP = LocationRegistry()
        val hostP = if (budget == null) {
            // The default-off test deliberately omits the budget argument here.
            ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        } else {
            ManagedHost(scheduler = controller.scheduler(), registry = registryP, budget = budget)
        }
        private val bridgeP = ManagedHost(scheduler = controller.scheduler(), registry = registryP)
        private val registryQ = LocationRegistry()
        val hostQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)
        private val bridgeQ = ManagedHost(scheduler = controller.scheduler(), registry = registryQ)

        private val sideP = Peering.Side(registryP, bridgeP, peer = PeerId("p"))
        private val sideQ = Peering.Side(registryQ, bridgeQ, peer = PeerId("q"))
        private val loopback = Peering.loopback(sideP, sideQ)

        val deadLetters = CopyOnWriteArrayList<DeadLetter>()
        val membrane = AttentionMembrane()
        private val membraneRef: CellRef

        init {
            listOf(hostP, bridgeP, hostQ, bridgeQ).forEach { host ->
                host.deadLetterOutlet.subscribe(
                    Use.fixed(
                        object : Propagate<DeadLetter> {
                            override fun propagate(value: DeadLetter) {
                                deadLetters += value
                            }
                        },
                        PortRef.generate(),
                    ),
                )
            }
            membraneRef = hostP.managementInlet.call.spawn(membrane)
            controller.runToIdle()
        }

        fun assertAttentionFromQ(attention: Attention) {
            loopback.bToA.deliver(attentionFrame(attention))
            controller.runToIdle()
        }

        private fun attentionFrame(attention: Attention): HostedPortInvocation {
            val edge = WireEdgeLink(
                id = UUID.randomUUID(),
                from = PortRef.generate(),
                to = PortRef.generate(membraneRef),
                fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
                toAddr = PortAddress(membraneRef, "exposedOutlet"),
            )
            return HostedPortInvocation(
                cellRef = membraneRef,
                portName = "exposedOutlet",
                type = HostedPortInvocation.Type.PORT_PROTOCOL,
                invocation = Invocation("", emptyList(), emptyList()),
                protocolId = Protocols.Attention,
                protocolLink = edge,
                protocolMessage = attention,
            )
        }
    }

    private companion object {
        val BUDGET_DENIAL_REASONS = setOf(
            DenialReason.BUDGET_EXHAUSTED,
            DenialReason.BUDGET_NOT_GRANTED,
            DenialReason.LEDGER_FAILURE,
        )
    }
}
