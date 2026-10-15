package civictech.bench.cost

import civictech.bench.Drive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The stable JSON boundary for the versioned cost model ([BEN2-30], [BEN2-31], [BEN2-32]).
 */
object CostSchema {
    const val VERSION = 1
    const val STABLE_PATH = "doc/bench/cost-model.json"

    /** Kernel-style JSON: pretty output, default omission, and strict unknown keys. */
    val json = Json { prettyPrint = true }

    /** Encodes a model with its explicit schema version intact. */
    fun encode(model: CostModel): String = json.encodeToString(CostModel.serializer(), model)

    /**
     * Reads the schema version before asking kotlinx.serialization to decode any entry.
     * This ordering makes a future-version document fail with a version error even when
     * its entry shape is not understood by this reader ([BEN2-32]).
     */
    fun parse(json: String): CostModel {
        val root = this.json.parseToJsonElement(json)
        val rootObject = root as? JsonObject
            ?: throw UnknownSchemaVersionException(
                "Unknown schemaVersion <non-object>; expected $VERSION",
            )
        val versionElement = rootObject["schemaVersion"]
        val version = (versionElement as? JsonPrimitive)
            ?.takeUnless { it.isString }
            ?.intOrNull
        if (version != VERSION) {
            val offending = versionElement?.toString() ?: "<missing>"
            throw UnknownSchemaVersionException(
                "Unknown schemaVersion $offending; expected $VERSION",
            )
        }
        return this.json.decodeFromJsonElement(CostModel.serializer(), root).also(::validate)
    }

    /** Refuses decoded values that could not have been produced by [CostWriter]. */
    private fun validate(model: CostModel) {
        validateEnvelopeDimensions(model.envelope)

        model.structural.forEach { entry ->
            val key = EntryKey(entry.subject, entry.at)
            if (entry.at.drive != Drive.SIM) {
                invalid("structural key $key requires ${Drive.SIM}, was ${entry.at.drive}")
            }
        }

        val structuralKeys = model.structural.map { EntryKey(it.subject, it.at) }
        structuralKeys.duplicate()?.let { key ->
            invalid("duplicate structural key $key")
        }
        model.calibration.map { it.name }.duplicate()?.let { name ->
            invalid("duplicate calibration name '$name'")
        }

        val exclusionKeys = model.exclusions.map { EntryKey(it.subject, it.at) }
        exclusionKeys.duplicate()?.let { key ->
            invalid("duplicate exclusion key $key")
        }
        exclusionKeys.firstOrNull(structuralKeys.toSet()::contains)?.let { key ->
            invalid("exclusion key $key conflicts with an admitted structural key")
        }

        model.laws.map { it.key() }.duplicate()?.let { key ->
            invalid("duplicate growth-law key $key")
        }

        validateDerivedEnvelope(model)
    }

    private fun validateEnvelopeDimensions(envelope: Envelope) {
        validateDimensionNames("numeric", envelope.numeric.keys, NUMERIC_DIMENSIONS)
        validateDimensionNames("categorical", envelope.categorical.keys, CATEGORICAL_DIMENSIONS)
    }

    private fun validateDimensionNames(
        section: String,
        actual: Set<String>,
        expected: Set<String>,
    ) {
        expected.firstOrNull { it !in actual }?.let { dimension ->
            invalid("coverage envelope $section section is missing dimension '$dimension'")
        }
        actual.firstOrNull { it !in expected }?.let { dimension ->
            invalid("coverage envelope $section section has unexpected dimension '$dimension'")
        }
    }

    private fun validateDerivedEnvelope(model: CostModel) {
        if (model.structural.isEmpty()) {
            invalid("coverage envelope cannot be derived from zero structural entries")
        }

        val derivedNumeric = linkedMapOf(
            Dimensions.CARDINALITY to model.structural.range { it.at.cardinality.toDouble() },
            Dimensions.BATCH_SIZE to model.structural.range { it.at.batchSize.toDouble() },
            Dimensions.HOST_COUNT to model.structural.range { it.at.hostCount.toDouble() },
            Dimensions.FAN_OUT to model.structural.range { it.at.fanOut.toDouble() },
        )
        val derivedCategorical = linkedMapOf(
            Dimensions.SHAPE to model.structural.mapTo(linkedSetOf()) { it.at.shape },
            Dimensions.REPLICATION to
                model.structural.mapTo(linkedSetOf()) { it.at.replication.toString() },
            Dimensions.DURABILITY to
                model.structural.mapTo(linkedSetOf()) { it.at.durability.toString() },
        )

        NUMERIC_DIMENSIONS.forEach { dimension ->
            val actual = model.envelope.numeric.getValue(dimension)
            val derived = derivedNumeric.getValue(dimension)
            if (actual != derived) {
                invalid(
                    "coverage envelope dimension '$dimension' is $actual; " +
                        "derived extent is $derived",
                )
            }
        }
        CATEGORICAL_DIMENSIONS.forEach { dimension ->
            val actual = model.envelope.categorical.getValue(dimension)
            val derived = derivedCategorical.getValue(dimension)
            if (actual != derived) {
                invalid(
                    "coverage envelope dimension '$dimension' is $actual; " +
                        "derived values are $derived",
                )
            }
        }
    }

    private fun List<StructuralCost>.range(
        selector: (StructuralCost) -> Double,
    ): Envelope.Range {
        val values = map(selector)
        return Envelope.Range(values.min(), values.max())
    }

    private fun GrowthLaw.key(): GrowthLawKey = GrowthLawKey(
        dimension = dimension,
        subject = subject,
        metric = metric,
        form = form,
        fittedOver = fittedOver.toSet(),
    )

    private fun <T> List<T>.duplicate(): T? {
        val seen = mutableSetOf<T>()
        return firstOrNull { !seen.add(it) }
    }

    private fun invalid(detail: String): Nothing {
        throw InvalidCostModelException("CostWriter invariant violated: $detail")
    }

    private data class EntryKey(
        val subject: CostSubject,
        val at: Coordinate,
    )

    private data class GrowthLawKey(
        val dimension: String,
        val subject: CostSubject,
        val metric: String,
        val form: GrowthForm,
        val fittedOver: Set<Coordinate>,
    )

    private val NUMERIC_DIMENSIONS = linkedSetOf(
        Dimensions.CARDINALITY,
        Dimensions.BATCH_SIZE,
        Dimensions.HOST_COUNT,
        Dimensions.FAN_OUT,
    )

    private val CATEGORICAL_DIMENSIONS = linkedSetOf(
        Dimensions.SHAPE,
        Dimensions.REPLICATION,
        Dimensions.DURABILITY,
    )
}

/** Thrown when a cost export's schema version is absent or unsupported ([BEN2-32]). */
class UnknownSchemaVersionException(message: String) : IllegalArgumentException(message)

/** Thrown when a decoded export violates an invariant enforced by [CostWriter]. */
class InvalidCostModelException(message: String) : IllegalArgumentException(message)
