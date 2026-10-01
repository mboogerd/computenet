package civictech.cell.link

import civictech.cell.port.Use

/**
 * Thread-scoped delivery-target substitution for one admitted staged link.
 *
 * The real inlet remains the handshake target and therefore remains visible to
 * policies, topology protocols and the wave frontier. Only the [Use] retained
 * by a fan outlet is replaced with the staged stand-in carried here.
 */
internal object StagedSubscription {
    private data class Scope(
        val standIn: Use<*>,
        val closeSequencer: CloseSequencer,
    )

    private val local = ThreadLocal<Scope?>()

    fun <T> with(standIn: Use<*>, closeSequencer: CloseSequencer, block: () -> T): T {
        val prior = local.get()
        local.set(Scope(standIn, closeSequencer))
        try {
            return block()
        } finally {
            if (prior == null) local.remove() else local.set(prior)
        }
    }

    /** The current stand-in without clearing it; one handshake may consult it more than once. */
    fun current(): Use<*>? = local.get()?.standIn

    /** Close ordering paired with [current] for the same admitted staged link. */
    fun currentSequencer(): CloseSequencer? = local.get()?.closeSequencer
}

/** Places a staged link's terminal marker behind data already accepted on that link. */
internal fun interface CloseSequencer {
    fun sequence(link: Link)
}
