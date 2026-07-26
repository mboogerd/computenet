@file:OptIn(ExperimentalSerializationApi::class)

package civictech.concord.yaml

import civictech.concord.value.Value
import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlContentPolymorphicSerializer
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * The Concord YAML front end. kaml is test-scope because every scenario parse
 * happens in a test source set (the runner is a JUnit harness — W1-A), so this
 * lives outside `main`. W1-A reuses / promotes this factory.
 *
 * - Sealed [civictech.concord.schema.Step] and [civictech.concord.schema.Check]
 *   use kaml's native `type`-property polymorphism.
 * - The neutral [Value] model (free-form JSON-shaped goldens/payloads) is decoded
 *   by [ValueYamlSerializer], registered contextually.
 * - `strictMode = false` so a future descriptor param does not break older files
 *   (schema growth is a deliberate ticket, but forward-compat parsing is free).
 */
object ConcordYaml {
    val instance: Yaml = Yaml(
        serializersModule = SerializersModule { contextual(Value::class, ValueYamlSerializer) },
        configuration = YamlConfiguration(
            strictMode = false,
            polymorphismStyle = PolymorphismStyle.Property,
            polymorphismPropertyName = "type",
        ),
    )
}

/**
 * Free-form value (de)serializer. Decode inspects kaml's [YamlNode] shape and
 * routes to a matching strategy — the supported kaml mechanism for
 * node-shape-directed decoding, so a scalar golden (`100`, `apple`) and a list
 * golden (`[pear, plum]`) both work under one field type. An untyped scalar is
 * widened int → real → bool → string (YAML core schema). Encode is
 * format-generic (works through any [Encoder]).
 */
object ValueYamlSerializer : YamlContentPolymorphicSerializer<Value>(Value::class) {

    override fun selectDeserializer(node: YamlNode): DeserializationStrategy<Value> = when (node) {
        is YamlNull -> NullStrategy
        is YamlScalar -> ScalarStrategy
        is YamlList -> ListStrategy
        is YamlMap -> MapStrategy
        else -> ScalarStrategy
    }

    override fun serialize(encoder: Encoder, value: Value) {
        when (value) {
            is Value.StrVal -> encoder.encodeString(value.value)
            is Value.IntVal -> encoder.encodeLong(value.value)
            is Value.RealVal -> encoder.encodeDouble(value.value)
            is Value.BoolVal -> encoder.encodeBoolean(value.value)
            Value.NullVal -> encoder.encodeNull()
            is Value.ListVal -> encoder.encodeSerializableValue(ListSerializer(this), value.items)
            is Value.MapVal -> encoder.encodeSerializableValue(
                MapSerializer(String.serializer(), this), value.entries,
            )
        }
    }
}

private fun widen(c: String): Value {
    c.toLongOrNull()?.let { return Value.IntVal(it) }
    c.toDoubleOrNull()?.let { return Value.RealVal(it) }
    return when (c) {
        "true" -> Value.BoolVal(true)
        "false" -> Value.BoolVal(false)
        else -> Value.StrVal(c)
    }
}

private object ScalarStrategy : DeserializationStrategy<Value> {
    override val descriptor = PrimitiveSerialDescriptor("concord.Value.Scalar", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Value = widen(decoder.decodeString())
}

private object NullStrategy : DeserializationStrategy<Value> {
    override val descriptor = PrimitiveSerialDescriptor("concord.Value.Null", PrimitiveKind.STRING)
    override fun deserialize(decoder: Decoder): Value {
        decoder.decodeNull()
        return Value.NullVal
    }
}

private object ListStrategy : DeserializationStrategy<Value> {
    private val delegate = ListSerializer(ValueYamlSerializer)
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): Value = Value.ListVal(delegate.deserialize(decoder))
}

private object MapStrategy : DeserializationStrategy<Value> {
    private val delegate = MapSerializer(String.serializer(), ValueYamlSerializer)
    override val descriptor = delegate.descriptor
    override fun deserialize(decoder: Decoder): Value = Value.MapVal(delegate.deserialize(decoder))
}
