package civictech.cell.wire

import civictech.cell.Borrowed
import civictech.cell.CellRef
import civictech.cell.Frozen
import civictech.cell.MessageContext
import civictech.cell.Owned
import civictech.cell.Timestamp
import civictech.cell.data.CounterDelta
import civictech.cell.data.PnCounterDelta
import civictech.cell.data.ListDelta
import civictech.cell.data.MapDelta
import civictech.cell.data.SetDelta
import civictech.cell.port.PortRef
import civictech.cell.proxy.HostedPortInvocation
import civictech.cell.proxy.Invocation
import civictech.gen.wire.ContractRegistry
import civictech.gen.wire.JvmDescriptors
import kotlinx.serialization.KSerializer
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.Polymorphic
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.util.UUID

/**
 * The serialized invocation envelope (spec 41 point 1, G-15). Identity is
 * ids-only: no `Method`, no argument class names — arguments travel as
 * polymorphic values whose discriminators are `@SerialName`-pinned stable
 * names, decoupled from packages and reflection (P9).
 */
@Serializable
data class WireFrame(
    /** Format version — bump on layout change; reserves the G-8 CellRef retrofit. */
    val version: Int = WireCodec.VERSION,
    val contractId: Long,
    val methodId: Long,
    val cellRef: CellRef,
    val portName: String,
    val type: HostedPortInvocation.Type,
    /** Wave context survives the wire (G-4). */
    val context: MessageContext? = null,
    val args: List<@Polymorphic Any?> = emptyList(),
)

object WireCodec {
    const val VERSION = 2 // v2 (M7.1): CellRef carries instanceId (G-8)

    private val polyAny = PolymorphicSerializer(Any::class)

    // ponytail: JSON as the M5 codec — kotlinx-serialization-json is already the
    // dependency and perf is out of scope; swap the format (CBOR) behind encode/decode
    // if profiling demands.
    private val json = Json {
        serializersModule = SerializersModule {
            polymorphic(Any::class) {
                subclass(String::class, String.serializer())
                subclass(Long::class, Long.serializer())
                subclass(Int::class, Int.serializer())
                subclass(Boolean::class, Boolean.serializer())
                subclass(Double::class, Double.serializer())
                subclass(UUID::class, UuidSerializer)
                subclass(Timestamp::class)
                subclass(CellRef::class)
                subclass(PortRef::class)
                subclass(MessageContext::class)
                subclass(CounterDelta::class)
                subclass(PnCounterDelta::class)
                @Suppress("UNCHECKED_CAST")
                subclass(SetDelta::class, SetDelta.serializer(polyAny) as KSerializer<SetDelta<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(MapDelta::class, MapDelta.serializer(polyAny, polyAny) as KSerializer<MapDelta<*, *>>)
                @Suppress("UNCHECKED_CAST")
                subclass(ListDelta::class, ListDelta.serializer(polyAny) as KSerializer<ListDelta<*>>)
                // ownership wrappers (spec 23): Owned moves, Frozen/Borrowed copy; Leased never crosses
                @Suppress("UNCHECKED_CAST")
                subclass(Owned::class, Owned.serializer(polyAny) as KSerializer<Owned<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(Frozen::class, Frozen.serializer(polyAny) as KSerializer<Frozen<*>>)
                @Suppress("UNCHECKED_CAST")
                subclass(Borrowed::class, Borrowed.serializer(polyAny) as KSerializer<Borrowed<*>>)
            }
        }.let { kernelModule ->
            // app-contributed delta serializers (M17): ServiceLoader-discovered,
            // mirroring ContractRegistry's ContractModule discovery (C-5);
            // `plus` fails fast if a contribution collides with a kernel type
            java.util.ServiceLoader.load(WireSerializers::class.java, WireSerializers::class.java.classLoader)
                .fold(kernelModule) { acc, contribution -> acc + contribution.module }
        }
        allowStructuredMapKeys = true // polymorphic delta keys encode as [k, v] arrays
        useArrayPolymorphism = true // ["SerialName", value] — works for primitive args too
    }

    /** @throws IllegalStateException when the invocation's contract has no `@Contract` ids (not wire-capable). */
    fun encode(invocation: HostedPortInvocation): ByteArray {
        val inv = invocation.invocation
        val contractId = checkNotNull(inv.contractId) {
            "not wire-capable: '${inv.methodName}' was not captured from a @Contract interface"
        }
        val frame = WireFrame(
            contractId = contractId,
            methodId = checkNotNull(inv.methodId),
            cellRef = invocation.cellRef,
            portName = invocation.portName,
            type = invocation.type,
            context = inv.context,
            args = inv.args,
        )
        return json.encodeToString(WireFrame.serializer(), frame).toByteArray()
    }

    /** @throws IllegalStateException on unknown version or ids (caller dead-letters). */
    fun decode(bytes: ByteArray): HostedPortInvocation {
        val frame = json.decodeFromString(WireFrame.serializer(), bytes.decodeToString())
        check(frame.version == VERSION) { "unsupported wire version ${frame.version}" }
        val method = checkNotNull(ContractRegistry.method(frame.contractId, frame.methodId)) {
            "unknown contract/method ids ${frame.contractId}/${frame.methodId} — no local descriptor"
        }
        return HostedPortInvocation(
            cellRef = frame.cellRef,
            portName = frame.portName,
            type = frame.type,
            invocation = Invocation(
                methodName = method.name,
                parameterTypes = JvmDescriptors.parameterTypeNames(method.jvmDescriptor),
                args = frame.args,
                context = frame.context,
                contractId = frame.contractId,
                methodId = frame.methodId,
            ),
        )
    }
}
