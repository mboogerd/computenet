package civictech.query.lower

import civictech.query.diag.Locus
import java.io.Serializable

/**
 * A non-fatal fact about a successful lowering: the graph is built, but some property the
 * reader might assume of it does not hold. Emitted by [Gating.decide] for absence-based
 * operators (`[QRY1-LOWER-08]`, `[QRY1-LOWER-09]`, cab.4-D3, cab.4-D6).
 */
sealed interface LoweringDiagnostic : Serializable {

    /** The plan node the diagnostic is about, by its numbered path. */
    val locus: Locus.PlanNode

    /** The spawn handle of the cell the diagnostic is about. */
    val handle: String

    /**
     * The operator's arms share a source relation, so the within-wave flicker the
     * `emitOnFrontier` gate removes can occur — but the gate was withheld because a shape
     * where the gate is known to withhold output at rest was found, named in [reason]:
     * either a source feeds only one arm, so the other inlet is a phantom expected edge for
     * its waves (`WaveGate`'s "The phantom expected edge (G-13)", computenet-cab.4.8), or an
     * arm is more than one operator deep from the source (`doc/demo-findings.md` F-15,
     * `WaveGate`'s "One root is NOT sufficient"). The cell runs ungated and may flicker
     * transiently; it still converges.
     */
    data class GateNotProvable(
        override val locus: Locus.PlanNode,
        override val handle: String,
        val reason: String,
    ) : LoweringDiagnostic

    /**
     * The operator's arms share no source relation, so no wave-completeness frontier spans
     * both of them: the output is eventually consistent, not glitch-free ([specId]).
     */
    data class EventuallyConsistent(
        override val locus: Locus.PlanNode,
        override val handle: String,
        val specId: String,
    ) : LoweringDiagnostic
}
