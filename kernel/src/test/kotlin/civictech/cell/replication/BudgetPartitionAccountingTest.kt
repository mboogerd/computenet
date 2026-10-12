package civictech.cell.replication

import civictech.cell.BudgetClaim
import civictech.cell.BudgetLedger
import civictech.cell.BudgetOutcome
import civictech.cell.CellRef
import civictech.cell.ClaimClass
import civictech.cell.RecordingLedger
import civictech.cell.data.Replicable
import civictech.cell.data.SetCell
import civictech.cell.data.SetOps
import civictech.cell.host.HostedCellProxy
import civictech.cell.host.LocationRegistry
import civictech.cell.host.ManagedHost
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.port.Use
import civictech.cell.wire.Peering
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ECO1 F5 T3 (`computenet-jtof7.3`): budget state remains local to the host
 * that admits a replication link. The two tests use one deterministic
 * [SimulationController] each and settle it before observing state.
 *
 * BS-11 uses a closed A↔B frame gate rather than a second JVM: A and B can
 * independently charge P while no decision can await the other host. The
 * frame assertions inspect bytes handed to the interposes, including dropped
 * frames, so a budget ledger cannot hide in a transport-local object.
 */
class BudgetPartitionAccountingTest {

    private interface SetInletProxy {
        val inlet: Use<SetOps<String>>
    }

    private class Peer(
        val controller: SimulationController,
        val peer: PeerId?,
        val budget: BudgetLedger = BudgetLedger.Unlimited,
    ) {
        val registry = LocationRegistry()
        val host = ManagedHost(scheduler = controller.scheduler(), registry = registry, budget = budget)
        private val bridgeHost = ManagedHost(scheduler = controller.scheduler(), registry = registry)
        val side = Peering.Side(registry, bridgeHost, peer = peer)
        val replication = Replication(registry)

        fun replica(logicalId: UUID, instanceId: Long): SetCell<String> =
            SetCell<String>(CellRef(logicalId, instanceId)).also {
                replication.replicate(it, host)
            }

        fun ops(replica: SetCell<String>): SetOps<String> =
            (HostedCellProxy.create(replica.ref, registry, SetInletProxy::class.java) as SetInletProxy).inlet.call
    }

    /** Records every frame handed to [apply], not only frames returned to the peer. */
    private class FrameRecorder(var closed: Boolean = false) : Peering.FrameInterpose {
        val frames = CopyOnWriteArrayList<ByteArray>()
        val framesWhileClosed = CopyOnWriteArrayList<ByteArray>()

        override fun apply(frame: ByteArray): List<ByteArray> {
            val copy = frame.copyOf()
            frames += copy
            if (closed) framesWhileClosed += copy
            return if (closed) emptyList() else listOf(frame)
        }
    }

    private companion object {
        val P = PeerId("p")
        val A = PeerId("a")
        val B = PeerId("b")
        const val PARTITION_BUDGET = 2L
        const val DIVERGENCE_BUDGET = 10L
    }

    @Test
    fun `partitioned hosts charge P independently and healing does not merge their budgets`() {
        val controller = SimulationController(0)
        val ledgerA = RecordingLedger("a-host", initial = PARTITION_BUDGET)
        val ledgerB = RecordingLedger("b-host", initial = PARTITION_BUDGET)
        val p = Peer(controller, P)
        val a = Peer(controller, A, ledgerA)
        val b = Peer(controller, B, ledgerB)

        val pToA = FrameRecorder()
        val aToP = FrameRecorder()
        val pToB = FrameRecorder()
        val bToP = FrameRecorder()
        val aToB = FrameRecorder(closed = true)
        val bToA = FrameRecorder(closed = true)

        Peering.loopback(p.side, a.side, pToA, aToP)
        Peering.loopback(p.side, b.side, pToB, bToP)
        val abLink = Peering.loopback(a.side, b.side, aToB, bToA)

        val aReplicas = mutableListOf<SetCell<String>>()
        val bReplicas = mutableListOf<SetCell<String>>()
        val pReplicas = mutableListOf<SetCell<String>>()
        for (instance in 0..2) {
            val logicalId = UUID.randomUUID()
            pReplicas += p.replica(logicalId, instance.toLong())
            aReplicas += a.replica(logicalId, instance + 10L)
            bReplicas += b.replica(logicalId, instance + 20L)
            controller.runToIdle()
            // [ECO1-PAR-03]: every claim-phase drain returns while A↔B is still closed;
            // charging A or B never awaits a decision from the other host.
            aToB.closed shouldBe true
            bToA.closed shouldBe true
        }

        val admittedA = PARTITION_BUDGET - ledgerA.balance(P, ClaimClass.Interest)
        val admittedB = PARTITION_BUDGET - ledgerB.balance(P, ClaimClass.Interest)
        ledgerA.balance(P, ClaimClass.Interest) shouldBe 0L
        ledgerA.balance(P, ClaimClass.Retention) shouldBe 0L
        ledgerB.balance(P, ClaimClass.Interest) shouldBe 0L
        ledgerB.balance(P, ClaimClass.Retention) shouldBe 0L
        // [ECO1-PAR-04] states the deliberately local aggregate: 2B = 4. This is
        // not a global bound on P; [ECO1-NV-03] says no such bound is asserted here.
        admittedA shouldBe 2L
        admittedB shouldBe 2L
        (admittedA + admittedB) shouldBe 4L
        a.host.boundaryDenialCount() shouldBe 1L
        b.host.boundaryDenialCount() shouldBe 1L
        a.replication.linkCountAmong(setOf(aReplicas[2].ref, pReplicas[2].ref)) shouldBe 0
        b.replication.linkCountAmong(setOf(bReplicas[2].ref, pReplicas[2].ref)) shouldBe 0

        val beforeHeal = listOf(
            ledgerA.balance(P, ClaimClass.Interest),
            ledgerA.balance(P, ClaimClass.Retention),
            ledgerB.balance(P, ClaimClass.Interest),
            ledgerB.balance(P, ClaimClass.Retention),
            ledgerA.charges.count { it.stamp.id == P },
            ledgerB.charges.count { it.stamp.id == P },
        )

        // The partition was real: every A↔B announcement was dropped, so neither
        // side ever learned the other's instances.
        a.registry.location(bReplicas[0].ref) shouldBe null
        b.registry.location(aReplicas[0].ref) shouldBe null

        // Opening a frame gate re-sends nothing: the announcements dropped while it
        // was closed are gone, and no frame crosses any of the six directions if the
        // gates are merely opened. The heal is therefore a fresh A↔B connection
        // instance, whose catch-up announcements now pass the open gates.
        aToB.closed = false
        bToA.closed = false
        abLink.heal()
        controller.runToIdle()

        // The heal delivered: each side now knows the other's instances and has
        // charged the other's stamp from its own ledger. Those charges are A's and
        // B's own principals; they are never folded into the P assertions below.
        a.registry.location(bReplicas[0].ref) shouldNotBe null
        b.registry.location(aReplicas[0].ref) shouldNotBe null
        ledgerA.charges.any { it.stamp.id == B } shouldBe true
        ledgerB.charges.any { it.stamp.id == A } shouldBe true

        val afterHeal = listOf(
            ledgerA.balance(P, ClaimClass.Interest),
            ledgerA.balance(P, ClaimClass.Retention),
            ledgerB.balance(P, ClaimClass.Interest),
            ledgerB.balance(P, ClaimClass.Retention),
            ledgerA.charges.count { it.stamp.id == P },
            ledgerB.charges.count { it.stamp.id == P },
        )
        afterHeal shouldBe beforeHeal

        val recorders = listOf(pToA, aToP, pToB, bToP, aToB, bToA)
        recorders.forEach { it.frames.shouldNotBeEmpty() }
        check(aToB.framesWhileClosed.isNotEmpty() || bToA.framesWhileClosed.isNotEmpty()) {
            "no A↔B frame was recorded while the partition gate was closed"
        }
        val forbidden = listOf(
            "BudgetClaim",
            "BudgetOutcome",
            "ClaimClass",
            "Retention",
            "RecordingLedger",
            "balance",
            "shortfall",
        )
        recorders.flatMap { it.frames }.forEach { frame ->
            val decoded = String(frame, StandardCharsets.UTF_8)
            forbidden.forEach { token -> decoded shouldNotContain token }
        }
    }

    @Test
    fun `divergence and reconvergence release and rehold Retention without a second Interest debit`() {
        val controller = SimulationController(0)
        val ledgerA = RecordingLedger("a-host", initial = DIVERGENCE_BUDGET, dedupKeys = true)
        val p = Peer(controller, P)
        val a = Peer(controller, A, ledgerA)
        val link = Peering.loopback(p.side, a.side)
        val logicalId = UUID.randomUUID()
        val onP = p.replica(logicalId, 0)
        val onA = a.replica(logicalId, 1)
        controller.runToIdle()

        a.ops(onA).add("a-before")
        p.ops(onP).add("p-before")
        controller.runToIdle()
        val interestAfterLink = ledgerA.balance(P, ClaimClass.Interest)
        val retentionAfterLink = ledgerA.balance(P, ClaimClass.Retention)
        interestAfterLink shouldBe DIVERGENCE_BUDGET - 1L
        retentionAfterLink shouldBe DIVERGENCE_BUDGET - 1L

        link.partition()
        controller.runToIdle()
        ledgerA.balance(P, ClaimClass.Retention) shouldBe DIVERGENCE_BUDGET
        ledgerA.balance(P, ClaimClass.Interest) shouldBe interestAfterLink

        a.ops(onA).add("a-during")
        p.ops(onP).add("p-during")
        controller.runToIdle()
        onA.membership() shouldNotBe onP.membership()

        link.heal()
        controller.runToIdle()
        val union = setOf("a-before", "p-before", "a-during", "p-during")
        onA.membership() shouldBe union
        onP.membership() shouldBe union
        ledgerA.balance(P, ClaimClass.Retention) shouldBe retentionAfterLink
        // The re-presented (A, P) key is handed to the deduplicating ledger again,
        // but the kernel never turns that replay into another debit.
        ledgerA.balance(P, ClaimClass.Interest) shouldBe interestAfterLink

        val interestClaims = ledgerA.charges.filter {
            it.stamp.id == P && it.claimClass == ClaimClass.Interest
        }
        interestClaims.size shouldBe 2
        interestClaims.map { it.key } shouldBe listOf(onA.ref to onP.ref, onA.ref to onP.ref)
        ledgerA.charges.forEach { claim ->
            claim.stamp.id shouldBe P
            claim.key?.let { key ->
                val pair = key as Pair<*, *>
                val localRef = pair.first as CellRef
                a.registry.locate(localRef) shouldBe a.host
            }
        }
        // P uses the default Unlimited host budget, so no P-side RecordingLedger exists;
        // the ledger state above is entirely A-local. A budget has no CellRef and its
        // "never enters registry.instances" property is therefore vacuous by type, not
        // asserted as a runtime membership check.
        p.budget shouldBe BudgetLedger.Unlimited
    }

    @Test
    fun `budget seam types are not replicable and budget vocabulary stays at the charge site`() {
        listOf(
            RecordingLedger::class.java,
            BudgetLedger::class.java,
            BudgetClaim::class.java,
            BudgetOutcome.Admitted::class.java,
            BudgetOutcome.Refused::class.java,
        ).forEach { type ->
            Replicable::class.java.isAssignableFrom(type) shouldBe false
        }

        val root = repoRoot()
        val cellRoot = File(root, "kernel/src/main/kotlin/civictech/cell")
        val scannedFiles = listOf("wire", "replication").flatMap { directory ->
            File(cellRoot, directory).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }
        check(scannedFiles.size > 2) {
            "source scan found only ${scannedFiles.size} files under wire/ and replication/"
        }

        val vocabulary = listOf("BudgetLedger", "BudgetOutcome", "BudgetClaim", "chargeThroughAncestors")
        val references = scannedFiles.associate { file ->
            file.relativeTo(cellRoot).path.replace(File.separatorChar, '/') to file.readText()
        }
        val offenders = references.filterKeys { it != "replication/Replication.kt" }
            .filterValues { source -> vocabulary.any(source::contains) }
            .keys
        offenders shouldBe emptySet()
        references.getValue("replication/Replication.kt").contains("chargeThroughAncestors") shouldBe true
    }

    private fun repoRoot(): File {
        var directory = File(System.getProperty("user.dir")).absoluteFile
        while (!File(directory, "settings.gradle.kts").isFile) {
            directory = directory.parentFile
                ?: error("Could not find settings.gradle.kts walking up from ${System.getProperty("user.dir")}")
        }
        return directory
    }
}
