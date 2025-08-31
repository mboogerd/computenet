package civictech.kernel.germ

/**
 * A Handle represents a live, dynamically reconfigurable reference to an API [T].
 * It supports:
 * - Direct synchronous access to the current API instance via [acquire].
 * - Branching into new Handles that share an upstream and update dynamically.
 * - Refreshing its API instance and propagating staleness downstream.
 */
class Handle<T> {

    /** Current usable API instance */
    private var active: T? = null

    /** Whether this Handle's [active] is outdated and must be rebuilt from origin */
    private var stale: Boolean = false

    /** Upstream Handle from which this one derives, if any */
    private var origin: Handle<T>? = null

    /** Downstream branches that depend on this Handle */
    private val children: MutableList<Handle<T>> = mutableListOf()

    /** A function to wrap or transform the API when deriving from an origin */
    private var wrapper: (T) -> T = { it }

    /**
     * Resolves the current API instance, rebuilding from origin if stale.
     */
    fun acquire(): T {
        if (stale) {
            val base = origin?.acquire() ?: throw IllegalStateException("No upstream to rebuild from")
            return wrapper(base).also {
                active = it
                stale = false
            }
        }
        return active ?: throw IllegalStateException("Handle has not been initialized")
    }

    /**
     * Creates a new Handle that derives from this one, without modification.
     */
    fun derive(): Handle<T> = deriveWith { it }

    /**
     * Creates a new Handle that derives from this one, wrapping the origin with the given function.
     */
    fun deriveWith(wrapper: (T) -> T): Handle<T> {
        return Handle<T>().apply {
            this.wrapper = wrapper
            origin = this@Handle
            stale = true
        }.also {
            children += it
        }
    }

    /**
     * If this Handle is the root of a tree, it replaces the root and marks all downstream branches as stale.
     */
    fun activate(newActive: T) {
        if (origin == null) {
            active = newActive
            stale = false
            children.forEach { it.invalidate() }
        } else {
            throw IllegalStateException("Cannot set the active instance for a branched/non-root Handle")
        }
    }

    /**
     * Sets the origin to a new Handle, clearing any prior origin.
     */
    fun reattach(handle: Handle<T>) {
        require(handle != this)
        setOrigin(handle)
        invalidate()
    }

    /**
     * Detaches from the origin and forms a new root handle.
     */
    fun detach(newActive: T) {
        if (!isRoot()) {
            setOrigin(null)
        }
        activate(newActive)
    }

    /**
     * true if this Handle has no origin, false otherwise.
     */
    fun isRoot(): Boolean = origin == null

    /**
     * Replaces the wrapper function and marks this handle stale so it rebuilds with new logic.
     */
    fun decorate(wrapper: (T) -> T) {
        this.wrapper = wrapper
        invalidate()
    }

    /**
     * Marks this handle and each downstream branch/fork as stale.
     */
    private fun invalidate() {
        if (stale) return
        stale = true
        children.forEach { it.invalidate() }
    }

    /**
     * Changes the origin of this handle.
     */
    private fun setOrigin(newOrigin: Handle<T>?) {
        origin?.children?.remove(this)
        origin = newOrigin
    }

    companion object {
        /**
         * Create a root handle wrapping some API.
         */
        fun <T> root(initialT: T): Handle<T> = Handle<T>().apply { activate(initialT) }
    }
}