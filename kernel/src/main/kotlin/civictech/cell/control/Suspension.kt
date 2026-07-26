package civictech.cell.control

import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
import civictech.nature.ProtocolCardinality
import civictech.nature.ProtocolDirection

/**
 * Recoverability of a stall determines a glitch-free join's disposition
 * (spec 20/22 "Completeness over silent or stuck edges", 30/34 decision 3,
 * decided in 93 I-18): WAIT (park-not-drop) or DEGRADE (frontier-shrink)
 * apply only to recoverable causes; RE-SCOPE (advance past the poisoned
 * wave, surface a GlitchViolation) is the only admissible disposition for
 * terminal ones.
 */
@kotlinx.serialization.Serializable
enum class StallReason(val recoverable: Boolean) {
    /** Attention-driven park (34 decision 3): park-not-drop, resumes emergently. */
    SUSPENDED(recoverable = true),
    /** Supervision RESTART (23 R6): state-restore in flight, resumes post-checkpoint. */
    RESTARTING(recoverable = true),
    /** Supervision dead-letter (31 rule 5): the contribution is gone for good. */
    DEAD_LETTERED(recoverable = false),
}

/**
 * Typed frontier-event family (spec 20/22, 30/34 decision 3, decided in 93
 * I-18): generalizes the shipped suspended/resumed pair. Hosts publish
 * [Stall] for parked/restarting/dead-lettered cells, traveling downstream
 * against attention; [Resume] retracts it. [Stall.timestamp], when known
 * (e.g. the wave the failing invocation was itself processing), lets a
 * downstream join rescue exactly the poisoned wave rather than every wave
 * pending on the edge.
 */
@kotlinx.serialization.Serializable
sealed interface StallNotice {
    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Stall")
    data class Stall(val reason: StallReason, val timestamp: civictech.cell.Timestamp? = null) : StallNotice {
        val recoverable: Boolean get() = reason.recoverable
    }

    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Resume")
    data object Resume : StallNotice
}

@Contract(management = true)
@Protocol("suspension", ProtocolDirection.DOWNSTREAM, band = 0, lane = "suspension", cardinality = ProtocolCardinality.FAN_OUT_BROADCAST)
fun interface SuspensionProtocol { fun suspension(message: StallNotice) }

/**
 * Marker (spec 34 decision 3, session delta 3): a cell that must never be
 * attention-parked. Membership is contagious — one non-suspendable member
 * vetoes suspension for its whole glitch-free region.
 */
interface NonSuspendable
