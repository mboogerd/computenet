package civictech.cell.replication

import civictech.cell.DenialReason
import civictech.cell.UuidSerializer
import civictech.cell.data.delta.SetDelta
import civictech.cell.data.delta.TaggedMapDelta
import civictech.cell.link.PeerId
import civictech.cell.membrane.SignatureVerifier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID

/** Who may author writes admitted by an authority-bearing replica. */
sealed interface WriteAuthority : java.io.Serializable {
    /** The compatibility default: no author restriction is applied. */
    data object Open : WriteAuthority

    /** One principal owns the whole replicated slice. */
    data class Principal(val id: PeerId) : WriteAuthority

    /** Every element touched by one delta must resolve to that delta's author. */
    data class PerElementOwner(val ownerOf: OwnerOf) : WriteAuthority
}

/** Serializable ownership projection used by [WriteAuthority.PerElementOwner]. */
fun interface OwnerOf : java.io.Serializable {
    fun ownerOf(element: Any?): PeerId?
}

private val ZERO_WRITE_COUNTER_INCARNATION: () -> Long = { 0L }

/** Kernel seam implemented by the runtime's identity-backed signer. */
interface WriteSigner {
    val peerId: PeerId

    /**
     * This signing identity's durable process incarnation, read once when its
     * [CountingWriteSigner] is constructed. The zero default preserves the
     * deterministic kernel-test and embedding seam; a runtime backed by a
     * persistent identity must override it so process restart cannot re-mint
     * retained `(author, counter)` pairs.
     */
    val counterIncarnation: () -> Long get() = ZERO_WRITE_COUNTER_INCARNATION

    fun sign(input: ByteArray): ByteArray
}

/**
 * Relay-independent, author-signed replication write.
 *
 * [signingInput] is the sole canonical encoding of the signed region. It is a
 * concatenation of four length-prefixed fields. Every length is an unsigned
 * value carried in four big-endian bytes (the JVM `Int` representation), and
 * the field bodies are, byte for byte:
 *
 * 1. `logicalId`: length `16`, then the UUID's most-significant and
 *    least-significant 64-bit words, each big-endian;
 * 2. `author`: the length of `author.name` encoded as UTF-8, then those bytes;
 * 3. `counter`: length `8`, then the signed 64-bit counter, big-endian;
 * 4. `payload`: its byte length, then the opaque Java-serialized bytes.
 *
 * Length-prefixing every field makes the concatenation injective without
 * asking a receiver to re-encode the decoded delta. The exact author bytes
 * therefore survive every relay and catch-up unchanged.
 */
@Serializable
@SerialName("SignedWrite")
data class SignedWrite(
    @Serializable(with = UuidSerializer::class)
    val logicalId: UUID,
    val author: PeerId,
    val counter: Long,
    val payload: ByteArray,
    val signature: ByteArray,
) : java.io.Serializable {
    fun signingInput(): ByteArray = signingInput(logicalId, author, counter, payload)

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SignedWrite &&
            logicalId == other.logicalId &&
            author == other.author &&
            counter == other.counter &&
            payload.contentEquals(other.payload) &&
            signature.contentEquals(other.signature)

    override fun hashCode(): Int {
        var result = logicalId.hashCode()
        result = 31 * result + author.hashCode()
        result = 31 * result + counter.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        private const val serialVersionUID: Long = 1L

        fun signingInput(
            logicalId: UUID,
            author: PeerId,
            counter: Long,
            payload: ByteArray,
        ): ByteArray {
            val logicalIdBytes = ByteBuffer.allocate(Long.SIZE_BYTES * 2)
                .putLong(logicalId.mostSignificantBits)
                .putLong(logicalId.leastSignificantBits)
                .array()
            val authorBytes = author.name.toByteArray(StandardCharsets.UTF_8)
            val counterBytes = ByteBuffer.allocate(Long.SIZE_BYTES).putLong(counter).array()

            return ByteArrayOutputStream().also { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeLengthPrefixed(logicalIdBytes)
                    output.writeLengthPrefixed(authorBytes)
                    output.writeLengthPrefixed(counterBytes)
                    output.writeLengthPrefixed(payload)
                }
            }.toByteArray()
        }

        private fun DataOutputStream.writeLengthPrefixed(bytes: ByteArray) {
            writeInt(bytes.size)
            write(bytes)
        }
    }
}

/** Catch-up unit; each envelope is admitted independently by the receiver. */
@Serializable
@SerialName("SignedWriteBatch")
data class SignedWriteBatch(val writes: List<SignedWrite>)

/** A write by the current principal that transfers the slice to [newPrincipal]. */
data class TransferAuthority(val newPrincipal: PeerId) : java.io.Serializable

/**
 * An author's signed fold of only its own data through [coversThrough].
 *
 * Per 7n5g2-D1, a relay may discard only that author's covered envelopes;
 * authority transfers are never part of [folded] and are never discarded.
 */
data class AuthorCheckpoint(
    val coversThrough: Long,
    val folded: Any?,
) : java.io.Serializable

/** The one failure shape exposed by [WriteAuthorityBytes.decodePayload]. */
class PayloadUndecodable(message: String, cause: Throwable) : IllegalArgumentException(message, cause)

/** Canonical signed bytes and the deliberately separate opaque payload codec. */
object WriteAuthorityBytes {
    /**
     * Adapter for `Ed25519SignatureVerifier`: the author and counter passed to
     * the generic seam must be the fields inside the [SignedWrite].
     */
    val canonicalBytes: (PeerId, Long, Any?) -> ByteArray = { author, counter, value ->
        val write = requireNotNull(value as? SignedWrite) {
            "write-authority canonical bytes require a SignedWrite"
        }
        require(write.author == author) { "write-authority author does not match verifier input" }
        require(write.counter == counter) { "write-authority counter does not match verifier input" }
        write.signingInput()
    }

    fun encodePayload(value: Any?): ByteArray = ByteArrayOutputStream().also { bytes ->
        ObjectOutputStream(bytes).use { it.writeObject(value) }
    }.toByteArray()

    @Throws(PayloadUndecodable::class)
    fun decodePayload(bytes: ByteArray): Any? = try {
        ObjectInputStream(ByteArrayInputStream(bytes)).use { it.readObject() }
    } catch (failure: Exception) {
        throw PayloadUndecodable("write-authority payload is not a Java-serialized value", failure)
    }
}

internal const val WRITE_COUNTER_INCARNATION_SHIFT: Int = 20
private const val WRITE_COUNTER_SEQUENCE_MASK: Long = (1L shl WRITE_COUNTER_INCARNATION_SHIFT) - 1L

/**
 * Assigns one replay counter sequence per [SignedWrite.logicalId] and delegates
 * only the cryptographic operation. The incarnation is read once at
 * construction. Its 20-bit sequence partition intentionally matches
 * `ANNOUNCEMENT_COUNTER_INCARNATION_SHIFT` without importing `.wire` into
 * `.replication` and creating an unrelated architecture edge.
 */
class CountingWriteSigner(
    private val delegate: WriteSigner,
    incarnation: () -> Long = delegate.counterIncarnation,
) {
    val peerId: PeerId get() = delegate.peerId

    val counterFloor: Long = incarnation().also { value ->
        require(value >= 0 && value <= (Long.MAX_VALUE ushr WRITE_COUNTER_INCARNATION_SHIFT)) {
            "write counter incarnation out of range: $value"
        }
    } shl WRITE_COUNTER_INCARNATION_SHIFT

    private val sequences = mutableMapOf<UUID, Long>()

    /** Assign the next counter for [logicalId], sign, and return its envelope. */
    @Synchronized
    fun sign(logicalId: UUID, payload: ByteArray): SignedWrite = nextWrite(logicalId, payload)

    /**
     * Assign and sign, then complete [writeAhead] before returning the envelope.
     *
     * The sequence is reserved before either signing or persistence. If either
     * fails, this signer leaves a harmless counter gap rather than risking a
     * retry that reuses a pair whose append may actually have reached storage.
     */
    @Synchronized
    internal fun signWriteAhead(
        logicalId: UUID,
        payload: ByteArray,
        writeAhead: (SignedWrite) -> Unit,
    ): SignedWrite = nextWrite(logicalId, payload).also(writeAhead)

    /**
     * Continue [logicalId]'s lane strictly after [observedCounter].
     *
     * Recovery calls this after all retained/journaled envelopes have applied,
     * before signing an accepted local operation whose envelope was absent at
     * the crash. A counter from an older incarnation already lies below
     * [counterFloor]; one above this incarnation's ceiling is a fail-closed
     * incarnation rollback rather than a pair we can safely continue past.
     */
    @Synchronized
    internal fun continueAfter(logicalId: UUID, observedCounter: Long) {
        if (observedCounter < counterFloor) return
        val ceiling = counterFloor or WRITE_COUNTER_SEQUENCE_MASK
        require(observedCounter <= ceiling) {
            "retained write counter $observedCounter is above this signer's incarnation ceiling $ceiling"
        }
        val observedSequence = observedCounter - counterFloor
        sequences[logicalId] = maxOf(sequences[logicalId] ?: 0L, observedSequence)
    }

    private fun nextWrite(logicalId: UUID, payload: ByteArray): SignedWrite {
        val sequence = (sequences[logicalId] ?: 0L) + 1L
        require(sequence <= WRITE_COUNTER_SEQUENCE_MASK) {
            "write counter sequence exhausted for logical cell $logicalId in this incarnation"
        }
        sequences[logicalId] = sequence
        val counter = counterFloor or sequence
        val input = SignedWrite.signingInput(logicalId, peerId, counter, payload)
        return SignedWrite(logicalId, peerId, counter, payload, delegate.sign(input))
    }
}

/** Pure result of [AuthorityState.admit]. */
sealed interface Admission {
    data class Admitted(val payload: Any?) : Admission
    data class Denied(
        val reason: DenialReason,
        val principal: PeerId?,
        val detail: String?,
    ) : Admission
}

/**
 * Retained-pair and principal-transfer state for one logical replica.
 *
 * [admit] is a pure predicate: it never records a counter and never changes
 * the transfer chain. The caller must invoke [apply] only for an admitted
 * payload. Replay means exact retained `(author, counter)` membership, not a
 * high-water comparison; gossip may legitimately deliver counter 7 before
 * counter 6.
 *
 * Ownership cannot be transferred back to a former principal. Per-author
 * counters can order that author's writes around its transfer-out, but after
 * re-acquisition they cannot distinguish writes made during the intervening
 * owner's tenure from writes made after ownership returned. Supporting that
 * shape therefore needs an authority epoch in the signed envelope; without
 * one it would make admission order-dependent. Competing transfers by one
 * author are instead resolved by their counter, so a lower-counter transfer
 * delivered late deterministically supersedes a higher-counter one.
 *
 * That deterministic supersession converges the authority chain, not data
 * already admitted under the superseded edge. If an author equivocates by
 * signing competing transfers, data writes admitted under the briefly selected
 * edge stay applied on replicas that saw it first and may be refused elsewhere.
 * The in-process local gate prevents this equivocation, but a restarted author
 * without a durable chain can still produce it.
 *
 * Per 7n5g2-D1/D2, only an author may sign a fold of its own data. Applying
 * that checkpoint records per-author, per-incarnation-lane coverage and drops
 * only the covered author's data envelopes; authority transfers remain
 * retained so the canonical chain can always be rebuilt. Later verified
 * non-transfer arrivals inside a covered range are replay, while transfers
 * still enter ordinary chain admission and other authors and lanes remain
 * independent.
 */
class AuthorityState(private val authority: WriteAuthority) {
    data class Fold(val coversThrough: Long, val folded: Any?)

    private data class RetainedKey(val author: PeerId, val counter: Long)
    private data class Retained(val write: SignedWrite, val payload: Any?)
    private data class CoverageKey(val author: PeerId, val lane: Long)
    private data class Transfer(val from: PeerId, val atCounter: Long, val to: PeerId)

    private val retained = linkedMapOf<RetainedKey, Retained>()
    private val covered = mutableMapOf<CoverageKey, Long>()
    private val transfers = mutableListOf<Transfer>()
    private val initialPrincipal: PeerId? = (authority as? WriteAuthority.Principal)?.id
    private var currentPrincipal: PeerId? = initialPrincipal

    @Synchronized
    fun admit(write: SignedWrite, verifier: SignatureVerifier): Admission {
        val retainedKey = RetainedKey(write.author, write.counter)
        if (retainedKey in retained) {
            return Admission.Denied(
                DenialReason.REPLAY,
                write.author,
                "write-authority pair (${write.author.name}, ${write.counter}) is already retained",
            )
        }

        val verified = try {
            verifier.verify(write.author, write.counter, write, write.signature)
        } catch (_: RuntimeException) {
            false
        }
        if (!verified) {
            return Admission.Denied(
                DenialReason.BAD_SIGNATURE,
                write.author,
                "write-authority signature did not verify",
            )
        }

        val payload = try {
            WriteAuthorityBytes.decodePayload(write.payload)
        } catch (failure: PayloadUndecodable) {
            return Admission.Denied(
                DenialReason.UNSIGNED,
                write.author,
                failure.message,
            )
        }

        if (payload !is TransferAuthority) {
            covered[CoverageKey(write.author, laneOf(write.counter))]?.let { coversThrough ->
                if (write.counter <= coversThrough) {
                    return Admission.Denied(
                        DenialReason.REPLAY,
                        write.author,
                        "write-authority pair (${write.author.name}, ${write.counter}) " +
                            "is covered by that author's checkpoint through $coversThrough",
                    )
                }
            }
        }

        if (payload is AuthorCheckpoint) {
            malformedCheckpoint(write, payload)?.let { detail ->
                return Admission.Denied(DenialReason.UNSIGNED, write.author, detail)
            }
        }

        return when (authority) {
            WriteAuthority.Open -> Admission.Admitted(payload)
            is WriteAuthority.Principal -> admitPrincipal(write, payload)
            is WriteAuthority.PerElementOwner -> if (payload is AuthorCheckpoint) {
                // Authorize the folded elements, but admit the checkpoint itself so apply compacts.
                when (val admission = admitElements(write, payload.folded, authority.ownerOf)) {
                    is Admission.Admitted -> Admission.Admitted(payload)
                    is Admission.Denied -> admission
                }
            } else {
                admitElements(write, payload, authority.ownerOf)
            }
        }
    }

    /**
     * Record an admission, compact any author-covered data, and return the
     * envelopes removed by that compaction.
     */
    @Synchronized
    fun apply(write: SignedWrite, payload: Any?): List<SignedWrite> {
        val key = RetainedKey(write.author, write.counter)
        if (retained.putIfAbsent(key, Retained(write, payload)) != null) return emptyList()
        if (authority is WriteAuthority.Principal && payload is TransferAuthority) {
            transfers += Transfer(write.author, write.counter, payload.newPrincipal)
            currentPrincipal = canonicalTransfers().lastOrNull()?.to ?: initialPrincipal
        }
        if (payload !is AuthorCheckpoint) return emptyList()

        val lane = laneOf(write.counter)
        val coverageKey = CoverageKey(write.author, lane)
        covered[coverageKey] = maxOf(covered[coverageKey] ?: Long.MIN_VALUE, payload.coversThrough)

        val dropped = mutableListOf<SignedWrite>()
        val entries = retained.entries.iterator()
        while (entries.hasNext()) {
            val entry = entries.next()
            val retainedWrite = entry.value.write
            if (
                retainedWrite.author == write.author &&
                laneOf(retainedWrite.counter) == lane &&
                retainedWrite.counter <= payload.coversThrough &&
                entry.value.payload !is TransferAuthority
            ) {
                dropped += retainedWrite
                entries.remove()
            }
        }
        return dropped
    }

    /** The author-signed log carried in a late-join catch-up batch. */
    @Synchronized
    fun retained(): List<SignedWrite> = retained.values.map { it.write }

    /** Whether [counter] lies in an admitted checkpoint range for [author]. */
    @Synchronized
    fun isCovered(author: PeerId, counter: Long): Boolean =
        coveredThrough(author, laneOf(counter))?.let { counter <= it } == true

    /** Whether checkpoint coverage supersedes this absent pair. */
    @Synchronized
    fun isCompactedPair(author: PeerId, counter: Long): Boolean =
        RetainedKey(author, counter) !in retained &&
            coveredThrough(author, laneOf(counter))?.let { counter <= it } == true

    /** The greatest admitted coverage in [author]'s exact incarnation [lane]. */
    @Synchronized
    fun coveredThrough(author: PeerId, lane: Long): Long? = covered[CoverageKey(author, lane)]

    /** Count retained, uncovered data envelopes by [author] at or above [laneFloor]. */
    @Synchronized
    fun uncoveredOwnDataCount(author: PeerId, laneFloor: Long): Int = retained.values.count { entry ->
        entry.write.author == author &&
            entry.write.counter >= laneFloor &&
            entry.payload !is TransferAuthority &&
            entry.payload !is AuthorCheckpoint
    }

    /**
     * Join [author]'s retained data and prior checkpoint folds at or above
     * [laneFloor]. Transfers are chain evidence, never fold input.
     */
    @Synchronized
    fun foldOwn(author: PeerId, laneFloor: Long): Fold? {
        val candidates = retained.values.mapNotNull { entry ->
            if (entry.write.author != author || entry.write.counter < laneFloor) return@mapNotNull null
            val payload = when (val retainedPayload = entry.payload) {
                is TransferAuthority -> return@mapNotNull null
                is AuthorCheckpoint -> retainedPayload.folded
                else -> retainedPayload
            }
            entry.write.counter to payload
        }
        if (candidates.isEmpty()) return null
        require(candidates.all { (_, payload) -> payload is SetDelta<*> || payload is TaggedMapDelta<*, *> }) {
            "write-authority fold requires SetDelta or TaggedMapDelta payloads"
        }

        val folded = candidates.map { it.second }.reduce(::mergeFolded)
        return Fold(candidates.maxOf { it.first }, folded)
    }

    /** Pure local-author check used before a cell mutates and again before signing. */
    @Synchronized
    fun authorizesLocal(peerId: PeerId, payloadOrElement: Any?): Boolean = when (authority) {
        WriteAuthority.Open -> true
        is WriteAuthority.Principal ->
            peerId == currentPrincipal &&
                (payloadOrElement !is TransferAuthority || transferTargetAllowed(peerId, Long.MAX_VALUE, payloadOrElement))
        is WriteAuthority.PerElementOwner -> {
            when (payloadOrElement) {
                is TransferAuthority -> false
                is AuthorCheckpoint ->
                    touchedElements(payloadOrElement.folded)?.all { authority.ownerOf.ownerOf(it) == peerId } == true
                else -> touchedElements(payloadOrElement)?.all { authority.ownerOf.ownerOf(it) == peerId }
                    ?: (authority.ownerOf.ownerOf(payloadOrElement) == peerId)
            }
        }
    }

    private fun malformedCheckpoint(write: SignedWrite, checkpoint: AuthorCheckpoint): String? = when {
        checkpoint.coversThrough >= write.counter ->
            "malformed author checkpoint: coversThrough ${checkpoint.coversThrough} must precede counter ${write.counter}"
        laneOf(checkpoint.coversThrough) != laneOf(write.counter) ->
            "malformed author checkpoint: coversThrough ${checkpoint.coversThrough} is outside counter ${write.counter}'s lane"
        checkpoint.folded !is SetDelta<*> && checkpoint.folded !is TaggedMapDelta<*, *> ->
            "malformed author checkpoint: folded payload must be SetDelta or TaggedMapDelta"
        else -> null
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeFolded(left: Any?, right: Any?): Any? = when {
        left is SetDelta<*> && right is SetDelta<*> ->
            (left as SetDelta<Any?>).merge(right as SetDelta<Any?>)
        left is TaggedMapDelta<*, *> && right is TaggedMapDelta<*, *> ->
            (left as TaggedMapDelta<Any?, Any?>).merge(right as TaggedMapDelta<Any?, Any?>)
        else -> throw IllegalArgumentException(
            "write-authority fold requires one delta family, got " +
                "${left?.javaClass?.name ?: "null"} and ${right?.javaClass?.name ?: "null"}",
        )
    }

    private fun laneOf(counter: Long): Long = counter ushr WRITE_COUNTER_INCARNATION_SHIFT

    private fun admitPrincipal(write: SignedWrite, payload: Any?): Admission {
        val current = currentPrincipal
        val retiredAt = canonicalTransfers().firstOrNull { it.from == write.author }?.atCounter
        val authorAuthorized = write.author == current || (retiredAt != null && write.counter < retiredAt)
        val authorized = authorAuthorized &&
            (payload !is TransferAuthority || transferTargetAllowed(write.author, write.counter, payload))
        return if (authorized) {
            Admission.Admitted(payload)
        } else {
            Admission.Denied(
                DenialReason.UNAUTHORIZED_WRITER,
                write.author,
                "${write.author.name} is not authorized for this principal-owned slice",
            )
        }
    }

    private fun admitElements(write: SignedWrite, payload: Any?, ownerOf: OwnerOf): Admission {
        if (payload is TransferAuthority) {
            return Admission.Denied(
                DenialReason.UNAUTHORIZED_WRITER,
                write.author,
                "per-element authority has no slice principal to transfer",
            )
        }

        val touched = touchedElements(payload)
            ?: return Admission.Denied(
                DenialReason.UNSIGNED,
                write.author,
                "per-element authority cannot inspect payload type ${payload?.javaClass?.name ?: "null"}",
            )

        touched.forEach { element ->
            if (ownerOf.ownerOf(element) != write.author) {
                return Admission.Denied(
                    DenialReason.UNAUTHORIZED_WRITER,
                    write.author,
                    "element $element is not owned by ${write.author.name}",
                )
            }
        }
        return Admission.Admitted(payload)
    }

    private fun touchedElements(payload: Any?): Set<Any?>? = when (payload) {
        is SetDelta<*> -> LinkedHashSet<Any?>(payload.adds.keys).also { it.addAll(payload.dels.keys) }
        is TaggedMapDelta<*, *> -> LinkedHashSet<Any?>(payload.puts.keys).also { it.addAll(payload.dels.keys) }
        else -> null
    }

    /** The canonical chain chooses each principal's lowest not-yet-used transfer counter. */
    private fun canonicalTransfers(): List<Transfer> {
        var principal = initialPrincipal ?: return emptyList()
        val counterFloor = mutableMapOf<PeerId, Long>()
        val selected = mutableListOf<Transfer>()
        val used = mutableSetOf<Transfer>()
        while (true) {
            val floor = counterFloor[principal] ?: Long.MIN_VALUE
            val next = transfers.asSequence()
                .filter { it !in used && it.from == principal && it.atCounter > floor }
                .minWithOrNull(compareBy<Transfer>({ it.atCounter }, { it.to.name }))
                ?: break
            used += next
            selected += next
            counterFloor[principal] = next.atCounter
            principal = next.to
        }
        return selected
    }

    private fun transferTargetAllowed(author: PeerId, counter: Long, transfer: TransferAuthority): Boolean {
        val chain = canonicalTransfers()
        val principals = buildSet {
            initialPrincipal?.let(::add)
            chain.forEach { add(it.to) }
        }
        if (transfer.newPrincipal !in principals) return true

        // A late, lower-counter copy of the same edge corrects the canonical
        // transfer point; it is not ownership re-acquisition.
        val existing = chain.firstOrNull { it.from == author }
        return existing != null && existing.to == transfer.newPrincipal && counter < existing.atCounter
    }
}
