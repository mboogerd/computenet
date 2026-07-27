package civictech.cell

import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import java.util.UUID

object UuidSerializer : KSerializer<UUID> {
    override val descriptor = PrimitiveSerialDescriptor("Uuid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: UUID) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): UUID = UUID.fromString(decoder.decodeString())
}

/** kotlinx ships no serializer for [IndexedValue] (ListDelta's element form). */
class IndexedValueSerializer<T>(private val element: KSerializer<T>) : KSerializer<IndexedValue<T>> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("IndexedValue") {
        element("index", Int.serializer().descriptor)
        element("value", element.descriptor)
    }

    override fun serialize(encoder: Encoder, value: IndexedValue<T>) = encoder.encodeStructure(descriptor) {
        encodeIntElement(descriptor, 0, value.index)
        encodeSerializableElement(descriptor, 1, element, value.value)
    }

    override fun deserialize(decoder: Decoder): IndexedValue<T> = decoder.decodeStructure(descriptor) {
        var index = -1
        var value: T? = null
        var hasValue = false
        while (true) {
            when (val i = decodeElementIndex(descriptor)) {
                0 -> index = decodeIntElement(descriptor, 0)
                1 -> {
                    value = decodeSerializableElement(descriptor, 1, element)
                    hasValue = true
                }
                CompositeDecoder.DECODE_DONE -> break
                else -> error("unexpected element index $i")
            }
        }
        require(index >= 0 && hasValue) { "incomplete IndexedValue" }
        @Suppress("UNCHECKED_CAST")
        IndexedValue(index, value as T)
    }
}
