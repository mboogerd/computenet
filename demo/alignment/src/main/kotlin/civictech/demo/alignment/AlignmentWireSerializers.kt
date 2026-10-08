package civictech.demo.alignment

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

/** Immutable snapshot of one topic's complete write-side record. */
internal data class TopicRecord(
    val id: String,
    val title: String,
    val creator: String,
    val ideaPolicy: String,
    val boardVisibility: String,
    val dimensions: Map<String, DimensionRecord> = emptyMap(),
    val ideas: Map<String, IdeaRecord> = emptyMap(),
    val notes: Map<String, NoteRecord> = emptyMap(),
    val overrides: Map<String, Double> = emptyMap(),
    val revealed: Boolean = false,
    val gutCheck: Boolean = false,
    val dotBudget: Int = 3,
) : java.io.Serializable

internal data class DimensionRecord(
    val name: String,
    val lowLabel: String,
    val highLabel: String,
    val weight: Double,
    val direction: String,
    val description: String = "",
) : java.io.Serializable

internal data class IdeaRecord(
    val id: String,
    val title: String,
    val description: String,
    val proposer: String,
) : java.io.Serializable

internal data class NoteRecord(val text: String, val author: String) : java.io.Serializable

/** Flattened journal key: one durable row per normalized unordered pair. */
internal data class JudgementRecordKey(
    val topic: String,
    val dim: String,
    val participant: String,
    val pair: String,
) : java.io.Serializable

internal data class JudgementRecord(val a: String, val b: String, val outcome: String) : java.io.Serializable

/** A stable named wire object containing a length-delimited list of string fields. */
private class MappedListSerializer<T>(
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

private fun values(vararg fields: String): List<String> = fields.toList()

private val ratingKeySerializer = MappedListSerializer(
    "alignment.RatingKey",
    { value: RatingKey -> values(value.topic.value, value.idea, value.dim, value.participant) },
    { value -> RatingKey(TopicId(value[0]), value[1], value[2], value[3]) },
)

private val ratingSerializer = MappedListSerializer(
    "alignment.Rating",
    { value: Rating ->
        values(value.key.topic.value, value.key.idea, value.key.dim, value.key.participant, value.milli.toString())
    },
    { value -> Rating(RatingKey(TopicId(value[0]), value[1], value[2], value[3]), value[4].toInt()) },
)

private val dimKeySerializer = MappedListSerializer(
    "alignment.DimKey",
    { value: DimKey -> values(value.topic.value, value.dim) },
    { value -> DimKey(TopicId(value[0]), value[1]) },
)

private val dimConfigSerializer = MappedListSerializer(
    "alignment.DimConfig",
    { value: DimConfig -> values(value.weight.toString(), value.direction.name) },
    { value -> DimConfig(value[0].toDouble(), Direction.valueOf(value[1])) },
)

private val dotKeySerializer = MappedListSerializer(
    "alignment.DotKey",
    { value: DotKey -> values(value.topic.value, value.idea, value.participant) },
    { value -> DotKey(TopicId(value[0]), value[1], value[2]) },
)

private val judgementKeySerializer = MappedListSerializer(
    "alignment.JudgementRecordKey",
    { value: JudgementRecordKey -> values(value.topic, value.dim, value.participant, value.pair) },
    { value -> JudgementRecordKey(value[0], value[1], value[2], value[3]) },
)

private val judgementSerializer = MappedListSerializer(
    "alignment.JudgementRecord",
    { value: JudgementRecord -> values(value.a, value.b, value.outcome) },
    { value -> JudgementRecord(value[0], value[1], value[2]) },
)

private fun TopicRecord.toFields(): List<String> = buildList {
    add(id)
    add(title)
    add(creator)
    add(ideaPolicy)
    add(boardVisibility)
    add(revealed.toString())
    add(gutCheck.toString())
    add(dotBudget.toString())
    add(dimensions.size.toString())
    dimensions.toSortedMap().forEach { (id, d) ->
        add(id); add(d.name); add(d.lowLabel); add(d.highLabel); add(d.weight.toString()); add(d.direction)
    }
    add(ideas.size.toString())
    ideas.toSortedMap().forEach { (id, idea) ->
        add(id); add(idea.id); add(idea.title); add(idea.description); add(idea.proposer)
    }
    add(notes.size.toString())
    notes.toSortedMap().forEach { (id, note) -> add(id); add(note.text); add(note.author) }
    add(overrides.size.toString())
    overrides.toSortedMap().forEach { (id, score) -> add(id); add(score.toString()) }
    // dimension descriptions: an optional trailing section, written only when one is set, so a
    // record with none stays byte-identical to the format before descriptions existed
    val described = dimensions.toSortedMap().filterValues { it.description.isNotEmpty() }
    if (described.isNotEmpty()) {
        add(described.size.toString())
        described.forEach { (id, d) -> add(id); add(d.description) }
    }
}

private fun topicRecord(fields: List<String>): TopicRecord {
    var index = 0
    fun next() = fields[index++]
    val id = next()
    val title = next()
    val creator = next()
    val ideaPolicy = next()
    val boardVisibility = next()
    val revealed = next().toBooleanStrict()
    val gutCheck = next().toBooleanStrict()
    val dotBudget = next().toInt()
    val dimensions = LinkedHashMap<String, DimensionRecord>()
    repeat(next().toInt()) {
        dimensions[next()] = DimensionRecord(next(), next(), next(), next().toDouble(), next())
    }
    val ideas = LinkedHashMap<String, IdeaRecord>()
    repeat(next().toInt()) { ideas[next()] = IdeaRecord(next(), next(), next(), next()) }
    val notes = LinkedHashMap<String, NoteRecord>()
    repeat(next().toInt()) { notes[next()] = NoteRecord(next(), next()) }
    val overrides = LinkedHashMap<String, Double>()
    repeat(next().toInt()) { overrides[next()] = next().toDouble() }
    if (index < fields.size) repeat(next().toInt()) {
        val id = next()
        val description = next()
        dimensions[id] = checkNotNull(dimensions[id]) { "description for unknown dimension $id" }
            .copy(description = description)
    }
    check(index == fields.size) { "trailing alignment.TopicRecord fields: ${fields.size - index}" }
    return TopicRecord(
        id, title, creator, ideaPolicy, boardVisibility, dimensions, ideas, notes, overrides, revealed, gutCheck, dotBudget,
    )
}

private val topicSerializer = MappedListSerializer("alignment.TopicRecord", TopicRecord::toFields, ::topicRecord)

/** Registers every alignment value that can appear as a journaled frame argument. */
class AlignmentWireSerializers : WireSerializers {
    override val module: SerializersModule = SerializersModule {
        polymorphic(Any::class) {
            subclass(RatingKey::class, ratingKeySerializer)
            subclass(Rating::class, ratingSerializer)
            subclass(DimKey::class, dimKeySerializer)
            subclass(DimConfig::class, dimConfigSerializer)
            subclass(DotKey::class, dotKeySerializer)
            subclass(TopicRecord::class, topicSerializer)
            subclass(JudgementRecordKey::class, judgementKeySerializer)
            subclass(JudgementRecord::class, judgementSerializer)
        }
    }
}
