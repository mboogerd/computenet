package civictech.kernel.data

/**
 * The standard interface for ports that exchange data of one type
 */
@FunctionalInterface
interface Propagate<T> {
    fun propagate(value: T)
}