package civictech.agora.cell

import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** Registers agora's delta types with the wire codec (journal + wire capable). */
class AgoraWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(StanceDelta::class)
            subclass(InfluenceDelta::class)
            subclass(CredenceUpdate::class)
        }
    }
}
