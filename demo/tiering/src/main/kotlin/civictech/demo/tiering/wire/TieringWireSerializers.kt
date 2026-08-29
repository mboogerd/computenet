package civictech.demo.tiering.wire

import civictech.cell.wire.WireSerializers
import civictech.demo.tiering.Pref
import civictech.demo.tiering.Valuation
import kotlinx.serialization.builtins.PairSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Registers demo/tiering's wire-crossing payload types with `WireCodec`
 * (computenet-3san), discovered through
 * `META-INF/services/civictech.cell.wire.WireSerializers` exactly as
 * `:demo:agora`'s `AgoraWireSerializers` and `:demo:beadsmirror`'s
 * `BeadsMirrorWireSerializers` are.
 *
 * The `tier` action routes a `Pair<String, String>` (agent, item) key
 * alongside a [Valuation] through `vals`, a `KeyedSetCell`; `pref`/`unpref`
 * route a [Pref] through `prefs`, a `SetCell`. Both cells are journalled via
 * a **routed** hosted lookup (`TieringApp.valOps`/`prefOps`), so
 * `HostDurability.journalFrame` encodes every payload through `WireCodec`'s
 * `polymorphic(Any)` scope — none of the three types were registered there,
 * which is what threw `SerializationException: Serializer for subclass
 * 'Pair' is not found in the polymorphic scope of 'Any'` before this class
 * existed. `item`/`unitem`/`retier` carry only `String`s, already covered by
 * the kernel baseline.
 *
 * [Pair] is not itself `@Serializable`; [PairSerializer] is kotlinx's builtin
 * serializer for it, parameterized here to exactly the `Pair<String, String>`
 * shape `vals`'s key uses — the same generic-type-argument pattern the kernel
 * baseline uses for its own delta types (`SetDelta.serializer(polyAny)` and
 * friends in `WireCodec.kt`).
 */
class TieringWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Valuation::class)
            subclass(Pref::class)
            @Suppress("UNCHECKED_CAST")
            subclass(
                Pair::class,
                PairSerializer(String.serializer(), String.serializer()) as kotlinx.serialization.KSerializer<Pair<*, *>>,
            )
        }
    }
}
