package civictech.bench.cost

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
        return this.json.decodeFromJsonElement(CostModel.serializer(), root)
    }
}

/** Thrown when a cost export's schema version is absent or unsupported ([BEN2-32]). */
class UnknownSchemaVersionException(message: String) : IllegalArgumentException(message)
