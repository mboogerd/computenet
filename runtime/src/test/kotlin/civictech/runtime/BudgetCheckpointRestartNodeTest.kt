package civictech.runtime

import civictech.cell.BoundarySeam
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.control.Attention
import civictech.cell.control.AttentionBand
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
import civictech.economy.persist.FallbackReason
import civictech.economy.persist.FileBudgetCheckpointStore
import civictech.economy.persist.RestoreOutcome
import civictech.testkit.awaitUntil
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private class CheckpointRuntimeBudgetMembrane(ref: CellRef) : CompositeCell(ref) {
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

/**
 * Runtime-level BS-14/BS-15 coverage. The missing-checkpoint test is the non-vacuity witness:
 * it admits the fresh bootstrap after the same close, so the restart test's refusal of version
 * 5 proves that the restored depleted bucket, rather than a permanently exhausted fixture,
 * caused the refusal.
 */
class BudgetCheckpointRestartNodeTest {

    companion object {
        private const val CONVERGENCE_MS = 15_000L
        private const val CHECKPOINT_CADENCE_NANOS = 3_600_000_000_000L
    }

    @TempDir
    lateinit var tempDir: Path

    @Test
    @Timeout(120)
    fun `a depleted remote budget stays depleted across close and re-boot`() {
        val policyFile = writePolicy()
        val checkpointDir = tempDir.resolve("budget-checkpoints")

        val firstRun = bootPair(policyFile, checkpointDir)
        try {
            assertEquals(
                RestoreOutcome.Fallback(FallbackReason.MISSING),
                firstRun.a.budgetRestore,
            )
            deliver(firstRun, 1L..4L)
            awaitSettled(firstRun, 4, "the first run settles all four Attention assertions")

            assertEquals(listOf(1L, 2L, 3L), firstRun.observed.map(Attention::version))
            assertEquals(1, firstRun.deadLetters.size)
            assertDenial(firstRun.deadLetters.single())
        } finally {
            firstRun.close()
        }

        val secondRun = bootPair(policyFile, checkpointDir)
        try {
            assertEquals(
                RestoreOutcome.Restored(sequence = 1L, buckets = 1),
                secondRun.a.budgetRestore,
            )

            deliver(secondRun, 5L..5L)
            awaitSettled(secondRun, 1, "the restored run refuses the next Attention assertion")

            val snapshot = (secondRun.a.budget as TokenBucketLedger).snapshot()
            assertAll(
                { assertTrue(secondRun.observed.isEmpty(), "a restored depleted budget admitted version 5") },
                { assertEquals(1, secondRun.deadLetters.size) },
                { assertDenial(secondRun.deadLetters.single()) },
                {
                    assertEquals(
                        0L,
                        snapshot.bucket(PeerId("b"), ClaimClass.Attention)!!.balance,
                    )
                },
                { assertEquals(0L, snapshot.bucket(PeerId("b"), ClaimClass.Attention)!!.held) },
                { assertEquals(0L, snapshot.admitted[ClaimClass.Attention]) },
                {
                    assertEquals(
                        1L,
                        snapshot.denied[ClaimClass.Attention]?.get(DenialReason.BUDGET_EXHAUSTED),
                    )
                },
                { assertEquals(0L, secondRun.a.mainHost.supervisionAccounting().restarts) },
            )
        } finally {
            secondRun.close()
        }

        val finalState = FileBudgetCheckpointStore(checkpointDir) { 0L }.read("a")
        assertEquals(2L, (finalState as civictech.economy.persist.CheckpointRead.Present).state.sequence)
    }

    @Test
    @Timeout(120)
    fun `a missing checkpoint bootstraps afresh and records the fallback`() {
        val policyFile = writePolicy()
        val checkpointDir = tempDir.resolve("budget-checkpoints")

        val firstRun = bootPair(policyFile, checkpointDir)
        try {
            assertEquals(
                RestoreOutcome.Fallback(FallbackReason.MISSING),
                firstRun.a.budgetRestore,
            )
            deliver(firstRun, 1L..4L)
            awaitSettled(firstRun, 4, "the source run settles all four Attention assertions")
            assertEquals(listOf(1L, 2L, 3L), firstRun.observed.map(Attention::version))
            assertEquals(1, firstRun.deadLetters.size)
            assertDenial(firstRun.deadLetters.single())
        } finally {
            firstRun.close()
        }

        Files.walk(checkpointDir).use { paths ->
            paths.filter(Files::isRegularFile).forEach(Files::delete)
        }

        val secondRun = bootPair(policyFile, checkpointDir)
        try {
            assertEquals(
                RestoreOutcome.Fallback(FallbackReason.MISSING),
                secondRun.a.budgetRestore,
            )
            deliver(secondRun, 5L..8L)
            awaitSettled(secondRun, 4, "the fresh bootstrap settles four later Attention assertions")

            assertEquals(listOf(5L, 6L, 7L), secondRun.observed.map(Attention::version))
            assertEquals(1, secondRun.deadLetters.size)
            assertDenial(secondRun.deadLetters.single())
        } finally {
            secondRun.close()
        }
    }

    private fun writePolicy(): Path {
        val policy = EconomicPolicy.placeholder().copy(
            prices = mapOf(ClaimClass.Attention to 1L),
            capacities = mapOf(ClaimClass.Attention to 10L),
            refill = mapOf(
                ClaimClass.Attention to EconomicPolicy.Refill(
                    tokensPerInterval = 1L,
                    intervalNanos = CHECKPOINT_CADENCE_NANOS,
                ),
            ),
            unvouchedBootstrap = mapOf(ClaimClass.Attention to 3L),
            checkpoint = EconomicPolicy.Checkpoint(
                cadenceNanos = CHECKPOINT_CADENCE_NANOS,
                stalenessBoundNanos = CHECKPOINT_CADENCE_NANOS,
            ),
        )
        return tempDir.resolve("policy.json").also {
            Files.writeString(it, Json.encodeToString(EconomicPolicy.serializer(), policy))
        }
    }

    private fun bootPair(policyFile: Path, checkpointDir: Path): Run {
        val captured = CapturedSpec()
        val manifest = Manifest(
            mapOf(
                "a" to NodeSpec(
                    transport = "ws",
                    listen = "ws://127.0.0.1:0",
                    replica = 0,
                    peerName = "a",
                    budget = policyFile.toAbsolutePath().toString(),
                    budgetCheckpointDir = checkpointDir.toAbsolutePath().toString(),
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
            bNode.open()

            awaitUntil("node b mirrors node a's Attention membrane", CONVERGENCE_MS) {
                bNode.registry.location(aMembrane.ref) is LocationRegistry.Remote
            }
            return Run(a, bNode, aMembrane, deadLetters, observed)
        } catch (failure: Throwable) {
            b?.close()
            a.close()
            throw failure
        }
    }

    private fun deliver(run: Run, versions: LongRange) {
        versions.forEach { version ->
            run.b.registry.deliver(attention(run.aMembrane.ref, version))
        }
    }

    private fun awaitSettled(run: Run, expected: Int, description: String) {
        awaitUntil(description, CONVERGENCE_MS) {
            run.observed.size + run.deadLetters.size == expected
        }
    }

    private fun assertDenial(deadLetter: DeadLetter) {
        val denial = requireNotNull(deadLetter.denial)
        assertAll(
            { assertEquals(PeerId("b"), denial.principal) },
            { assertEquals(DenialReason.BUDGET_EXHAUSTED, denial.reason) },
            { assertEquals(BoundarySeam.PROTOCOL_AUTHORITY, denial.seam) },
            { assertEquals(Protocols.Attention.name, denial.subject) },
        )
    }

    private class CapturedSpec {
        val membranes = mutableListOf<CheckpointRuntimeBudgetMembrane>()
        private val membraneLogicalId = UUID.randomUUID()
        val spec = GraphSpec(
            listOf(
                SpawnStep(
                    handle = "attention",
                    factory = CellFactory { ref ->
                        CheckpointRuntimeBudgetMembrane(ref).also(membranes::add)
                    },
                    identity = IdentityBinding.NewInstanceOf(membraneLogicalId),
                ),
            ),
        )
    }

    private data class Run(
        val a: Runtime.Node,
        val b: Runtime.Node,
        val aMembrane: CheckpointRuntimeBudgetMembrane,
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
