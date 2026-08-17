package civictech.demo.beadsmirror.wire

import civictech.cell.wire.WireSerializers
import civictech.demo.beadsmirror.projector.MirrorEdge
import civictech.demo.beadsmirror.projector.MirrorKey
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers the mirror's wire-crossing payload types with `WireCodec` (M17,
 * computenet-7em.1.5), discovered through
 * `META-INF/services/civictech.cell.wire.WireSerializers` exactly as
 * `:demo:agora`'s `AgoraWireSerializers` is.
 *
 * The mirror sends no delta types of its own: it gossips a kernel
 * `TaggedMapDelta` (the `OrMapCell`) and a kernel `SetDelta` (the edge
 * `SetCell`), both already registered by the kernel. What is *not* registered
 * by the kernel is what those generic deltas carry — the codec encodes a
 * delta's keys, values and elements through `PolymorphicSerializer(Any)`, so
 * every application type reachable inside one needs its own entry here. For
 * this module that is exactly two:
 *
 * - [MirrorKey], the OR-map's composite `(issueId, field)` key;
 * - [MirrorEdge], the dependency set's element triple.
 *
 * The map's *values* are plain [String]s (field values are stored in their
 * JSON string form, see [MirrorKey]'s note), which the kernel scope already
 * covers. Any future payload type the projector puts inside a gossiped delta
 * must be added here too — an unregistered one throws
 * `SerializationException` inside the poll loop and freezes the node's HTTP
 * routes at 503, which is the defect this class closes.
 */
class BeadsMirrorWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(MirrorKey::class)
            subclass(MirrorEdge::class)
        }
    }
}
