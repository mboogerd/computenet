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
 * Register via `META-INF/services/civictech.cell.wire.WireSerializers`.
 */
interface WireSerializers {
    val module: SerializersModule
}
