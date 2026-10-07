package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT
import civictech.cell.wire.WireCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.UUID

class WriteAuthorityAdmissionTest {
    private val logicalId = UUID.fromString("00000000-0000-0000-0000-000000000123")
    private val p = PeerId("principal-p")
    private val q = PeerId("principal-q")
    private val pSecret = "p-secret".toByteArray()
    private val qSecret = "q-secret".toByteArray()
    private val known = mapOf(p to pSecret, q to qSecret)

    @Test
    fun `principal admits its signed payload and distinguishes unauthorized unknown-key and forged writes`() {
        val payload = SetDelta(adds = mapOf("owned" to setOf(Timestamp(logicalId, 1))))
        val state = AuthorityState(WriteAuthority.Principal(p))
        val verifier = verifier(known)

        state.admit(write(p, 1, payload), verifier) shouldBe Admission.Admitted(payload)

        state.admit(write(q, 2, payload), verifier).denied() shouldHaveDenial
            (DenialReason.UNAUTHORIZED_WRITER to q)

        state.admit(write(p, 3, payload), verifier(emptyMap())).denied() shouldHaveDenial
            (DenialReason.BAD_SIGNATURE to p)

        val signed = write(p, 4, payload)
        val forged = signed.copy(signature = signed.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
        state.admit(forged, verifier).denied() shouldHaveDenial (DenialReason.BAD_SIGNATURE to p)
    }

    @Test
    fun `reorder arm admits an unseen lower counter after a retained higher counter and replays exact pairs`() {
        val state = AuthorityState(WriteAuthority.Principal(p))
        val verifier = verifier(known)
        val seven = write(p, 7, SetDelta(adds = mapOf("seven" to emptySet())))
        val admittedSeven = state.admit(seven, verifier).shouldBeInstanceOf<Admission.Admitted>()
        state.apply(seven, admittedSeven.payload)

        val six = write(p, 6, SetDelta(adds = mapOf("six" to emptySet())))
        state.admit(six, verifier).shouldBeInstanceOf<Admission.Admitted>()

        state.admit(seven, verifier).denied().reason shouldBe DenialReason.REPLAY
        val resignedDifferentPayload = write(p, 7, SetDelta(adds = mapOf("different" to emptySet())))
        state.admit(resignedDifferentPayload, verifier).denied().reason shouldBe DenialReason.REPLAY
    }

    @Test
    fun `per-element owner admits set and tagged-map deltas only when every touched key belongs to the author`() {
        val owners = mapOf<Any?, PeerId>(
            "set-add" to p,
            "set-del" to p,
            "map-put" to p,
            "map-del" to p,
            "foreign" to q,
        )
        val state = AuthorityState(WriteAuthority.PerElementOwner(OwnerOf(owners::get)))
        val verifier = verifier(known)
        val dot = Timestamp(logicalId, 1)

        val set = SetDelta(
            adds = mapOf("set-add" to setOf(dot)),
            dels = mapOf("set-del" to setOf(dot)),
        )
        state.admit(write(p, 1, set), verifier) shouldBe Admission.Admitted(set)

        val taggedMap = TaggedMapDelta(
            puts = mapOf("map-put" to mapOf(dot to "value")),
            dels = mapOf("map-del" to setOf(dot)),
        )
        state.admit(write(p, 2, taggedMap), verifier) shouldBe Admission.Admitted(taggedMap)

        val foreign = SetDelta(adds = mapOf("foreign" to setOf(dot)))
        state.admit(write(p, 3, foreign), verifier).denied() shouldHaveDenial
            (DenialReason.UNAUTHORIZED_WRITER to p)

        val noOwner = TaggedMapDelta(puts = mapOf("unowned" to mapOf(dot to "value")))
        state.admit(write(p, 4, noOwner), verifier).denied() shouldHaveDenial
            (DenialReason.UNAUTHORIZED_WRITER to p)
    }

    @Test
    fun `per-element owner refuses transfer and uninspectable payload while malformed bytes are unsigned`() {
        val state = AuthorityState(WriteAuthority.PerElementOwner(OwnerOf { p }))
        val verifier = verifier(known)

        state.admit(write(p, 1, TransferAuthority(q)), verifier).denied() shouldHaveDenial
            (DenialReason.UNAUTHORIZED_WRITER to p)
        state.admit(write(p, 2, "not a delta"), verifier).denied().reason shouldBe DenialReason.UNSIGNED

        val garbage = signedBytes(p, 3, byteArrayOf(0x13, 0x37))
        val denial = state.admit(garbage, verifier).denied()
        denial.reason shouldBe DenialReason.UNSIGNED
        denial.principal shouldBe p
        (denial.detail != null) shouldBe true
    }

    @Test
    fun `principal transfer admits the new owner and only pre-transfer writes by the former owner`() {
        val state = AuthorityState(WriteAuthority.Principal(p))
        val verifier = verifier(known)
        val transfer = write(p, 10, TransferAuthority(q))
        val admittedTransfer = state.admit(transfer, verifier).shouldBeInstanceOf<Admission.Admitted>()
        state.apply(transfer, admittedTransfer.payload)

        state.admit(write(q, 1, SetDelta(adds = mapOf("new" to emptySet()))), verifier)
            .shouldBeInstanceOf<Admission.Admitted>()
        state.admit(write(p, 9, SetDelta(adds = mapOf("before" to emptySet()))), verifier)
            .shouldBeInstanceOf<Admission.Admitted>()
        state.admit(write(p, 11, SetDelta(adds = mapOf("after" to emptySet()))), verifier)
            .denied().reason shouldBe DenialReason.UNAUTHORIZED_WRITER
        state.admit(write(p, 12, TransferAuthority(PeerId("principal-r"))), verifier)
            .denied().reason shouldBe DenialReason.UNAUTHORIZED_WRITER
    }

    @Test
    fun `counting signer assigns per-logical-id incarnation counters and reads incarnation once`() {
        val signedInputs = mutableListOf<ByteArray>()
        val delegate = object : WriteSigner {
            override val peerId: PeerId = p
            override fun sign(input: ByteArray): ByteArray {
                signedInputs += input.copyOf()
                return digest(pSecret, input)
            }
        }
        var incarnationReads = 0
        val signer = CountingWriteSigner(delegate) { incarnationReads += 1; 7L }
        val anotherLogicalId = UUID.fromString("00000000-0000-0000-0000-000000000456")

        val first = signer.sign(logicalId, byteArrayOf(1))
        val second = signer.sign(logicalId, byteArrayOf(2))
        val firstForOtherCell = signer.sign(anotherLogicalId, byteArrayOf(3))

        first.counter shouldBe ((7L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 1L)
        second.counter shouldBe ((7L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 2L)
        firstForOtherCell.counter shouldBe ((7L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 1L)
        incarnationReads shouldBe 1
        signedInputs[0].contentEquals(first.signingInput()) shouldBe true

        val restarted = CountingWriteSigner(delegate) { 8L }.sign(logicalId, byteArrayOf(4))
        (restarted.counter > listOf(first.counter, second.counter, firstForOtherCell.counter).max()) shouldBe true
    }

    @Test
    fun `signed write and batch round-trip as hosted arguments with content equality and no version bump`() {
        val one = write(p, 1, SetDelta(adds = mapOf("one" to emptySet())))
        val two = write(p, 2, SetDelta(adds = mapOf("two" to emptySet())))
        val equalCopy = one.copy(payload = one.payload.copyOf(), signature = one.signature.copyOf())
        one shouldBe equalCopy
        one.hashCode() shouldBe equalCopy.hashCode()

        roundTripArgument(one) shouldBe one
        roundTripArgument(SignedWriteBatch(listOf(one, two))) shouldBe SignedWriteBatch(listOf(one, two))
        WireCodec.VERSION shouldBe 2
    }

    @Test
    fun `unauthorized writer denial immediately follows replay`() {
        val replay = DenialReason.entries.indexOf(DenialReason.REPLAY)
        DenialReason.entries[replay + 1] shouldBe DenialReason.UNAUTHORIZED_WRITER
    }

    private fun write(author: PeerId, counter: Long, payload: Any?): SignedWrite =
        signedBytes(author, counter, WriteAuthorityBytes.encodePayload(payload))

    private fun signedBytes(author: PeerId, counter: Long, payload: ByteArray): SignedWrite {
        val unsigned = SignedWrite(logicalId, author, counter, payload, ByteArray(0))
        val secret = known.getValue(author)
        return unsigned.copy(signature = digest(secret, unsigned.signingInput()))
    }

    private fun verifier(directory: Map<PeerId, ByteArray>): SignatureVerifier =
        SignatureVerifier { author, counter, payload, signature ->
            val secret = directory[author]
            val input = runCatching { WriteAuthorityBytes.canonicalBytes(author, counter, payload) }.getOrNull()
            secret != null && input != null && digest(secret, input).contentEquals(signature)
        }

    private fun digest(secret: ByteArray, input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(secret + input)

    private fun Admission.denied(): Admission.Denied = shouldBeInstanceOf()

    private infix fun Admission.Denied.shouldHaveDenial(expected: Pair<DenialReason, PeerId>) {
        reason shouldBe expected.first
        principal shouldBe expected.second
    }

    private fun roundTripArgument(value: Any?): Any? {
        val propagate = Propagate::class.java.getMethod("propagate", Any::class.java)
        val frame = HostedPortInvocation(
            cellRef = CellRef(logicalId),
            portName = "deltaInlet",
            type = HostedPortInvocation.Type.PORT_API,
            invocation = Invocation.of(propagate, arrayOf(value), null),
        )
        return WireCodec.decode(WireCodec.encode(frame)).invocation.args.single()
    }
}
