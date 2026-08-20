package civictech.cell.wire

import kotlinx.serialization.modules.SerializersModule

/**
 * ServiceLoader-discovered serializer contribution (M17): an application
 * module registers polymorphic serializers for its own `@Serializable` delta
 * types so they can cross the journal and the wire — the codec's module is
 * no longer closed over kernel types. Same discovery pattern as
 * `ContractRegistry`'s `ContractModule` (C-5). Contributions MUST NOT
 * re-register kernel types; a collision fails codec construction fast.
 *
 * Two registration routes, both folded into the same codec module:
 *  - **`META-INF/services/civictech.cell.wire.WireSerializers`** — discovered
 *    by `ServiceLoader` once, when `WireCodec` is first touched. Use this for
 *    anything on the process classpath at start.
 *  - **[WireCodec.contribute]** — a *late* contribution, for a module loaded
 *    after `WireCodec` has already encoded frames (JAR1 [JAR1-REG-08], arm 1).
 *    It rebuilds the codec's module from the process-start baseline plus every
 *    live contribution; [WireCodec.withdraw] removes exactly that contribution
 *    again when the module unloads.
 *
 * Either way a collision with a kernel type (or with another contribution)
 * fails fast at registration — never silently, and never at first use.
 */
interface WireSerializers {
    val module: SerializersModule
}
