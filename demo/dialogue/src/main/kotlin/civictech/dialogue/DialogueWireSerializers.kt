package civictech.dialogue

import civictech.cell.wire.WireSerializers
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers `:demo:dialogue`'s wire-capable payload types with the wire codec
 * (journal + wire capable) — the repo's standard `WireSerializers`
 * contribution, loaded from `META-INF/services/civictech.cell.wire.WireSerializers`
 * at process start. Mirrors `AgoraWireSerializers`.
 *
 * Only [Utterance] is registered: it is the only dialogue payload the WAL
 * ever encodes ([DialogueRuntime.isDurable] — the derived pipeline is
 * deliberately volatile). See `DialogueRuntime`'s follow-up bead for the
 * mint vocabulary's own wire-capability status.
 */
class DialogueWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Utterance::class)
        }
    }
}
