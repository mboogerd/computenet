package civictech.bench.cost

import civictech.bench.Drive
import kotlinx.serialization.Serializable

/**
 * The complete identity of one benchmark point ([BEN2-22], [BEN2-34]).
 *
 * Keeping the drive in the coordinate prevents a simulated structural count and a
 * real wall-clock calibration from being mistaken for the same measurement. The
 * export's structural layer admits SIM coordinates; REAL coordinates identify the
 * separate calibration regime.
 */
@Serializable
data class Coordinate(
    val shape: String,
    val shapeSeed: Long,
    val cardinality: Long,
    val batchSize: Int,
    val hostCount: Int,
    val fanOut: Int,
    val replication: Boolean,
    val durability: Boolean,
    val drive: Drive,
)

/**
 * Structural counters recorded at one coordinate ([BEN2-23]).
 *
 * A missing message count is represented explicitly by [messagesNotMeasured], rather
 * than by a fabricated zero. Exactly one of the two message fields must be present so
 * an export cannot silently turn an unmeasured count into a measured one.
 */
@Serializable
data class StructuralCounts(
    val messages: Long?,
    val messagesNotMeasured: String?,
    val crossHostFrames: Long,
    val wireBytes: Long,
    val journalRecords: Long,
    val simSteps: Long,
) {
    init {
        require((messages == null) != (messagesNotMeasured == null)) {
            "exactly one of messages and messagesNotMeasured must be provided"
        }
    }
}

/**
 * The structural category a cost point describes ([BEN2-33], zbhyf-D11).
 */
@Serializable
enum class CostSubject {
    PROPAGATION,
    REBASELINE,
    JOURNAL,
}

/**
 * Stable names for the dimensions used by the coverage envelope ([BEN2-39], zbhyf-D12).
 */
object Dimensions {
    const val CARDINALITY = "cardinality"
    const val BATCH_SIZE = "batchSize"
    const val HOST_COUNT = "hostCount"
    const val FAN_OUT = "fanOut"
    const val SHAPE = "shape"
    const val REPLICATION = "replication"
    const val DURABILITY = "durability"
}
