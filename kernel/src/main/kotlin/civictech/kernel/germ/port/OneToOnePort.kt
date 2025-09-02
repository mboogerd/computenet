package civictech.kernel.germ.port

import civictech.kernel.germ.proxy.noop
import civictech.kernel.port.PortRef

class OneToOnePort<Api : Any>(
    private val unicastFactory: () -> Api
) : UseMany<Api>, Broadcast<Api>, Invalidating {

    private var subscription: Pair<PortRef, Use<Api>>? = null
    private var implementationTrackers: MutableSet<Invalidating> = mutableSetOf()
    private var unicastApi: Api = unicastFactory()
    private var stale: Boolean = false

    override fun subscribe(portRef: PortRef, portApi: Use<Api>) {
        if (subscription != null) {
            throw IllegalStateException("OneToOnePort already has a subscriber")
        }
        subscription = portRef to portApi
        portApi.attach(this)
        invalidate()
    }

    override fun unsubscribe(portRef: PortRef) {
        if (subscription?.first == portRef) {
            subscription?.second?.detach(this)
            subscription = null
            invalidate()
        }
    }

    override fun one(portRef: PortRef): Api? {
        if (stale && portRef == subscription?.first) {
            unicastApi = unicastFactory()
            stale = false
        }
        return unicastApi.takeIf { subscription?.first == portRef }
    }

    override fun all(): Api {
        if (stale) {
            unicastApi = subscription?.second?.use() ?: unicastFactory()
            stale = false
        }
        return unicastApi
    }

    override fun attach(invalidating: Invalidating) {
        implementationTrackers.add(invalidating)
    }

    override fun detach(invalidating: Invalidating) {
        implementationTrackers.remove(invalidating)
    }

    override fun invalidate() {
        if (stale) return
        stale = true
        implementationTrackers.forEach { it.invalidate() }
    }

    companion object {
        inline fun <reified Api : Any> withProxy(): OneToOnePort<Api> = OneToOnePort { noop() }
    }
}
