package civictech.cell.graph

import civictech.cell.CellRef
import civictech.cell.host.HostManagementApi
import civictech.cell.host.ManagedHost
import civictech.cell.port.Use
import java.io.Serializable

/**
 * A [CellRef] that remembers, in its type, an API the referenced cell
 * implements — minted at graph build ([refAs]) where the instance is in
 * hand, consumed by [lookup] with no per-cell proxy interface. Pure data:
 * serializable, wire-compatible, nothing but the ref at runtime.
 */
data class TypedRef<A : Any>(val ref: CellRef) : Serializable

/**
 * Mint a typed ref; verifies (erasure-level, once, at graph build) that the
 * built cell implements [A]. The generic instantiation (`SetApi<Valuation>`)
 * is trusted from the declared target type — the same guarantee a
 * hand-written monomorphic proxy interface gave.
 */
inline fun <reified A : Any> TypedCellHandle<*>.refAs(): TypedRef<A> {
    require(cell is A) { "cell ${cell::class.qualifiedName} does not implement ${A::class.qualifiedName}" }
    return TypedRef(ref)
}

inline fun <reified A : Any> ManagedHost.lookup(tref: TypedRef<A>): A? =
    lookup(tref.ref, A::class.java)

inline fun <reified A : Any> HostManagementApi.lookup(tref: TypedRef<A>): A? =
    lookup(tref.ref, A::class.java)

inline fun <reified A : Any> Use<HostManagementApi>.lookup(tref: TypedRef<A>): A? =
    call.lookup(tref.ref, A::class.java)
