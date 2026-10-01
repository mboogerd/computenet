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
    private val local = ThreadLocal<Use<*>?>()

    fun <T> with(standIn: Use<*>, block: () -> T): T {
        val prior = local.get()
        local.set(standIn)
        try {
            return block()
        } finally {
            if (prior == null) local.remove() else local.set(prior)
        }
    }

    /** The current stand-in without clearing it; one handshake may consult it more than once. */
    fun current(): Use<*>? = local.get()
}
