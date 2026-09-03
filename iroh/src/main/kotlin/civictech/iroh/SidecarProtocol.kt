package civictech.iroh

/**
 * Constants of the sidecar's local-socket protocol.
 *
 * The normative description is `iroh/sidecar/PROTOCOL.md`; its Rust half is
 * `iroh/sidecar/src/protocol.rs`. This file is the JVM half's transcription of
 * the same numbers, and nothing here may drift from that document — a kind
 * present in one and absent from the other is a defect.
 */
object SidecarProtocol {

    /** Bytes of big-endian length prefix that precede every message body. */
    const val LENGTH_PREFIX_LEN: Int = 4

    /** Bytes of header after the length prefix: kind (1) + link id (8). */
    const val MSG_HEADER_LEN: Int = 9

    /** Largest peer frame the sidecar will carry (16 MiB). */
    const val MAX_FRAME_LEN: Int = 16 * 1024 * 1024

    /** Largest legal value of the length prefix. */
    const val MAX_MESSAGE_LEN: Int = MSG_HEADER_LEN + MAX_FRAME_LEN

    /** The link id reserved for messages that are not about one link. */
    const val CONTROL_LINK: Long = 0L

    /** Bytes in an iroh endpoint id (an ed25519 public key). */
    const val NODE_ID_LEN: Int = 32

    /** [Kind.LINK_UP] direction byte: this side dialled. */
    const val DIRECTION_OUTBOUND: Byte = 0x00

    /** [Kind.LINK_UP] direction byte: this side accepted. */
    const val DIRECTION_INBOUND: Byte = 0x01

    /**
     * Message kinds. Kinds below `0x80` originate at the host; kinds at or above
     * `0x80` originate at the sidecar. [Kind.DATA] is the single kind that
     * travels both ways.
     */
    object Kind {
        const val GET_ID: Byte = 0x01
        const val LISTEN: Byte = 0x02
        const val ADD_PEER: Byte = 0x03
        const val DIAL: Byte = 0x04
        const val DATA: Byte = 0x05
        const val CLOSE_LINK: Byte = 0x06
        const val SHUTDOWN: Byte = 0x07

        const val ID: Byte = 0x81.toByte()
        const val LISTENING: Byte = 0x82.toByte()
        const val PEER_ADDED: Byte = 0x83.toByte()
        const val LINK_UP: Byte = 0x84.toByte()
        const val LINK_DOWN: Byte = 0x85.toByte()
        const val ERROR: Byte = 0x86.toByte()
    }

    /** True when [link] is a host-allocated (odd, non-zero) link id. */
    fun isHostLink(link: Long): Boolean = link != 0L && (link and 1L) == 1L

    /** True when [link] is a sidecar-allocated (even, non-zero) link id. */
    fun isSidecarLink(link: Long): Boolean = link != 0L && (link and 1L) == 0L
}
