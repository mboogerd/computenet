package civictech.bench.cost

import civictech.bench.Drive

/** The result of asking the cost model to price one placement-relevant operation. */
sealed interface CostAnswer {
    /** A measured prediction with the evidence used to derive it. */
    data class Priced(
        val value: Double,
        val unit: String,
        val dispersion: Double,
        val from: Provenance,
    ) : CostAnswer

    /** A refusal because the requested coordinate lies beyond measured coverage. */
    data class OutOfEnvelope(
        val dimension: String,
        val requested: String,
        val measured: Measured,
    ) : CostAnswer

    /** A refusal because the coordinate is covered but required evidence is absent. */
    data class NotMeasured(val reason: String) : CostAnswer
}

/** The measured extent of one envelope dimension. */
sealed interface Measured {
    /** The observed values of a categorical dimension. */
    data class Values(val values: Set<String>) : Measured
}

/** The structural point and calibration constants that produced a price. */
data class Provenance(
    val structural: StructuralRef,
    val calibration: List<CalibrationRef>,
    val dominant: String,
)

/** The SIM-derived structural observation used by a price. */
data class StructuralRef(
    val subject: CostSubject,
    val at: Coordinate,
    val harnessSha: String,
)

/** One REAL-derived calibration observation used by a price. */
data class CalibrationRef(
    val name: String,
    val value: Double,
    val unit: String,
    val dispersion: Double,
    val harnessCommitSha: String,
)

/**
 * Answers the placement-cost questions consumed by PLC1 ([BEN2-35], [BEN2-36]).
 *
 * The formulas are:
 * - co-located edge = `messages * localDispatchNanos`;
 * - cross-host edge = the co-located term plus
 *   `crossHostFrames * crossHostFrameNanos + wireBytes * wireByteNanos`;
 * - cell residency = `cardinality * retainedBytesPerElement`; and
 * - re-baseline = the cross-host formula over a REBASELINE structural entry.
 *
 * Every query first checks the envelope in cardinality, batch-size, host-count,
 * fan-out, shape, replication, durability order. It then looks up the required
 * structural subject at the same coordinate under [Drive.SIM], followed by each
 * calibration constant in formula order. No interpolation or extrapolation is used.
 * The dominant constant is the one with the largest `count * value` contribution;
 * ties select the first constant in formula order.
 */
class CostQuery(private val model: CostModel) {

    /** Prices local delivery of the measured messages at [at]. */
    fun coLocatedEdge(at: Coordinate): CostAnswer = query(
        at = at,
        subject = CostSubject.PROPAGATION,
        unit = NANOS,
        calibrationNames = listOf(Calibration.LOCAL_DISPATCH_NANOS),
    ) { entry ->
        entry.counts.messages?.let { listOf(it.toDouble()) }
    }

    /** Prices local delivery plus the measured frame and wire-byte costs at [at]. */
    fun crossHostEdge(at: Coordinate): CostAnswer = query(
        at = at,
        subject = CostSubject.PROPAGATION,
        unit = NANOS,
        calibrationNames = listOf(
            Calibration.LOCAL_DISPATCH_NANOS,
            Calibration.CROSS_HOST_FRAME_NANOS,
            Calibration.WIRE_BYTE_NANOS,
        ),
    ) { entry ->
        entry.counts.messages?.let { messages ->
            listOf(
                messages.toDouble(),
                entry.counts.crossHostFrames.toDouble(),
                entry.counts.wireBytes.toDouble(),
            )
        }
    }

    /** Prices the retained bytes for a cell whose cardinality was swept at [at]. */
    fun cellResidency(at: Coordinate): CostAnswer = query(
        at = at,
        subject = CostSubject.PROPAGATION,
        unit = BYTES,
        calibrationNames = listOf(Calibration.RETAINED_BYTES_PER_ELEMENT),
    ) { entry ->
        listOf(entry.at.cardinality.toDouble())
    }

    /** Prices the measured REBASELINE structural entry at [at]. */
    fun reBaseline(at: Coordinate): CostAnswer = query(
        at = at,
        subject = CostSubject.REBASELINE,
        unit = NANOS,
        calibrationNames = listOf(
            Calibration.LOCAL_DISPATCH_NANOS,
            Calibration.CROSS_HOST_FRAME_NANOS,
            Calibration.WIRE_BYTE_NANOS,
        ),
    ) { entry ->
        entry.counts.messages?.let { messages ->
            listOf(
                messages.toDouble(),
                entry.counts.crossHostFrames.toDouble(),
                entry.counts.wireBytes.toDouble(),
            )
        }
    }

    private fun query(
        at: Coordinate,
        subject: CostSubject,
        unit: String,
        calibrationNames: List<String>,
        countsFor: (StructuralCost) -> List<Double>?,
    ): CostAnswer {
        outsideEnvelope(at)?.let { return it }

        val normalized = at.copy(drive = Drive.SIM)
        val structural = model.structural.firstOrNull { entry ->
            entry.subject == subject && entry.at == normalized
        } ?: return CostAnswer.NotMeasured("no $subject point at $normalized")

        val constants = mutableListOf<CalibrationConstant>()
        for (name in calibrationNames) {
            val constant = model.calibration.firstOrNull { it.name == name }
                ?: return CostAnswer.NotMeasured("no calibration constant '$name'")
            constants += constant
        }

        val counts = countsFor(structural)
            ?: return CostAnswer.NotMeasured(
                structural.counts.messagesNotMeasured
                    ?: "messages not measured at ${structural.at}",
            )
        check(counts.size == constants.size) {
            "formula supplied ${counts.size} counts for ${constants.size} constants"
        }

        val contributions = counts.zip(constants) { count, constant -> count * constant.value }
        val dispersions = counts.zip(constants) { count, constant -> count * constant.dispersion }
        val dominantIndex = contributions.indices.reduce { dominant, candidate ->
            if (contributions[candidate] > contributions[dominant]) candidate else dominant
        }

        return CostAnswer.Priced(
            value = contributions.sum(),
            unit = unit,
            dispersion = dispersions.sum(),
            from = Provenance(
                structural = StructuralRef(
                    subject = structural.subject,
                    at = structural.at,
                    harnessSha = structural.harnessSha,
                ),
                calibration = constants.map { constant ->
                    CalibrationRef(
                        name = constant.name,
                        value = constant.value,
                        unit = constant.unit,
                        dispersion = constant.dispersion,
                        harnessCommitSha = constant.env.harnessCommitSha,
                    )
                },
                dominant = constants[dominantIndex].name,
            ),
        )
    }

    private fun outsideEnvelope(at: Coordinate): CostAnswer.OutOfEnvelope? {
        val numeric = listOf(
            NumericRequest(Dimensions.CARDINALITY, at.cardinality.toString(), at.cardinality.toDouble()),
            NumericRequest(Dimensions.BATCH_SIZE, at.batchSize.toString(), at.batchSize.toDouble()),
            NumericRequest(Dimensions.HOST_COUNT, at.hostCount.toString(), at.hostCount.toDouble()),
            NumericRequest(Dimensions.FAN_OUT, at.fanOut.toString(), at.fanOut.toDouble()),
        )
        for (request in numeric) {
            val measured = model.envelope.numeric.getValue(request.dimension)
            if (request.value < measured.min || request.value > measured.max) {
                return CostAnswer.OutOfEnvelope(
                    dimension = request.dimension,
                    requested = request.rendered,
                    measured = measured,
                )
            }
        }

        val categorical = listOf(
            Dimensions.SHAPE to at.shape,
            Dimensions.REPLICATION to at.replication.toString(),
            Dimensions.DURABILITY to at.durability.toString(),
        )
        for ((dimension, requested) in categorical) {
            val values = model.envelope.categorical.getValue(dimension)
            if (requested !in values) {
                return CostAnswer.OutOfEnvelope(
                    dimension = dimension,
                    requested = requested,
                    measured = Measured.Values(values),
                )
            }
        }
        return null
    }

    private data class NumericRequest(
        val dimension: String,
        val rendered: String,
        val value: Double,
    )

    private companion object {
        const val NANOS = "ns"
        const val BYTES = "bytes"
    }
}
