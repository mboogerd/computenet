package civictech.cell.replication

import civictech.cell.DenialReason
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.host.SimulationController
import civictech.cell.link.PeerId
import civictech.cell.wire.Peering
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class OwnershipTransferTest {
    private val pA = PeerId("owner-a")
    private val pB = PeerId("owner-b")
    private val pC = PeerId("owner-c")
    private val pD = PeerId("late-d")

    @Test
    fun `transfer changes the writer on every replica and late join catch-up teaches the chain`() {
        val controller = SimulationController()
        val signing = StubWriteSigning(pA, pB, pC, pD)
        val a = AuthorityTestPeer(controller, pA, signing)
        val b = AuthorityTestPeer(controller, pB, signing)
        val c = AuthorityTestPeer(controller, pC, signing)
        Peering.loopback(a.side, b.side)
        Peering.loopback(a.side, c.side)
        Peering.loopback(b.side, c.side)
        val logicalId = UUID.randomUUID()
        val authority = WriteAuthority.Principal(pA)
        val onA = a.replica(logicalId, 0, authority)
        val onB = b.replica(logicalId, 1, authority)
        val onC = c.replica(logicalId, 2, authority)
        controller.runToIdle()

        a.replication.authorityOf(onA.ref)!!.transfer(pB)
        controller.runToIdle()
        b.ops(onB).add("by-b")
        controller.runToIdle()
        listOf(onA, onB, onC).map { it.membership() } shouldBe List(3) { setOf("by-b") }

        a.ops(onA).add("old-local")
        controller.runToIdle()
        onA.membership() shouldBe setOf("by-b")
        a.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }

        val oldRemote = signing.signed(logicalId, pA, 50, add("old-remote", 50))
        a.delta(onB.ref).propagate(oldRemote)
        a.delta(onC.ref).propagate(oldRemote)
        controller.runToIdle()
        listOf(b, c).forEach { peer ->
            peer.deadLetters.last().denial!!.run {
                reason shouldBe DenialReason.UNAUTHORIZED_WRITER
                principal shouldBe pA
            }
        }

        val beforeSecondTransfer = a.deadLetters.size
        a.replication.authorityOf(onA.ref)!!.transfer(pC)
        controller.runToIdle()
        a.deadLetters.size shouldBe beforeSecondTransfer + 1
        a.deadLetters.last().denial!!.reason shouldBe DenialReason.UNAUTHORIZED_WRITER

        val d = AuthorityTestPeer(controller, pD, signing)
        Peering.loopback(b.side, d.side)
        val onD = d.replica(logicalId, 3, authority)
        controller.runToIdle()
        onD.membership() shouldBe setOf("by-b")

        b.delta(onD.ref).propagate(signing.signed(logicalId, pA, 51, add("late-old", 51)))
        controller.runToIdle()
        onD.membership() shouldBe setOf("by-b")
        d.deadLetters.last().denial!!.run {
            reason shouldBe DenialReason.UNAUTHORIZED_WRITER
            principal shouldBe pA
        }
    }

    @Test
    fun `former owner writes compare with its transfer counter in either arrival order`() {
        val signing = StubWriteSigning(pA, pB)
        val logicalId = UUID.randomUUID()
        val before = signing.signed(logicalId, pA, 9, add("before", 9))
        val transfer = signing.signed(logicalId, pA, 10, TransferAuthority(pB))
        val after = signing.signed(logicalId, pA, 11, add("after", 11))

        val writeThenTransfer = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(writeThenTransfer, before, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(writeThenTransfer, transfer, signing).shouldBeInstanceOf<Admission.Admitted>()

        val transferThenWrite = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(transferThenWrite, transfer, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(transferThenWrite, before, signing).shouldBeInstanceOf<Admission.Admitted>()

        listOf(writeThenTransfer, transferThenWrite).forEach { state ->
            state.admit(after, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
                DenialReason.UNAUTHORIZED_WRITER
            state.authorizesLocal(pB, add("new", 12)) shouldBe true
            state.authorizesLocal(pA, add("old", 12)) shouldBe false
        }
    }

    @Test
    fun `re-acquisition is refused and reordered competing transfers select the lower counter`() {
        val signing = StubWriteSigning(pA, pB, pC)
        val logicalId = UUID.randomUUID()
        val pToB = signing.signed(logicalId, pA, 10, TransferAuthority(pB))
        val bToP = signing.signed(logicalId, pB, 5, TransferAuthority(pA))
        val pToC = signing.signed(logicalId, pA, 20, TransferAuthority(pC))

        val causal = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(causal, pToB, signing).shouldBeInstanceOf<Admission.Admitted>()
        causal.admit(bToP, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER
        causal.admit(pToC, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER

        val reordered = AuthorityState(WriteAuthority.Principal(pA))
        admitApply(reordered, pToC, signing).shouldBeInstanceOf<Admission.Admitted>()
        admitApply(reordered, pToB, signing).shouldBeInstanceOf<Admission.Admitted>()
        reordered.admit(bToP, signing.verifier).shouldBeInstanceOf<Admission.Denied>().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER

        listOf(causal, reordered).forEach { state ->
            state.authorizesLocal(pA, add("a", 30)) shouldBe false
            state.authorizesLocal(pB, add("b", 30)) shouldBe true
            state.authorizesLocal(pC, add("c", 30)) shouldBe false
        }
    }

    private fun admitApply(
        state: AuthorityState,
        write: SignedWrite,
        signing: StubWriteSigning,
    ): Admission = state.admit(write, signing.verifier).also { admission ->
        if (admission is Admission.Admitted) state.apply(write, admission.payload)
    }

    private fun add(element: String, counter: Long): SetDelta<String> =
        SetDelta(adds = mapOf(element to setOf(Timestamp(UUID(1, counter), counter))))
}
