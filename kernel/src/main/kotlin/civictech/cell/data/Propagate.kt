package civictech.cell.data

import civictech.gen.wire.Contract

/**
 * The standard interface for ports that exchange data of one type
 */
@Contract
@FunctionalInterface
interface Propagate<T> {
    fun propagate(value: T)
}