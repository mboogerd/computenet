package civictech.runtime

import civictech.cell.BoundarySeam
import civictech.cell.BudgetLedger
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
import civictech.cell.data.SetApi
import civictech.cell.data.SetCell
import civictech.cell.graph.CellFactory
import civictech.cell.graph.GraphSpec
import civictech.cell.graph.IdentityBinding
import civictech.cell.graph.SpawnStep
import civictech.cell.host.DeadLetter
import civictech.cell.host.LocationRegistry
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
import civictech.cell.wire.PortAddress
import civictech.cell.wire.WireEdgeLink
import civictech.economy.EconomicPolicy
import civictech.economy.TokenBucketLedger
import civictech.testkit.awaitUntil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private class RuntimeBudgetMembrane(ref: CellRef) : CompositeCell(ref) {
    private val source = SetCell<String>()

    val exposure = mediateOutlet(
        "attention",
        "outlet",
        source.outlet,
        policy = BoundaryPolicy(
            protocolAuthority = mapOf(
                Protocols.Attention to ProtocolAuthority(ceiling = AttentionBand.LOW),
            ),
        ),
    )
}

class BudgetedNodeTest {

    companion object {
        private const val CONVERGENCE_MS = 15_000L
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(60)
    fun `a manifest budget refuses a remote peer's surplus Attention without faulting or closing the peering`() {
        val policyFile = tempDir.resolve("policy.json")
        val policy = EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Attention to 1L),
            capacities = mapOf(ClaimClass.Attention to 10L),
            refill = mapOf(
                ClaimClass.Attention to EconomicPolicy.Refill(
                    tokensPerInterval = 1L,
                    intervalNanos = 3_600_000_000_000L,
                ),
            ),
            unvouchedBootstrap = mapOf(ClaimClass.Attention to 3L),
        )
        Files.writeString(policyFile, Json.encodeToString(EconomicPolicy.serializer(), policy))

        val run = bootPair(policyFile)
        try {
            repeat(4) { offset ->
                run.b.registry.deliver(attention(run.aMembrane.ref, version = offset + 1L))
            }

            awaitUntil("node a reports the fourth Attention assertion as one denial", CONVERGENCE_MS) {
                run.deadLetters.size == 1
            }

            // This assertion is deliberately first: the Runtime crossing must carry b's
            // transport-vouched stamp. Without it there is no principal whose bootstrap
            // the node ledger could charge, and adding identity wiring here would hide that.
            assertEquals(PeerId("b"), run.deadLetters.single().denial?.principal)

            awaitUntil("node a accounts three admissions and one refusal", CONVERGENCE_MS) {
                run.observed.size == 3 &&
                    run.aMembrane.boundaryDenials["attention"]!!.denialCount == 1L &&
                    (run.a.budget as TokenBucketLedger).snapshot().let { snapshot ->
                        snapshot.admitted[ClaimClass.Attention] == 3L &&
                            snapshot.bucket(PeerId("b"), ClaimClass.Attention)?.balance == 0L &&
                            snapshot.denied[ClaimClass.Attention]?.get(DenialReason.BUDGET_EXHAUSTED) == 1L
                    }
            }

            assertEquals(listOf(1L, 2L, 3L), run.observed.map(Attention::version))
            val denial = requireNotNull(run.deadLetters.single().denial)
            assertEquals(DenialReason.BUDGET_EXHAUSTED, denial.reason)
            assertEquals(BoundarySeam.PROTOCOL_AUTHORITY, denial.seam)
            assertEquals(Protocols.Attention.name, denial.subject)

            val snapshot = (run.a.budget as TokenBucketLedger).snapshot()
            assertEquals(3L, snapshot.admitted[ClaimClass.Attention])
            assertEquals(0L, snapshot.bucket(PeerId("b"), ClaimClass.Attention)?.balance)
            assertEquals(
                1L,
                snapshot.denied[ClaimClass.Attention]?.get(DenialReason.BUDGET_EXHAUSTED),
            )
            assertEquals(0L, run.a.mainHost.supervisionAccounting().restarts)

            setOps(run.a).add("apple")
            awaitUntil("node b observes apple after node a refused the surplus Attention", CONVERGENCE_MS) {
                "apple" in run.bItems.membership()
            }
        } finally {
            run.close()
        }
    }

    @Test
    @Timeout(60)
    fun `a node with no manifest budget admits every remote Attention assertion`() {
        val run = bootPair(policyFile = null)
        try {
            assertSame(BudgetLedger.Unlimited, run.a.budget)

            repeat(4) { offset ->
                run.b.registry.deliver(attention(run.aMembrane.ref, version = offset + 1L))
            }

            awaitUntil("node a delivers all four Attention assertions without a budget", CONVERGENCE_MS) {
                run.observed.size == 4
            }
            assertEquals(listOf(1L, 2L, 3L, 4L), run.observed.map(Attention::version))
            assertTrue(run.deadLetters.isEmpty(), "an Unlimited node reported a budget denial")
            assertEquals(0L, run.aMembrane.boundaryDenials["attention"]!!.denialCount)
        } finally {
            run.close()
        }
    }

    private fun bootPair(policyFile: Path?): Run {
        val captured = CapturedSpec()
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(
                    transport = "ws",
                    listen = "ws://127.0.0.1:0",
                    replica = 0,
                    peerName = "a",
                    budget = policyFile?.toAbsolutePath()?.toString(),
                ),
                "b" to NodeSpec(
                    transport = "ws",
                    dial = listOf("a"),
                    replica = 1,
                    peerName = "b",
                ),
            ),
        )
        val a = Runtime.boot(manifest, "a", captured.spec)
        var b: Runtime.Node? = null

        try {
            val aMembrane = captured.membranes.single()
            val aItems = captured.items.single()
            val deadLetters = CopyOnWriteArrayList<DeadLetter>()
            val observed = CopyOnWriteArrayList<Attention>()
            a.mainHost.deadLetterOutlet.subscribe(
                Use.fixed(
                    object : Propagate<DeadLetter> {
                        override fun propagate(value: DeadLetter) {
                            deadLetters += value
                        }
                    },
                    PortRef.generate(),
                ),
            )
            ProtocolSupport.of(aMembrane.exposure).handle(Protocols.Attention) { _, message ->
                observed += message as Attention
            }

            a.open()
            val address = requireNotNull(a.boundAddress) { "node a did not expose its granted address" }
            val bNode = Runtime.boot(manifest, "b", captured.spec, overrides = mapOf("a" to address.text))
            b = bNode
            val bItems = captured.items.last()
            assertTrue(aItems.ref.sameLogical(bItems.ref), "the shared spec did not mint one logical set")
            assertNotEquals(aItems.ref, bItems.ref, "the replicated set reused one instance id")
            bNode.open()

            awaitUntil("node b mirrors node a's Attention membrane", CONVERGENCE_MS) {
                bNode.registry.location(aMembrane.ref) is LocationRegistry.Remote
            }
            return Run(a, bNode, aMembrane, bItems, deadLetters, observed)
        } catch (failure: Throwable) {
            b?.close()
            a.close()
            throw failure
        }
    }

    private fun setOps(node: Runtime.Node): civictech.cell.data.SetOps<String> =
        node.mainHost.lookup<SetApi<String>>(node.refs.getValue("items"))!!.inlet.call

    private class CapturedSpec {
        val membranes = mutableListOf<RuntimeBudgetMembrane>()
        val items = mutableListOf<SetCell<String>>()
        private val membraneLogicalId = UUID.randomUUID()
        private val itemsLogicalId = UUID.randomUUID()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "attention",
                    factory = CellFactory { ref -> RuntimeBudgetMembrane(ref).also(membranes::add) },
                    identity = IdentityBinding.NewInstanceOf(membraneLogicalId),
                ),
                SpawnStep(
                    handle = "items",
                    factory = CellFactory { ref -> SetCell<String>(ref).also(items::add) },
                    identity = IdentityBinding.NewInstanceOf(itemsLogicalId),
                    replicated = true,
                ),
            ),
        )
    }

    private data class Run(
        val a: Runtime.Node,
        val b: Runtime.Node,
        val aMembrane: RuntimeBudgetMembrane,
        val bItems: SetCell<String>,
        val deadLetters: CopyOnWriteArrayList<DeadLetter>,
        val observed: CopyOnWriteArrayList<Attention>,
    ) : AutoCloseable {
        override fun close() {
            b.close()
            a.close()
        }
    }

    private fun attention(target: CellRef, version: Long) = HostedPortInvocation(
        cellRef = target,
        portName = "attention",
        type = HostedPortInvocation.Type.PORT_PROTOCOL,
        invocation = Invocation("", emptyList(), emptyList()),
        protocolId = Protocols.Attention,
        protocolLink = WireEdgeLink(
            id = UUID.randomUUID(),
            from = PortRef.generate(),
            to = PortRef.generate(target),
            fromAddr = PortAddress(CellRef(UUID.randomUUID()), "inlet"),
            toAddr = PortAddress(target, "attention"),
        ),
        protocolMessage = Attention(AttentionBand.HIGH.level, version),
    )
}
