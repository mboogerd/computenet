package civictech.iroh

import civictech.iroh.SidecarProtocol.CONTROL_LINK
import civictech.iroh.SidecarProtocol.Kind
import civictech.iroh.SidecarProtocol.LENGTH_PREFIX_LEN
import civictech.iroh.SidecarProtocol.MAX_MESSAGE_LEN
import civictech.iroh.SidecarProtocol.MSG_HEADER_LEN
import civictech.iroh.SidecarProtocol.NODE_ID_LEN

/**
 * One message as it sits on the host socket, length prefix stripped:
 * a kind byte, an 8-byte big-endian link id, and a kind-specific payload.
 *
 * This is the untyped layer. [SidecarCodec] turns a frame into one of the
 * thirteen typed messages `PROTOCOL.md` §3 names, and back.
 */
class Frame(val kind: Byte, val link: Long, val payload: ByteArray) {

    /** The message as it appears on the socket, length prefix included. */
    fun encode(): ByteArray {
        val bodyLen = MSG_HEADER_LEN + payload.size
        val out = ByteArray(LENGTH_PREFIX_LEN + bodyLen)
        writeInt(out, 0, bodyLen)
        out[4] = kind
        writeLong(out, 5, link)
        payload.copyInto(out, LENGTH_PREFIX_LEN + MSG_HEADER_LEN)
        return out
    }

    override fun equals(other: Any?): Boolean =
        other is Frame && other.kind == kind && other.link == link && other.payload.contentEquals(payload)

    override fun hashCode(): Int = (31 * (31 * kind.toInt() + link.hashCode())) + payload.contentHashCode()

    override fun toString(): String = "Frame(kind=0x%02x, link=$link, payload=${payload.size} bytes)".format(kind)

    companion object {
        internal fun writeInt(target: ByteArray, offset: Int, value: Int) {
            target[offset] = (value ushr 24).toByte()
            target[offset + 1] = (value ushr 16).toByte()
            target[offset + 2] = (value ushr 8).toByte()
            target[offset + 3] = value.toByte()
        }

        internal fun writeLong(target: ByteArray, offset: Int, value: Long) {
            for (i in 0 until 8) target[offset + i] = (value ushr (56 - 8 * i)).toByte()
        }

        internal fun readInt(source: ByteArray, offset: Int): Int =
            ((source[offset].toInt() and 0xff) shl 24) or
                ((source[offset + 1].toInt() and 0xff) shl 16) or
                ((source[offset + 2].toInt() and 0xff) shl 8) or
                (source[offset + 3].toInt() and 0xff)

        internal fun readLong(source: ByteArray, offset: Int): Long {
            var acc = 0L
            for (i in 0 until 8) acc = (acc shl 8) or (source[offset + i].toLong() and 0xff)
            return acc
        }
    }
}

/** Why a byte sequence is not a legal message or not the message it claims to be. */
enum class DecodeProblem {
    /** The buffer is shorter than the 4-byte length prefix plus the length it declares. */
    TRUNCATED,

    /** The declared length is below the 9-byte minimum of `PROTOCOL.md` §2. */
    LENGTH_BELOW_MINIMUM,

    /** The declared length exceeds `9 + MAX_FRAME_LEN`. */
    LENGTH_ABOVE_MAXIMUM,

    /** The kind byte names no message in `PROTOCOL.md` §3. */
    UNKNOWN_KIND,

    /** The kind is known but originates at the other end of the socket. */
    WRONG_DIRECTION,

    /** The payload is too short, too long, or malformed for that kind. */
    MALFORMED_PAYLOAD,

    /** The kind requires [CONTROL_LINK] and the frame named another link, or vice versa. */
    WRONG_LINK,
}

/**
 * The outcome of a decode: either a typed message or a [DecodeProblem].
 *
 * Malformed input never escapes as an exception from [SidecarCodec] — a host
 * that reads a corrupt socket has to be able to account for it rather than
 * unwind through an arbitrary stack.
 */
sealed interface Decoded<out T> {
    data class Ok<T>(val message: T) : Decoded<T>
    data class Malformed(val problem: DecodeProblem, val detail: String) : Decoded<Nothing>
}

/** A message the host sends to the sidecar (`PROTOCOL.md` §3, host → sidecar). */
sealed interface HostMessage {
    val link: Long

    data object GetId : HostMessage {
        override val link: Long get() = CONTROL_LINK
    }

    data object Listen : HostMessage {
        override val link: Long get() = CONTROL_LINK
    }

    /** 32-byte endpoint id, then UTF-8 comma-separated socket addresses. */
    class AddPeer(val nodeId: ByteArray, val addresses: List<String>) : HostMessage {
        override val link: Long get() = CONTROL_LINK
        override fun toString(): String = "AddPeer(${nodeId.toHex()}, $addresses)"
    }

    /** Dial [peerId] on a host-chosen odd [link]. */
    class Dial(override val link: Long, val peerId: ByteArray) : HostMessage {
        override fun toString(): String = "Dial(link=$link, ${peerId.toHex()})"
    }

    /** One peer frame, host → sidecar. */
    class Data(override val link: Long, val payload: ByteArray) : HostMessage {
        override fun toString(): String = "Data(link=$link, ${payload.size} bytes)"
    }

    data class CloseLink(override val link: Long) : HostMessage

    data object Shutdown : HostMessage {
        override val link: Long get() = CONTROL_LINK
    }
}

/** A message the sidecar sends to the host (`PROTOCOL.md` §3, sidecar → host). */
sealed interface SidecarMessage {
    val link: Long

    class Id(val nodeId: ByteArray) : SidecarMessage {
        override val link: Long get() = CONTROL_LINK
        override fun toString(): String = "Id(${nodeId.toHex()})"
    }

    data class Listening(val addresses: List<String>) : SidecarMessage {
        override val link: Long get() = CONTROL_LINK
    }

    class PeerAdded(val nodeId: ByteArray) : SidecarMessage {
        override val link: Long get() = CONTROL_LINK
        override fun toString(): String = "PeerAdded(${nodeId.toHex()})"
    }

    /** 32-byte remote endpoint id, then one direction byte. */
    class LinkUp(override val link: Long, val remoteNodeId: ByteArray, val direction: Byte) : SidecarMessage {
        override fun toString(): String = "LinkUp(link=$link, ${remoteNodeId.toHex()}, direction=$direction)"
    }

    /** Terminal for that link id; the reason is human-readable only. */
    data class LinkDown(override val link: Long, val reason: String) : SidecarMessage

    /** A request failed. [link] is the link it concerns, or [CONTROL_LINK]. */
    data class Failure(override val link: Long, val reason: String) : SidecarMessage

    /** One peer frame, sidecar → host. */
    class Data(override val link: Long, val payload: ByteArray) : SidecarMessage {
        override fun toString(): String = "Data(link=$link, ${payload.size} bytes)"
    }
}

/**
 * Pure encode/decode of the sidecar's local-socket protocol. No IO, no state.
 *
 * `DATA` is the one kind that travels both ways, and both directions build it
 * through the single [dataFrame] site below. That is deliberate: if
 * `computenet-ey4v` settles the refusal contract by giving `DATA` a host-chosen
 * sequence number, the header gains a field in exactly one place.
 */
object SidecarCodec {

    /**
     * The ONE place a `DATA` message's header is built, in either direction.
     *
     * Keep it that way. See the object KDoc.
     */
    private fun dataFrame(link: Long, payload: ByteArray): Frame = Frame(Kind.DATA, link, payload)

    fun encode(message: HostMessage): ByteArray = frameOf(message).encode()

    fun encode(message: SidecarMessage): ByteArray = frameOf(message).encode()

    fun frameOf(message: HostMessage): Frame = when (message) {
        is HostMessage.GetId -> Frame(Kind.GET_ID, CONTROL_LINK, EMPTY)
        is HostMessage.Listen -> Frame(Kind.LISTEN, CONTROL_LINK, EMPTY)
        is HostMessage.AddPeer ->
            Frame(Kind.ADD_PEER, CONTROL_LINK, message.nodeId + message.addresses.joinToString(",").toByteArray(Charsets.UTF_8))
        is HostMessage.Dial -> Frame(Kind.DIAL, message.link, message.peerId)
        is HostMessage.Data -> dataFrame(message.link, message.payload)
        is HostMessage.CloseLink -> Frame(Kind.CLOSE_LINK, message.link, EMPTY)
        is HostMessage.Shutdown -> Frame(Kind.SHUTDOWN, CONTROL_LINK, EMPTY)
    }

    fun frameOf(message: SidecarMessage): Frame = when (message) {
        is SidecarMessage.Id -> Frame(Kind.ID, CONTROL_LINK, message.nodeId)
        is SidecarMessage.Listening ->
            Frame(Kind.LISTENING, CONTROL_LINK, message.addresses.joinToString(",").toByteArray(Charsets.UTF_8))
        is SidecarMessage.PeerAdded -> Frame(Kind.PEER_ADDED, CONTROL_LINK, message.nodeId)
        is SidecarMessage.LinkUp ->
            Frame(Kind.LINK_UP, message.link, message.remoteNodeId + byteArrayOf(message.direction))
        is SidecarMessage.LinkDown -> Frame(Kind.LINK_DOWN, message.link, message.reason.toByteArray(Charsets.UTF_8))
        is SidecarMessage.Failure -> Frame(Kind.ERROR, message.link, message.reason.toByteArray(Charsets.UTF_8))
        is SidecarMessage.Data -> dataFrame(message.link, message.payload)
    }

    /**
     * Decode a complete on-the-wire message — length prefix included — into a
     * [Frame]. Enforces `PROTOCOL.md` §2's bounds on the length prefix.
     */
    fun decodeFrame(wire: ByteArray, offset: Int = 0, limit: Int = wire.size): Decoded<Frame> {
        val available = limit - offset
        if (available < LENGTH_PREFIX_LEN) {
            return Decoded.Malformed(DecodeProblem.TRUNCATED, "only $available bytes; need a 4-byte length prefix")
        }
        val declared = Frame.readInt(wire, offset)
        return when {
            declared < MSG_HEADER_LEN ->
                Decoded.Malformed(DecodeProblem.LENGTH_BELOW_MINIMUM, "length $declared is below the minimum $MSG_HEADER_LEN")
            declared > MAX_MESSAGE_LEN ->
                Decoded.Malformed(DecodeProblem.LENGTH_ABOVE_MAXIMUM, "length $declared exceeds the maximum $MAX_MESSAGE_LEN")
            available - LENGTH_PREFIX_LEN < declared ->
                Decoded.Malformed(
                    DecodeProblem.TRUNCATED,
                    "length $declared but only ${available - LENGTH_PREFIX_LEN} body bytes present",
                )
            else -> Decoded.Ok(decodeBody(wire, offset + LENGTH_PREFIX_LEN, declared))
        }
    }

    /**
     * Decode a message body — kind, link, payload, no length prefix — of exactly
     * [bodyLen] bytes. Callers that read the length prefix themselves (the socket
     * reader) use this after validating the length.
     */
    fun decodeBody(wire: ByteArray, offset: Int, bodyLen: Int): Frame {
        require(bodyLen >= MSG_HEADER_LEN) { "body length $bodyLen is below $MSG_HEADER_LEN" }
        val kind = wire[offset]
        val link = Frame.readLong(wire, offset + 1)
        val payload = wire.copyOfRange(offset + MSG_HEADER_LEN, offset + bodyLen)
        return Frame(kind, link, payload)
    }

    /** Interpret a frame as a host → sidecar message. */
    fun asHostMessage(frame: Frame): Decoded<HostMessage> = when (frame.kind) {
        Kind.GET_ID -> controlEmpty(frame) { HostMessage.GetId }
        Kind.LISTEN -> controlEmpty(frame) { HostMessage.Listen }
        Kind.ADD_PEER -> {
            if (frame.link != CONTROL_LINK) wrongLink(frame)
            else if (frame.payload.size < NODE_ID_LEN) {
                Decoded.Malformed(DecodeProblem.MALFORMED_PAYLOAD, "ADD_PEER payload is ${frame.payload.size} bytes, needs at least $NODE_ID_LEN")
            } else {
                val id = frame.payload.copyOfRange(0, NODE_ID_LEN)
                val addrs = String(frame.payload, NODE_ID_LEN, frame.payload.size - NODE_ID_LEN, Charsets.UTF_8)
                Decoded.Ok(HostMessage.AddPeer(id, splitAddresses(addrs)))
            }
        }
        Kind.DIAL ->
            if (frame.payload.size != NODE_ID_LEN) {
                Decoded.Malformed(DecodeProblem.MALFORMED_PAYLOAD, "DIAL payload is ${frame.payload.size} bytes, needs $NODE_ID_LEN")
            } else if (!SidecarProtocol.isHostLink(frame.link)) {
                Decoded.Malformed(DecodeProblem.WRONG_LINK, "DIAL link ${frame.link} is not a host-allocated odd id")
            } else {
                Decoded.Ok(HostMessage.Dial(frame.link, frame.payload))
            }
        Kind.DATA ->
            if (frame.link == CONTROL_LINK) wrongLink(frame) else Decoded.Ok(HostMessage.Data(frame.link, frame.payload))
        Kind.CLOSE_LINK ->
            if (frame.link == CONTROL_LINK) wrongLink(frame)
            else if (frame.payload.isNotEmpty()) {
                Decoded.Malformed(DecodeProblem.MALFORMED_PAYLOAD, "CLOSE_LINK carries no payload; got ${frame.payload.size} bytes")
            } else {
                Decoded.Ok(HostMessage.CloseLink(frame.link))
            }
        Kind.SHUTDOWN -> controlEmpty(frame) { HostMessage.Shutdown }
        Kind.ID, Kind.LISTENING, Kind.PEER_ADDED, Kind.LINK_UP, Kind.LINK_DOWN, Kind.ERROR ->
            Decoded.Malformed(DecodeProblem.WRONG_DIRECTION, "kind 0x%02x originates at the sidecar".format(frame.kind))
        else -> Decoded.Malformed(DecodeProblem.UNKNOWN_KIND, "kind 0x%02x names no message".format(frame.kind))
    }

    /** Interpret a frame as a sidecar → host message. */
    fun asSidecarMessage(frame: Frame): Decoded<SidecarMessage> = when (frame.kind) {
        Kind.ID -> nodeIdPayload(frame, "ID") { SidecarMessage.Id(it) }
        Kind.LISTENING ->
            if (frame.link != CONTROL_LINK) wrongLink(frame)
            else Decoded.Ok(SidecarMessage.Listening(splitAddresses(String(frame.payload, Charsets.UTF_8))))
        Kind.PEER_ADDED -> nodeIdPayload(frame, "PEER_ADDED") { SidecarMessage.PeerAdded(it) }
        Kind.LINK_UP ->
            if (frame.link == CONTROL_LINK) wrongLink(frame)
            else if (frame.payload.size != NODE_ID_LEN + 1) {
                Decoded.Malformed(
                    DecodeProblem.MALFORMED_PAYLOAD,
                    "LINK_UP payload is ${frame.payload.size} bytes, needs ${NODE_ID_LEN + 1}",
                )
            } else {
                Decoded.Ok(
                    SidecarMessage.LinkUp(
                        frame.link,
                        frame.payload.copyOfRange(0, NODE_ID_LEN),
                        frame.payload[NODE_ID_LEN],
                    ),
                )
            }
        Kind.LINK_DOWN ->
            if (frame.link == CONTROL_LINK) wrongLink(frame)
            else Decoded.Ok(SidecarMessage.LinkDown(frame.link, String(frame.payload, Charsets.UTF_8)))
        Kind.ERROR -> Decoded.Ok(SidecarMessage.Failure(frame.link, String(frame.payload, Charsets.UTF_8)))
        Kind.DATA ->
            if (frame.link == CONTROL_LINK) wrongLink(frame)
            else Decoded.Ok(SidecarMessage.Data(frame.link, frame.payload))
        Kind.GET_ID, Kind.LISTEN, Kind.ADD_PEER, Kind.DIAL, Kind.CLOSE_LINK, Kind.SHUTDOWN ->
            Decoded.Malformed(DecodeProblem.WRONG_DIRECTION, "kind 0x%02x originates at the host".format(frame.kind))
        else -> Decoded.Malformed(DecodeProblem.UNKNOWN_KIND, "kind 0x%02x names no message".format(frame.kind))
    }

    private val EMPTY = ByteArray(0)

    private fun splitAddresses(raw: String): List<String> =
        raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private inline fun <T> controlEmpty(frame: Frame, build: () -> T): Decoded<T> = when {
        frame.link != CONTROL_LINK -> wrongLink(frame)
        frame.payload.isNotEmpty() ->
            Decoded.Malformed(DecodeProblem.MALFORMED_PAYLOAD, "kind 0x%02x carries no payload; got %d bytes".format(frame.kind, frame.payload.size))
        else -> Decoded.Ok(build())
    }

    private inline fun <T> nodeIdPayload(frame: Frame, name: String, build: (ByteArray) -> T): Decoded<T> = when {
        frame.link != CONTROL_LINK -> wrongLink(frame)
        frame.payload.size != NODE_ID_LEN ->
            Decoded.Malformed(DecodeProblem.MALFORMED_PAYLOAD, "$name payload is ${frame.payload.size} bytes, needs $NODE_ID_LEN")
        else -> Decoded.Ok(build(frame.payload))
    }

    private fun wrongLink(frame: Frame): Decoded.Malformed =
        Decoded.Malformed(DecodeProblem.WRONG_LINK, "kind 0x%02x on link %d".format(frame.kind, frame.link))
}

/** Lowercase hex, the form `PROTOCOL.md` §1 uses for a node id. */
fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Decode 64 lowercase hex characters into 32 bytes; `null` when the input is not that. */
fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val out = ByteArray(length / 2)
    for (i in out.indices) {
        val hi = Character.digit(this[2 * i], 16)
        val lo = Character.digit(this[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}
