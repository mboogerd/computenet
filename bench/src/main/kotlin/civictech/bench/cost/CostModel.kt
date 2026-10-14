package civictech.bench.cost

import civictech.bench.BenchResult
import civictech.bench.Drive
import civictech.bench.RunEnvironment
import kotlinx.serialization.Serializable

/**
 * A SIM-derived structural cost entry ([BEN2-33], [BEN2-42]).
 *
 * The nullable [transportValidated] slot is reserved for the later wire cross-check;
 * leaving it null is meaningful and must survive serialization without a schema bump.
 */
@Serializable
data class StructuralCost(
    val at: Coordinate,
    val subject: CostSubject,
    val counts: StructuralCounts,
    val harnessSha: String,
    val transportValidated: Boolean? = null,
)

/**
 * A REAL-drive per-unit calibration constant ([BEN2-25], [BEN2-38], zbhyf-D19).
 *
 * Construction is restricted to [from] because a calibration constant is an admitted
 * wall-clock observation, not a value a caller may hand-build from a SIM result. The
 * primary constructor is `internal` so the serialization plugin can reconstruct an
 * exported value while public production code still passes through the REAL-drive
 * check.
 */
@Serializable
data class CalibrationConstant internal constructor(
    val name: String,
    val value: Double,
    val unit: String,
    val dispersion: Double,
    val env: RunEnvironment,
) {
    companion object {
        /** Builds a calibration constant only from a REAL-drive result ([BEN2-25]). */
        fun from(name: String, result: BenchResult): CalibrationConstant {
            require(result.drive == Drive.REAL) {
                "calibration constant '$name' requires a REAL BenchResult, was ${result.drive}"
            }
            return CalibrationConstant(
                name = name,
                value = result.value,
                unit = result.unit,
                dispersion = result.dispersion,
                env = result.env,
            )
        }
    }
}

/**
 * The measured coverage boundary for a cost model ([BEN2-39], zbhyf-D4/D12).
 *
 * The constructor is `internal` because the envelope is derived from admitted points
 * by the writer; callers must not widen coverage by declaring a range by hand.
 */
@Serializable
data class Envelope internal constructor(
    val numeric: Map<String, Range>,
    val categorical: Map<String, Set<String>>,
) {
    /** A closed numeric extent in the derived envelope ([BEN2-39]). */
    @Serializable
    data class Range(
        val min: Double,
        val max: Double,
    )
}

/** The supported growth-law form ([BEN2-43], zbhyf-D5). */
@Serializable
enum class GrowthForm {
    LINEAR,
}

/**
 * A fitted growth law retained with its goodness-of-fit evidence ([BEN2-43], [BEN2-44]).
 *
 * Requiring [goodnessOfFit] in the only constructor keeps a law from being presented as
 * an extrapolation without the admission evidence that justifies it.
 */
@Serializable
data class GrowthLaw(
    val dimension: String,
    val subject: CostSubject,
    val metric: String,
    val form: GrowthForm,
    val coefficients: List<Double>,
    val fittedOver: List<Coordinate>,
    val goodnessOfFit: Double,
) {
    init {
        require(goodnessOfFit in 0.0..1.0) {
            "goodnessOfFit must be between 0.0 and 1.0, was $goodnessOfFit"
        }
    }
}

/** An admitted-point exclusion and the reason its coordinate is absent ([BEN2-41]). */
@Serializable
data class ExcludedPoint(
    val at: Coordinate,
    val subject: CostSubject,
    val kind: String,
    val reason: String,
)

/**
 * The versioned two-layer cost export ([BEN2-30], [BEN2-31], [BEN2-33], [BEN2-42]).
 *
 * [structural] contains SIM-derived counts and [calibration] contains REAL-derived
 * per-unit observations. Keeping those layers distinct prevents wall-clock values from
 * being mistaken for structural measurements.
 */
@Serializable
data class CostModel(
    val schemaVersion: Int,
    val harnessSha: String,
    val envelope: Envelope,
    val structural: List<StructuralCost>,
    val calibration: List<CalibrationConstant>,
    val laws: List<GrowthLaw>,
    val exclusions: List<ExcludedPoint>,
)

/**
 * Names of calibration constants consumed by later cost queries ([BEN2-38]).
 */
object Calibration {
    const val LOCAL_DISPATCH_NANOS = "localDispatchNanos"
    const val CROSS_HOST_FRAME_NANOS = "crossHostFrameNanos"
    const val WIRE_BYTE_NANOS = "wireByteNanos"
    const val JOURNAL_FSYNC_NANOS = "journalFsyncNanos"
    const val RETAINED_BYTES_PER_ELEMENT = "retainedBytesPerElement"
}
