package civictech.demo.backlogtriage

import civictech.cell.wire.WireSerializers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/** A stable named wire object containing a length-delimited list of string fields. */
private class StringFieldsSerializer<T>(
    serialName: String,
    private val toFields: (T) -> List<String>,
    private val fromFields: (List<String>) -> T,
) : KSerializer<T> {
    private val fieldsSerializer = ListSerializer(String.serializer())
    override val descriptor = buildClassSerialDescriptor(serialName) {
        element("fields", fieldsSerializer.descriptor)
    }

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeStructure(descriptor) {
        encodeSerializableElement(descriptor, 0, fieldsSerializer, toFields(value))
    }

    override fun deserialize(decoder: Decoder): T {
        var fields: List<String>? = null
        decoder.decodeStructure(descriptor) {
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    CompositeDecoder.DECODE_DONE -> break
                    0 -> fields = decodeSerializableElement(descriptor, 0, fieldsSerializer)
                    else -> error("unexpected $descriptor element $index")
                }
            }
        }
        return fromFields(checkNotNull(fields) { "missing fields for ${descriptor.serialName}" })
    }
}

/** The `backlogtriage.Pref` wire name stays stable across the class move. */
private val prefSerializer = StringFieldsSerializer(
    "backlogtriage.Pref",
    { value: Pref -> listOf(value.agent, value.winner, value.loser) },
    { fields -> Pref(fields[0], fields[1], fields[2]) },
)

private val featureMetaSerializer = StringFieldsSerializer(
    "backlogtriage.FeatureMeta",
    { value: FeatureMeta -> listOf(value.title, value.body) },
    { fields -> FeatureMeta(fields[0], fields[1]) },
)

/** Registers every backlog-triage value that can cross the host journal as a frame argument. */
class TriageWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(Pref::class, prefSerializer)
            subclass(FeatureMeta::class, featureMetaSerializer)
        }
    }
}
