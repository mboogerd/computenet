package civictech.cell

import civictech.cell.port.Serve
import civictech.gen.wire.Contract

/**
 * The standard interface for ports that exchange data of one type
 */
@Contract
fun interface Propagate<T> {
    fun propagate(value: T)
}

/**
 * Serves a lambda as this inlet's handler. A plain `serve { … }` cannot
 * SAM-convert (the parameter's declared type is the type variable `Api`,
 * not the fun-interface literal), hence this named helper.
 */
fun <T> Serve<Propagate<T>>.onEach(handler: (T) -> Unit) = serve(Propagate(handler))
