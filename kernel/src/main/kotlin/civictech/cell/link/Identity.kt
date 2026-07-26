package civictech.cell.link

/** Marker only in M2 — a real identity model is G-29. */
interface Identity

/**
 * G-29 phase 1 (M8.2): who is asking. A bridge ingress stamps every delivered
 * invocation with its transport peer's id; handshakes running during that
 * delivery see it on [LinkRequest.identity]. Local links carry null (= this
 * process). Authentication of the name is future work (43) — today it
 * identifies the *connection*, which the transport vouches for.
 */
@kotlinx.serialization.Serializable
@kotlinx.serialization.SerialName("PeerId")
data class PeerId(val name: String) : Identity, java.io.Serializable

/**
 * Ambient identity of the delivery being executed (set by the host around
 * bridged management invocations, read by [handshake]).
 */
object CurrentPeer {
    private val local = ThreadLocal<PeerId?>()

    fun get(): PeerId? = local.get()

    fun <R> with(peer: PeerId?, block: () -> R): R {
        val previous = local.get()
        local.set(peer)
        try {
            return block()
        } finally {
            local.set(previous)
        }
    }
}
