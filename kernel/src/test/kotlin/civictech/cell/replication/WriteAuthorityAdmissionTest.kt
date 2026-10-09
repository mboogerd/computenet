package civictech.cell.replication

import civictech.cell.CellRef
import civictech.cell.DenialReason
import civictech.cell.Propagate
import civictech.cell.Timestamp
import civictech.cell.data.OrMapCell
import civictech.cell.data.SetCell
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.cell.wire.ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT
import civictech.cell.wire.WireCodec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
    fun `checkpoint compaction drops only the author's covered data envelopes and keeps transfers`() {
        val verifier = verifier(known)
        val state = AuthorityState(WriteAuthority.Open)
        val dot = Timestamp(logicalId, 1)
        val dataOne = write(p, 1, SetDelta(adds = mapOf("one" to setOf(dot))))
        val transfer = write(p, 2, TransferAuthority(q))
        admitAndApply(state, dataOne, verifier)
        admitAndApply(state, transfer, verifier)

        val firstFold = SetDelta(adds = mapOf("one" to setOf(dot)))
        val firstCheckpoint = write(p, 3, AuthorCheckpoint(coversThrough = 1, folded = firstFold))
        admitAndApply(state, firstCheckpoint, verifier) shouldBe listOf(dataOne)

        val dataFour = write(p, 4, SetDelta(adds = mapOf("four" to setOf(Timestamp(logicalId, 4)))))
        val dataFive = write(p, 5, SetDelta(adds = mapOf("five" to setOf(Timestamp(logicalId, 5)))))
        val otherAuthor = write(q, 1, SetDelta(adds = mapOf("q" to setOf(Timestamp(logicalId, 6)))))
        val otherLane = write(
            p,
            (1L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 1L,
            SetDelta(adds = mapOf("next-lane" to setOf(Timestamp(logicalId, 7)))),
        )
        listOf(dataFour, dataFive, otherAuthor, otherLane).forEach { admitAndApply(state, it, verifier) }

        val finalCheckpoint = write(
            p,
            6,
            AuthorCheckpoint(coversThrough = 4, folded = firstFold.merge(decodeSet(dataFour))),
        )
        admitAndApply(state, finalCheckpoint, verifier) shouldBe listOf(firstCheckpoint, dataFour)
        state.retained() shouldBe listOf(transfer, dataFive, otherAuthor, otherLane, finalCheckpoint)

        val principalState = AuthorityState(WriteAuthority.Principal(p))
        val principalTransfer = write(p, 10, TransferAuthority(q))
        admitAndApply(principalState, principalTransfer, verifier)
        val qData = write(q, 1, SetDelta(adds = mapOf("new-owner" to setOf(dot))))
        admitAndApply(principalState, qData, verifier)
        val qCheckpoint = write(q, 2, AuthorCheckpoint(coversThrough = 1, folded = decodeSet(qData)))
        admitAndApply(principalState, qCheckpoint, verifier) shouldBe listOf(qData)

        principalState.retained() shouldBe listOf(principalTransfer, qCheckpoint)
        principalState.admit(write(q, 3, SetDelta(adds = mapOf("still-current" to setOf(dot)))), verifier)
            .shouldBeInstanceOf<Admission.Admitted>()
        principalState.admit(write(p, 11, SetDelta(adds = mapOf("retired" to setOf(dot)))), verifier)
            .denied().reason shouldBe DenialReason.UNAUTHORIZED_WRITER
    }

    @Test
    fun `a covered counter is refused as replay and an uncovered one is admitted`() {
        val verifier = verifier(known)
        val state = AuthorityState(WriteAuthority.Principal(p))
        val covered = write(p, 2, SetDelta(adds = mapOf("covered" to emptySet())))
        admitAndApply(state, covered, verifier)
        val checkpoint = write(
            p,
            5,
            AuthorCheckpoint(coversThrough = 3, folded = decodeSet(covered)),
        )
        admitAndApply(state, checkpoint, verifier) shouldBe listOf(covered)

        state.isCovered(p, 2) shouldBe true
        state.isCovered(p, 3) shouldBe true
        state.isCovered(p, 4) shouldBe false
        state.coveredThrough(p, 0) shouldBe 3

        val replay = state.admit(covered, verifier).denied()
        replay.reason shouldBe DenialReason.REPLAY
        replay.detail.orEmpty() shouldContain "checkpoint through 3"

        val forgedCovered = covered.copy(
            signature = covered.signature.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() },
        )
        state.admit(forgedCovered, verifier).denied().reason shouldBe DenialReason.BAD_SIGNATURE
        state.admit(write(p, 4, SetDelta(adds = mapOf("uncovered" to emptySet()))), verifier)
            .shouldBeInstanceOf<Admission.Admitted>()

        val nextLaneCounter = (1L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 2L
        state.isCovered(p, nextLaneCounter) shouldBe false
        state.admit(write(p, nextLaneCounter, SetDelta(adds = mapOf("next-lane" to emptySet()))), verifier)
            .shouldBeInstanceOf<Admission.Admitted>()
    }

    @Test
    fun `forged unauthorized and malformed checkpoints are refused without compacting`() {
        val verifier = verifier(known)
        val state = AuthorityState(WriteAuthority.Principal(p))
        val seed = write(p, 1, SetDelta(adds = mapOf("owned" to emptySet())))
        admitAndApply(state, seed, verifier)
        val retainedBefore = state.retained()

        val forged = signedBytes(
            p,
            8,
            WriteAuthorityBytes.encodePayload(
                AuthorCheckpoint(coversThrough = 7, folded = SetDelta(adds = mapOf("owned" to emptySet()))),
            ),
            qSecret,
        )
        state.admit(forged, verifier).denied().reason shouldBe DenialReason.BAD_SIGNATURE

        val unauthorized = write(
            q,
            8,
            AuthorCheckpoint(coversThrough = 7, folded = SetDelta(adds = mapOf("owned" to emptySet()))),
        )
        state.admit(unauthorized, verifier).denied().reason shouldBe DenialReason.UNAUTHORIZED_WRITER

        val selfCovering = write(
            p,
            9,
            AuthorCheckpoint(coversThrough = 9, folded = SetDelta(adds = mapOf("owned" to emptySet()))),
        )
        val wrongLane = write(
            p,
            (1L shl ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT) or 2L,
            AuthorCheckpoint(coversThrough = 1, folded = SetDelta(adds = mapOf("owned" to emptySet()))),
        )
        val nonDelta = write(p, 10, AuthorCheckpoint(coversThrough = 9, folded = "not a delta"))
        listOf(selfCovering, wrongLane, nonDelta).forEach { malformed ->
            val denial = state.admit(malformed, verifier).denied()
            denial.reason shouldBe DenialReason.UNSIGNED
            denial.detail.orEmpty() shouldContain "author checkpoint"
        }

        state.retained() shouldBe retainedBefore
        state.coveredThrough(p, 0) shouldBe null
        state.coveredThrough(p, 1) shouldBe null
        state.authorizesLocal(p, AuthorCheckpoint(0, SetDelta(adds = mapOf("owned" to emptySet())))) shouldBe true
        state.authorizesLocal(q, AuthorCheckpoint(0, SetDelta(adds = mapOf("owned" to emptySet())))) shouldBe false

        val owners = mapOf<Any?, PeerId>("owned" to p, "foreign" to q)
        val perElement = AuthorityState(WriteAuthority.PerElementOwner(OwnerOf(owners::get)))
        val ownedCheckpoint = AuthorCheckpoint(4, SetDelta(adds = mapOf("owned" to emptySet())))
        val foreignCheckpoint = AuthorCheckpoint(5, SetDelta(adds = mapOf("foreign" to emptySet())))
        perElement.admit(write(p, 5, ownedCheckpoint), verifier).shouldBeInstanceOf<Admission.Admitted>()
        perElement.admit(write(p, 6, foreignCheckpoint), verifier).denied().reason shouldBe
            DenialReason.UNAUTHORIZED_WRITER
        perElement.authorizesLocal(p, ownedCheckpoint) shouldBe true
        perElement.authorizesLocal(p, foreignCheckpoint) shouldBe false
        perElement.retained() shouldBe emptyList()
    }

    @Test
    fun `foldOwn joins the author's lane and equals applying its envelopes individually`() {
        val verifier = verifier(known)
        val setState = AuthorityState(WriteAuthority.Open)
        val one = Timestamp(logicalId, 1)
        val two = Timestamp(logicalId, 2)
        val three = Timestamp(logicalId, 3)
        val setOne = SetDelta(adds = mapOf("removed" to setOf(one), "kept" to setOf(two)))
        val setTwo = SetDelta(dels = mapOf("removed" to setOf(one)))
        admitAndApply(setState, write(p, 1, setOne), verifier)
        admitAndApply(setState, write(p, 2, setTwo), verifier)
        val checkpointFold = setOne.merge(setTwo)
        admitAndApply(setState, write(p, 3, AuthorCheckpoint(2, checkpointFold)), verifier)
        val setFour = SetDelta(adds = mapOf("later" to setOf(three)))
        admitAndApply(setState, write(p, 4, setFour), verifier)
        admitAndApply(setState, write(p, 5, TransferAuthority(q)), verifier)
        admitAndApply(setState, write(q, 6, SetDelta(adds = mapOf("other" to setOf(three)))), verifier)

        setState.uncoveredOwnDataCount(p, 0) shouldBe 1
        val setFold = setState.foldOwn(p, 0).shouldBeInstanceOf<AuthorityState.Fold>()
        setFold.coversThrough shouldBe 4
        setFold.folded shouldBe checkpointFold.merge(setFour)
        setState.foldOwn(PeerId("absent"), 0) shouldBe null

        val individualSet = SetCell<String>()
        individualSet.deltaInlet.call.propagate(setFour)
        individualSet.deltaInlet.call.propagate(checkpointFold)
        val foldedSet = SetCell<String>()
        foldedSet.deltaInlet.call.propagate(setFold.folded.shouldBeInstanceOf<SetDelta<String>>())
        foldedSet.membership() shouldBe individualSet.membership()
        individualSet.deltaInlet.call.propagate(setOne)
        foldedSet.deltaInlet.call.propagate(setOne)
        foldedSet.membership() shouldBe individualSet.membership()

        val mapState = AuthorityState(WriteAuthority.Open)
        val mapOne = TaggedMapDelta(
            puts = mapOf("removed" to mapOf(one to "old"), "kept" to mapOf(two to "value")),
        )
        val mapTwo = TaggedMapDelta<String, String>(dels = mapOf("removed" to setOf(one)))
        admitAndApply(mapState, write(p, 1, mapOne), verifier)
        admitAndApply(mapState, write(p, 2, mapTwo), verifier)
        val mapCheckpointFold = mapOne.merge(mapTwo)
        admitAndApply(mapState, write(p, 3, AuthorCheckpoint(2, mapCheckpointFold)), verifier)
        val mapFour = TaggedMapDelta(puts = mapOf("later" to mapOf(three to "new")))
        admitAndApply(mapState, write(p, 4, mapFour), verifier)

        mapState.uncoveredOwnDataCount(p, 0) shouldBe 1
        val mapFold = mapState.foldOwn(p, 0).shouldBeInstanceOf<AuthorityState.Fold>()
        mapFold.coversThrough shouldBe 4
        mapFold.folded shouldBe mapCheckpointFold.merge(mapFour)

        val individualMap = OrMapCell<String, String>()
        individualMap.deltaInlet.call.propagate(mapFour)
        individualMap.deltaInlet.call.propagate(mapCheckpointFold)
        val foldedMap = OrMapCell<String, String>()
        foldedMap.deltaInlet.call.propagate(mapFold.folded.shouldBeInstanceOf<TaggedMapDelta<String, String>>())
        foldedMap.membership() shouldBe individualMap.membership()
        listOf("removed", "kept", "later").forEach { key ->
            foldedMap.value(key) shouldBe individualMap.value(key)
        }
        individualMap.deltaInlet.call.propagate(mapOne)
        foldedMap.deltaInlet.call.propagate(mapOne)
        foldedMap.membership() shouldBe individualMap.membership()
        foldedMap.value("removed") shouldBe individualMap.value("removed")
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

    private fun signedBytes(
        author: PeerId,
        counter: Long,
        payload: ByteArray,
        signingSecret: ByteArray = known.getValue(author),
    ): SignedWrite {
        val unsigned = SignedWrite(logicalId, author, counter, payload, ByteArray(0))
        return unsigned.copy(signature = digest(signingSecret, unsigned.signingInput()))
    }

    private fun admitAndApply(
        state: AuthorityState,
        write: SignedWrite,
        verifier: SignatureVerifier,
    ): List<SignedWrite> {
        val admitted = state.admit(write, verifier).shouldBeInstanceOf<Admission.Admitted>()
        return state.apply(write, admitted.payload)
    }

    private fun decodeSet(write: SignedWrite): SetDelta<String> =
        WriteAuthorityBytes.decodePayload(write.payload).shouldBeInstanceOf()

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
