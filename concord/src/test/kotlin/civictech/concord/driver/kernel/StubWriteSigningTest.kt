package civictech.concord.driver.kernel

import civictech.cell.data.delta.SetDelta
import civictech.cell.replication.WriteAuthorityBytes
import io.kotest.matchers.shouldBe
import java.util.UUID
import kotlin.test.Test

class StubWriteSigningTest {
    @Test
    fun `registered actors verify deterministically while an unknown actor has no key`() {
        val logical = UUID.fromString("00000000-0000-0000-0000-000000000043")
        val first = StubWriteSigning()
        val write = first.signed("alice", logical, SetDelta<String>())
        first.verifier.verify(write.author, write.counter, write, write.signature) shouldBe true

        val second = StubWriteSigning()
        second.principal("alice")
        second.verifier.verify(write.author, write.counter, write, write.signature) shouldBe true

        val unknown = StubWriteSigning()
        unknown.verifier.verify(write.author, write.counter, write, write.signature) shouldBe false
    }

    @Test
    fun `signature covers the envelope input and actor tag lanes are stable and monotone`() {
        val logical = UUID.fromString("00000000-0000-0000-0000-000000000044")
        val signing = StubWriteSigning()
        val write = signing.signed("alice", logical, "payload")
        val forged = write.copy(payload = WriteAuthorityBytes.encodePayload("other"))
        signing.verifier.verify(forged.author, forged.counter, forged, forged.signature) shouldBe false

        val one = signing.freshTag("alice", logical)
        val two = signing.freshTag("alice", logical)
        one.sourceId shouldBe two.sourceId
        two.counter shouldBe one.counter + 1L
    }

    @Test
    fun `forged write preserves the signed tuple but fails verification`() {
        val logical = UUID.fromString("00000000-0000-0000-0000-000000000045")
        val signing = StubWriteSigning()
        val forged = signing.forged("alice", logical, "payload")

        forged.author shouldBe signing.principal("alice")
        signing.verifier.verify(forged.author, forged.counter, forged, forged.signature) shouldBe false
    }
}
