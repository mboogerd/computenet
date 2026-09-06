package civictech.cell.control

import civictech.gen.wire.Contract
import civictech.gen.wire.Protocol
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

    /**
     * Causal stability is frozen on a replica slot that stopped advancing
     * while every other open slot moved past it (E3.5, `computenet-9sm.5`,
     * decisions 9sm.5-D2/D3/D6; spec `doc/spec/40-distribution/42-replication.md`
     * §"The stability read"). Emitted by
     * [civictech.cell.consistency.StabilityFreezeDetector] through
     * `Replication.onStabilityStall`.
     *
     * **`recoverable = true` (9sm.5-D3).** WAIT parks on the edge and DEGRADE
     * shrinks the frontier past it — the same disposition an attention-park
     * gets. RE-SCOPE is **never** chosen for it: nothing about the stalled
     * slot's contribution is poisoned, only late, so advancing past the wave
     * and raising a `GlitchViolation` would be a lie about the data.
     *
     * **"Frozen", not "dead".** This reason is a *diagnostic*, never a
     * failure verdict: no failure detector, lease or timeout stands behind it
     * ([KE3-28]). It says only that the causal-stability MIN is pinned on
     * this slot right now — which is also true of a member that is alive but
     * idle, whose heartbeat republish is a fixpoint and does not advance its
     * row. Nothing in the kernel closes, suspends or evicts in response.
     *
     * **Retraction is not automatic and is not a timeout.** A single
     * [StallNotice.Resume] retracts the latch when the slot's row advances at
     * all, when the slot is `close`d (a clean departure), or when it leaves
     * the membership open set — an operator `evict`, a re-spawn that catches
     * up, or a heal. Until one of those happens the notice stands.
     */
    STABILITY_FROZEN(recoverable = true),
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
    data class Stall(
        val reason: StallReason,
        val timestamp: civictech.cell.Timestamp? = null,
        /**
         * The **replica slot** this stall is about, when the cause is a
         * particular member rather than the cell itself (decision 9sm.5-D5,
         * [KE3-39]): a [civictech.cell.data.WatermarkCell.slotId] — the
         * frozen member's companion-ref identity — set only by
         * [StallReason.STABILITY_FROZEN] today, null for every other reason.
         *
         * It is deliberately a *second* field rather than an overload of
         * [timestamp]: `timestamp` keeps its existing meaning, a WAVE
         * position (`Timestamp(sourceId = the data source on which this slot
         * is the floor, counter = the slot's row value for it)`, or null for
         * a slot that has been announced but has no row yet). Packing the
         * slot id into `timestamp.sourceId` would overload a wave source id
         * with a slot id and lose the position entirely.
         *
         * **Additive on the wire.** It defaults to null and
         * `civictech.cell.wire.WireCodec` builds its `Json` with
         * `encodeDefaults = false`, so an unset `slot` contributes zero
         * bytes and a `Stall` encoded before this field existed decodes
         * unchanged.
         */
        @kotlinx.serialization.Serializable(with = civictech.cell.UuidSerializer::class)
        val slot: java.util.UUID? = null,
    ) : StallNotice {
        val recoverable: Boolean get() = reason.recoverable
    }

    @kotlinx.serialization.Serializable
    @kotlinx.serialization.SerialName("Resume")
    data object Resume : StallNotice
}

@Contract(management = true)
@Protocol("suspension", ProtocolDirection.DOWNSTREAM, band = 0)
fun interface SuspensionProtocol { fun suspension(message: StallNotice) }

/**
 * Marker (spec 34 decision 3, session delta 3): a cell that must never be
 * attention-parked. Membership is contagious — one non-suspendable member
 * vetoes suspension for its whole glitch-free region.
 */
interface NonSuspendable
