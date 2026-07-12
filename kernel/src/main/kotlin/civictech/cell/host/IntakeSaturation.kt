package civictech.cell.host

import civictech.cell.CellRef
import civictech.cell.port.PortRef
import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.gen.wire.ProtocolCardinality
import civictech.gen.wire.ProtocolDirection

enum class IntakeState { OPEN, SATURATED, CLOSED }

data class IntakeBound(
    val highWater: Int,
    val lowWater: Int = highWater / 2,
    val policy: SaturationPolicy = SaturationPolicy.Coalesce,
) {
    init {
        require(highWater > 0) { "highWater must be positive" }
        require(lowWater in 0 until highWater) { "lowWater must be in [0, highWater)" }
    }
}

enum class SaturationPolicy { Coalesce, Park }

/** Retractable upstream notice; W2.3 supplies its transitive transport. */
data class SaturationSignal(val portRef: PortRef, val asserted: Boolean) : java.io.Serializable

@Contract(management = true)
@Protocol("saturation", ProtocolDirection.UPSTREAM, band = 0, lane = "saturation", cardinality = ProtocolCardinality.FAN_IN_MERGE)
fun interface SaturationProtocol { fun saturation(message: SaturationSignal) }

class IntakeSaturatedException(val hostRef: CellRef) :
    IllegalStateException("host $hostRef intake is saturated")

/** Payloads declaring the associative merge used by a saturated intake. */
interface MergeablePayload {
    fun mergeWith(other: MergeablePayload): MergeablePayload
}
