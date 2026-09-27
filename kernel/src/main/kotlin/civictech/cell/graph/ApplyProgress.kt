package civictech.cell.graph

import civictech.cell.Cell
import civictech.cell.CellRef
import civictech.cell.Propagate
import civictech.cell.port.FanOutlet
import civictech.cell.port.registerPort
import java.io.Serializable
import java.util.UUID

/**
 * One reported outcome of [GraphSpec.applyRemote]'s per-step application
 * (computenet-4jdw0-D1): [index] is the 0-based position of the step in
 * [GraphSpec.lowered], [handle] is exactly the key that step's [StepResult]
 * is folded under in the returned [ApplyReport] (the [SpawnStep.handle], or
 * `"$from.$outlet->$to.$inlet"` for a [ConnectStep]), and [result] is that
 * step's outcome.
 */
data class StepEvent(val index: Int, val handle: String, val result: StepResult) : Serializable

/**
 * Callback invoked once per [GraphSpec.lowered] step during
 * [GraphSpec.applyRemote], synchronously on the applier's thread, in step
 * order, immediately after that step's [StepResult] is known and before the
 * next step starts. A throw from [onStep] propagates to the applier — it is
 * the applier's own callback, not one the apply machinery guards.
 */
fun interface ApplyProgress {
    fun onStep(event: StepEvent)
}

/**
 * The R4 "dataflow-linkable outlet" for apply progress (spec
 * `15-lifecycle.md` §Hot phase, G-51; `95-research-plan.md` §R4;
 * computenet-4jdw0-D3): an [ApplyProgress] that republishes every
 * [StepEvent] on its [progress] outlet, so apply progress flows through the
 * graph like any other data instead of living only in a direct return value.
 *
 * Usage — link *before* the target cells exist: spawn this cell on the host,
 * link (subscribe, tap, or management `connect`) a consumer to [progress],
 * then pass the cell as the `progress` argument of
 * `GraphSpec.applyRemote(host, progress)`. The consumer then sees one event
 * per lowered step, in step order, while the apply runs.
 *
 * Each event is a **fresh origination** of [progress] ([FanOutlet.originate]):
 * a new wave minted from this outlet, detached from whatever `CurrentContext`
 * the applier happens to be running under. The applier is a *caller*, not a
 * served dispatch — there is no triggering wave this emission is reactive
 * to, so inheriting an ambient frame would weld unrelated progress onto a
 * foreign wave (the same argument as `WatermarkCell.republish`).
 *
 * No inlet, no state, no activation logic (`[15-RULE-01]`), and no
 * catch-up: nothing is buffered or replayed, so a consumer linked after an
 * apply has returned sees none of that apply's events.
 */
class ApplyProgressCell(override val ref: CellRef = CellRef(UUID.randomUUID())) : Cell, ApplyProgress {
    val progress = registerPort("progress", FanOutlet.create<Propagate<StepEvent>>())

    override fun onStep(event: StepEvent) {
        progress.originate { propagate(event) }
    }
}
